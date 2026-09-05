package ghost;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.wrapper.InvWrapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reaching into a machine, slot by slot.
 *
 * <p>A player standing at a furnace does not "insert an item into the block";
 * they open it, see three slots, and put the coal in the bottom one. Everything
 * this bridge could do until now was the former - hand a stack to a block and
 * hope its own insert logic put it somewhere useful. That fails exactly where
 * it matters most: a machine with a fuel slot and an input slot will take coal
 * into either, and which one it picks is not something the caller controls.
 *
 * <p>So these verbs name the slot. {@code list} to see what a machine has,
 * {@code take} and {@code put} to move one specific stack in or out of one
 * specific slot, through Shelby's satchel.
 *
 * <p>Item handlers first, vanilla {@link Container} as the fallback - the same
 * order {@link Ae2Cells} uses, and for the same reason: modded machines answer
 * the capability and a plain chest does not.
 */
public final class Slots {

    private Slots() {
    }

    /** Slots listed in one call, so a 500-slot drawer controller cannot flood a reply. */
    private static final int MAX_SLOTS = 200;

    /** The block's inventory, or null if it has none. */
    private static IItemHandler handlerAt(ServerLevel level, BlockPos p) {
        try {
            IItemHandler h = level.getCapability(Capabilities.ItemHandler.BLOCK, p, null);
            if (h != null) {
                return h;
            }
        } catch (Exception ignored) {
            // fall through to the vanilla container
        }
        return level.getBlockEntity(p) instanceof Container c ? new InvWrapper(c) : null;
    }

    private static Map<String, Object> noInventory(BlockPos p) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", false);
        out.put("error", "nothing to open there");
        out.put("at", List.of(p.getX(), p.getY(), p.getZ()));
        return out;
    }

    private static Map<String, Object> describe(ItemStack st) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("item", BuiltInRegistries.ITEM.getKey(st.getItem()).toString());
        m.put("count", st.getCount());
        if (st.isDamaged()) {
            m.put("damage", st.getDamageValue());
        }
        return m;
    }

    /** What is in each slot. */
    static Map<String, Object> list(ServerLevel level, BlockPos p) {
        IItemHandler h = handlerAt(level, p);
        if (h == null) {
            return noInventory(p);
        }
        List<Map<String, Object>> slots = new ArrayList<>();
        int shown = Math.min(h.getSlots(), MAX_SLOTS);
        int filled = 0;
        for (int i = 0; i < shown; i++) {
            ItemStack st = h.getStackInSlot(i);
            if (st.isEmpty()) {
                continue;
            }
            filled++;
            Map<String, Object> row = describe(st);
            row.put("slot", i);
            slots.add(row);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("at", List.of(p.getX(), p.getY(), p.getZ()));
        out.put("block", BuiltInRegistries.BLOCK.getKey(
                level.getBlockState(p).getBlock()).toString());
        out.put("slots", h.getSlots());
        out.put("filled", filled);
        out.put("contents", slots);
        if (h.getSlots() > MAX_SLOTS) {
            out.put("note", "showing the first " + MAX_SLOTS + " of " + h.getSlots() + " slots");
        }
        return out;
    }

    /**
     * Take from one slot into the satchel.
     *
     * <p>Extracts by simulation first so a satchel with no room refuses the job
     * cleanly instead of pulling a stack out of the machine and dropping it on
     * the floor, which is how an item disappears in a way nobody can trace.
     */
    static Map<String, Object> take(ServerLevel level, BlockPos p, int slot, int count,
                                    SimpleContainer bag) {
        IItemHandler h = handlerAt(level, p);
        if (h == null) {
            return noInventory(p);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        if (slot < 0 || slot >= h.getSlots()) {
            out.put("ok", false);
            out.put("error", "no slot " + slot + " - it has " + h.getSlots());
            return out;
        }
        ItemStack peek = h.extractItem(slot, count, true);
        if (peek.isEmpty()) {
            out.put("ok", false);
            out.put("error", "slot " + slot + " is empty, or will not give that up");
            return out;
        }
        ItemStack leftover = bag.addItem(peek.copy());
        int room = peek.getCount() - leftover.getCount();
        if (room <= 0) {
            out.put("ok", false);
            out.put("error", "my satchel is full");
            return out;
        }
        // Only now, and only as much as actually fitted.
        ItemStack got = h.extractItem(slot, room, false);
        out.put("ok", true);
        out.put("took", describe(got));
        out.put("from", slot);
        if (got.getCount() < count) {
            out.put("short", "asked for " + count + ", got " + got.getCount());
        }
        return out;
    }

    /**
     * Put an item from the satchel into one slot.
     *
     * @param want the item to hand over, or null to use whatever is first
     */
    static Map<String, Object> put(ServerLevel level, BlockPos p, int slot, int count,
                                   Item want, SimpleContainer bag) {
        IItemHandler h = handlerAt(level, p);
        if (h == null) {
            return noInventory(p);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        if (slot < 0 || slot >= h.getSlots()) {
            out.put("ok", false);
            out.put("error", "no slot " + slot + " - it has " + h.getSlots());
            return out;
        }
        int found = -1;
        for (int i = 0; i < bag.getContainerSize(); i++) {
            ItemStack st = bag.getItem(i);
            if (!st.isEmpty() && (want == null || st.is(want))) {
                found = i;
                break;
            }
        }
        if (found < 0) {
            out.put("ok", false);
            out.put("error", want == null
                    ? "my satchel is empty"
                    : "I am not carrying any " + BuiltInRegistries.ITEM.getKey(want));
            return out;
        }
        ItemStack have = bag.getItem(found);
        ItemStack offer = have.copy();
        offer.setCount(Math.min(count, have.getCount()));

        ItemStack rejected = h.insertItem(slot, offer, false);
        int moved = offer.getCount() - rejected.getCount();
        if (moved <= 0) {
            out.put("ok", false);
            out.put("error", "slot " + slot + " would not accept that");
            out.put("item", BuiltInRegistries.ITEM.getKey(have.getItem()).toString());
            return out;
        }
        have.shrink(moved);
        if (have.isEmpty()) {
            bag.setItem(found, ItemStack.EMPTY);
        }
        out.put("ok", true);
        out.put("put", moved);
        out.put("item", BuiltInRegistries.ITEM.getKey(offer.getItem()).toString());
        out.put("into", slot);
        return out;
    }

    /**
     * What Shelby has ON, slot by slot.
     *
     * <p>Separate from the satchel on purpose: worn and carried are different
     * questions, and the one that prompted this was "where did the armour I
     * gave her go", which the satchel cannot answer either way.
     *
     * <p>Reports damage and enchantments, because "she still has the chestplate"
     * and "she has the chestplate and it is two hits from breaking" are not the
     * same answer - a helmet at 310 damage looked fine right up until it did not.
     */
    static Map<String, Object> worn(net.minecraft.world.entity.LivingEntity who) {
        List<Map<String, Object>> on = new ArrayList<>();
        int pieces = 0;
        for (net.minecraft.world.entity.EquipmentSlot slot
                : net.minecraft.world.entity.EquipmentSlot.values()) {
            ItemStack st = who.getItemBySlot(slot);
            if (st.isEmpty()) {
                continue;
            }
            pieces++;
            Map<String, Object> row = describe(st);
            row.put("slot", slot.getName());
            if (st.isDamageableItem()) {
                row.put("durability", st.getMaxDamage() - st.getDamageValue());
                row.put("maxDurability", st.getMaxDamage());
            }
            on.add(row);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("wearing", pieces);
        out.put("equipment", on);
        if (pieces == 0) {
            out.put("note", "nothing equipped at all - not even a held item");
        }
        return out;
    }

    /** A one-line summary of what is worn, for saying out loud in chat. */
    public static String wornLine(net.minecraft.world.entity.LivingEntity who) {
        StringBuilder sb = new StringBuilder();
        for (net.minecraft.world.entity.EquipmentSlot slot
                : net.minecraft.world.entity.EquipmentSlot.values()) {
            ItemStack st = who.getItemBySlot(slot);
            if (st.isEmpty()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(slot.getName()).append(": ").append(st.getHoverName().getString());
            if (st.isDamageableItem()) {
                sb.append(" (").append(st.getMaxDamage() - st.getDamageValue())
                        .append("/").append(st.getMaxDamage()).append(")");
            }
        }
        return sb.length() == 0 ? "nothing at all" : sb.toString();
    }

    /** What Shelby is carrying. */
    static Map<String, Object> bag(SimpleContainer bag) {
        List<Map<String, Object>> rows = new ArrayList<>();
        int used = 0;
        for (int i = 0; i < bag.getContainerSize(); i++) {
            ItemStack st = bag.getItem(i);
            if (st.isEmpty()) {
                continue;
            }
            used++;
            Map<String, Object> row = describe(st);
            row.put("slot", i);
            rows.add(row);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("slots", bag.getContainerSize());
        out.put("used", used);
        out.put("carrying", rows);
        return out;
    }
}
