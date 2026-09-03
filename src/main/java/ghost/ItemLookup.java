package ghost;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Turns what someone typed into an item, or says why it could not.
 *
 * <p>Every item lookup here used to be
 * {@code BuiltInRegistries.ITEM.get(ResourceLocation.parse(text))}, which has a
 * failure mode worth naming: an id it does not recognise is not an error, it is
 * <b>AIR</b>. So asking how much "essence" a network holds parsed to
 * {@code minecraft:essence}, resolved to air, counted the air in the network,
 * and answered <b>0</b> - beside a farm of 1,795 Inferium crops feeding that
 * exact network. The number was correct for the question actually asked, and the
 * question was silently not the one anyone meant.
 *
 * <p>That is the same shape as the ME network that reported zero because it was
 * asked for the wrong capability, and the block count that reported zero because
 * it counted from the world origin. Three separate confident zeros in one day,
 * each meaning "I did not understand you" and each indistinguishable from
 * "there are none".
 *
 * <p>So this never returns air by accident. An exact id wins; failing that a
 * unique substring match is accepted and reported, because nobody types
 * {@code mysticalagriculture:inferium_essence} when they mean "inferium
 * essence"; and an ambiguous or unknown name comes back as a refusal carrying
 * the candidates, which is far more use than a zero.
 */
public final class ItemLookup {

    private ItemLookup() {
    }

    /** Candidates listed back when a name is ambiguous. */
    private static final int MAX_SUGGESTIONS = 12;

    /** The outcome of a lookup: exactly one of {@link #item} or {@link #error}. */
    public static final class Result {
        public final Item item;
        public final String id;
        /** Set when the name was not an exact id but matched one thing anyway. */
        public final String resolvedFrom;
        public final String error;
        public final List<String> candidates;

        private Result(Item item, String id, String resolvedFrom,
                       String error, List<String> candidates) {
            this.item = item;
            this.id = id;
            this.resolvedFrom = resolvedFrom;
            this.error = error;
            this.candidates = candidates;
        }

        public boolean ok() {
            return item != null;
        }
    }

    /**
     * Resolve {@code query} to a single item.
     *
     * <p>Three passes, narrowing only when it must: the exact id, then ids that
     * end with the name (so {@code inferium_essence} finds the modded one
     * without knowing its namespace), then anything containing it.
     */
    public static Result resolve(String query) {
        String q = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        if (q.isEmpty()) {
            return new Result(null, null, null, "no item given", List.of());
        }

        // 1. Exact id. Air only counts when it was actually asked for.
        try {
            ResourceLocation rl = ResourceLocation.parse(q);
            Item exact = BuiltInRegistries.ITEM.get(rl);
            if (exact != Items.AIR || q.equals("minecraft:air") || q.equals("air")) {
                return new Result(exact, rl.toString(), null, null, List.of());
            }
        } catch (Exception ignored) {
            // not a well-formed id; fall through to matching
        }

        // 2. Path-suffix match, then 3. substring - the first that finds
        //    anything wins, so a precise name is not drowned by a loose one.
        List<String> endsWith = new ArrayList<>();
        List<String> contains = new ArrayList<>();
        String bare = q.contains(":") ? q.substring(q.indexOf(':') + 1) : q;
        String needle = bare.replace(' ', '_');
        for (ResourceLocation key : BuiltInRegistries.ITEM.keySet()) {
            String id = key.toString();
            String path = key.getPath();
            if (path.equals(needle)) {
                endsWith.add(id);
            } else if (id.contains(needle)) {
                contains.add(id);
            }
        }
        List<String> hits = !endsWith.isEmpty() ? endsWith : contains;

        if (hits.isEmpty()) {
            return new Result(null, null, null,
                    "no item matches \"" + query + "\"", List.of());
        }
        if (hits.size() == 1) {
            String id = hits.get(0);
            Item item = BuiltInRegistries.ITEM.get(ResourceLocation.parse(id));
            return new Result(item, id, query, null, List.of());
        }
        List<String> shown = hits.size() > MAX_SUGGESTIONS
                ? new ArrayList<>(hits.subList(0, MAX_SUGGESTIONS))
                : hits;
        return new Result(null, null, null,
                "\"" + query + "\" matches " + hits.size()
                        + " items - name one exactly", shown);
    }

    /**
     * The same three passes, for blocks.
     *
     * <p>Worth its own method rather than going through items, because the
     * failure here is not merely a wrong answer. {@code BLOCK.get()} returns
     * <b>AIR</b> for an id it does not know, and {@code place} feeds that
     * straight to {@code setBlockAndUpdate} - so a mistyped block id does not
     * fail to place something, it <b>deletes whatever was already there</b>.
     * A silent wrong number is bad; a silent destructive edit is worse.
     */
    public static BlockResult resolveBlock(String query) {
        String q = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        if (q.isEmpty()) {
            return new BlockResult(null, null, null, "no block given", List.of());
        }
        try {
            ResourceLocation rl = ResourceLocation.parse(q);
            net.minecraft.world.level.block.Block exact = BuiltInRegistries.BLOCK.get(rl);
            if (exact != net.minecraft.world.level.block.Blocks.AIR
                    || q.equals("minecraft:air") || q.equals("air")) {
                return new BlockResult(exact, rl.toString(), null, null, List.of());
            }
        } catch (Exception ignored) {
            // not a well-formed id; fall through to matching
        }
        List<String> endsWith = new ArrayList<>();
        List<String> contains = new ArrayList<>();
        String bare = q.contains(":") ? q.substring(q.indexOf(':') + 1) : q;
        String needle = bare.replace(' ', '_');
        for (ResourceLocation key : BuiltInRegistries.BLOCK.keySet()) {
            String id = key.toString();
            if (key.getPath().equals(needle)) {
                endsWith.add(id);
            } else if (id.contains(needle)) {
                contains.add(id);
            }
        }
        List<String> hits = !endsWith.isEmpty() ? endsWith : contains;
        if (hits.isEmpty()) {
            return new BlockResult(null, null, null,
                    "no block matches \"" + query + "\"", List.of());
        }
        if (hits.size() == 1) {
            String id = hits.get(0);
            return new BlockResult(BuiltInRegistries.BLOCK.get(ResourceLocation.parse(id)),
                    id, query, null, List.of());
        }
        List<String> shown = hits.size() > MAX_SUGGESTIONS
                ? new ArrayList<>(hits.subList(0, MAX_SUGGESTIONS)) : hits;
        return new BlockResult(null, null, null,
                "\"" + query + "\" matches " + hits.size()
                        + " blocks - name one exactly", shown);
    }

    /** Block counterpart to {@link Result}. */
    public static final class BlockResult {
        public final net.minecraft.world.level.block.Block block;
        public final String id;
        public final String resolvedFrom;
        public final String error;
        public final List<String> candidates;

        private BlockResult(net.minecraft.world.level.block.Block block, String id,
                            String resolvedFrom, String error, List<String> candidates) {
            this.block = block;
            this.id = id;
            this.resolvedFrom = resolvedFrom;
            this.error = error;
            this.candidates = candidates;
        }

        public boolean ok() {
            return block != null;
        }
    }
}
