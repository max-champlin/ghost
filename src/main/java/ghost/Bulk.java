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
        int cleared = 0;
        int alreadyAir = 0;
        List<String> refusedNames = new ArrayList<>();
        int refused = 0;

        for (BlockPos p : BlockPos.betweenClosed(from, to)) {
            BlockState st = level.getBlockState(p);
            if (st.isAir()) {
                alreadyAir++;
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
