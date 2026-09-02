package ghost;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Search the registry by what a block IS, not by how it is made.
 *
 * <p>JEI answers "how do I craft this" and "what uses this". It cannot answer
 * "what emits light 15", "what is blast resistant", "what can I actually walk
 * on" - because those are properties of a blockstate rather than edges in a
 * recipe graph. With six hundred mods installed, that is the question worth
 * asking, and the answer is otherwise a wiki crawl.
 *
 * <p>Results are crossed with what the player owns, which is the part that
 * makes it useful: "seventeen blocks emit light 15, and you have three of
 * them" beats a list of seventeen names.
 */
public final class Query {

    private Query() {
    }

    public record Lamp(String id, int light, int owned) {
    }

    /**
     * Every block emitting at least {@code minLight}, with how many the player
     * has to hand.
     *
     * <p>Counting ownership is the expensive half - it walks every container in
     * radius once per candidate - so it only runs when a radius is given.
     */
    public static List<Lamp> lights(ServerLevel level, ServerPlayer player,
                                    int minLight, int radius) {
        List<Lamp> out = new ArrayList<>();
        for (Block block : BuiltInRegistries.BLOCK) {
            BlockState state = block.defaultBlockState();
            int light;
            try {
                light = state.getLightEmission();
            } catch (Exception e) {
                continue;                       // a state that will not resolve
            }
            if (light < minLight) {
                continue;
            }
            String id = BuiltInRegistries.BLOCK.getKey(block).toString();
            int owned = 0;
            if (radius > 0) {
                Item item = block.asItem();
                if (item != net.minecraft.world.item.Items.AIR) {
                    owned = Finder.count(level, player, item, radius).total();
                }
            }
            out.add(new Lamp(id, light, owned));
        }
        // Owned first, then brightest, then alphabetical - so the answer opens
        // with what can actually be placed today.
        out.sort(Comparator.<Lamp>comparingInt(l -> l.owned() > 0 ? 0 : 1)
                .thenComparing(Comparator.comparingInt(Lamp::light).reversed())
                .thenComparing(Lamp::id));
        return out;
    }
}
