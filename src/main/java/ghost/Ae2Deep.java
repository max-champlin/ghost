package ghost;

import appeng.api.config.Actionable;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.storage.MEStorage;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeType;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Hand-crafting a whole tree, not one step of it.
 *
 * <p>{@link Ae2Direct} looks a recipe up and crafts it from the network, which
 * is the right behaviour and stops one level down: asked for a Growth
 * Accelerator it answers "you are short 16 Inferium Essence and 2 Inferium
 * Gemstone" while the network holds 475 Insanium Essence and 16,000 diamonds -
 * every one of those missing items being three or four ordinary recipes away.
 * A person at a crafting bench would just make them.
 *
 * <p>The reason the one-level rule existed was sound: a half-built tree that
 * consumed the inputs and produced nothing is worse than a clear refusal. So
 * the whole plan is resolved down to items the network actually holds
 * <b>before a single item moves</b>. If any branch cannot be paid for, nothing
 * is extracted and the shortfall is named.
 *
 * <h2>The two things that make this safe</h2>
 *
 * <p><b>A reservation ledger.</b> Stock is counted once. If two branches both
 * want the same diamonds, the second sees what the first already claimed rather
 * than both believing the network is full.
 *
 * <p><b>Cycle detection, which this pack genuinely needs.</b> Mystical
 * Agriculture converts essence in both directions - four Inferium make a
 * Prudentium, and one Prudentium uncrafts into four Inferium. A planner that
 * follows the first recipe it finds will happily descend forever. Any item
 * already being expanded higher up the branch is refused as an ingredient, so a
 * tier can be walked down but never back up into itself.
 *
 * <p>Surplus is kept, not discarded: a recipe making three when two are needed
 * puts the third back in the network.
 */
final class Ae2Deep {

    private Ae2Deep() {
    }

    /** How deep a tree may go. Insanium to Inferium is five, plus the item itself. */
    private static final int MAX_DEPTH = 12;

    /** Total recipe expansions allowed, so a pathological tree cannot hang a tick. */
    private static final int MAX_STEPS = 512;

    /** What the plan resolved to. */
    static final class Deep {
        /** Real items to pull from the network. */
        final Map<AEItemKey, Long> leaves = new LinkedHashMap<>();
        /** Intermediate crafts, in the order they would be done. Reporting only. */
        final List<String> steps = new ArrayList<>();
        /** Surplus produced along the way, to be put back. */
        final Map<Item, Long> spare = new LinkedHashMap<>();
        String missing;
        int expansions;

        boolean ok() {
            return missing == null;
        }
    }

    /** Live stock, minus whatever the plan has already claimed. */
    private static final class Ledger {
        private final Map<AEKey, Long> held = new HashMap<>();

        Ledger(MEStorage storage) {
            for (var e : storage.getAvailableStacks()) {
                held.put(e.getKey(), e.getLongValue());
            }
        }

        /** Claim up to {@code want} of anything matching, returning what it got. */
        long claim(Ingredient ingredient, long want, Map<AEItemKey, Long> into) {
            long got = 0;
            for (Map.Entry<AEKey, Long> e : held.entrySet()) {
                if (got >= want) {
                    break;
                }
                if (e.getValue() <= 0 || !(e.getKey() instanceof AEItemKey key)) {
                    continue;
                }
                if (!ingredient.test(key.toStack())) {
                    continue;
                }
                long take = Math.min(e.getValue(), want - got);
                e.setValue(e.getValue() - take);
                into.merge(key, take, Long::sum);
                got += take;
            }
            return got;
        }
    }

    private static List<RecipeHolder<CraftingRecipe>> recipesFor(ServerLevel level, Item want) {
        List<RecipeHolder<CraftingRecipe>> out = new ArrayList<>();
        for (RecipeHolder<CraftingRecipe> h
                : level.getRecipeManager().getAllRecipesFor(RecipeType.CRAFTING)) {
            ItemStack r = h.value().getResultItem(level.registryAccess());
            if (!r.isEmpty() && r.getItem() == want) {
                out.add(h);
            }
        }
        return out;
    }

    /**
     * Resolve {@code want} x{@code amount} into leaves the network holds.
     *
     * @param path items currently being expanded above this one - the cycle guard
     */
    private static boolean resolve(ServerLevel level, Ledger ledger, Deep deep,
                                   Ingredient need, long amount, Set<Item> path,
                                   int depth) {
        if (amount <= 0) {
            return true;
        }
        // Whatever the network can pay for directly is the cheap answer.
        long got = ledger.claim(need, amount, deep.leaves);
        long shortBy = amount - got;
        if (shortBy <= 0) {
            return true;
        }
        if (depth >= MAX_DEPTH || deep.expansions >= MAX_STEPS) {
            deep.missing = "the recipe tree is deeper than I will follow";
            return false;
        }

        // Which concrete item is missing? Take the first the ingredient accepts,
        // so the message names something real rather than a tag.
        ItemStack[] options = need.getItems();
        for (ItemStack option : options) {
            if (option.isEmpty()) {
                continue;
            }
            Item item = option.getItem();
            if (path.contains(item)) {
                continue;              // would close a loop; try another option
            }
            // Use surplus from an earlier branch before making more.
            long spare = deep.spare.getOrDefault(item, 0L);
            if (spare > 0) {
                long use = Math.min(spare, shortBy);
                deep.spare.put(item, spare - use);
                shortBy -= use;
                if (shortBy <= 0) {
                    return true;
                }
            }
            for (RecipeHolder<CraftingRecipe> holder : recipesFor(level, item)) {
                CraftingRecipe recipe = holder.value();
                ItemStack result = recipe.getResultItem(level.registryAccess());
                int per = Math.max(1, result.getCount());
                // A recipe that consumes something we are already making is the
                // loop this pack is full of. Refuse it here, not at depth 12.
                boolean loops = false;
                for (Ingredient ing : recipe.getIngredients()) {
                    if (ing.isEmpty()) {
                        continue;
                    }
                    for (ItemStack s : ing.getItems()) {
                        if (!s.isEmpty() && path.contains(s.getItem())) {
                            loops = true;
                            break;
                        }
                    }
                    if (loops) {
                        break;
                    }
                }
                if (loops) {
                    continue;
                }

                long batches = (shortBy + per - 1) / per;
                Map<AEItemKey, Long> before = new LinkedHashMap<>(deep.leaves);
                Map<Item, Long> spareBefore = new LinkedHashMap<>(deep.spare);
                deep.expansions++;
                path.add(item);
                boolean all = true;
                for (Ingredient ing : recipe.getIngredients()) {
                    if (ing.isEmpty()) {
                        continue;
                    }
                    if (!resolve(level, ledger, deep, ing, batches, path, depth + 1)) {
                        all = false;
                        break;
                    }
                }
                path.remove(item);
                if (!all) {
                    // Roll this branch back and try the next recipe.
                    deep.leaves.clear();
                    deep.leaves.putAll(before);
                    deep.spare.clear();
                    deep.spare.putAll(spareBefore);
                    deep.missing = null;
                    continue;
                }
                long made = batches * per;
                if (made > shortBy) {
                    deep.spare.merge(item, made - shortBy, Long::sum);
                }
                deep.steps.add(batches + "x " + item.getDescription().getString());
                return true;
            }
        }
        deep.missing = options.length > 0 && !options[0].isEmpty()
                ? shortBy + "x " + options[0].getHoverName().getString()
                : "an ingredient I cannot identify";
        return false;
    }

    /**
     * Plan the whole tree. Nothing moves; this only reads.
     */
    static Deep plan(ServerLevel level, MEStorage storage, Item want, long amount) {
        Deep deep = new Deep();
        Set<Item> path = new HashSet<>();
        path.add(want);
        for (RecipeHolder<CraftingRecipe> holder : recipesFor(level, want)) {
            CraftingRecipe recipe = holder.value();
            ItemStack result = recipe.getResultItem(level.registryAccess());
            int per = Math.max(1, result.getCount());
            long batches = (amount + per - 1) / per;
            Deep attempt = new Deep();
            // ONE ledger for the whole attempt.
            //
            // This was `new Ledger(storage)` inside the loop, so every
            // ingredient slot independently believed it had the network's full
            // stock: five slots wanting Prudentium each claimed the same 183
            // and the plan cheerfully reported 915. It survived the dry run and
            // died at extraction, which is exactly where the simulate-first
            // guard is supposed to catch it - but a plan that lies is worse
            // than one that refuses, so the ledger is shared as designed.
            Ledger ledger = new Ledger(storage);
            boolean all = true;
            for (Ingredient ing : recipe.getIngredients()) {
                if (ing.isEmpty()) {
                    continue;
                }
                if (!resolve(level, ledger, attempt, ing, batches, path, 1)) {
                    all = false;
                    break;
                }
            }
            if (all) {
                attempt.steps.add(batches + "x " + want.getDescription().getString());
                long made = batches * per;
                if (made > amount) {
                    attempt.spare.merge(want, made - amount, Long::sum);
                }
                return attempt;
            }
            deep = attempt;             // keep the last shortfall to report
        }
        if (deep.missing == null) {
            deep.missing = "no crafting recipe I can manage by hand";
        }
        return deep;
    }

    /**
     * Pull the leaves and put the result back. Simulated in full first, so a
     * craft either happens completely or does not start.
     */
    static String execute(MEStorage storage, IActionSource source, Deep deep,
                          Item want, long amount) {
        for (Map.Entry<AEItemKey, Long> e : deep.leaves.entrySet()) {
            long could = storage.extract(e.getKey(), e.getValue(), Actionable.SIMULATE, source);
            if (could < e.getValue()) {
                return "The network would not release "
                        + e.getKey().getDisplayName().getString()
                        + ". It may be in a locked or read-only cell.";
            }
        }
        for (Map.Entry<AEItemKey, Long> e : deep.leaves.entrySet()) {
            storage.extract(e.getKey(), e.getValue(), Actionable.MODULATE, source);
        }
        long left = amount;
        while (left > 0) {
            int batch = (int) Math.min(left, want.getDefaultInstance().getMaxStackSize());
            storage.insert(AEItemKey.of(new ItemStack(want, batch)), batch,
                    Actionable.MODULATE, source);
            left -= batch;
        }
        // Surplus goes back rather than evaporating.
        for (Map.Entry<Item, Long> e : deep.spare.entrySet()) {
            long n = e.getValue();
            while (n > 0) {
                int batch = (int) Math.min(n, e.getKey().getDefaultInstance().getMaxStackSize());
                storage.insert(AEItemKey.of(new ItemStack(e.getKey(), batch)), batch,
                        Actionable.MODULATE, source);
                n -= batch;
            }
        }
        return null;
    }
}
