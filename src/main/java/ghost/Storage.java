package ghost;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;

/**
 * The guard between Ghost and AE2.
 *
 * <p>{@link Ae2} names AE2 classes directly, so merely calling into it resolves
 * them and throws {@code NoClassDefFoundError} on a pack without AE2 installed.
 * This class is the only thing allowed to reference it, and it checks first.
 *
 * <p>The check is deliberately cheap and cached: it runs on every item count,
 * and walking the mod list each time would be silly.
 */
public final class Storage {

    private Storage() {
    }

    private static Boolean present;

    public static boolean ae2Loaded() {
        if (present == null) {
            boolean found;
            try {
                found = net.neoforged.fml.ModList.get().isLoaded("ae2");
            } catch (Exception e) {
                found = false;
            }
            present = found;
            Ghost.LOG.info("AE2 {} - ME networks {} be readable",
                    found ? "detected" : "not installed", found ? "will" : "will not");
        }
        return present;
    }

    /** Items in reachable ME networks, or 0 when AE2 is absent. */
    public static long inNetworks(ServerLevel level, BlockPos centre, int radius, Item want) {
        if (!ae2Loaded()) {
            return 0L;
        }
        try {
            return Ae2.count(level, centre, radius, want);
        } catch (Throwable t) {
            // Throwable, not Exception: a missing class or a changed AE2 API
            // surfaces as an Error, and neither should take the command down.
            Ghost.LOG.error("AE2 lookup failed", t);
            return 0L;
        }
    }

    /** Distinct networks in range, so a zero count can be explained. */
    public static int networkCount(ServerLevel level, BlockPos centre, int radius) {
        if (!ae2Loaded()) {
            return 0;
        }
        try {
            return Ae2.networks(level, centre, radius);
        } catch (Throwable t) {
            return 0;
        }
    }

    /**
     * Ask a network to craft something, guarded the same way the reads are.
     *
     * @return a human-readable status for the immediate reply; the real outcome
     *         is reported later in chat by {@link Ae2Craft#tick}
     */
    public static String craft(ServerLevel level, BlockPos centre, int radius,
                               Item want, long amount,
                               net.minecraft.server.level.ServerPlayer requester,
                               boolean checkOnly) {
        if (!ae2Loaded()) {
            return "AE2 is not installed";
        }
        try {
            return Ae2Craft.start(level, centre, radius, want, amount, requester, checkOnly);
        } catch (Throwable t) {
            Ghost.LOG.error("AE2 craft failed", t);
            return "the crafting request failed";
        }
    }

    /** Advance any in-flight crafting calculations. No-op without AE2. */
    public static void tickCrafting(net.minecraft.server.MinecraftServer server) {
        if (!ae2Loaded()) {
            return;
        }
        try {
            Ae2Craft.tick(server);
        } catch (Throwable t) {
            Ghost.LOG.error("AE2 craft tick failed", t);
        }
    }
}
