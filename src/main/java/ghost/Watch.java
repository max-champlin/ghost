package ghost;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;

import java.util.Map;

/**
 * The repeating sample.
 *
 * <p>Held in memory only, and deliberately so: a watch that survived a restart
 * would quietly keep writing to a series whose conditions had changed, and the
 * data would look continuous when it was not.
 */
public final class Watch {

    private static ResourceKey<Level> dim;
    private static BlockPos cornerA;
    private static BlockPos cornerB;
    private static long intervalTicks;
    private static long nextAt = -1;
    private static int samples;

    private Watch() {
    }

    public static boolean active() {
        return nextAt >= 0;
    }

    public static void start(ServerLevel level, BlockPos a, BlockPos b, int minutes) {
        dim = level.dimension();
        cornerA = a.immutable();
        cornerB = b.immutable();
        intervalTicks = Math.max(1L, minutes) * 60L * 20L;
        nextAt = level.getGameTime() + intervalTicks;
        samples = 0;
    }

    public static void stop() {
        nextAt = -1;
    }

    public static String status() {
        if (!active()) {
            return "not watching";
        }
        return String.format("watching %s (%d,%d,%d)-(%d,%d,%d) every %d min, %d samples taken",
                dim.location(), cornerA.getX(), cornerA.getY(), cornerA.getZ(),
                cornerB.getX(), cornerB.getY(), cornerB.getZ(),
                intervalTicks / 1200L, samples);
    }

    /**
     * Called every server tick; does nothing until the interval elapses.
     *
     * <p>Samples are stored WITHOUT per-crop detail. A hundred readings of eight
     * hundred crops is a file nobody opens - the aggregates are what carry a
     * growth rate.
     */
    public static void tick(MinecraftServer server) {
        if (!active()) {
            return;
        }
        ServerLevel level = server.getLevel(dim);
        if (level == null) {
            return;
        }
        if (level.getGameTime() < nextAt) {
            return;
        }
        nextAt = level.getGameTime() + intervalTicks;
        try {
            Map<String, Object> data = Sampler.scan(level, cornerA, cornerB, false);
            data.put("sample", ++samples);
            Sampler.appendSeries(data);
        } catch (Exception e) {
            Ghost.LOG.error("sample failed", e);
        }
    }
}
