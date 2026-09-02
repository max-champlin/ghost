package ghost;

import appeng.api.config.Actionable;
import appeng.api.networking.IGrid;
import appeng.api.networking.security.IActionSource;
import appeng.api.networking.storage.IStorageService;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.storage.MEStorage;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeType;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Crafts something the network has no pattern for.
 *
 * <p>AE2 autocrafting can only make what someone has already encoded a pattern
 * for, which is the right rule for a factory and a poor one for an assistant.
 * Asked for a Block of Redstone, {@link Ae2Craft} correctly answers "no
 * pattern" - while nine redstone dust sit in the network and the recipe is
 * common knowledge. This closes that gap: look the recipe up, check the stock,
 * and do the crafting by hand.
 *
 * <h2>Where the recipe comes from</h2>
 *
 * <p>The server's own {@code RecipeManager}, not JEI. JEI is a client-side
 * viewer, and everything it displays for a craftable item comes from this same
 * manager - which already exists in the server process, already contains every
 * modded recipe, and needs no dependency. Reaching for JEI here would mean
 * adding a client mod to fetch data sitting in memory a method call away.
 *
 * <h2>What it will not do</h2>
 *
 * <p><b>One level only.</b> If a recipe needs an ingredient that is itself
 * missing, it stops and says so rather than recursively crafting the tree.
 * Recursion needs the whole plan proved affordable before a single item is
 * extracted, and a half-built tree that consumed the inputs and produced nothing
 * would be far worse than a clear refusal.
 *
 * <p>Every extraction is <b>simulated in full before anything is modulated</b>,
 * so a craft either happens completely or does not start. Items are never taken
 * out of the network for a craft that then turns out to be short.
 */
final class Ae2Direct {

    private Ae2Direct() {
    }

    /** Ingredients named in a "you are short of" message before summarising. */
    private static final int MISSING_SHOWN = 5;

    /**
     * Try to make {@code want} out of what the network is holding.
     *
     * @return a human-readable outcome, always - success or the reason not
     */
    static String attempt(ServerLevel level, IGrid grid, Item want, long amount,
                          ServerPlayer requester, boolean checkOnly) {
        IStorageService storageService = grid.getService(IStorageService.class);
        if (storageService == null) {
            return "that network has no storage to draw on";
        }
        MEStorage storage = storageService.getInventory();
        IActionSource source = requester != null
                ? IActionSource.ofPlayer(requester)
                : IActionSource.empty();

        List<RecipeHolder<CraftingRecipe>> candidates = recipesFor(level, want);
        if (candidates.isEmpty()) {
            return "there is no crafting recipe for " + want.getDescription().getString()
                    + " that I can do by hand";
        }

        // Several recipes can make the same item. Try each and take the first
        // the network can actually pay for, rather than failing on whichever
        // happened to be registered first.
        String shortfall = null;
        for (RecipeHolder<CraftingRecipe> holder : candidates) {
            ItemStack result = holder.value().getResultItem(level.registryAccess());
            if (result.isEmpty()) {
                continue;
            }
            int per = Math.max(1, result.getCount());
            int batches = (int) Math.max(1, (amount + per - 1) / per);

            Plan plan = plan(storage, holder.value(), batches);
            if (!plan.missing.isEmpty()) {
                if (shortfall == null) {
                    shortfall = (checkOnly ? "would be short of " : "cannot make "
                            + want.getDescription().getString() + " by hand - short of ")
                            + summarise(plan.missing);
                }
                continue;
            }
            if (checkOnly) {
                return "can make " + ((long) batches * per) + "x "
                        + result.getHoverName().getString() + " - would use "
                        + describe(plan.take) + ". Nothing taken.";
            }
            return execute(storage, source, plan, result, batches, per, requester);
        }
        return shortfall != null ? shortfall
                : "I could not work out a way to make that from what is in the network";
    }

    /** Every crafting-table recipe whose result is this item. */
    private static List<RecipeHolder<CraftingRecipe>> recipesFor(ServerLevel level, Item want) {
        List<RecipeHolder<CraftingRecipe>> out = new ArrayList<>();
        for (RecipeHolder<CraftingRecipe> holder :
                level.getServer().getRecipeManager().getAllRecipesFor(RecipeType.CRAFTING)) {
            try {
                ItemStack result = holder.value().getResultItem(level.registryAccess());
                if (!result.isEmpty() && result.getItem() == want) {
                    out.add(holder);
                }
            } catch (Exception ignored) {
                // A recipe that will not tell us its result is simply not a
                // candidate; some modded ones are context-dependent.
            }
        }
        return out;
    }

    private static final class Plan {
        final Map<AEItemKey, Long> take = new LinkedHashMap<>();
        final List<String> missing = new ArrayList<>();
    }

    /**
     * Work out exactly what would have to come out of the network.
     *
     * <p>Runs against a local copy of the network's contents so that ingredients
     * compete for stock properly - a recipe calling for two of something must
     * not be told twice that the same single item is available.
     */
    private static Plan plan(MEStorage storage, CraftingRecipe recipe, int batches) {
        Map<AEKey, Long> pool = new HashMap<>();
        for (var entry : storage.getAvailableStacks()) {
            pool.put(entry.getKey(), entry.getLongValue());
        }

        Plan plan = new Plan();
        for (int batch = 0; batch < batches; batch++) {
            for (Ingredient ingredient : recipe.getIngredients()) {
                if (ingredient.isEmpty()) {
                    continue;               // a gap in a shaped recipe
                }
                AEItemKey found = null;
                for (Map.Entry<AEKey, Long> held : pool.entrySet()) {
                    if (held.getValue() <= 0) {
                        continue;
                    }
                    if (held.getKey() instanceof AEItemKey key
                            && ingredient.test(key.toStack())) {
                        found = key;
                        break;
                    }
                }
                if (found == null) {
                    plan.missing.add(describe(ingredient));
                } else {
                    pool.merge(found, -1L, Long::sum);
                    plan.take.merge(found, 1L, Long::sum);
                }
            }
        }
        return plan;
    }

    /**
     * Take the ingredients and put the result back.
     *
     * <p>Simulated end to end first. Only once every extraction is known to
     * succeed does anything actually move, so the network cannot be left short
     * of ingredients for a craft that never completed.
     */
    private static String execute(MEStorage storage, IActionSource source, Plan plan,
                                  ItemStack result, int batches, int per,
                                  ServerPlayer requester) {
        for (Map.Entry<AEItemKey, Long> entry : plan.take.entrySet()) {
            long could = storage.extract(entry.getKey(), entry.getValue(),
                    Actionable.SIMULATE, source);
            if (could < entry.getValue()) {
                return "the network would not release "
                        + entry.getKey().getDisplayName().getString()
                        + " - it may be in a locked or read-only cell";
            }
        }
        for (Map.Entry<AEItemKey, Long> entry : plan.take.entrySet()) {
            storage.extract(entry.getKey(), entry.getValue(), Actionable.MODULATE, source);
        }

        long made = (long) batches * per;
        AEItemKey outKey = AEItemKey.of(result);
        long stored = storage.insert(outKey, made, Actionable.MODULATE, source);
        long leftover = made - stored;
        String name = result.getHoverName().getString();

        if (leftover > 0 && requester != null) {
            // The network had no room. Hand it over rather than voiding it -
            // the ingredients are already spent.
            handOver(requester, result, leftover);
            return "made " + made + "x " + name + " by hand - " + stored
                    + " into the network, " + leftover + " straight to you (network full)";
        }
        if (leftover > 0) {
            return "made " + made + "x " + name + " but only " + stored
                    + " would fit in the network";
        }
        return "made " + made + "x " + name + " by hand from network stock"
                + (batches > 1 ? " (" + batches + " crafts)" : "");
    }

    /** Give the player the overflow, or drop it at their feet. */
    private static void handOver(ServerPlayer player, ItemStack result, long count) {
        long left = count;
        while (left > 0) {
            int size = (int) Math.min(left, result.getMaxStackSize());
            ItemStack stack = result.copyWithCount(size);
            if (!player.getInventory().add(stack)) {
                player.drop(stack, false);
            }
            left -= size;
        }
    }

    /** What a plan would consume, for a dry run that must not surprise anyone. */
    private static String describe(Map<AEItemKey, Long> take) {
        StringBuilder sb = new StringBuilder();
        int shown = 0;
        int more = 0;
        for (Map.Entry<AEItemKey, Long> entry : take.entrySet()) {
            if (shown < MISSING_SHOWN) {
                if (shown > 0) {
                    sb.append(", ");
                }
                sb.append(entry.getValue()).append("x ")
                        .append(entry.getKey().getDisplayName().getString());
                shown++;
            } else {
                more++;
            }
        }
        if (more > 0) {
            sb.append(" and ").append(more).append(" more");
        }
        return sb.length() == 0 ? "nothing" : sb.toString();
    }

    /** A readable name for what an ingredient wanted. */
    private static String describe(Ingredient ingredient) {
        try {
            ItemStack[] options = ingredient.getItems();
            if (options.length > 0 && !options[0].isEmpty()) {
                return options[0].getHoverName().getString();
            }
        } catch (Exception ignored) {
            // fall through to the vague answer
        }
        return "something";
    }

    /** Collapse repeats so "9x Redstone" does not print nine times. */
    private static String summarise(List<String> missing) {
        Map<String, Integer> counted = new LinkedHashMap<>();
        for (String name : missing) {
            counted.merge(name, 1, Integer::sum);
        }
        StringBuilder sb = new StringBuilder();
        int shown = 0;
        int more = 0;
        for (Map.Entry<String, Integer> entry : counted.entrySet()) {
            if (shown < MISSING_SHOWN) {
                if (shown > 0) {
                    sb.append(", ");
                }
                sb.append(entry.getValue()).append("x ").append(entry.getKey());
                shown++;
            } else {
                more++;
            }
        }
        if (more > 0) {
            sb.append(" and ").append(more).append(" more");
        }
        return sb.toString().toLowerCase(Locale.ROOT).isEmpty() ? "something" : sb.toString();
    }
}
