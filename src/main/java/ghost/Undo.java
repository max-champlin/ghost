package ghost;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One step back. A mulligan, not a history.
 *
 * <p>Shelby no longer refuses to touch anything - she does the job and says
 * loudly when it was something normally protected. That is the right shape for
 * an assistant rather than a tool, but it only works if a wrong instruction can
 * be taken back. Legibility without recovery just means you get to watch the
 * mistake happen.
 *
 * <p><b>Exactly one step.</b> Not a stack, not a timeline. A second destructive
 * action replaces the record, and undoing clears it - so there is never a
 * question of how far back "undo" goes, and no way to walk backwards through an
 * afternoon's work by accident. If you need more than one step you need a
 * backup, and pretending otherwise would be worse than offering nothing.
 *
 * <p>Restores blocks and their block-entity contents: undoing a broken chest
 * gives back the chest AND what was in it. It does NOT recall the items the
 * original break dropped - those are on the floor. Said plainly in the result
 * rather than quietly leaving you with duplicates you did not expect.
 */
final class Undo {

    private Undo() {
    }

    /** What was there before, at one position. */
    private record Was(BlockState state, CompoundTag data) {
    }

    /** Matches the fill/clear cap: nothing can record more than it can change. */
    private static final int MAX = 4096;

    private static ServerLevel where;
    private static String description = "";
    private static long at;
    private static boolean capped;
    private static final Map<BlockPos, Was> BEFORE = new LinkedHashMap<>();

    /** Start recording a new action, discarding whatever the last one was. */
    static synchronized void begin(ServerLevel level, String what) {
        BEFORE.clear();
        capped = false;
        where = level;
        description = what;
        at = System.currentTimeMillis();
    }

    /**
     * Capture one position, BEFORE it changes.
     *
     * <p>Only the first capture of a position counts. A fill that breaks a block
     * and then places over the same spot must remember the ORIGINAL, not the
     * intermediate - otherwise undo restores a state that never existed for
     * longer than a tick.
     */
    static synchronized void record(ServerLevel level, BlockPos pos) {
        if (level != where || BEFORE.size() >= MAX) {
            if (BEFORE.size() >= MAX) {
                capped = true;
            }
            return;
        }
        BlockPos key = pos.immutable();
        if (BEFORE.containsKey(key)) {
            return;
        }
        BlockState state = level.getBlockState(key);
        CompoundTag data = null;
        BlockEntity be = level.getBlockEntity(key);
        if (be != null) {
            try {
                data = be.saveWithFullMetadata(level.registryAccess());
            } catch (Exception e) {
                Ghost.LOG.warn("could not snapshot the block entity at {}", key, e);
            }
        }
        BEFORE.put(key, new Was(state, data));
    }

    /** Is there anything to take back? */
    static synchronized boolean available() {
        return where != null && !BEFORE.isEmpty();
    }

    static synchronized Map<String, Object> describe() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("available", available());
        if (available()) {
            out.put("action", description);
            out.put("blocks", BEFORE.size());
            out.put("secondsAgo", (System.currentTimeMillis() - at) / 1000);
            out.put("dimension", where.dimension().location().toString());
        }
        return out;
    }

    /** Put it all back. */
    static synchronized Map<String, Object> undo() {
        Map<String, Object> out = new LinkedHashMap<>();
        if (!available()) {
            out.put("ok", false);
            out.put("error", "nothing to undo - I only ever keep the last action");
            return out;
        }
        int restored = 0;
        int failed = 0;
        for (Map.Entry<BlockPos, Was> e : BEFORE.entrySet()) {
            try {
                BlockPos p = e.getKey();
                where.setBlock(p, e.getValue().state(), 3);
                CompoundTag data = e.getValue().data();
                if (data != null) {
                    BlockEntity be = where.getBlockEntity(p);
                    if (be != null) {
                        be.loadWithComponents(data, where.registryAccess());
                        be.setChanged();
                    }
                }
                restored++;
            } catch (Exception ex) {
                failed++;
                Ghost.LOG.warn("could not restore {}", e.getKey(), ex);
            }
        }
        out.put("ok", restored > 0);
        out.put("undid", description);
        out.put("restored", restored);
        if (failed > 0) {
            out.put("failed", failed);
        }
        if (capped) {
            out.put("partial", "that action changed more than " + MAX
                    + " blocks, so only the first " + MAX + " were remembered");
        }
        out.put("note", "blocks and their contents are back. Anything the "
                + "original action DROPPED is still on the floor - pick it up or "
                + "you will be holding a copy.");
        // One step means one. Nothing to walk back to now.
        BEFORE.clear();
        where = null;
        description = "";
        out.put("stepsLeft", 0);
        return out;
    }

    /** Human-readable name for the thing being recorded. */
    static String label(String verb, BlockState st) {
        return verb + " " + BuiltInRegistries.BLOCK.getKey(st.getBlock());
    }
}
