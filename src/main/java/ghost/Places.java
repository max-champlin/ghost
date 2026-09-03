package ghost;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.core.BlockPos;

import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Names for places, so instructions can be given the way people give them.
 *
 * <p>Everything here took coordinates. That is fine for a program and wrong for
 * a conversation: "go and look at the garden" had to become
 * {@code [12196, 174, 1510]} somewhere, and that somewhere was a human
 * remembering a number. Naming a place once turns every later instruction into
 * the sentence it always was.
 *
 * <p>A place is a point and, optionally, a box. The box matters more than it
 * looks: "the garden" is not a coordinate, it is an area, and a named area can
 * be handed straight to {@code scan} or {@code blockmap} as its corners rather
 * than being re-typed each time.
 *
 * <p>Stored as plain JSON in {@code ghost/places.json} - readable, editable by
 * hand, and diffable. It is a small file that a person may well want to fix at
 * three in the morning without a tool.
 */
public final class Places {

    private Places() {
    }

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /** name -> place. Insertion-ordered so the file reads in the order things were named. */
    private static final Map<String, Place> PLACES = new LinkedHashMap<>();
    private static boolean loaded = false;

    /** A named point, and optionally the box it covers. */
    public static final class Place {
        public int[] pos;
        /** Opposite corners, or null for a place that is just a point. */
        public int[] from;
        public int[] to;
        public String dim;
        public String note;

        public BlockPos point() {
            return new BlockPos(pos[0], pos[1], pos[2]);
        }

        public boolean hasBox() {
            return from != null && to != null && from.length == 3 && to.length == 3;
        }

        public BlockPos cornerFrom() {
            return hasBox() ? new BlockPos(from[0], from[1], from[2]) : point();
        }

        public BlockPos cornerTo() {
            return hasBox() ? new BlockPos(to[0], to[1], to[2]) : point();
        }
    }

    private static Path file() {
        return Sampler.dir().resolve("places.json");
    }

    /** Names are matched case- and space-insensitively: "Sky Farm" finds "sky_farm". */
    private static String key(String name) {
        return name == null ? "" : name.trim().toLowerCase(Locale.ROOT).replace(' ', '_');
    }

    public static synchronized void load() {
        PLACES.clear();
        loaded = true;
        Path p = file();
        if (!Files.exists(p)) {
            return;
        }
        try (Reader r = Files.newBufferedReader(p, StandardCharsets.UTF_8)) {
            JsonObject root = JsonParser.parseReader(r).getAsJsonObject();
            for (String name : root.keySet()) {
                Place place = GSON.fromJson(root.get(name), Place.class);
                if (place != null && place.pos != null && place.pos.length == 3) {
                    PLACES.put(key(name), place);
                }
            }
            Ghost.LOG.info("places: {} remembered", PLACES.size());
        } catch (Exception e) {
            Ghost.LOG.error("could not read places.json - starting empty", e);
        }
    }

    private static void save() {
        try {
            JsonObject root = new JsonObject();
            for (Map.Entry<String, Place> e : PLACES.entrySet()) {
                root.add(e.getKey(), GSON.toJsonTree(e.getValue()));
            }
            Path tmp = Sampler.dir().resolve(".places.tmp");
            try (Writer w = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
                GSON.toJson(root, w);
            }
            Files.move(tmp, file(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception e) {
            Ghost.LOG.error("could not write places.json", e);
        }
    }

    public static synchronized Place get(String name) {
        if (!loaded) {
            load();
        }
        return PLACES.get(key(name));
    }

    public static synchronized boolean has(String name) {
        return get(name) != null;
    }

    public static synchronized Map<String, Place> all() {
        if (!loaded) {
            load();
        }
        return new LinkedHashMap<>(PLACES);
    }

    public static synchronized void remember(String name, Place place) {
        if (!loaded) {
            load();
        }
        PLACES.put(key(name), place);
        save();
    }

    public static synchronized boolean forget(String name) {
        if (!loaded) {
            load();
        }
        boolean had = PLACES.remove(key(name)) != null;
        if (had) {
            save();
        }
        return had;
    }

    /**
     * Names that look like what was asked for, for when a lookup misses.
     *
     * <p>A place that is not found should suggest, not just refuse - the usual
     * reason is a near miss ("the garden" for "garden"), and a refusal carrying
     * the actual names costs nothing and saves a round trip.
     */
    public static synchronized java.util.List<String> suggest(String name) {
        if (!loaded) {
            load();
        }
        String k = key(name);
        java.util.List<String> near = new java.util.ArrayList<>();
        for (String known : PLACES.keySet()) {
            if (known.contains(k) || k.contains(known)) {
                near.add(known);
            }
        }
        return near.isEmpty() ? new java.util.ArrayList<>(PLACES.keySet()) : near;
    }
}
