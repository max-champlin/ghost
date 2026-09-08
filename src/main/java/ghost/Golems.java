package ghost;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Reads a golem workforce the way a foreman would.
 *
 * <p>{@link Entities} can already see golems - they are entities - but it
 * answers "what is nearby", and the question that actually gets asked is "why
 * is the garden slow". Answering that meant, on 2026-09-07, reading eight hours
 * of log by hand and writing throwaway scripts: positions, held items, hunger,
 * who could reach a chest. Every number was already in the world. This is that
 * hour, as one call.
 *
 * <p>Nothing here links against the golem mod. Everything is read from the
 * entity's own saved NBT, the same way {@link Entities} works, so it keeps
 * working across mod updates and simply reports less if the keys change.
 *
 * <p><b>Counting.</b> Golems are identified by their entity type id containing
 * "golem", and counted per entity - NOT by the runtime {@code id}. That id is
 * reassigned every time a chunk unloads and reloads, and using it as identity is
 * how 37 golems got reported as 154.
 */
public final class Golems {

    private Golems() {
    }

    /** Hunger climbs toward a configured maximum; without it a raw number is meaningless. */
    private static int maxHunger = -1;
    private static boolean lookedForConfig;

    /**
     * The golem mod's own configured hunger ceiling, or -1.
     *
     * <p>Read from its properties file rather than assumed. The default is 4800
     * and hardcoding that would quietly produce wrong percentages for anyone who
     * changed it - which is the whole class of bug this codebase keeps finding.
     */
    private static int maxHunger() {
        if (!lookedForConfig) {
            lookedForConfig = true;
            try {
                Path p = Sampler.dir().getParent().resolve("config")
                        .resolve("strawgolem.properties");
                if (Files.isReadable(p)) {
                    for (String line : Files.readAllLines(p)) {
                        if (line.startsWith("Hunger Time")) {
                            maxHunger = Integer.parseInt(
                                    line.substring(line.indexOf('=') + 1).trim());
                            break;
                        }
                    }
                }
            } catch (Exception e) {
                Ghost.LOG.warn("could not read the golem hunger config", e);
            }
        }
        return maxHunger;
    }

    private static boolean isGolem(String id) {
        return id.contains("golem");
    }

    /** "strawgolem:artisan_golem" -> "artisan". The job, which is what is being asked about. */
    private static String job(String id) {
        String s = id.substring(id.indexOf(':') + 1);
        if (s.endsWith("_golem")) {
            s = s.substring(0, s.length() - 6);
        }
        return s.isEmpty() ? "straw" : s;
    }

    private static String posOf(CompoundTag tag, String key) {
        if (!tag.contains(key)) {
            return null;
        }
        String s = String.valueOf(tag.get(key))
                .replaceAll("[\\[\\]{}]", "").replace("I;", "")
                .replaceAll("[A-Za-z]:", "").replaceAll("[a-zA-Z]", "")
                .trim().replaceAll("[,;]+", " ").replaceAll("\\s+", " ").trim();
        // An unset position is stored as a sentinel, not as absent.
        return s.isEmpty() || s.startsWith("-1 -1 -1")
                || s.contains("2147483647") ? null : s;
    }

    public static Map<String, Object> survey(ServerLevel level, BlockPos centre,
                                             int radius, boolean detail) {
        AABB box = new AABB(centre).inflate(radius);
        int max = maxHunger();

        Map<String, Integer> byJob = new TreeMap<>();
        Map<String, Integer> byCrew = new TreeMap<>();
        Map<String, Integer> homes = new TreeMap<>();
        List<Map<String, Object>> rows = new ArrayList<>();
        List<Map<String, Object>> idle = new ArrayList<>();

        int total = 0;
        int hungry = 0;
        int holding = 0;
        int homeless = 0;
        long jobsTotal = 0;

        for (Entity e : level.getEntities(null, box)) {
            String id = BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).toString();
            if (!isGolem(id)) {
                continue;
            }
            total++;
            byJob.merge(job(id), 1, Integer::sum);

            CompoundTag tag = new CompoundTag();
            try {
                e.saveWithoutId(tag);
            } catch (Exception ignored) {
                continue;                 // unreadable is still counted above
            }

            String crew = tag.contains("CrewColour") ? tag.getString("CrewColour") : "none";
            byCrew.merge(crew.isEmpty() ? "none" : crew, 1, Integer::sum);

            String home = posOf(tag, "homePos");
            if (home == null) {
                homeless++;
            } else {
                homes.merge(home, 1, Integer::sum);
            }

            int hunger = tag.contains("hunger") ? tag.getInt("hunger") : -1;
            boolean isHungry = max > 0 && hunger >= 0 && hunger * 2 >= max;
            if (isHungry) {
                hungry++;
            }
            jobsTotal += tag.contains("jobsDone") ? tag.getInt("jobsDone") : 0;

            ItemStack hand = e instanceof LivingEntity le
                    ? le.getItemBySlot(EquipmentSlot.MAINHAND) : ItemStack.EMPTY;
            boolean hasHands = !hand.isEmpty();
            if (hasHands) {
                holding++;
            }

            if (!detail && !(hasHands || isHungry)) {
                continue;         // summary mode reports only what needs a look
            }

            Map<String, Object> row = new LinkedHashMap<>();
            row.put("job", job(id));
            row.put("name", tag.contains("birthName") ? tag.getString("birthName")
                    : (e.hasCustomName() ? e.getCustomName().getString() : job(id)));
            row.put("at", List.of((int) e.getX(), (int) e.getY(), (int) e.getZ()));
            if (!"none".equals(crew)) {
                row.put("crew", crew);
            }
            if (hunger >= 0) {
                row.put("hunger", hunger);
                if (max > 0) {
                    row.put("hungerPct", Math.round(hunger * 100.0 / max));
                }
            }
            if (tag.contains("jobsDone")) {
                row.put("jobsDone", tag.getInt("jobsDone"));
            }
            if (home != null) {
                row.put("home", home);
            }
            String bound = posOf(tag, "priorityPos");
            if (bound != null) {
                row.put("boundChest", bound);
            }
            if (hasHands) {
                row.put("holding", BuiltInRegistries.ITEM.getKey(hand.getItem())
                        + " x" + hand.getCount());
                // A golem with full hands and nowhere to put them is the shape
                // of the overnight stall: it is not broken, it is waiting on
                // storage it cannot reach.
                if (bound == null) {
                    idle.add(row);
                }
            }
            rows.add(row);
        }

        // Asleep is not the same as gone.
        //
        // A golem in a bunkhouse is NOT an entity - the block entity holds it as
        // NBT in a "Sleepers" list and rebuilds it at dawn. An entity scan alone
        // therefore reports a full bunkhouse as a missing workforce, which is
        // the exact question this verb gets asked after a scare. Counted from
        // the block, and kept separate from the walking count so the two can
        // never be silently added together.
        Map<String, Object> asleepAt = new LinkedHashMap<>();
        int asleep = 0;
        List<Map<String, Object>> sleepers = new ArrayList<>();
        for (BlockPos bp : BlockPos.betweenClosed(
                centre.offset(-radius, -radius, -radius),
                centre.offset(radius, radius, radius))) {
            var be = level.getBlockEntity(bp);
            if (be == null) {
                continue;
            }
            String block = BuiltInRegistries.BLOCK.getKey(
                    level.getBlockState(bp).getBlock()).toString();
            if (!block.contains("bunkhouse")) {
                continue;
            }
            try {
                CompoundTag bt = be.saveWithFullMetadata(level.registryAccess());
                var list = bt.getList("Sleepers", 10);
                String where = bp.getX() + " " + bp.getY() + " " + bp.getZ();
                asleepAt.put(where, list.size());
                asleep += list.size();
                for (int i = 0; i < list.size(); i++) {
                    CompoundTag st = list.getCompound(i);
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("name", st.contains("birthName")
                            ? st.getString("birthName") : "?");
                    row.put("job", st.contains("id") ? job(st.getString("id")) : "?");
                    if (st.contains("jobsDone")) {
                        row.put("jobsDone", st.getInt("jobsDone"));
                    }
                    row.put("asleepIn", where);
                    sleepers.add(row);
                }
            } catch (Exception ex) {
                Ghost.LOG.warn("could not read the bunkhouse at {}", bp, ex);
            }
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("centre", List.of(centre.getX(), centre.getY(), centre.getZ()));
        out.put("radius", radius);
        out.put("awake", total);
        out.put("asleep", asleep);
        out.put("accountedFor", total + asleep);
        if (asleep > 0) {
            out.put("asleepPerBunkhouse", asleepAt);
            out.put("asleepNote", "sleeping golems are NBT inside the bunkhouse, "
                    + "not entities - they will not appear in an entity scan and "
                    + "are not missing");
        }
        out.put("byJob", byJob);
        out.put("byCrew", byCrew);
        out.put("jobsDone", jobsTotal);
        out.put("holdingSomething", holding);
        out.put("homeless", homeless);
        out.put("bunkhouses", homes.size());
        out.put("occupancy", homes);
        if (max > 0) {
            out.put("hungry", hungry);
            out.put("hungerMax", max);
            out.put("hungerNote", "hungry means at or past half the configured "
                    + "maximum; they slow down as it climbs");
        } else {
            out.put("hungerNote", "could not read Hunger Time from "
                    + "config/strawgolem.properties, so hunger is raw and "
                    + "there is no percentage to give");
        }
        if (!idle.isEmpty()) {
            out.put("stalledLikely", idle.size());
            out.put("stalledNote", "holding goods with no bound chest - the "
                    + "overnight-stall shape. Check for a container in reach "
                    + "that will accept what they are carrying.");
        }
        out.put(detail ? "all" : "needingAttention", rows);
        if (detail && !sleepers.isEmpty()) {
            out.put("sleeping", sleepers);
        }
        return out;
    }
}
