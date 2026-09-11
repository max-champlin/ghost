package ghost;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.UUID;

/**
 * One actor's work: what they are doing now and what they have left to do.
 *
 * <p>Every field here used to be a {@code static} on {@link Bridge}, which is a
 * correct state machine for exactly one actor and wrong for two in three
 * separate ways: player B's batch queued behind A's travel time, B's results
 * written under A's request id, and A's journey judged complete by B's body
 * standing still. See {@code docs/lanes.md}.
 *
 * <p>Nothing about the state machine changed in moving them here. The priority
 * order in {@link Bridge#tickLane} is the same order it always ran in - a plain
 * delay, then an action waiting out its beat, then a journey, then a
 * {@code waitFor}, then the next thing off the queue. Only the variables moved.
 *
 * <p><b>The property this refactor is checked against:</b> with exactly one
 * lane, behaviour must be identical to before. Single-player exercises precisely
 * one lane, so any behavioural difference there is a bug in the refactor rather
 * than a multiplayer feature.
 */
final class Lane {

    /** Whose work this is. {@link ghost.body.Roster#UNOWNED} for console-driven batches. */
    final UUID owner;

    final Deque<JsonObject> queue = new ArrayDeque<>();
    final JsonArray results = new JsonArray();

    JsonObject pendingWait;
    long waitDeadline;

    JsonObject pendingGo;
    long goDeadline;

    JsonObject pendingAct;
    long actAt;

    int waitTicks;
    int batches;

    /**
     * Which request the batch in flight belongs to, so its answer is addressed
     * back rather than dropped in a shared pigeonhole - or, with two lanes,
     * written under somebody else's id.
     */
    String currentId;

    Lane(UUID owner) {
        this.owner = owner;
    }

    /** Is any part of this lane's batch still happening? */
    boolean inFlight() {
        return pendingGo != null || pendingAct != null || pendingWait != null;
    }

    /**
     * Nothing queued, nothing in flight, nothing waiting.
     *
     * <p>Used to drop the lane, so the map does not grow by one entry for every
     * player who has ever connected.
     */
    boolean idle() {
        return queue.isEmpty() && !inFlight() && waitTicks <= 0 && results.size() == 0;
    }
}
