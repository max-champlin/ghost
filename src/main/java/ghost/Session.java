package ghost;

import com.google.gson.GsonBuilder;
import net.minecraft.server.MinecraftServer;

import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * A generation counter for the server.
 *
 * <p>An agent holding a picture of the world has no way to know it has gone
 * stale. The server restarts, chunks reload, someone rebuilds half the base -
 * and a cached scan from before all that looks exactly as valid as one from a
 * second ago. Worse, Ghost deliberately disarms the bridge and drops every
 * watch on restart, so an agent that kept queueing actions would be talking to
 * a mod that had stopped listening.
 *
 * <p>{@code session.json} carries a {@code bootId} that changes on every start.
 * Watch that one field: if it differs from the one you saw last, everything you
 * think you know is from a previous life - rescan, and re-arm the bridge.
 *
 * <p>It also records whether the last shutdown was clean. A new bootId whose
 * predecessor never wrote {@code stopped} means the server died rather than
 * exited, which is worth a human's attention and is exactly the kind of thing
 * Ghost cannot see from inside the game.
 */
public final class Session {

    private Session() {
    }

    private static final String BOOT_ID = UUID.randomUUID().toString();

    private static void write(MinecraftServer server, String state) {
        Map<String, Object> o = new LinkedHashMap<>();
        o.put("bootId", BOOT_ID);
        o.put("state", state);
        o.put("at", java.time.OffsetDateTime.now().toString());
        if (server != null) {
            o.put("gameTime", server.overworld().getGameTime());
            o.put("dayTime", server.overworld().getDayTime() % 24000L);
            o.put("players", server.getPlayerList().getPlayerCount());
            o.put("dedicated", server.isDedicatedServer());
            o.put("levelName", server.getWorldData().getLevelName());
        }
        // Restated every start because both are dropped on restart by design -
        // an agent reading this knows it must re-arm rather than assuming.
        o.put("bridgeArmed", Bridge.armed());
        o.put("watching", Watch.active());
        try {
            var p = Sampler.dir().resolve("session.json");
            try (Writer w = Files.newBufferedWriter(p, StandardCharsets.UTF_8)) {
                new GsonBuilder().setPrettyPrinting().create().toJson(o, w);
            }
        } catch (Exception e) {
            Ghost.LOG.error("could not write session.json", e);
        }
    }

    public static void started(MinecraftServer server) {
        write(server, "running");
        Ghost.LOG.info("session {} started", BOOT_ID);
    }

    public static void stopping(MinecraftServer server) {
        write(server, "stopped");
    }
}
