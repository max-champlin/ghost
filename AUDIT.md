# Audit

Known debt in Ghost, with the file and the evidence for each. Kept in the repo
rather than in someone's head, because every item below was found by the world
disagreeing with the code and would otherwise have to be found that way again.

Written 2026-09-11. **Measured, not recalled** — the counts come from the tree,
and where an earlier estimate was wrong it is corrected here.

Scale, for context: 34 Java files, 9,413 lines. `Bridge.java` is 1,772 of them,
`Body.java` 1,115, `GhostCommand.java` 752.

---

## 1. Ownership is enforced in one place and ignored in seventeen

**FIXED 2026-09-11.** 13 call sites now go through `Bridge.body(server, a)`
(the asker's own body, falling back to `find` only when there is no requester or
they own nothing) and 3 pipeline stages through `currentBody(server)`, which
reads a new `currentOwner` set when a request starts and cleared with
`currentId`. Three raw `find()` calls remain and are meant to: the bootstrap in
`requester()` plus the two fallbacks.

*Worth recording how close this came to being worse:* a blanket rewrite of the
call sites also replaced the one inside `requester()`, which `body()` calls to
decide whose body to look for — infinite recursion, directly beneath a comment
saying that line must stay ownership-blind. Caught by counting the remaining
`find()` calls against what was expected (2 found, 3 expected) rather than by
reading the diff.

**Original severity: high — this is the one that bit users.**

`/ghost body here` knows which body belongs to which player. Almost nothing else
does. **17 call sites** of `Bodies.find(server)` across `Bridge.java` and
`Chat.java` still take *the first live body in level-iteration order*, which is
Overworld first and has nothing to do with who asked.

Consequence: on a server, one player's verb acts on another player's body. In
single-player with two bodies, `where` reports one and the verbs move the other —
observed, twice.

**Fix:** resolve the requester first (`requester(server, a)` already does this),
then `Bodies.owned(...)`. `find()` stays only as the no-owner fallback.

---

## 2. The bridge is a singleton pipeline

**Severity: high — it is the multiplayer blocker.**

One inbox directory, and the in-flight state is static:

```java
private static JsonObject pendingWait;   private static long waitDeadline;
private static JsonObject pendingGo;     private static long goDeadline;
private static JsonObject pendingAct;    private static long actAt;
private static String currentId;         private static int batches;
```

One action in flight for the entire server. Two players driving two bodies
contend for the same slot, and `currentId` means their results can be attributed
to each other.

**Fix:** per-player queues and per-player pending state, keyed on the requester.
This is a design change, not a patch, and should be done *after* item 1 — there
is no point routing requests per player while the verbs still guess.

**Partial, 2026-09-11:** `currentOwner` now tracks whose request is in flight,
which is the first thing per-player state needs. It is still *one* owner at a
time — this makes the singleton honest, not plural.

---

## 3. `Bodies.all` promises every dimension and delivers loaded chunks

**DOCUMENTED 2026-09-11** — the javadoc now states the qualifier, says an empty
result means "none loaded" and never "none exist", lists the three bugs that
came from reading it the other way, and points at `Roster` for the "does one
exist?" question. `find()` additionally warns that it is ownership-blind and
order-dependent. The *behaviour* is unchanged and cannot be changed; the lie was
in the description.

**Original severity: high — three separate bugs traced back to this in one day.**

Its own javadoc says *"Every live body, in every dimension."* It walks
`server.getAllLevels()` and calls `level.getEntities(...)`, which only sees
**loaded chunks**.

Measured 2026-09-10 by polling `where` every two seconds across a dimension
change: a body stops being visible **within 2.6 seconds** of the player leaving.
So "not found" is the normal state for any body that is not standing next to
you.

What it caused, in order:
- `where` answering `no live body in any dimension` about a body we had been
  looking at a minute earlier *(fixed — it now says "no body in a loaded chunk")*
- `body here` concluding the player had no body and standing up a second one
  *(fixed — `Roster`, then a refusal)*
- Me asserting one-per-player was "enforced by construction" when it was
  enforced only among loaded bodies *(wrong, corrected)*

**Remaining work:** audit every other caller of `all()` / `find()` for the same
assumption, and make the javadoc say "loaded" everywhere it currently says
"every dimension".

---

## 4. `anchor()` takes position and dimension from different places

**Severity: medium — latent, currently harmless.**

Position comes from the body (`Bodies.find`), dimension from the player
(`dimSource`). They agree today because both resolve to the same place. When
they stop agreeing — the found body is in another dimension — Ghost reports
coordinates from one world interpreted in another, with the same confidence as
everything else.

**Fix:** take both from the same body, or refuse when they disagree.

---

## 5. Nothing Shelby says reaches the log

**Severity: medium — it is what makes every other bug expensive.**

40 user-facing message calls (`sendSuccess`, `displayClientMessage`) against 54
`LOG.` calls, and **a full session produced zero "Shelby" lines in
`latest.log`**. `sendSuccess(..., false)` goes only to the command source.

Consequence: when a suit of armour went missing, the only way to establish
whether the command had dropped it was to ask the person who had watched it
happen. Reconstructing behaviour from memory is how an afternoon disappears.

Partly addressed — `Roster.resolve()` and `Body.spill()` now log — but the
principle is not applied across the board.

**Fix:** any path that moves or destroys property logs it, by name, server-side.

---

## 6. `Bridge.java` is 1,772 lines around one switch

**Severity: low — it works, but it is where per-verb bugs hide.**

32 `case` arms covering 36 verbs (four arms handle pairs: `fill`/`clear`,
`withdraw`/`deposit`, `crouch`/`jump`, `take`/`put`). Argument parsing,
permission checks, the action delay and the response shape are all inline and
repeated.

`checkVerbs` in `build.gradle` keeps the schema and the switch honest about
*which* verbs exist, which is why the count is trustworthy. Nothing keeps them
honest about behaviour.

**Fix, if ever:** one class per verb with a shared contract. Worth doing only
when a verb change starts costing more than the refactor would.

---

## 7. Untested paths

Not bugs. Things that have never run, listed so nobody assumes otherwise:

- **`Roster` relocate** — has never successfully relocated a body. It currently
  refuses instead, by design, pending the entity-load question below
- **Deadman check** — has never fired
- **`/ghost produce`** — armed and disarmed, never left running
- **Multiplayer** — every hour Ghost has ever run has been single-player

---

## Open question

**Does `level.getChunk(x, z)` make an unloaded entity resolvable in the same
tick?** Evidence says no: since 1.17 entities live in separate storage
(`entities/` vs `region/`) and are registered by the entity section manager on a
deferred schedule, and `resolve()` returned null every time for exactly that
case. That is a hypothesis supported by behaviour, **not a verified mechanism**.

`Roster.resolve()` now logs which branch fires, so the next occurrence answers
this from the log instead of from theory. If the answer is "no", the relocate
path needs a chunk ticket and a follow-up tick rather than a synchronous lookup.

---

## How to add to this file

Name the file, quote the evidence, and say how it was found. An item that cannot
say how it was found is a hunch, and hunches belong in an issue, not here.
