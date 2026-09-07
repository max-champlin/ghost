package ghost;

import com.google.gson.Gson;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.Level;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Is the base still producing?
 *
 * <p>{@link Watch} samples a region of blocks. This samples the only number that
 * actually answers the question a player cares about at 3am: how much of a thing
 * has reached the ME network. Everything upstream - crops, golems, chests,
 * conduits - exists to move that number, so a flatline catches ANY break in the
 * chain, including the ones nobody thought to instrument.
 *
 * <p>Written after a real one, 2026-09-07. Golems filled four chests, EnderIO
 * conduits that had never been switched to always-on failed to empty them, and
 * 154 golems deadlocked overnight holding one essence each. Every local signal
 * looked plausible: half the storage read empty, the drain looked present, and
 * the chest that WAS full needed a second reading to interpret. The count in the
 * ME system would have said "nothing has arrived in six hours" immediately.
 *
 * <p><b>What this can and cannot tell you.</b> It measures a total, not a rate
 * of production, so a falling or flat count has two explanations it genuinely
 * cannot separate: production stopped, or you are consuming faster than you
 * produce. The alert says so in as many words rather than asserting a cause -
 * the point is to make you look, not to be believed.
 */
public final class Throughput {

    private Throughput() {
    }

    private static ResourceKey<Level> dim;
    private static BlockPos centre;
    private static int radius;
    private static Item want;
    private static long intervalTicks;
    private static long quietTicks;
    private static long nextAt = -1;

    /** Highest count seen. Rising past it is the only proof of production. */
    private static long peak = -1;
    private static long lastRiseAt;
    private static int samples;
    /** Alert on the TRANSITION, so an outage does not bury the phone. */
    private static boolean alerted;

    public static boolean active() {
        return nextAt >= 0;
    }

    public static void start(ServerLevel level, BlockPos at, int r, Item item,
                             int everyMinutes, int quietMinutes) {
        dim = level.dimension();
        centre = at.immutable();
        radius = r;
        want = item;
        intervalTicks = Math.max(1L, everyMinutes) * 60L * 20L;
        quietTicks = Math.max(1L, quietMinutes) * 60L * 20L;
        nextAt = level.getGameTime() + intervalTicks;
        peak = -1;
        lastRiseAt = level.getGameTime();
        samples = 0;
        alerted = false;
    }

    public static void stop() {
        nextAt = -1;
    }

    public static String status() {
        if (!active()) {
            return "not watching production";
        }
        return String.format(
                "watching %s in %s within %d of (%d,%d,%d), every %d min, "
                + "alerting after %d min with no increase | peak %d, %d samples%s",
                BuiltInRegistries.ITEM.getKey(want), dim.location(), radius,
                centre.getX(), centre.getY(), centre.getZ(),
                intervalTicks / 1200L, quietTicks / 1200L, peak, samples,
                alerted ? " | ALERTED" : "");
    }

    public static void tick(MinecraftServer server) {
        if (!active()) {
            return;
        }
        ServerLevel level = server.getLevel(dim);
        if (level == null) {
            return;
        }
        long now = level.getGameTime();
        if (now < nextAt) {
            return;
        }
        nextAt = now + intervalTicks;

        long count;
        try {
            count = Storage.inNetworks(level, centre, radius, want);
        } catch (Exception e) {
            Ghost.LOG.error("production sample failed", e);
            return;
        }
        samples++;

        if (peak < 0 || count > peak) {
            peak = count;
            lastRiseAt = now;
            if (alerted) {
                // Back on its feet. Say so, because an alert with no all-clear
                // trains you to ignore the next one.
                alerted = false;
                announce(server, level, Component.literal(
                        "production recovered - " + BuiltInRegistries.ITEM.getKey(want)
                        + " is rising again (" + count + ")"));
                record("recovered", count, (now - lastRiseAt) / 1200L);
            }
            return;
        }

        long quietMin = (now - lastRiseAt) / 1200L;
        if (now - lastRiseAt < quietTicks || alerted) {
            return;
        }
        alerted = true;
        String id = String.valueOf(BuiltInRegistries.ITEM.getKey(want));
        announce(server, level, Component.literal(
                "no more " + id + " has reached the network in " + quietMin
                + " min (still " + count + ", peak " + peak + "). Either production "
                + "stopped or you are using it faster than it arrives."));
        record("flatline", count, quietMin);
        Ghost.LOG.warn("THROUGHPUT flatline: {} static at {} for {} min", id, count, quietMin);
    }

    private static void announce(MinecraftServer server, ServerLevel level, Component what) {
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            p.sendSystemMessage(Component.literal("[Shelby] ")
                    .withStyle(ChatFormatting.AQUA)
                    .append(what.copy().withStyle(ChatFormatting.YELLOW)));
        }
    }

    /**
     * One line to {@code ghost/alerts.jsonl}.
     *
     * <p>This file is the whole delivery mechanism. The mod deliberately sends
     * nothing itself: no webhook, no SMTP, no API key in a config anyone could
     * leak. Something outside - a Claude session, a script, a phone-notifier of
     * your choosing - tails this and decides who to wake. See the README.
     */
    private static void record(String kind, long count, long quietMinutes) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("at", java.time.OffsetDateTime.now().toString());
        row.put("alert", kind);
        row.put("item", String.valueOf(BuiltInRegistries.ITEM.getKey(want)));
        row.put("count", count);
        row.put("peak", peak);
        row.put("quietMinutes", quietMinutes);
        row.put("dimension", dim.location().toString());
        row.put("centre", java.util.List.of(centre.getX(), centre.getY(), centre.getZ()));
        row.put("radius", radius);
        row.put("meaning", "flatline".equals(kind)
                ? "no increase seen - production stopped, OR consumption exceeds it. "
                  + "This cannot tell the two apart; go and look."
                : "the count is rising again");
        try {
            Path p = Sampler.dir().resolve("alerts.jsonl");
            Files.writeString(p, new Gson().toJson(row) + System.lineSeparator(),
                    StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (Exception e) {
            Ghost.LOG.error("could not write alerts.jsonl", e);
        }
    }
}
