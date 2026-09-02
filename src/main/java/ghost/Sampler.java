package ghost;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.level.block.state.properties.Property;
import net.neoforged.fml.loading.FMLPaths;

import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Walks a box of the live world and writes what it finds.
 *
 * <p>Everything is read through blockstate property NAMES rather than known
 * classes - anything with an {@code age} property counts as a crop, anything
 * with {@code moisture} reports its wetness. That means it surveys mods it has
 * never been compiled against, which is the whole point.
 *
 * <p>Records the four things that decide whether a crop grows: its age, the
 * soil under it, that soil's moisture, and the light on it. A census that
 * reported age alone could not tell a slow soil from a dark corner.
 */
public final class Sampler {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /** Bounds the work. A survey that freezes the server is not a survey. */
    public static final long MAX_BLOCKS = 4_000_000L;

    /**
     * Cap on distinct block types listed by a scan. Generous on purpose - the
     * point of the cap is to stop a pathological result being enormous, not to
     * tidy the output. Anything cut is counted in {@code blockCountsOmitted}.
     */
    public static final int MAX_BLOCK_TYPES = 400;

    private Sampler() {
    }

    public static Path dir() {
        Path p = FMLPaths.GAMEDIR.get().resolve("ghost");
        try {
            Files.createDirectories(p);
        } catch (IOException ignored) {
        }
        return p;
    }

    private static Integer intProp(BlockState state, String name) {
        for (Property<?> p : state.getProperties()) {
            if (p.getName().equals(name) && p instanceof IntegerProperty ip) {
                return state.getValue(ip);
            }
        }
        return null;
    }

    private static Integer maxOf(BlockState state, String name) {
        for (Property<?> p : state.getProperties()) {
            if (p.getName().equals(name) && p instanceof IntegerProperty ip) {
                return ip.getPossibleValues().stream().max(Integer::compareTo).orElse(null);
            }
        }
        return null;
    }

    private static String id(BlockState state) {
        return net.minecraft.core.registries.BuiltInRegistries.BLOCK
                .getKey(state.getBlock()).toString();
    }

    /**
     * One reading of the box.
     *
     * @param detail when true every crop is listed individually; when false only
     *               the per-soil aggregates are kept. A nightly series wants the
     *               aggregates - a hundred samples of 800 crops each is a file
     *               nobody will read.
     */
    public static Map<String, Object> scan(ServerLevel level, BlockPos a, BlockPos b,
                                           boolean detail) {
        int x0 = Math.min(a.getX(), b.getX()), x1 = Math.max(a.getX(), b.getX());
        int y0 = Math.min(a.getY(), b.getY()), y1 = Math.max(a.getY(), b.getY());
        int z0 = Math.min(a.getZ(), b.getZ()), z1 = Math.max(a.getZ(), b.getZ());

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("dimension", level.dimension().location().toString());
        out.put("gameTime", level.getGameTime());
        out.put("dayTime", level.getDayTime() % 24000L);
        out.put("realTime", java.time.OffsetDateTime.now().toString());
        out.put("box", List.of(x0, y0, z0, x1, y1, z1));

        long volume = (long) (x1 - x0 + 1) * (y1 - y0 + 1) * (z1 - z0 + 1);
        out.put("blocksScanned", volume);
        if (volume > MAX_BLOCKS) {
            out.put("error", "box too large: " + volume + " > " + MAX_BLOCKS);
            return out;
        }

        List<Map<String, Object>> crops = new ArrayList<>();
        Map<String, int[]> byPair = new TreeMap<>();   // crop|soil -> [n, ageSum, mature, lightSum, accelSum]
        Map<String, Integer> blockCounts = new TreeMap<>();
        BlockPos.MutableBlockPos cur = new BlockPos.MutableBlockPos();

        for (int x = x0; x <= x1; x++) {
            for (int z = z0; z <= z1; z++) {
                for (int y = y0; y <= y1; y++) {
                    cur.set(x, y, z);
                    BlockState st = level.getBlockState(cur);
                    if (st.isAir()) {
                        continue;
                    }
                    blockCounts.merge(id(st), 1, Integer::sum);

                    Integer age = intProp(st, "age");
                    if (age == null) {
                        continue;
                    }
                    Integer maxAge = maxOf(st, "age");

                    BlockPos soilPos = cur.below();
                    BlockState soil = level.getBlockState(soilPos);
                    Integer moisture = intProp(soil, "moisture");

                    // Accelerators stack downward. Count the run below the soil,
                    // and keep the depth of the lowest - range is measured from
                    // each accelerator, so the deepest one is what decides
                    // whether the bottom of the stack is doing anything at all.
                    int accel = 0;
                    int lowestY = soilPos.getY();
                    for (int d = 1; d <= 24; d++) {
                        BlockState below = level.getBlockState(soilPos.below(d));
                        if (id(below).contains("growth_accelerator")) {
                            accel++;
                            lowestY = soilPos.getY() - d;
                        } else if (accel > 0) {
                            break;
                        }
                    }

                    int light = level.getRawBrightness(cur, 0);
                    boolean mature = maxAge != null && age.equals(maxAge);

                    String key = id(st) + "|" + id(soil);
                    int[] agg = byPair.computeIfAbsent(key, k -> new int[5]);
                    agg[0]++;
                    agg[1] += age;
                    agg[2] += mature ? 1 : 0;
                    agg[3] += light;
                    agg[4] += accel;

                    if (detail) {
                        Map<String, Object> c = new LinkedHashMap<>();
                        c.put("pos", List.of(x, y, z));
                        c.put("crop", id(st));
                        c.put("age", age);
                        c.put("maxAge", maxAge);
                        c.put("soil", id(soil));
                        c.put("moisture", moisture);
                        c.put("accelerators", accel);
                        c.put("lowestAcceleratorY", accel > 0 ? lowestY : null);
                        c.put("light", light);
                        crops.add(c);
                    }
                }
            }
        }

        List<Map<String, Object>> summary = new ArrayList<>();
        for (Map.Entry<String, int[]> e : byPair.entrySet()) {
            String[] parts = e.getKey().split("\\|", 2);
            int[] v = e.getValue();
            Map<String, Object> s = new LinkedHashMap<>();
            s.put("crop", parts[0]);
            s.put("soil", parts[1]);
            s.put("count", v[0]);
            s.put("meanAge", round(v[1] / (double) v[0]));
            s.put("maturePct", round(100.0 * v[2] / v[0]));
            s.put("meanLight", round(v[3] / (double) v[0]));
            s.put("meanAccelerators", round(v[4] / (double) v[0]));
            summary.add(s);
        }
        summary.sort((p, q) -> Integer.compare((Integer) q.get("count"), (Integer) p.get("count")));
        out.put("summary", summary);
        out.put("totalCrops", crops.isEmpty() && !detail
                ? summary.stream().mapToInt(s -> (Integer) s.get("count")).sum()
                : crops.size());
        if (detail) {
            out.put("crops", crops);
        }
        // Report EVERY block type present, including the ones there is only one
        // of. This used to drop anything appearing fewer than 8 times, to keep
        // the output small - which silently deleted exactly the blocks a scan is
        // usually looking for. A controller, a spawner, a single machine: gone,
        // with no indication anything had been removed, so the census read as
        // authoritative while being wrong. On 2026-08-31 that cost an hour of
        // chasing a missing Functional Storage controller that was in the box
        // the whole time.
        //
        // Size is still bounded, but by TRUNCATION THAT SAYS SO: the rarest
        // types are dropped last and the result states how many were omitted.
        out.put("distinctBlockTypes", blockCounts.size());
        if (blockCounts.size() > MAX_BLOCK_TYPES) {
            List<Map.Entry<String, Integer>> ranked = new ArrayList<>(blockCounts.entrySet());
            ranked.sort((p, q) -> q.getValue() - p.getValue());
            Map<String, Integer> kept = new TreeMap<>();
            for (int i = 0; i < MAX_BLOCK_TYPES; i++) {
                kept.put(ranked.get(i).getKey(), ranked.get(i).getValue());
            }
            out.put("blockCounts", kept);
            out.put("blockCountsOmitted", blockCounts.size() - MAX_BLOCK_TYPES);
        } else {
            out.put("blockCounts", blockCounts);
        }
        return out;
    }

    private static double round(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    public static Path writeScan(Map<String, Object> data, long gameTime) throws IOException {
        Path p = dir().resolve("scan-" + gameTime + ".json");
        try (Writer w = Files.newBufferedWriter(p, StandardCharsets.UTF_8)) {
            GSON.toJson(data, w);
        }
        return p;
    }

    /** One line per sample. Append-only so a series survives crashes and restarts. */
    public static Path appendSeries(Map<String, Object> data) throws IOException {
        Path p = dir().resolve("series.jsonl");
        String line = new Gson().toJson(data) + System.lineSeparator();
        Files.writeString(p, line, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        return p;
    }
}
