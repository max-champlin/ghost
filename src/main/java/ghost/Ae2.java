package ghost;

import appeng.api.AECapabilities;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.KeyCounter;
import appeng.api.storage.MEStorage;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;

import java.util.HashSet;
import java.util.Set;

/**
 * Reads what is inside an ME network.
 *
 * <p>The gap this closes: {@code have} and {@code can} walk block entities that
 * implement {@link net.minecraft.world.Container} - chests, barrels, most modded
 * storage - and an ME cell is not one. On a base where most things live in the
 * network, the counts were quietly and badly wrong, and wrong counts are worse
 * than no counts because nobody doubts them.
 *
 * <p><b>This class must only ever be touched when AE2 is actually loaded.</b>
 * It references AE2 types directly, so the JVM resolves them the moment it is
 * first used - calling into it without AE2 present throws
 * {@code NoClassDefFoundError}. {@link Storage} does the guarding; nothing else
 * should reference this class.
 *
 * <p>Read-only. It counts what a network holds and never extracts anything.
 */
final class Ae2 {

    private Ae2() {
    }

    /**
     * Count one item across every distinct ME network in range.
     *
     * <p>Networks are found through AE2's own {@code ME_STORAGE} block
     * capability, which is the supported way in - anything a cable can reach
     * answers it, so a terminal, an interface or a chest all work as a door.
     *
     * <p>Deduplicated by the storage object rather than by position: a network
     * presents the same {@link MEStorage} through every block attached to it,
     * so scanning a room full of terminals would otherwise report the same
     * 4,000 certus quartz a dozen times over.
     */
    static long count(ServerLevel level, BlockPos centre, int radius, Item want) {
        Set<MEStorage> seen = new HashSet<>();
        long total = 0;
        for (BlockPos p : BlockPos.betweenClosed(centre.offset(-radius, -radius, -radius),
                                                 centre.offset(radius, radius, radius))) {
            if (level.getBlockEntity(p) == null) {
                continue;                     // capabilities live on block entities
            }
            for (Direction d : Direction.values()) {
                MEStorage storage;
                try {
                    storage = level.getCapability(AECapabilities.ME_STORAGE, p, d);
                } catch (Exception e) {
                    continue;
                }
                if (storage == null || !seen.add(storage)) {
                    continue;
                }
                try {
                    KeyCounter counter = storage.getAvailableStacks();
                    for (var entry : counter) {
                        AEKey key = entry.getKey();
                        if (key instanceof AEItemKey ik && ik.getItem() == want) {
                            total += entry.getLongValue();
                        }
                    }
                } catch (Exception e) {
                    Ghost.LOG.warn("could not read an ME network at {}", p, e);
                }
            }
        }
        return total;
    }

    /** How many distinct networks are reachable - reported so a zero can be explained. */
    static int networks(ServerLevel level, BlockPos centre, int radius) {
        Set<MEStorage> seen = new HashSet<>();
        for (BlockPos p : BlockPos.betweenClosed(centre.offset(-radius, -radius, -radius),
                                                 centre.offset(radius, radius, radius))) {
            if (level.getBlockEntity(p) == null) {
                continue;
            }
            for (Direction d : Direction.values()) {
                try {
                    MEStorage s = level.getCapability(AECapabilities.ME_STORAGE, p, d);
                    if (s != null) {
                        seen.add(s);
                    }
                } catch (Exception ignored) {
                    // a block entity that dislikes being asked is not an error
                }
            }
        }
        return seen.size();
    }
}
