package ghost;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeHolder;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * "Do I have the stuff?"
 *
 * <p>Counts an item across the player's inventory and every container within
 * reach, and can work backwards from a recipe to say what is missing. In a pack
 * with six hundred mods the answer is usually buried in one of forty chests,
 * and walking them is the boring part.
 *
 * <p>Containers are found by scanning block entities rather than by any mod's
 * API, so it reads whatever implements {@link Container} - vanilla chests,
 * barrels, most modded storage, machine inventories. Reads only; it never moves
 * an item.
 */
public final class Finder {

    private Finder() {
    }

    /** One place something was found. */
    public record Hit(BlockPos pos, String container, int count) {
    }

    public record Found(int total, List<Hit> hits, int inPlayer, long inNetwork) {
        /** Containers plus the player plus any ME network. */
        public long grandTotal() {
            return total + inNetwork;
        }
    }

    public static Item item(String id) {
        return BuiltInRegistries.ITEM.get(ResourceLocation.parse(id));
    }

    public static String idOf(Item item) {
        return BuiltInRegistries.ITEM.getKey(item).toString();
    }

    /**
     * Count one item in the player and in every container within radius.
     *
     * <p>Player inventory is counted separately: "you have 12, but 9 of them
     * are in your pocket" is a different answer to "you have 12 in a chest
     * somewhere", and the caller usually cares which.
     */
    public static Found count(ServerLevel level, ServerPlayer player, Item want, int radius) {
        int inPlayer = 0;
        if (player != null) {
            var inv = player.getInventory();
            for (int i = 0; i < inv.getContainerSize(); i++) {
                ItemStack s = inv.getItem(i);
                if (s.is(want)) {
                    inPlayer += s.getCount();
                }
            }
        }

        List<Hit> hits = new ArrayList<>();
        int total = inPlayer;
        BlockPos origin = player != null ? player.blockPosition() : BlockPos.ZERO;
        for (BlockPos p : BlockPos.betweenClosed(origin.offset(-radius, -radius, -radius),
                                                 origin.offset(radius, radius, radius))) {
            if (!(level.getBlockEntity(p) instanceof Container c)) {
                continue;
            }
            int here = 0;
            for (int i = 0; i < c.getContainerSize(); i++) {
                ItemStack s = c.getItem(i);
                if (s.is(want)) {
                    here += s.getCount();
                }
            }
            if (here > 0) {
                hits.add(new Hit(p.immutable(),
                        level.getBlockState(p).getBlock().getName().getString(), here));
                total += here;
            }
        }
        hits.sort((a, b) -> Integer.compare(b.count(), a.count()));
        // ME networks are counted separately and added on: they are not
        // containers and were previously invisible, which made every count on
        // an AE2 base quietly too low.
        long net = Storage.inNetworks(level, origin, Math.min(radius, 24), want);
        return new Found(total, hits, inPlayer, net);
    }

    /**
     * Locate every block of a type in a radius.
     *
     * <p>Distinct from {@link #count}: that searches the CONTENTS of containers,
     * this searches the world itself. "Where is my nearest sprinkler" and "how
     * many torches do I have in a chest" are different questions and were both
     * being asked of a method that only answered the second.
     *
     * <p>Matches on substring, so {@code "sprinkler"} finds it without the
     * namespace and {@code "mysticalagriculture:"} finds everything that mod
     * placed.
     */
    public static Map<String, Object> findBlocks(ServerLevel level, BlockPos centre,
                                                 String match, int radius) {
        String want = match.toLowerCase(java.util.Locale.ROOT);
        List<BlockPos> hits = new ArrayList<>();
        Map<String, Integer> byId = new java.util.TreeMap<>();
        for (BlockPos p : BlockPos.betweenClosed(centre.offset(-radius, -radius, -radius),
                                                 centre.offset(radius, radius, radius))) {
            var state = level.getBlockState(p);
            if (state.isAir()) {
                continue;
            }
            String id = net.minecraft.core.registries.BuiltInRegistries.BLOCK
                    .getKey(state.getBlock()).toString();
            if (!id.toLowerCase(java.util.Locale.ROOT).contains(want)) {
                continue;
            }
            byId.merge(id, 1, Integer::sum);
            if (hits.size() < 512) {
                hits.add(p.immutable());
            }
        }
        hits.sort(java.util.Comparator.comparingDouble(centre::distSqr));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("match", match);
        out.put("radius", radius);
        out.put("total", byId.values().stream().mapToInt(Integer::intValue).sum());
        out.put("byId", byId);
        if (!hits.isEmpty()) {
            BlockPos n = hits.get(0);
            out.put("nearest", List.of(n.getX(), n.getY(), n.getZ()));
            List<List<Integer>> all = new ArrayList<>();
            for (BlockPos p : hits) {
                all.add(List.of(p.getX(), p.getY(), p.getZ()));
            }
            out.put("positions", all);
            if (byId.values().stream().mapToInt(Integer::intValue).sum() > hits.size()) {
                out.put("note", "positions capped at 512; counts are complete");
            }
        }
        return out;
    }

    /** What a recipe needs, against what is actually to hand. */
    public record Need(String ingredient, int required, int have) {
        public boolean satisfied() {
            return have >= required;
        }
    }

    /**
     * Work out whether a recipe can be made from what is within reach.
     *
     * <p>Takes the FIRST listed option for each ingredient when a recipe accepts
     * alternatives (any plank, any log). That understates what is possible - the
     * player may hold a different plank than the one named - so a "missing"
     * result is a hint to go and look, not a verdict. Saying so is better than
     * silently picking one and being confidently wrong.
     */
    public static List<Need> canCraft(ServerLevel level, ServerPlayer player,
                                      Item result, int radius) {
        List<Need> needs = new ArrayList<>();
        RecipeHolder<?> chosen = null;
        for (RecipeHolder<?> holder : level.getServer().getRecipeManager().getRecipes()) {
            ItemStack out;
            try {
                out = holder.value().getResultItem(level.registryAccess());
            } catch (Exception e) {
                continue;
            }
            if (out != null && out.is(result)) {
                chosen = holder;
                break;
            }
        }
        if (chosen == null) {
            return needs;                       // no recipe: caller reports that
        }

        Map<String, Integer> required = new LinkedHashMap<>();
        for (Ingredient ing : chosen.value().getIngredients()) {
            if (ing.isEmpty()) {
                continue;
            }
            ItemStack[] options = ing.getItems();
            if (options.length == 0) {
                continue;
            }
            required.merge(idOf(options[0].getItem()), 1, Integer::sum);
        }
        for (Map.Entry<String, Integer> e : required.entrySet()) {
            Item ingItem = item(e.getKey());
            int have = (int) Math.min(Integer.MAX_VALUE,
                    count(level, player, ingItem, radius).grandTotal());
            needs.add(new Need(e.getKey(), e.getValue(), have));
        }
        return needs;
    }
}
