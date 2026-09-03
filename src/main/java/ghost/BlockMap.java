package ghost;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * One pass over a volume that returns WHERE things are, not just how many.
 *
 * <p>{@link Sampler#scan} answers "what is in this box" with counts, and
 * {@link Finder#findBlocks} answers "where is X" for a single substring. Drawing
 * a diagram of a base needs both at once, for a dozen different systems - AE2,
 * Mekanism, Powah, EnderIO, XNet, LaserIO, Pipez, Flux, Refined Storage - and
 * running {@code find} a dozen times means walking the same million blocks a
 * dozen times over.
 *
 * <p>So this takes a list of matches and walks the volume once, bucketing every
 * hit by the match that claimed it. That is the difference between a map you can
 * ask for and a map you have to be patient for.
 *
 * <h2>Caps, and why the numbers are still honest</h2>
 *
 * <p>A base can hold tens of thousands of cable segments, and neither a JSON
 * file nor a diagram wants them all. Positions are capped per block id, but the
 * <b>counts are never capped</b> - so a cable run reports its true length even
 * when only the first few hundred positions are listed, and
 * {@code positionsTruncated} says plainly when that has happened. A count that
 * quietly stopped rising would be the same class of bug as a network that
 * quietly reported zero.
 */
public final class BlockMap {

    private BlockMap() {
    }

    /** Positions kept per block id. Enough to draw a run, not enough to drown in. */
    private static final int MAX_POSITIONS_PER_ID = 256;

    /** Refuse a box bigger than this rather than stalling the server on it. */
    private static final long MAX_BLOCKS = 8_000_000L;

    /**
     * Walk the box once, keeping every block whose id contains any of
     * {@code matches}.
     *
     * @param matches lowercase substrings - namespaces like {@code "ae2:"} work
     *                well, as do fragments like {@code "conduit"}
     */
    public static Map<String, Object> of(ServerLevel level, BlockPos from, BlockPos to,
                                         List<String> matches) {
        int x0 = Math.min(from.getX(), to.getX());
        int y0 = Math.min(from.getY(), to.getY());
        int z0 = Math.min(from.getZ(), to.getZ());
        int x1 = Math.max(from.getX(), to.getX());
        int y1 = Math.max(from.getY(), to.getY());
        int z1 = Math.max(from.getZ(), to.getZ());

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("box", List.of(x0, y0, z0, x1, y1, z1));
        long volume = (long) (x1 - x0 + 1) * (y1 - y0 + 1) * (z1 - z0 + 1);
        out.put("blocksScanned", volume);
        if (volume > MAX_BLOCKS) {
            out.put("error", "box too large: " + volume + " > " + MAX_BLOCKS);
            return out;
        }

        List<String> wants = new ArrayList<>();
        for (String m : matches) {
            wants.add(m.toLowerCase(Locale.ROOT));
        }

        // id -> count, and id -> positions (capped)
        Map<String, Integer> counts = new TreeMap<>();
        Map<String, List<List<Integer>>> positions = new TreeMap<>();
        Map<String, Integer> perMatch = new TreeMap<>();
        boolean truncated = false;

        BlockPos.MutableBlockPos cur = new BlockPos.MutableBlockPos();
        for (int x = x0; x <= x1; x++) {
            for (int z = z0; z <= z1; z++) {
                for (int y = y0; y <= y1; y++) {
                    cur.set(x, y, z);
                    BlockState st = level.getBlockState(cur);
                    if (st.isAir()) {
                        continue;
                    }
                    String id = BuiltInRegistries.BLOCK.getKey(st.getBlock()).toString();
                    String lower = id.toLowerCase(Locale.ROOT);
                    String claimed = null;
                    for (String want : wants) {
                        if (lower.contains(want)) {
                            claimed = want;
                            break;
                        }
                    }
                    if (claimed == null) {
                        continue;
                    }
                    counts.merge(id, 1, Integer::sum);
                    perMatch.merge(claimed, 1, Integer::sum);
                    List<List<Integer>> list =
                            positions.computeIfAbsent(id, k -> new ArrayList<>());
                    if (list.size() < MAX_POSITIONS_PER_ID) {
                        list.add(List.of(x, y, z));
                    } else {
                        truncated = true;
                    }
                }
            }
        }

        out.put("matches", wants);
        out.put("distinctBlockTypes", counts.size());
        out.put("total", counts.values().stream().mapToInt(Integer::intValue).sum());
        out.put("byMatch", perMatch);
        out.put("counts", counts);
        out.put("positions", positions);
        out.put("maxPositionsPerId", MAX_POSITIONS_PER_ID);
        out.put("positionsTruncated", truncated);
        return out;
    }
}
