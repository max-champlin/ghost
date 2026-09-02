package ghost;

import appeng.api.AECapabilities;
import appeng.api.networking.IGrid;
import appeng.api.networking.IGridNode;
import appeng.api.networking.IInWorldGridNodeHost;
import appeng.api.networking.crafting.CalculationStrategy;
import appeng.api.networking.crafting.ICraftingPlan;
import appeng.api.networking.crafting.ICraftingService;
import appeng.api.networking.crafting.ICraftingSimulationRequester;
import appeng.api.networking.crafting.ICraftingSubmitResult;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Future;

/**
 * Asks an ME network to craft something.
 *
 * <p>The counterpart to {@link Ae2}, which only ever reads. This one spends:
 * it starts a real autocrafting job on the player's own network, which is why
 * it is gated behind {@link Perms.Ability#CRAFT} and runs under
 * {@link IActionSource#ofPlayer}. Going through the player's action source
 * means AE2's own security terminal rules apply for free - Shelby can never do
 * anything on a network that the person asking could not do standing at it.
 *
 * <p><b>Only touch this when AE2 is loaded.</b> Like {@link Ae2} it names AE2
 * types directly, so the JVM resolves them on first use; {@link Storage} does
 * the guarding for the read side and {@link Bridge} does it here.
 *
 * <h2>Why this is not a simple verb</h2>
 *
 * <p>AE2 plans a craft on a background thread and hands back a
 * {@link Future}. A bridge action runs to completion inside one server tick, so
 * it cannot wait for that without freezing the server - the exact mistake that
 * cost 55 seconds of stall the last time something blocked the main thread
 * here. So a request is split in two: {@link #start} kicks off the calculation
 * and returns immediately, and {@link #tick} finishes the job on a later tick
 * and reports the outcome in chat.
 *
 * <p>That reporting is the point. A craft can fail three genuinely different
 * ways - no network, nothing craftable, missing ingredients - and an assistant
 * that answered "sure, crafting it" to all three would be worse than useless.
 * Each one gets said out loud, with the missing items named.
 */
public final class Ae2Craft {

    private Ae2Craft() {
    }

    /** Give up on a calculation that has not finished in this many ticks. */
    private static final int PLAN_TIMEOUT = 1200;

    /** How many missing ingredients to name before summarising the rest. */
    private static final int MISSING_SHOWN = 5;

    private record Pending(Future<ICraftingPlan> future,
                           ICraftingService service,
                           UUID requester,
                           String label,
                           long amount,
                           long deadline,
                           boolean checkOnly) {
    }

    private static final List<Pending> PENDING = new ArrayList<>();

    /**
     * Begin a craft. Returns a human-readable status for the immediate reply -
     * the real outcome arrives later, through {@link #tick}.
     */
    public static String start(ServerLevel level, BlockPos centre, int radius,
                               Item item, long amount, ServerPlayer requester,
                               boolean checkOnly) {
        IGrid grid = gridNear(level, centre, radius);
        if (grid == null) {
            return "no ME network within " + radius + " blocks of there";
        }
        ICraftingService service = grid.getService(ICraftingService.class);
        if (service == null) {
            return "that network has no crafting service";
        }
        AEKey key = AEItemKey.of(item);
        if (!service.isCraftable(key)) {
            // No pattern is not the end of it. AE2's rule - only what someone
            // encoded - is right for a factory and wrong for an assistant, when
            // the ingredients are sitting in the network and the recipe is
            // ordinary knowledge. Do it by hand instead.
            return Ae2Direct.attempt(level, grid, item, amount, requester, checkOnly);
        }

        IActionSource source = requester != null
                ? IActionSource.ofPlayer(requester)
                : IActionSource.empty();
        ICraftingSimulationRequester sim = new ICraftingSimulationRequester() {
            @Override
            public IActionSource getActionSource() {
                return source;
            }

            @Override
            public IGridNode getGridNode() {
                return null;
            }
        };

        try {
            // REPORT_MISSING_ITEMS rather than CRAFT_LESS: a partial craft that
            // silently makes 3 of the 64 asked for is the kind of "success"
            // nobody wants. Better to come back and say what is missing.
            Future<ICraftingPlan> future = service.beginCraftingCalculation(
                    level, sim, key, amount, CalculationStrategy.REPORT_MISSING_ITEMS);
            PENDING.add(new Pending(future, service,
                    requester != null ? requester.getUUID() : null,
                    label(item), amount,
                    level.getGameTime() + PLAN_TIMEOUT, checkOnly));
            return (checkOnly ? "checking whether I can make " : "working out how to make ")
                    + amount + "x " + label(item);
        } catch (Exception e) {
            Ghost.LOG.error("crafting calculation would not start", e);
            return "the network refused the request";
        }
    }

    /**
     * Finish any calculation that has come back, and say what happened.
     *
     * <p>Called every server tick. Cheap when idle - the list is empty almost
     * always, and a pending entry costs one {@code isDone} check.
     */
    public static void tick(MinecraftServer server) {
        if (PENDING.isEmpty()) {
            return;
        }
        long now = server.overworld().getGameTime();
        PENDING.removeIf(job -> {
            if (now > job.deadline()) {
                job.future().cancel(true);
                report(server, job, "gave up working out " + job.label()
                        + " - the calculation took too long");
                return true;
            }
            if (!job.future().isDone()) {
                return false;
            }
            try {
                complete(server, job, job.future().get());
            } catch (Exception e) {
                Ghost.LOG.error("crafting calculation failed", e);
                report(server, job, "the calculation for " + job.label() + " failed");
            }
            return true;
        });
    }

    private static void complete(MinecraftServer server, Pending job, ICraftingPlan plan) {
        if (plan == null) {
            report(server, job, "no plan came back for " + job.label());
            return;
        }
        // A simulation is AE2's way of saying "this is what it WOULD take" -
        // the job cannot actually run as asked.
        if (plan.simulation()) {
            report(server, job, (job.checkOnly() ? "would be short of " + missing(plan)
                    + " to make " + job.amount() + "x " + job.label()
                    : "cannot make " + job.amount() + "x " + job.label()
                            + " - short of " + missing(plan)));
            return;
        }

        if (job.checkOnly()) {
            report(server, job, "can make " + job.amount() + "x " + job.label()
                    + " - the network has a pattern and the materials ("
                    + plan.bytes() + " bytes). Nothing submitted.");
            return;
        }
        ServerPlayer player = player(server, job);
        IActionSource source = player != null
                ? IActionSource.ofPlayer(player)
                : IActionSource.empty();
        try {
            // Null requester and null CPU: no callback wanted, and let AE2 pick
            // whichever crafting CPU is free. This is what the crafting
            // terminal itself does for a plain "craft this" request.
            ICraftingSubmitResult result =
                    job.service().submitJob(plan, null, null, false, source);
            if (result != null && result.successful()) {
                report(server, job, "crafting " + job.amount() + "x " + job.label()
                        + " - job submitted (" + plan.bytes() + " bytes)");
            } else {
                report(server, job, "the network would not take the job for "
                        + job.label() + reason(result));
            }
        } catch (Exception e) {
            Ghost.LOG.error("could not submit crafting job", e);
            report(server, job, "could not submit the job for " + job.label());
        }
    }

    private static String reason(ICraftingSubmitResult result) {
        if (result == null || result.errorCode() == null) {
            return "";
        }
        return " (" + result.errorCode().name().toLowerCase(java.util.Locale.ROOT)
                .replace('_', ' ') + ")";
    }

    /** Name what the plan could not find, so the answer is actionable. */
    private static String missing(ICraftingPlan plan) {
        StringBuilder sb = new StringBuilder();
        int shown = 0;
        int more = 0;
        for (var entry : plan.missingItems()) {
            if (shown < MISSING_SHOWN) {
                if (shown > 0) {
                    sb.append(", ");
                }
                sb.append(entry.getLongValue()).append("x ")
                        .append(name(entry.getKey()));
                shown++;
            } else {
                more++;
            }
        }
        if (shown == 0) {
            return "something it will not name";
        }
        if (more > 0) {
            sb.append(" and ").append(more).append(" more");
        }
        return sb.toString();
    }

    private static String name(AEKey key) {
        try {
            return key.getDisplayName().getString();
        } catch (Exception e) {
            return key.toString();
        }
    }

    private static String label(Item item) {
        return item.getDescription().getString();
    }

    private static ServerPlayer player(MinecraftServer server, Pending job) {
        return job.requester() == null ? null
                : server.getPlayerList().getPlayer(job.requester());
    }

    private static void report(MinecraftServer server, Pending job, String text) {
        ServerPlayer player = player(server, job);
        if (player != null) {
            Chat.reply(player, text);
        } else {
            Ghost.LOG.info("craft result (nobody to tell): {}", text);
        }
    }

    /**
     * Find a grid by walking outward from a point.
     *
     * <p>Uses AE2's in-world grid node capability, so anything a cable reaches
     * is a valid door in - terminal, interface, drive, controller. Returns the
     * first live grid found rather than collecting them all: crafting into an
     * ambiguous set of networks is not a thing anyone wants.
     */
    private static IGrid gridNear(ServerLevel level, BlockPos centre, int radius) {
        for (BlockPos p : BlockPos.betweenClosed(
                centre.offset(-radius, -radius, -radius),
                centre.offset(radius, radius, radius))) {
            if (level.getBlockEntity(p) == null) {
                continue;
            }
            IInWorldGridNodeHost host;
            try {
                host = level.getCapability(AECapabilities.IN_WORLD_GRID_NODE_HOST, p, null);
            } catch (Exception e) {
                continue;
            }
            if (host == null) {
                continue;
            }
            for (Direction d : Direction.values()) {
                try {
                    IGridNode node = host.getGridNode(d);
                    if (node != null && node.isActive() && node.getGrid() != null) {
                        return node.getGrid();
                    }
                } catch (Exception ignored) {
                    // a host that dislikes a particular face is not an error
                }
            }
        }
        return null;
    }
}
