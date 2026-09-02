package ghost;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Reads the things that move.
 *
 * <p>Ghost could originally only see blocks, which meant it could describe a
 * farm in detail and had nothing at all to say about the forty golems working
 * it, the mobs in a grinder, or the items lying on the floor. In a modded world
 * half of what matters is an entity.
 *
 * <p>Everything is read through the entity's own saved NBT rather than through
 * any mod's classes. {@code saveWithoutId} is what the game itself writes to
 * disk, so a golem's satchel, a villager's trades and a vehicle's fuel all come
 * out without this ever having heard of the mod that added them.
 */
public final class Entities {

    private Entities() {
    }

    /** NBT keys that are noise in every entity and never worth reporting. */
    private static final List<String> BORING = List.of(
            "Pos", "Motion", "Rotation", "UUID", "FallDistance", "Fire", "Air",
            "OnGround", "Invulnerable", "PortalCooldown", "Dimension", "fall_distance");

    /**
     * Group entities by the home they have claimed.
     *
     * <p>Answers "how many are living in that bunkhouse" without walking to it
     * and counting heads. The home is read from whatever position-shaped NBT the
     * entity persists, so it works for anything that remembers where it sleeps
     * rather than only for one mod's golems.
     */
    public static Map<String, Object> homes(ServerLevel level, BlockPos centre,
                                            int radius, String typeFilter) {
        AABB box = new AABB(centre).inflate(radius);
        Map<String, List<String>> byHome = new java.util.LinkedHashMap<>();
        int homeless = 0;
        int seen = 0;

        for (Entity e : level.getEntities(null, box)) {
            String id = BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).toString();
            if (typeFilter != null && !id.contains(typeFilter)) {
                continue;
            }
            seen++;
            String home = null;
            try {
                CompoundTag tag = new CompoundTag();
                e.saveWithoutId(tag);
                for (String key : List.of("homePos", "HomePos", "home", "priorityPos")) {
                    if (tag.contains(key)) {
                        home = tidy(String.valueOf(tag.get(key)));
                        break;
                    }
                }
            } catch (Exception ignored) {
                // unreadable NBT just means we cannot place this one
            }
            if (home == null || home.isEmpty()) {
                homeless++;
                continue;
            }
            String who = e.hasCustomName() ? e.getCustomName().getString()
                    : id.substring(id.indexOf(':') + 1);
            byHome.computeIfAbsent(home, k -> new ArrayList<>()).add(who);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("scanned", seen);
        out.put("homes", byHome.size());
        out.put("homeless", homeless);
        Map<String, Integer> counts = new LinkedHashMap<>();
        byHome.forEach((k, v) -> counts.put(k, v.size()));
        out.put("occupancy", counts);
        out.put("residents", byHome);
        return out;
    }

    /** NBT positions print as [I;12,34,56] or {X:..}; both reduce to "12 34 56". */
    private static String tidy(String raw) {
        String s = raw.replaceAll("[\\[\\]{}]", "")
                .replace("I;", "")
                .replaceAll("[A-Za-z]:", "")
                .replaceAll("[a-zA-Z]", "")
                .trim();
        return s.replaceAll("[,;]+", " ").replaceAll("\\s+", " ").trim();
    }

    public static Map<String, Object> survey(ServerLevel level, BlockPos centre,
                                             int radius, boolean detail, String typeFilter) {
        AABB box = new AABB(centre).inflate(radius);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("centre", List.of(centre.getX(), centre.getY(), centre.getZ()));
        out.put("radius", radius);

        Map<String, Integer> counts = new TreeMap<>();
        List<Map<String, Object>> rows = new ArrayList<>();
        int items = 0;
        Map<String, Integer> onFloor = new TreeMap<>();

        for (Entity e : level.getEntities(null, box)) {
            String id = BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).toString();
            if (typeFilter != null && !id.contains(typeFilter)) {
                continue;
            }
            counts.merge(id, 1, Integer::sum);

            // Dropped items are summarised separately - "118 item entities" is
            // useless, "14 stacks of inferium essence" is not.
            if (e instanceof ItemEntity ie) {
                items++;
                var st = ie.getItem();
                onFloor.merge(BuiltInRegistries.ITEM.getKey(st.getItem()).toString(),
                        st.getCount(), Integer::sum);
                continue;
            }
            if (!detail) {
                continue;
            }

            Map<String, Object> row = new LinkedHashMap<>();
            row.put("type", id);
            row.put("pos", List.of((int) e.getX(), (int) e.getY(), (int) e.getZ()));
            if (e.hasCustomName()) {
                row.put("name", e.getCustomName().getString());
            }
            if (e instanceof LivingEntity le) {
                row.put("health", Math.round(le.getHealth() * 10) / 10.0);
                row.put("maxHealth", Math.round(le.getMaxHealth() * 10) / 10.0);
            }
            // The mod-agnostic part: whatever the entity itself persists.
            try {
                CompoundTag tag = new CompoundTag();
                e.saveWithoutId(tag);
                Map<String, String> extra = new LinkedHashMap<>();
                for (String key : tag.getAllKeys()) {
                    if (BORING.contains(key)) {
                        continue;
                    }
                    Tag v = tag.get(key);
                    String s = String.valueOf(v);
                    if (s.length() > 220) {
                        s = s.substring(0, 220) + "...";
                    }
                    extra.put(key, s);
                }
                if (!extra.isEmpty()) {
                    row.put("data", extra);
                }
            } catch (Exception ignored) {
                // An entity that will not serialise is not worth failing over.
            }
            rows.add(row);
        }

        out.put("counts", counts);
        out.put("total", counts.values().stream().mapToInt(Integer::intValue).sum());
        if (items > 0) {
            out.put("itemEntities", items);
            out.put("onFloor", onFloor);
        }
        if (detail) {
            out.put("entities", rows);
        }
        return out;
    }
}
