package ghost;

import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;

/**
 * Fetching a body that is not loaded yet, across ticks.
 *
 * <h2>Why this cannot be done in the command</h2>
 *
 * <p>{@link ghost.body.Roster#resolve} pulls the block chunk in with
 * {@code getChunk} and then asks the level for the entity by uuid - and it
 * comes back null, every time, for exactly the unloaded body it was written to
 * find. Confirmed from a live world 2026-09-11 by the diagnostic that failure
 * now prints:
 *
 * <pre>
 * Roster: chunk [6073, 718] in twilightforest:twilight_forest was loaded, but
 * entity c0220f7e-... is still not registered.
 * </pre>
 *
 * <p>Since 1.17 entities live in their own storage rather than in the region
 * files, and the entity section manager registers them on a <b>deferred
 * schedule</b>. Loading the chunk inside a command therefore cannot produce the
 * entity inside that same command, no matter how the lookup is written.
 *
 * <p>So the work is split. The command holds a ticket on the chunk and says it
 * is fetching; this retries each tick until the entity appears, then moves them.
 * If it never appears, it says so and leaves the record alone - the same rule as
 * everywhere else in this mod, that failing to verify is not the same as knowing
 * there is nothing there.
 */
public final class Recall {

    private Recall() {
    }

    /**
     * How long to keep trying, in ticks.
     *
     * <p>Entity registration normally lands within a tick or two. Ten seconds is
     * far more than that and still short enough that a player is not left
     * wondering whether the command did anything.
     */
    private static final int DEADLINE_TICKS = 200;

    private record Job(UUID owner, ServerLevel from, BlockPos at, UUID entity,
                       ChunkPos held, ServerLevel to, Vec3 dest, float yaw,
                       long expiresAt) {
    }

    private static final List<Job> JOBS = new ArrayList<>();

    /**
     * Ask for a body to be brought here.
     *
     * <p>Takes a ticket on the source chunk so it cannot unload again between
     * now and the entity appearing, which is a real race: the player who ran the
     * command is by definition somewhere else.
     */
    public static void request(ServerPlayer owner, ghost.body.Roster.Entry e,
                               ServerLevel to, Vec3 dest, float yaw) {
        MinecraftServer server = owner.getServer();
        if (server == null) {
            return;
        }
        ServerLevel from = server.getLevel(e.dimension());
        if (from == null) {
            owner.sendSystemMessage(Component.literal(
                    "Shelby: " + e.dimension().location() + " is not loaded on this server."));
            return;
        }
        ChunkPos cp = new ChunkPos(SectionPos.blockToSectionCoord(e.pos().getX()),
                SectionPos.blockToSectionCoord(e.pos().getZ()));
        // FORCED, not POST_TELEPORT. POST_TELEPORT is TicketType<Integer> and
        // carries a lifespan of a few ticks - shorter than the window this is
        // waiting on. FORCED has no timeout, which means it MUST be released on
        // both exit paths below or the chunk stays loaded until a restart.
        from.getChunkSource().addRegionTicket(TicketType.FORCED, cp, 2, cp);
        JOBS.add(new Job(owner.getUUID(), from, e.pos(), e.entity(), cp, to, dest, yaw,
                server.overworld().getGameTime() + DEADLINE_TICKS));
        Ghost.LOG.info("Recall: holding chunk {} in {} for body {}, fetching to {} {}",
                cp, e.dimension().location(), e.entity(), to.dimension().location(), dest);
    }

    /** True while a fetch is outstanding for this player. */
    public static boolean pending(UUID owner) {
        for (Job j : JOBS) {
            if (j.owner().equals(owner)) {
                return true;
            }
        }
        return false;
    }

    public static void tick(MinecraftServer server) {
        if (JOBS.isEmpty()) {
            return;
        }
        long now = server.overworld().getGameTime();
        Iterator<Job> it = JOBS.iterator();
        while (it.hasNext()) {
            Job j = it.next();
            ServerPlayer owner = server.getPlayerList().getPlayer(j.owner());
            net.minecraft.world.entity.Entity found = j.from().getEntity(j.entity());

            if (found instanceof ghost.body.Body body && body.isAlive()) {
                it.remove();
                release(j);
                move(server, owner, j, body);
                continue;
            }
            if (now >= j.expiresAt()) {
                it.remove();
                release(j);
                // Do NOT clear the roster record. Failing to fetch is not proof
                // the body is gone, and deleting the record on an inference is
                // the exact bug that cloned a Shelby and destroyed the evidence.
                Ghost.LOG.warn("Recall: gave up on body {} in {} after {} ticks - "
                        + "it never registered. Record kept.",
                        j.entity(), j.from().dimension().location(), DEADLINE_TICKS);
                if (owner != null) {
                    owner.sendSystemMessage(Component.literal(
                            "Shelby: I could not reach the body in "
                            + j.from().dimension().location()
                            + ". It is still remembered - go there, or /ghost body forget."));
                }
            }
        }
    }

    /** Give the chunk back. Missing this leaks a permanently loaded chunk. */
    private static void release(Job j) {
        try {
            j.from().getChunkSource().removeRegionTicket(TicketType.FORCED, j.held(), 2, j.held());
        } catch (Throwable t) {
            Ghost.LOG.warn("Recall: could not release the ticket on {} in {}",
                    j.held(), j.from().dimension().location(), t);
        }
    }

    /**
     * Stand the replacement up BEFORE removing the original.
     *
     * <p>Written the other way round first: discard, then create, then place.
     * If the placement failed - no room, a level that refused the entity - the
     * original was already gone and its armour with it, because {@code discard}
     * drops nothing. That is the same silent loss fixed in {@code body here} and
     * then in {@code body away} earlier today, reintroduced an hour later in new
     * code. The ordering is the whole safety property: nothing is removed until
     * its replacement is standing.
     */
    private static void move(MinecraftServer server, ServerPlayer owner, Job j,
                             ghost.body.Body body) {
        CompoundTag kit = new CompoundTag();
        body.saveWithoutId(kit);

        ghost.body.Body fresh = ghost.body.Bodies.SHELBY.get().create(j.to());
        if (fresh == null || !placeAndClaim(server, owner, j, fresh)) {
            Ghost.LOG.error("Recall: reached body {} but could not stand a replacement up "
                    + "at {}. The original is untouched and still remembered.",
                    j.entity(), j.dest());
            if (owner != null) {
                owner.sendSystemMessage(Component.literal(
                        "Shelby: I reached them but could not stand them up here - "
                        + "they are still where they were."));
            }
            return;
        }
        body.discard();                   // only now is the original expendable
        int moved = GhostCommand.carryOver(kit, fresh);
        Ghost.LOG.info("Recall: moved body from {} {} to {} {} with {} item(s)",
                j.from().dimension().location(), j.at(),
                j.to().dimension().location(), fresh.blockPosition(), moved);
        if (owner != null) {
            owner.sendSystemMessage(Component.literal(
                    "Shelby: fetched them from " + j.from().dimension().location()
                    + (moved > 0 ? " (brought " + moved + " item" + (moved == 1 ? "" : "s") + ")"
                                 : "")));
        }
    }

    private static boolean placeAndClaim(MinecraftServer server, ServerPlayer owner,
                                         Job j, ghost.body.Body fresh) {
        fresh.moveTo(j.dest().x, j.dest().y, j.dest().z, j.yaw(), 0.0F);
        if (!j.to().addFreshEntity(fresh)) {
            return false;
        }
        fresh.setFollowed(j.owner());
        ghost.body.Roster.of(server).put(j.owner(), j.to().dimension(),
                fresh.blockPosition(), fresh.getUUID());
        return true;
    }
}
