package ghost;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Work on many blocks at once - filling a box, or emptying one.
 *
 * <p>A player who wants a wall gone does not break it one block at a time, and
 * an assistant that can only do it one block at a time is not much of one. The
 * single-block {@code break} and {@code place} verbs stay, because precision
 * still matters; this is for when the unit of work is a region.
 *
 * <p><b>Everything here is destructive and irreversible.</b> Three guards,
 * therefore, and all of them deliberate:
 *
 * <ul>
 *   <li>the {@code buildinggadgets2:deny} tag is honoured block by block, so
 *       the 177 cables and conduits it protects survive a clear that was aimed
 *       at the wall behind them;</li>
 *   <li>a hard cap on volume, so a fat-fingered coordinate cannot eat a
 *       chunk before anyone notices;</li>
 *   <li>rank, checked by the caller before we ever get here.</li>
 * </ul>
 *
 * <p>Refusals are counted and named rather than silently skipped. "I cleared
 * 400 of 412 blocks and here are the twelve I would not touch" is a usable
 * answer; "done" when it quietly left a dozen behind is not.
 */
final class Bulk {

    private Bulk() {
    }

    /**
     * Most blocks one call will touch.
     *
     * <p>16 x 16 x 16. Big enough for a room, small enough that a typo in a
     * coordinate is an annoyance rather than an excavation. A caller that
     * genuinely wants more can ask twice, which is a deliberate speed bump.
     */
    static final int MAX_BLOCKS = 4096;

    static final TagKey<Block> DENY = TagKey.create(Registries.BLOCK,
            ResourceLocation.fromNamespaceAndPath("buildinggadgets2", "deny"));

    /** How many distinct protected blocks to name back before summarising. */
    private static final int MAX_NAMED = 12;

    /**
     * Snapshot the whole job BEFORE changing any of it.
     *
     * <p>Recording each block as we destroy it looks equivalent and is not.
     * {@link BlockPos#betweenClosed} walks x fastest and y next, so a support
     * block is reached before whatever stands on top of it - and destroying the
     * support pops the torch above immediately. By the time the walk arrives at
     * the torch's own position it is already air, so it was never recorded, and
     * undo could not put it back. It was then counted as "already air", which
     * hid the whole thing behind a number that looked fine.
     *
     * <p>Found the honest way, 2026-09-06: a cleared volume of stone with a
     * torch, a lever and a redstone torch on it came back with
     * {@code restored: 10} and two of the three attachments missing. The lever
     * survived only because its support happened to sit later in the walk.
     *
     * <p>Also records a one-block shell around the box, filtered to blocks that
     * are not full cubes. A torch on the OUTSIDE face of a wall being cleared
     * pops for the same reason, and it is the non-full blocks - torches,
     * levers, buttons, signs, ladders, rails, dust - that hang on their
     * neighbours. Full blocks are left out because a solid shell would be
     * thousands of pointless records. Falling blocks resting on top of the box
     * are a known gap: sand above a cleared volume comes down and is not
     * tracked.
     *
     * @return how many positions inside the box were air before we started -
     *         the honest count, as opposed to what is air once we are underway
     */
    private static int snapshot(ServerLevel level, BlockPos from, BlockPos to) {
        int wasAir = 0;
        for (BlockPos p : BlockPos.betweenClosed(from, to)) {
            if (level.getBlockState(p).isAir()) {
                wasAir++;
                continue;
            }
            Undo.record(level, p);
        }

        BlockPos lo = new BlockPos(Math.min(from.getX(), to.getX()),
                Math.min(from.getY(), to.getY()), Math.min(from.getZ(), to.getZ()));
        BlockPos hi = new BlockPos(Math.max(from.getX(), to.getX()),
                Math.max(from.getY(), to.getY()), Math.max(from.getZ(), to.getZ()));
        for (BlockPos p : BlockPos.betweenClosed(lo.offset(-1, -1, -1), hi.offset(1, 1, 1))) {
            if (p.getX() >= lo.getX() && p.getX() <= hi.getX()
                    && p.getY() >= lo.getY() && p.getY() <= hi.getY()
                    && p.getZ() >= lo.getZ() && p.getZ() <= hi.getZ()) {
                continue;                       // inside the box, already done
            }
            BlockState st = level.getBlockState(p);
            if (st.isAir() || st.isCollisionShapeFullBlock(level, p)) {
                continue;
            }
            Undo.record(level, p);
        }
        return wasAir;
    }

    /** The box, clamped, or null when it is bigger than the cap. */
    private static long volume(BlockPos a, BlockPos b) {
        long dx = Math.abs(a.getX() - b.getX()) + 1L;
        long dy = Math.abs(a.getY() - b.getY()) + 1L;
        long dz = Math.abs(a.getZ() - b.getZ()) + 1L;
        return dx * dy * dz;
    }

    private static Map<String, Object> tooBig(long size) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", false);
        out.put("error", "area too large");
        out.put("blocks", size);
        out.put("cap", MAX_BLOCKS);
        out.put("detail", "That is " + size + " blocks; I will do " + MAX_BLOCKS
                + " at a time. Ask for it in pieces.");
        return out;
    }

    /**
     * Empty a box.
     *
     * @param drop whether the blocks yield their items, as breaking them would
     */
    static Map<String, Object> clear(ServerLevel level, BlockPos from, BlockPos to, boolean drop) {
        long size = volume(from, to);
        if (size > MAX_BLOCKS) {
            return tooBig(size);
        }
        Undo.begin(level, "clear " + size + " blocks");
        int alreadyAir = snapshot(level, from, to);
        int cleared = 0;
        List<String> refusedNames = new ArrayList<>();
        int refused = 0;

        for (BlockPos p : BlockPos.betweenClosed(from, to)) {
            BlockState st = level.getBlockState(p);
            if (st.isAir()) {
                // Either air before we started, or something we knocked down
                // on the way. Both are counted from the snapshot, not here.
                continue;
            }
            if (st.is(DENY)) {
                refused++;
                String id = BuiltInRegistries.BLOCK.getKey(st.getBlock()).toString();
                if (!refusedNames.contains(id) && refusedNames.size() < MAX_NAMED) {
                    refusedNames.add(id);
                }
                continue;
            }
            // immutable(): betweenClosed hands back one mutable cursor, and
            // destroyBlock can run long enough for it to have moved on.
            if (level.destroyBlock(p.immutable(), drop)) {
                cleared++;
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("cleared", cleared);
        out.put("alreadyAir", alreadyAir);
        out.put("scanned", size);
        // Anything that went to air without us breaking it: an attachment
        // whose support we took first. Named rather than folded into
        // alreadyAir, because "I did not touch it" and "it fell down because
        // of me" are different answers and the caller deserves the second one.
        long collateral = size - alreadyAir - cleared - refused;
        if (collateral > 0) {
            out.put("collapsed", collateral);
            out.put("collapsedNote", "came down when their support went; "
                    + "recorded before the job started, so undo restores them");
        }
        if (refused > 0) {
            out.put("refused", refused);
            out.put("refusedKinds", refusedNames);
            out.put("why", "in buildinggadgets2:deny - left standing on purpose");
        }
        return out;
    }

    /**
     * Fill a box with one block.
     *
     * @param onlyAir when true, existing blocks are left alone - the difference
     *                between "build this wall" and "replace whatever is there"
     */
    static Map<String, Object> fill(ServerLevel level, BlockPos from, BlockPos to,
                                    Block what, boolean onlyAir) {
        long size = volume(from, to);
        if (size > MAX_BLOCKS) {
            return tooBig(size);
        }
        Undo.begin(level, "fill " + size + " with "
                + BuiltInRegistries.BLOCK.getKey(what));
        // Same reasoning as clear: filling destroys, and destroying collapses.
        snapshot(level, from, to);
        BlockState want = what.defaultBlockState();
        int placed = 0;
        int skipped = 0;
        int refused = 0;
        List<String> refusedNames = new ArrayList<>();

        for (BlockPos p : BlockPos.betweenClosed(from, to)) {
            BlockState st = level.getBlockState(p);
            if (onlyAir && !st.isAir()) {
                skipped++;
                continue;
            }
            // Overwriting a protected block destroys it just as surely as
            // breaking it does, so fill honours the tag exactly as clear does.
            if (st.is(DENY)) {
                refused++;
                String id = BuiltInRegistries.BLOCK.getKey(st.getBlock()).toString();
                if (!refusedNames.contains(id) && refusedNames.size() < MAX_NAMED) {
                    refusedNames.add(id);
                }
                continue;
            }
            if (st.getBlock() == what) {
                skipped++;
                continue;
            }
            if (!st.isAir() && what != Blocks.AIR) {
                // Break it first so it drops rather than being annihilated.
                level.destroyBlock(p.immutable(), true);
            }
            if (level.setBlockAndUpdate(p.immutable(), want)) {
                placed++;
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("block", BuiltInRegistries.BLOCK.getKey(what).toString());
        out.put("placed", placed);
        out.put("skipped", skipped);
        out.put("scanned", size);
        if (refused > 0) {
            out.put("refused", refused);
            out.put("refusedKinds", refusedNames);
            out.put("why", "in buildinggadgets2:deny - left standing on purpose");
        }
        return out;
    }
}
