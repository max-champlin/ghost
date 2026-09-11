# Lanes — making the bridge work for more than one player

Design note for AUDIT.md item 2. Written before the code, so there is something
to check the result against.

---

## The problem

`Bridge` is a state machine with **one** of everything, held in statics:

```java
private static final Deque<JsonObject> QUEUE;   // what is still to do
private static final JsonArray RESULTS;         // what this batch has produced
private static JsonObject pendingWait;          private static long waitDeadline;
private static JsonObject pendingGo;            private static long goDeadline;
private static JsonObject pendingAct;           private static long actAt;
private static int waitTicks;                   private static int batches;
private static String currentId;                private static UUID currentOwner;
```

`tick()` walks those in priority order — a plain delay, then an action waiting
out its beat, then a journey in progress, then a `waitFor` condition, then the
next thing off the queue. That is a correct state machine for **one** actor.

With two players driving two bodies it is wrong in three separate ways:

1. **Contention.** Player B's batch sits in the same `QUEUE` as player A's, so B
   waits out A's travel time. One slow `goto` blocks everyone.
2. **Misattribution.** `currentId` is the id the *results file* is written
   under. With interleaved batches, B's results can be written under A's id —
   the caller gets an answer to someone else's question.
3. **Cross-talk in the pipeline.** `pendingGo` belongs to whoever started
   travelling. `currentBody(server)` reads `currentOwner`, which the newer
   request has already overwritten, so A's journey can be judged complete by B's
   body standing still.

Only (3) is partly fixed today, by `currentOwner`. That made the singleton
honest about *whose* request is in flight. It did not make it plural.

---

## What is per-player and what is not

**Per lane** — everything in the list above. All of it is "what this actor is
doing right now".

**Global, and staying global:**

- `armed` — the bridge is on or off for the server, not per person
- the inbox directory and the file watcher — one reader, see *Routing*
- `MAX_ACTIONS`, `BEAT`, `ARRIVED_WITHIN`, `REACH`, `TRAVEL_TIMEOUT` — constants
- `Roster` — already keyed by owner
- `Recall` — already keyed by owner

**Already per-player without needing a lane:** the body itself, via
`Bodies.owned`. The 2026-09-11 ownership pass did that; lanes are the same idea
applied to the *work*, and should be read as the second half of it.

---

## The Lane object

```java
final class Lane {
    final UUID owner;                  // Roster.UNOWNED for console-driven work
    final Deque<JsonObject> queue   = new ArrayDeque<>();
    final JsonArray         results = new JsonArray();
    JsonObject pendingWait;  long waitDeadline;
    JsonObject pendingGo;    long goDeadline;
    JsonObject pendingAct;   long actAt;
    int waitTicks;
    int batches;
    String currentId;
}
```

`Bridge` holds `Map<UUID, Lane> LANES`, and `tick()` becomes:

```java
for (Lane lane : LANES.values()) {
    tickLane(server, lane);
}
```

Every existing `pendingGo` etc. becomes `lane.pendingGo`. The body lookup takes
the lane's owner instead of reading a static. **The state machine itself does
not change** — only where its variables live.

---

## Routing

One reader still picks files off the inbox, because two readers on one directory
is a race nobody needs. What changes is where a batch is *put*:

1. `as` present and names an online player → that player's lane.
2. No `as` → the lane of the player whose body would be resolved, i.e. the
   existing `requester()` rule.
3. Neither → the `Roster.UNOWNED` lane. Console-driven and single-player-with-no-
   claim setups keep working exactly as now.

A batch is **never** split across lanes. It arrives as one file, it runs in one
lane, in order, and its results are written under its own `currentId`.

---

## Fairness and cost

Lanes are ticked round-robin, one step each per server tick. A lane blocked on
travel or `waitFor` yields immediately, so a slow lane costs the others one map
lookup per tick.

Empty lanes are dropped when their queue and pending slots are all clear, so the
map does not grow with every player who has ever connected.

---

## The safety property this must be checked against

> **With exactly one lane, behaviour must be identical to today.**

That is the whole test plan for a change nobody can currently run two players
against. Single-player exercises precisely one lane, so any behavioural
difference in single-player is a *bug in the refactor*, not a multiplayer
feature. Concretely, after the change these must still hold:

- a batch of reads returns in one tick, in order
- `goto` still blocks its own batch until arrival or timeout
- `waitFor` still polls and still times out
- the results file is still written once per batch under the right id
- `undo` still restores the batch that was actually run

## What this does NOT fix

- **It is still one world.** Two bodies acting on the same block in the same
  tick is a last-writer-wins race, exactly as two players are today.
- **`armed` is global.** One person turning the bridge off turns it off for
  everyone. That is intended.
- **Nothing here is tested against two real players.** Until it is, the
  multiplayer claim in the README stays "untested", and the safety property
  above is the only evidence.
