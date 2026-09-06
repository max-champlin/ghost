package ghost;

import appeng.api.AECapabilities;
import appeng.api.networking.IGrid;
import appeng.api.networking.IGridNode;
import appeng.api.networking.IInWorldGridNodeHost;
import appeng.api.networking.storage.IStorageService;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.config.Actionable;
import appeng.api.networking.security.IActionSource;
import appeng.api.storage.MEStorage;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.HashSet;
import java.util.Map;
import java.util.LinkedHashMap;
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

    /**
     * Take an item out of the network and into a carried container.
     *
     * <p>The gap this fills: {@code craft} can make a thing that afterwards
     * exists only as a number in ME storage. It is in no block's inventory, so
     * {@code slots} cannot see it and {@code take} cannot reach it - reading a
     * drive shows the storage CELLS sitting in their slots, not the virtual
     * contents held inside them. Without this, crafting something on the network
     * and then carrying it anywhere was not expressible at all.
     *
     * <p>Room is measured before anything is extracted, and only what fits is
     * pulled. Anything that somehow still will not go in is pushed straight
     * back. Items are never destroyed to make an operation look tidy.
     */
    static Map<String, Object> withdraw(ServerLevel level, BlockPos centre, int radius,
                                        Item want, int count,
                                        net.minecraft.world.SimpleContainer into) {
        AEItemKey key = AEItemKey.of(want);
        Map<String, Object> out = new LinkedHashMap<>();
        int max = new ItemStack(want).getMaxStackSize();

        // Free space for THIS item, counted without mutating anything.
        int room = 0;
        for (int i = 0; i < into.getContainerSize(); i++) {
            ItemStack st = into.getItem(i);
            if (st.isEmpty()) {
                room += max;
            } else if (st.is(want)) {
                room += Math.max(0, st.getMaxStackSize() - st.getCount());
            }
        }
        if (room <= 0) {
            out.put("ok", false);
            out.put("error", "no room in the satchel for "
                    + BuiltInRegistries.ITEM.getKey(want));
            return out;
        }

        int wanted = Math.min(count, room);
        long got = 0;
        int grids = 0;
        Set<MEStorage> seen = new HashSet<>();
        for (IGrid grid : gridsNear(level, centre, radius)) {
            if (got >= wanted) {
                break;
            }
            IStorageService storage = grid.getService(IStorageService.class);
            if (storage == null) {
                continue;
            }
            MEStorage inv = storage.getInventory();
            if (inv == null || !seen.add(inv)) {
                continue;
            }
            grids++;
            try {
                got += inv.extract(key, wanted - got, Actionable.MODULATE,
                        IActionSource.empty());
            } catch (Exception e) {
                Ghost.LOG.warn("could not withdraw from a network near {}", centre, e);
            }
        }

        long placed = 0;
        long left = got;
        while (left > 0) {
            int n = (int) Math.min(max, left);
            ItemStack over = into.addItem(new ItemStack(want, n));
            placed += n - over.getCount();
            if (!over.isEmpty()) {
                // Should not happen - room was measured up front - but if it
                // does the remainder goes back rather than vanishing.
                putBack(level, centre, radius, key, over.getCount());
                break;
            }
            left -= n;
        }

        out.put("ok", placed > 0);
        out.put("item", BuiltInRegistries.ITEM.getKey(want).toString());
        out.put("withdrew", placed);
        out.put("networks", grids);
        if (placed == 0) {
            out.put("error", grids == 0
                    ? "no ME network within " + radius + " blocks"
                    : "the network has none of that");
        } else if (placed < count) {
            out.put("note", "asked for " + count + ", got " + placed
                    + " - limited by stock or satchel room");
        }
        return out;
    }

    /** Return items to the first network that will take them. */
    private static void putBack(ServerLevel level, BlockPos centre, int radius,
                                AEItemKey key, int amount) {
        for (IGrid grid : gridsNear(level, centre, radius)) {
            IStorageService storage = grid.getService(IStorageService.class);
            if (storage == null || storage.getInventory() == null) {
                continue;
            }
            storage.getInventory().insert(key, amount, Actionable.MODULATE,
                    IActionSource.empty());
            return;
        }
    }

    /**
     * Put carried items back into the network.
     *
     * <p>The other half of {@link #withdraw}, and the reason "go and tidy that
     * up" can end somewhere useful. Only moves what the network actually
     * accepts: a full network leaves the remainder in the satchel rather than
     * eating it.
     *
     * @param want the item to deposit, or null for everything carried
     */
    static Map<String, Object> deposit(ServerLevel level, BlockPos centre, int radius,
                                       Item want,
                                       net.minecraft.world.SimpleContainer from) {
        Map<String, Object> out = new LinkedHashMap<>();
        MEStorage inv = null;
        int grids = 0;
        for (IGrid grid : gridsNear(level, centre, radius)) {
            IStorageService storage = grid.getService(IStorageService.class);
            if (storage == null || storage.getInventory() == null) {
                continue;
            }
            grids++;
            if (inv == null) {
                inv = storage.getInventory();
            }
        }
        if (inv == null) {
            out.put("ok", false);
            out.put("error", "no ME network within " + radius + " blocks");
            out.put("networks", 0);
            return out;
        }

        long moved = 0;
        int kinds = 0;
        for (int i = 0; i < from.getContainerSize(); i++) {
            ItemStack st = from.getItem(i);
            if (st.isEmpty() || (want != null && !st.is(want))) {
                continue;
            }
            try {
                long in = inv.insert(AEItemKey.of(st.getItem()), st.getCount(),
                        Actionable.MODULATE, IActionSource.empty());
                if (in > 0) {
                    st.shrink((int) in);
                    if (st.isEmpty()) {
                        from.setItem(i, ItemStack.EMPTY);
                    }
                    moved += in;
                    kinds++;
                }
            } catch (Exception e) {
                Ghost.LOG.warn("could not deposit into a network near {}", centre, e);
            }
        }
        out.put("ok", moved > 0);
        out.put("deposited", moved);
        out.put("stacks", kinds);
        out.put("networks", grids);
        if (moved == 0) {
            out.put("error", want == null
                    ? "nothing in the satchel the network would take"
                    : "not carrying any " + BuiltInRegistries.ITEM.getKey(want));
        }
        return out;
    }

    /** How many distinct networks are reachable - reported so a zero can be explained. */
    static int networks(ServerLevel level, BlockPos centre, int radius) {
        return gridsNear(level, centre, radius).size();
    }
}
