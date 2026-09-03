package ghost;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
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
     * Characters of NBT kept per block entity.
     *
     * <p>Configuration - a pipe's filter, a conduit's per-face setting, an
     * XNet channel - is small. Contents are not: a drive full of cells or a
     * barrel of items runs to hundreds of kilobytes and says nothing about how
     * the base is wired. Cutting here keeps the interesting part and discards
     * the inventory, and {@code nbtTruncated} marks anything that was cut so a
     * clipped filter is never mistaken for a short one.
     */
    private static final int MAX_NBT_CHARS = 4000;

    /** Block entities to read NBT from in one pass. */
    private static final int MAX_NBT_ENTRIES = 400;

    /**
     * Walk the box once, keeping every block whose id contains any of
     * {@code matches}.
     *
     * @param matches lowercase substrings - namespaces like {@code "ae2:"} work
     *                well, as do fragments like {@code "conduit"}
     */
    public static Map<String, Object> of(ServerLevel level, BlockPos from, BlockPos to,
                                         List<String> matches, boolean withNbt) {
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
        // Configuration, keyed by "x,y,z" so a diagram can join it to a position.
        Map<String, Object> config = new LinkedHashMap<>();
        boolean nbtTruncated = false;
        boolean nbtCapped = false;

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

                    if (!withNbt) {
                        continue;
                    }
                    if (config.size() >= MAX_NBT_ENTRIES) {
                        nbtCapped = true;
                        continue;
                    }
                    BlockEntity be = level.getBlockEntity(cur);
                    if (be == null) {
                        continue;               // plain block, nothing to configure
                    }
                    String snbt;
                    try {
                        CompoundTag tag = be.saveWithFullMetadata(level.registryAccess());
                        snbt = tag.toString();
                    } catch (Exception e) {
                        continue;               // a block entity that will not save is not a map problem
                    }
                    if (snbt.length() > MAX_NBT_CHARS) {
                        snbt = snbt.substring(0, MAX_NBT_CHARS);
                        nbtTruncated = true;
                    }
                    Map<String, Object> entry = new LinkedHashMap<>();
                    entry.put("id", id);
                    entry.put("nbt", snbt);
                    config.put(x + "," + y + "," + z, entry);
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
        if (withNbt) {
            out.put("config", config);
            out.put("configEntries", config.size());
            out.put("nbtTruncated", nbtTruncated);
            out.put("nbtEntryCapReached", nbtCapped);
        }
        return out;
    }
}
