package ghost;

import appeng.api.AECapabilities;
import appeng.api.networking.IGrid;
import appeng.api.networking.IGridNode;
import appeng.api.networking.IInWorldGridNodeHost;
import appeng.api.networking.storage.IStorageService;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.storage.MEStorage;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;

import java.util.HashSet;
import java.util.LinkedHashSet;
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
 * <h2>How a network is found, and the wrong turn this used to take</h2>
 *
 * <p>This asked every block in range for AE2's {@code ME_STORAGE} capability.
 * That capability is exposed by only a <b>handful</b> of blocks, and a drive is
 * not one of them - nor is a controller. So standing directly on an
 * {@code extendedae:ex_drive}, beside an online controller, this reported
 * <b>zero networks</b>, and a caller had no way to tell that apart from an empty
 * network. Exactly the failure this class was written to prevent, one level up.
 *
 * <p>The route that actually works is the one the crafting side already used:
 * {@code IN_WORLD_GRID_NODE_HOST} is exposed by <b>every</b> grid-connected
 * block - drive, controller, cable, terminal, interface - and leads to the
 * {@link IGrid}, whose {@link IStorageService} owns the real inventory. Both
 * halves of this mod now find networks the same way, because having two ways in
 * where only one of them worked is how this went unnoticed.
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
     * Cap on the search cube.
     *
     * <p>The radius is cubed: 32 is already a quarter of a million block-entity
     * lookups, and 100 - which a caller reasonably tried - is eight million.
     * A network is found from any block touching it, so a large radius buys
     * nothing that a sensible one does not.
     */
    private static final int MAX_RADIUS = 32;

    /**
     * Every distinct grid reachable from a block in range.
     *
     * <p>Deduplicated by the grid itself rather than by position: a network
     * answers through every block attached to it, so a room full of terminals
     * is one network, not a dozen.
     */
    static Set<IGrid> gridsNear(ServerLevel level, BlockPos centre, int radius) {
        int r = Math.min(Math.max(radius, 0), MAX_RADIUS);
        Set<IGrid> grids = new LinkedHashSet<>();
        for (BlockPos p : BlockPos.betweenClosed(centre.offset(-r, -r, -r),
                                                 centre.offset(r, r, r))) {
            if (level.getBlockEntity(p) == null) {
                continue;                     // capabilities live on block entities
            }
            IInWorldGridNodeHost host;
            try {
                host = level.getCapability(AECapabilities.IN_WORLD_GRID_NODE_HOST, p, null);
            } catch (Exception e) {
                continue;
            }
            if (host == null) {
                continue;
            }
            // A host may only answer on the faces it is actually connected by,
            // and null is a legitimate ask for "the internal node".
            for (Direction d : FACES) {
                try {
                    IGridNode node = host.getGridNode(d);
                    if (node != null && node.getGrid() != null) {
                        grids.add(node.getGrid());
                        break;
                    }
                } catch (Exception ignored) {
                    // a host that dislikes a particular face is not an error
                }
            }
        }
        return grids;
    }

    /** Every face, plus the internal node. */
    private static final Direction[] FACES;

    static {
        Direction[] dirs = Direction.values();
        FACES = new Direction[dirs.length + 1];
        System.arraycopy(dirs, 0, FACES, 0, dirs.length);
        FACES[dirs.length] = null;
    }

    /** Count one item across every distinct ME network in range. */
    static long count(ServerLevel level, BlockPos centre, int radius, Item want) {
        AEKey wanted = AEItemKey.of(want);
        Set<MEStorage> seen = new HashSet<>();
        long total = 0;
        for (IGrid grid : gridsNear(level, centre, radius)) {
            IStorageService storage = grid.getService(IStorageService.class);
            if (storage == null) {
                continue;
            }
            MEStorage inv = storage.getInventory();
            if (inv == null || !seen.add(inv)) {
                continue;
            }
            try {
                // Ask for the one key rather than walking every stack: a big
                // network holds tens of thousands of entries and this is called
                // to answer a single question.
                total += inv.getAvailableStacks().get(wanted);
            } catch (Exception e) {
                Ghost.LOG.warn("could not read an ME network near {}", centre, e);
            }
        }
        return total;
    }

    /** How many distinct networks are reachable - reported so a zero can be explained. */
    static int networks(ServerLevel level, BlockPos centre, int radius) {
        return gridsNear(level, centre, radius).size();
    }
}
