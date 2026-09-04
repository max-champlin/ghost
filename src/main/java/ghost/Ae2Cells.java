package ghost;

import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.storage.StorageCells;
import appeng.api.storage.cells.StorageCell;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.items.IItemHandler;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Which cell holds a thing, and how much of it.
 *
 * <p>"Where are my blaze seeds" is a question players actually ask, and until
 * now the only way to answer it was to dump a drive's whole NBT and read the
 * cell contents out by hand. That does not work: one unpartitioned junk cell
 * fills the entire NBT budget, so the first cell in the first drive is as far as
 * anyone ever got, with no way to reach the rest and no way to know what was
 * missed.
 *
 * <p>The NBT was the wrong tool. AE2 exposes every cell through
 * {@link StorageCells#getCellInventory}, which hands back a real
 * {@link StorageCell} - so each cell can be asked directly what it holds,
 * one at a time, with no dumping and no truncation. A drive is read cell by
 * cell, in slot order, and the answer names the slot.
 *
 * <p><b>Only touch this when AE2 is loaded.</b> {@link Storage} does the
 * guarding. Read-only throughout.
 */
final class Ae2Cells {

    private Ae2Cells() {
    }

    /** Drives to inspect in one call, so a wide radius cannot run away. */
    private static final int MAX_HOSTS = 32;

    /**
     * Look through every cell in range for {@code want}.
     *
     * @param want the item to look for, or null to simply list what is where
     */
    static Map<String, Object> find(ServerLevel level, BlockPos centre, int radius, Item want) {
        int r = Math.min(Math.max(radius, 0), 16);
        AEKey wanted = want == null ? null : AEItemKey.of(want);

        List<Map<String, Object>> hosts = new ArrayList<>();
        long grandTotal = 0;
        int cellsSeen = 0;
        boolean capped = false;

        for (BlockPos p : BlockPos.betweenClosed(centre.offset(-r, -r, -r),
                                                 centre.offset(r, r, r))) {
            if (hosts.size() >= MAX_HOSTS) {
                capped = true;
                break;
            }
            BlockEntity be = level.getBlockEntity(p);
            if (be == null) {
                continue;
            }
            List<ItemStack> slots = slotsOf(level, p);
            if (slots.isEmpty()) {
                continue;
            }

            List<Map<String, Object>> cells = new ArrayList<>();
            long hostTotal = 0;
            for (int i = 0; i < slots.size(); i++) {
                ItemStack stack = slots.get(i);
                if (stack.isEmpty() || !StorageCells.isCellHandled(stack)) {
                    continue;
                }
                StorageCell cell;
                try {
                    cell = StorageCells.getCellInventory(stack, null);
                } catch (Exception e) {
                    continue;
                }
                if (cell == null) {
                    continue;
                }
                cellsSeen++;
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("slot", i);
                entry.put("cell", stack.getHoverName().getString());
                try {
                    entry.put("status", String.valueOf(cell.getStatus()));
                } catch (Exception ignored) {
                    // a cell that will not state its status is still readable
                }
                try {
                    var stacks = cell.getAvailableStacks();
                    if (wanted != null) {
                        long held = stacks.get(wanted);
                        entry.put("held", held);
                        hostTotal += held;
                        grandTotal += held;
                        // Only cells that actually have some are interesting
                        // when a specific item was asked for.
                        if (held <= 0) {
                            continue;
                        }
                    } else {
                        int distinct = 0;
                        long items = 0;
                        for (var e : stacks) {
                            distinct++;
                            items += e.getLongValue();
                        }
                        entry.put("distinctTypes", distinct);
                        entry.put("totalItems", items);
                    }
                } catch (Exception e) {
                    entry.put("error", "could not read this cell");
                }
                cells.add(entry);
            }
            if (cells.isEmpty()) {
                continue;
            }
            Map<String, Object> host = new LinkedHashMap<>();
            host.put("at", List.of(p.getX(), p.getY(), p.getZ()));
            host.put("block", net.minecraft.core.registries.BuiltInRegistries.BLOCK
                    .getKey(level.getBlockState(p).getBlock()).toString());
            if (wanted != null) {
                host.put("held", hostTotal);
            }
            host.put("cells", cells);
            hosts.add(host);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("centre", List.of(centre.getX(), centre.getY(), centre.getZ()));
        out.put("radius", r);
        out.put("hosts", hosts);
        out.put("cellsInspected", cellsSeen);
        if (wanted != null) {
            out.put("total", grandTotal);
        }
        out.put("hostCapReached", capped);
        return out;
    }

    /**
     * The item slots of whatever is at {@code p}.
     *
     * <p>Tries the item-handler capability first, since AE2 drives and most
     * modded storage answer that, and falls back to vanilla {@link Container}
     * for the rest.
     */
    private static List<ItemStack> slotsOf(ServerLevel level, BlockPos p) {
        List<ItemStack> out = new ArrayList<>();
        try {
            IItemHandler handler = level.getCapability(Capabilities.ItemHandler.BLOCK, p, null);
            if (handler != null) {
                for (int i = 0; i < handler.getSlots(); i++) {
                    out.add(handler.getStackInSlot(i));
                }
                return out;
            }
        } catch (Exception ignored) {
            // fall through to the vanilla container
        }
        if (level.getBlockEntity(p) instanceof Container c) {
            for (int i = 0; i < c.getContainerSize(); i++) {
                out.add(c.getItem(i));
            }
        }
        return out;
    }
}
