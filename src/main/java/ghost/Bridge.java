package ghost;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.TagKey;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.util.FakePlayerFactory;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayDeque;
import java.util.Deque;

/**
 * A file channel for acting on the world.
 *
 * <p>Actions arrive as JSON in {@code ghost/inbox.json}, are executed on the
 * server thread, and the results are written to {@code ghost/outbox.json}.
 * In-process rather than over the network, so every modded block and item is
 * simply present - no protocol to implement and nothing to keep in sync with
 * 600 mods.
 *
 * <h2>Why it is deliberately hard to fire by accident</h2>
 *
 * This can destroy things. It is disarmed on every server start and must be
 * turned on with {@code /ghost bridge on}; the inbox is consumed (renamed)
 * before execution so a leftover file cannot replay itself on the next world
 * load; batches are capped; and breaking honours the same
 * {@code buildinggadgets2:deny} tag that protects the cables and ores, so the
 * bridge cannot do what the Destruction Gadget is already forbidden to do.
 */
public final class Bridge {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /** A hole in the dome from one bad coordinate is cheap; a thousand is not. */
    public static final int MAX_ACTIONS = 256;

    private static final TagKey<Block> DENY = TagKey.create(Registries.BLOCK,
            ResourceLocation.fromNamespaceAndPath("buildinggadgets2", "deny"));

    private static boolean armed = false;
    /**
     * A waitFor being evaluated, and the tick it gives up on.
     *
     * <p>Held rather than popped: a condition has to be re-tested every tick
     * until it is met. Fixed tick waits were guesswork - I undersized a settle
     * window and then read a sweep mid-flight on the same afternoon, and both
     * looked like results rather than mistakes.
     */
    private static JsonObject pendingWait;
    private static long waitDeadline;

    /**
     * An action parked until Shelby has physically got to where it happens.
     *
     * <p>Actions used to take effect at coordinates regardless of where she was
     * standing, which made the body decorative - she could be told to break a
     * block on the other side of the base and simply do it from the sofa. With
     * {@code "go": true} the action waits until she is actually there.
     *
     * <p>Opt-in rather than always, deliberately. A twenty-step batch that
     * walked between every step would turn a second of work into ten minutes,
     * so presence is for the things worth watching, and speed is for the rest.
     */
    private static JsonObject pendingGo;
    private static long goDeadline;

    /**
     * An action that has been reached for but not yet done.
     *
     * <p>The beat. She arrives, turns, puts a hand out - and then, a fraction
     * later, the thing happens. Doing it in the same tick as the arrival reads
     * as the world reacting to her presence rather than to her hands, which is
     * exactly the "looks at you and poops out a block" problem. Four tenths of a
     * second is enough to see cause and effect in the right order.
     */
    private static JsonObject pendingAct;
    private static long actAt;

    /** Ticks between reaching for a thing and the thing happening. */
    private static final int BEAT = 8;

    /** How close counts as "there". */
    private static final double ARRIVED_WITHIN = 3.5;

    /**
     * A player's reach. Past this she walks over rather than acting at range.
     *
     * <p>This is the difference between an assistant and a cursor. Every
     * hands-on verb used to work at any distance: she would stand across the
     * room, not look up, and a block would appear in a chest forty blocks away.
     * It worked, and it read as a scripted effect rather than as someone
     * helping. If the pitch is "she works at your base with you", then she has
     * to go to the chest.
     */
    private static final double REACH = 4.5;

    /**
     * Verbs that physically do something somewhere.
     *
     * <p>These travel by default. Reads ({@code scan}, {@code find},
     * {@code read}, {@code have}) deliberately do not - she is allowed to know
     * things without walking to them, the same way you can read a map.
     */
    private static final java.util.Set<String> HANDS_ON = java.util.Set.of(
            "use", "break", "place", "take", "put", "slots",
            "withdraw", "deposit", "fill", "clear");

    /** Longest she may spend travelling before the action happens anyway. */
    private static final int TRAVEL_TIMEOUT = 600;

    private static final Deque<JsonObject> QUEUE = new ArrayDeque<>();
    private static final JsonArray RESULTS = new JsonArray();
    private static int waitTicks = 0;
    private static int batches = 0;

    /**
     * Which request the batch in flight belongs to, so its answer can be
     * addressed back rather than dropped in a shared pigeonhole.
     */
    private static String currentId = null;

    private Bridge() {
    }

    public static boolean armed() {
        return armed;
    }

    public static void arm(boolean on) {
        armed = on;
        if (!on) {
            QUEUE.clear();
            waitTicks = 0;
        }
    }

    /**
     * Whether the bridge has work in flight right now.
     *
     * <p>Read by the body so that being busy is visible from across the room
     * rather than only in a log file.
     */
    public static boolean busy() {
        return !QUEUE.isEmpty() || pendingWait != null;
    }

    public static String status() {
        return (armed ? "ARMED" : "disarmed")
                + ", queue " + QUEUE.size()
                + ", batches run " + batches;
    }

    private static Path inbox() {
        return Sampler.dir().resolve("inbox.json");
    }

    private static Path outbox() {
        return Sampler.dir().resolve("outbox.json");
    }

    /**
     * The request queue: one file per request, so two writers cannot collide.
     *
     * <p>{@code inbox.json} is a single slot. Two callers writing it at the
     * same time means one request is silently destroyed before anything reads
     * it, and the survivor's answer lands in a shared {@code outbox.json} with
     * nothing to say whose it is. That is not a hypothetical - it happened
     * twice in one afternoon with two sessions running, and it is the same
     * failure a server hits when two players ask Shelby for something at once.
     *
     * <p>A caller now drops {@code ghost/in/&lt;whatever&gt;.json}. The name is
     * the request id, files are taken oldest-first, and the answer comes back
     * at {@code ghost/out/&lt;id&gt;.json}. Nothing overwrites anything.
     */
    private static Path inDir() {
        return Sampler.dir().resolve("in");
    }

    private static Path outDir() {
        return Sampler.dir().resolve("out");
    }

    /** Every completed batch, appended, so no answer is ever overwritten. */
    private static Path outLog() {
        return Sampler.dir().resolve("outbox.jsonl");
    }

    public static void tick(MinecraftServer server) {
        if (!armed) {
            return;
        }
        if (waitTicks > 0) {
            waitTicks--;
            return;
        }
        if (pendingAct != null) {
            if (server.overworld().getGameTime() < actAt) {
                return;                       // mid-reach; let it land
            }
            JsonObject act = pendingAct;
            pendingAct = null;
            JsonObject res = new JsonObject();
            res.addProperty("action", act.has("do") ? act.get("do").getAsString() : "?");
            res.addProperty("travelled", true);
            String[] before = snapshotBody(server);
            try {
                run(server, act, res);
            } catch (Exception e) {
                res.addProperty("ok", false);
                res.addProperty("error", String.valueOf(e));
                explain(e, res, server, before);
                Ghost.LOG.error("bridge action failed after the beat: {}", act, e);
            }
            RESULTS.add(res);
            if (QUEUE.isEmpty() && !inFlight()) {
                finish(server);
            }
            return;
        }
        if (pendingGo != null) {
            ServerLevel lvl = level(server, pendingGo);
            ghost.body.Body body = ghost.body.Bodies.find(server);
            boolean there = body == null || body.arrived(ARRIVED_WITHIN);
            boolean expired = lvl.getGameTime() >= goDeadline;
            if (!there && !expired) {
                return;                       // still on her way
            }
            JsonObject act = pendingGo;
            pendingGo = null;
            act.addProperty("__arrived", true);
            // She has walked to it; now face it and put a hand out, so the
            // thing that happens next visibly comes from her.
            if (body != null && act.has("at")) {
                try {
                    reachFor(body, pos(act, "at"));
                } catch (Exception ignored) {
                    // a named place that no longer resolves must not stop the work
                }
            }
            // Reached for above; now let the beat pass before it happens.
            pendingAct = act;
            actAt = server.overworld().getGameTime() + BEAT;
            return;
        }
        if (pendingWait != null) {
            ServerLevel lvl = level(server, pendingWait);
            boolean met = conditionMet(lvl, pendingWait);
            boolean expired = lvl.getGameTime() >= waitDeadline;
            if (!met && !expired) {
                return;                       // still waiting, try again next tick
            }
            JsonObject res = new JsonObject();
            res.addProperty("action", "waitFor");
            res.addProperty("ok", met);
            res.addProperty("timedOut", !met && expired);
            RESULTS.add(res);
            pendingWait = null;
            if (QUEUE.isEmpty() && !inFlight()) {
                finish(server);
            }
            return;
        }
        if (QUEUE.isEmpty()) {
            pickUpInbox();
            if (QUEUE.isEmpty()) {
                return;
            }
        }
        // One action per tick keeps redstone, block updates and entity motion
        // able to actually happen between steps. Draining the whole queue in a
        // single tick would make "throw, wait, look" meaningless.
        JsonObject act = QUEUE.poll();
        JsonObject res = new JsonObject();
        res.addProperty("action", act.has("do") ? act.get("do").getAsString() : "?");
        String[] before = snapshotBody(server);
        try {
            run(server, act, res);
        } catch (Exception e) {
            res.addProperty("ok", false);
            res.addProperty("error", String.valueOf(e));
            explain(e, res, server, before);
            Ghost.LOG.error("bridge action failed: {}", act, e);
        }
        RESULTS.add(res);
        if (QUEUE.isEmpty() && !inFlight()) {
            finish(server);
        }
    }

    /**
     * The next request to run, or null.
     *
     * <p>Prefers the {@code in/} queue and falls back to the old single-slot
     * {@code inbox.json}, so anything already driving the bridge keeps working
     * while callers move over.
     */
    private static Path nextRequest() {
        Path dir = inDir();
        if (Files.isDirectory(dir)) {
            try (java.util.stream.Stream<Path> files = Files.list(dir)) {
                Path oldest = files
                        .filter(f -> f.getFileName().toString().endsWith(".json"))
                        .min(java.util.Comparator.comparing(f -> f.getFileName().toString()))
                        .orElse(null);
                if (oldest != null) {
                    return oldest;
                }
            } catch (IOException e) {
                Ghost.LOG.error("could not list {}", dir, e);
            }
        }
        Path legacy = inbox();
        return Files.exists(legacy) ? legacy : null;
    }

    private static void pickUpInbox() {
        Path in = nextRequest();
        if (in == null) {
            return;
        }
        // The file name is the request id; the legacy single slot has none of
        // its own, so it gets a serial one rather than sharing a name with the
        // next caller's batch.
        String name = in.getFileName().toString();
        currentId = name.equals("inbox.json")
                ? "legacy-" + (batches + 1)
                : name.substring(0, name.length() - 5);
        try {
            // Consume before running: a batch must never be able to replay
            // itself on the next load because the file was still sitting there.
            Path taken = Sampler.dir().resolve("inbox.running.json");
            Files.deleteIfExists(taken);
            Files.move(in, taken);
            try (Reader r = Files.newBufferedReader(taken, StandardCharsets.UTF_8)) {
                JsonElement root = JsonParser.parseReader(r);
                JsonArray arr;
                if (root.isJsonArray()) {
                    arr = root.getAsJsonArray();
                } else {
                    JsonObject obj = root.getAsJsonObject();
                    // An id the caller chose itself beats the file name, so a
                    // requester can pick something it will recognise later.
                    if (obj.has("id")) {
                        currentId = obj.get("id").getAsString();
                    }
                    arr = obj.getAsJsonArray("actions");
                }
                if (arr.size() > MAX_ACTIONS) {
                    Ghost.LOG.error("bridge batch of {} exceeds cap {}", arr.size(), MAX_ACTIONS);
                    writeError("batch of " + arr.size() + " exceeds cap " + MAX_ACTIONS);
                    return;
                }
                RESULTS.getAsJsonArray();
                while (RESULTS.size() > 0) {
                    RESULTS.remove(0);
                }
                for (JsonElement e : arr) {
                    QUEUE.add(e.getAsJsonObject());
                }
                batches++;
                Ghost.LOG.info("bridge batch accepted: {} action(s)", QUEUE.size());
            }
        } catch (Exception e) {
            Ghost.LOG.error("could not read inbox", e);
            writeError(String.valueOf(e));
        }
    }

    /**
     * Is any part of this batch still happening?
     *
     * <p>A batch is NOT over just because the queue drained. A hands-on verb
     * arms a walk and returns immediately, so the dispatcher used to call
     * {@code finish()} while the body was still crossing the room - which wrote
     * the answer, and cleared {@code currentId}. The real result then arrived
     * with no request id at all, went to {@code outbox.jsonl} only, and never
     * reached the caller's own {@code out/<id>.json}. A caller polling its own
     * file waited forever for an answer that had been filed anonymously.
     *
     * <p>Reported from in-game as "the travel-completion result never wrote to
     * my out file". It is the single-slot mailbox bug again in miniature: the
     * answer existed, and nothing said whose it was.
     */
    private static boolean inFlight() {
        return pendingGo != null || pendingAct != null || pendingWait != null;
    }

    /** Where the body is, as {dimension, "x y z"}, or null if there is none. */
    private static String[] snapshotBody(MinecraftServer server) {
        ghost.body.Body b = ghost.body.Bodies.find(server);
        if (b == null) {
            return null;
        }
        BlockPos at = b.blockPosition();
        return new String[]{b.level().dimension().location().toString(),
                at.getX() + " " + at.getY() + " " + at.getZ()};
    }

    /**
     * Work out what a thrown exception actually means, by looking.
     *
     * <p>An exception says a code path did not finish. It does NOT say nothing
     * happened - and treating the two as the same produces the mirror image of
     * a confident false success: a confident false FAILURE. Entering a pocket
     * dimension throws on a fake player's missing network channel, but only
     * <em>after</em> the entity has already been moved. Reporting that as
     * {@code ok:false} with a stack trace describes the one step that failed and
     * hides the one that worked.
     *
     * <p>So before saying a thing failed: check whether the world moved. If she
     * changed dimension or was carried somewhere, the load-bearing part happened
     * and the caller needs to know that far more than it needs the trace.
     */
    private static void explain(Exception e, JsonObject res,
                                MinecraftServer server, String[] before) {
        String s = String.valueOf(e);
        boolean noConnection = s.contains("io.netty") || s.contains("Connection.channel");

        String[] after = snapshotBody(server);
        if (before != null && after != null) {
            if (!before[0].equals(after[0])) {
                // Dimension changed. Whatever threw, the move landed.
                res.addProperty("ok", true);
                res.addProperty("partial", true);
                res.addProperty("movedTo", after[0]);
                res.addProperty("at", after[1]);
                res.addProperty("why", "the interaction threw, but only after it "
                        + "had already carried her from " + before[0] + " to "
                        + after[0] + ". The part that matters happened; the step "
                        + "that failed was a follow-up that wanted a real "
                        + "client to talk to.");
                return;
            }
            if (!before[1].equals(after[1])) {
                res.addProperty("movedFrom", before[1]);
                res.addProperty("at", after[1]);
                res.addProperty("note", "she moved despite the error - check "
                        + "where she is before assuming nothing happened.");
            }
        }

        if (noConnection) {
            res.addProperty("why", "the block tried to SEND something to the "
                    + "player - a clientbound packet - and the stand-in used for "
                    + "world interaction has no network channel to receive it. "
                    + "Often this is cosmetic: DimensionalPocketsII throws here "
                    + "playing the teleport SOUND, after the transfer it was "
                    + "asked for already completed. It is not about screens or "
                    + "menus. Check whether the thing you wanted has already "
                    + "happened before you retry or report a failure.");
        }
    }

    /**
     * Face a thing and reach for it.
     *
     * <p>Cosmetic, and the point. An arm that swings and a head that turns are
     * what make an action read as done BY someone rather than done TO the
     * world; without them she stares into the middle distance while chests
     * rearrange themselves. The container sound is here for the same reason -
     * hearing a lid is most of what tells you a chest was opened.
     */
    private static void reachFor(ghost.body.Body body, BlockPos at) {
        body.getLookControl().setLookAt(at.getX() + 0.5, at.getY() + 0.5, at.getZ() + 0.5);
        body.swing(InteractionHand.MAIN_HAND, true);
        if (body.level() instanceof ServerLevel lvl) {
            net.minecraft.world.level.block.entity.BlockEntity be = lvl.getBlockEntity(at);
            if (be instanceof net.minecraft.world.Container) {
                lvl.playSound(null, at,
                        net.minecraft.sounds.SoundEvents.CHEST_OPEN,
                        net.minecraft.sounds.SoundSource.BLOCKS, 0.4F, 1.0F);
            }
        }
    }

    /**
     * Which world an action happens in.
     *
     * <p>Defaults to the PLAYER's dimension, which is usually what someone
     * means by "here" - but note it is not the body's. If Max walks into the
     * mining dimension and leaves Shelby in the overworld, a coordinate scan
     * reads the mining dimension while {@code where} reports the overworld.
     * Both are honest and they are about different subjects, which is why every
     * result now says which world it used and why.
     */
    private static ServerLevel level(MinecraftServer server, JsonObject a) {
        if (a.has("dim")) {
            String want = a.get("dim").getAsString();
            for (ServerLevel l : server.getAllLevels()) {
                if (l.dimension().location().toString().equals(want)) {
                    return l;
                }
            }
            // Refuse rather than quietly using somewhere else.
            //
            // This used to fall through to the player's dimension, so a typo in
            // "dim" returned a perfectly formed answer about the wrong world -
            // the caller asked for one place, got another, and nothing said so.
            StringBuilder known = new StringBuilder();
            for (ServerLevel l : server.getAllLevels()) {
                if (known.length() > 0) {
                    known.append(", ");
                }
                known.append(l.dimension().location());
            }
            throw new IllegalArgumentException("no dimension called \"" + want
                    + "\" - loaded: " + known);
        }
        ServerPlayer p = server.getPlayerList().getPlayers().stream().findFirst().orElse(null);
        return p != null ? p.serverLevel() : server.overworld();
    }

    /**
     * Say out loud when a job touches something on the protection list.
     *
     * <p>Not a veto. {@code buildinggadgets2:deny} guards against an AREA tool
     * destroying things nobody aimed at; a single named block is aimed at by
     * definition. The value of the list here is as a WARNING - "you just had me
     * break a conduit" is worth saying, and worth saying in chat where the
     * player will see it, not only in a JSON field an agent may not read.
     *
     * <p>{@code fill} and {@code clear} still skip these blocks outright. They
     * are the area case the tag was written for, and there the veto is right.
     */
    private static void noteProtected(MinecraftServer server, ServerLevel level,
                                      BlockPos at, BlockState st, JsonObject res,
                                      String verb) {
        if (!st.is(DENY)) {
            return;
        }
        String id = blockId(st);
        res.addProperty("protected", true);
        res.addProperty("warning", "that block is on the protection list ("
                + id + ") - normally only area tools are kept off it. Doing it "
                + "anyway because you named this one.");
        // In chat as well, because the person who cares most is standing there.
        String where = at.getX() + " " + at.getY() + " " + at.getZ();
        for (ServerPlayer sp : server.getPlayerList().getPlayers()) {
            if (sp.level() != level) {
                continue;
            }
            sp.sendSystemMessage(net.minecraft.network.chat.Component
                    .literal("[Shelby] ")
                    .withStyle(net.minecraft.ChatFormatting.AQUA)
                    .append(net.minecraft.network.chat.Component.literal(
                            "about to " + verb + " " + id + " at " + where
                            + " - that one is normally protected.")
                            .withStyle(net.minecraft.ChatFormatting.YELLOW)));
        }
    }

    /** Why that world, so a surprising answer explains itself. */
    private static String dimSource(MinecraftServer server, JsonObject a) {
        if (a.has("dim")) {
            return "you asked for it";
        }
        ServerPlayer p = server.getPlayerList().getPlayers().stream().findFirst().orElse(null);
        return p != null
                ? "defaulted to where " + p.getGameProfile().getName() + " is standing"
                : "no players online - defaulted to the overworld";
    }

    /** Where "here" means when no position is given: the first player, else spawn. */
    /**
     * Where an action happens when it names no position of its own.
     *
     * <p>Shelby's body wins if she has one. That is the whole difference between
     * a tool and a presence: "what is around you" should mean around HER, at the
     * spot the player can see her standing, rather than around whoever happens
     * to be first in the player list. Falls back to the old behaviour when there
     * is no body, so nothing that worked before stops working.
     */
    /**
     * Who an action is being carried out for.
     *
     * <p>The inbox is written by a program, not a player, so a rank check needs
     * a person to attach to. Named explicitly with {@code "as"} where it
     * matters; otherwise it is whoever the body is currently keeping up with,
     * which is the person who last spoke to her - the same someone whose
     * request this almost certainly is.
     *
     * <p>Falling back to "any player on the server" is deliberate and safe:
     * that player is then the one whose rank is checked, so an unattributed
     * request gets the permissions of an ordinary player rather than of the
     * console.
     */
    private static ServerPlayer requester(MinecraftServer server, JsonObject a) {
        if (a.has("as")) {
            ServerPlayer named = server.getPlayerList()
                    .getPlayerByName(a.get("as").getAsString());
            if (named != null) {
                return named;
            }
        }
        ghost.body.Body body = ghost.body.Bodies.find(server);
        if (body != null && body.followed() instanceof ServerPlayer bound) {
            return bound;
        }
        return server.getPlayerList().getPlayers().stream().findFirst().orElse(null);
    }

    private static BlockPos anchor(MinecraftServer server, ServerLevel level) {
        // Bodies.find, like everywhere else. This used to search one level with
        // a 60-million-block box and no isAlive filter, so it could answer with
        // a different entity than the verb standing next to it was moving.
        ghost.body.Body body = ghost.body.Bodies.find(server);
        if (body != null) {
            return body.blockPosition();
        }
        ServerPlayer p = server.getPlayerList().getPlayers().stream().findFirst().orElse(null);
        return p != null ? p.blockPosition() : level.getSharedSpawnPos();
    }

    /**
     * Is a parked waitFor satisfied?
     *
     * <p>{@code block} waits for a position to become a given block.
     * {@code idle} waits for everything nearby to stop moving, which is how you
     * wait for a physics event to finish without knowing how long it takes.
     */
    private static boolean conditionMet(ServerLevel level, JsonObject a) {
        if (a.has("block")) {
            BlockPos p = pos(a, "block");
            String want = a.get("is").getAsString();
            return blockId(level.getBlockState(p)).equals(want);
        }
        if (a.has("idle")) {
            BlockPos p = a.has("at") ? pos(a, "at") : BlockPos.ZERO;
            double r = a.has("radius") ? a.get("radius").getAsDouble() : 16.0;
            var box = new net.minecraft.world.phys.AABB(p).inflate(r);
            for (var e : level.getEntities(null, box)) {
                if (e.getDeltaMovement().lengthSqr() > 1.0E-4) {
                    return false;
                }
            }
            return true;
        }
        return true;                          // nothing asked for: already met
    }

    /**
     * Coordinates, or the name of a remembered place.
     *
     * <p>{@code "at": [12196, 174, 1510]} and {@code "at": "garden"} both work,
     * so an instruction can be written the way it would be spoken. A name that
     * covers a box resolves to the corner appropriate to the key: {@code from}
     * and {@code to} take the box's corners, anything else takes its point.
     */
    private static BlockPos pos(JsonObject a, String key) {
        JsonElement e = a.get(key);
        if (e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isString()) {
            String name = e.getAsString();
            Places.Place place = Places.get(name);
            if (place == null) {
                throw new IllegalArgumentException("no place called \"" + name
                        + "\" - known: " + String.join(", ", Places.suggest(name)));
            }
            if ("from".equals(key)) {
                return place.cornerFrom();
            }
            if ("to".equals(key)) {
                return place.cornerTo();
            }
            return place.point();
        }
        JsonArray p = a.getAsJsonArray(key);
        return new BlockPos(p.get(0).getAsInt(), p.get(1).getAsInt(), p.get(2).getAsInt());
    }

    private static void run(MinecraftServer server, JsonObject a, JsonObject res) {
        String what = a.get("do").getAsString();
        ServerLevel level = level(server, a);
        // On EVERY result, not just the ones that happen to mention it. A
        // coordinate means nothing without a world, and the expensive way to
        // find that out is to read terrain from somewhere you were not asking
        // about and believe it.
        res.addProperty("dimension", level.dimension().location().toString());
        res.addProperty("dimFrom", dimSource(server, a));

        // "go": true - send her there first, and run this when she arrives.
        // Go there first - by default, for anything hands-on.
        //
        // "go" used to be opt-in, so the normal case was acting at arbitrary
        // range. Now the normal case is walking over, and "go": false is the
        // escape hatch for when you deliberately want the effect without the
        // journey. Already within arm's length? Then there is nothing to walk,
        // and she just does it.
        boolean handsOn = HANDS_ON.contains(what);
        boolean wantGo = a.has("go") ? a.get("go").getAsBoolean() : handsOn;

        // A standing order outranks a default.
        //
        // Travelling by default is right for the common case and wrong the
        // moment someone has deliberately STATIONED her: "wait by the bed" then
        // "check that chest" should not walk her across the base and leave the
        // post. The explicit instruction wins over the implied one, and an
        // explicit "go": true still overrides that if the journey is the point.
        //
        // Found the honest way: an operator who had read the change still did
        // not expect a network call to relocate her. A default that surprises
        // someone who knows about it is too blunt.
        if (wantGo && !a.has("go")) {
            ghost.body.Body stationedBody = ghost.body.Bodies.find(server);
            if (stationedBody != null && stationedBody.stationed()) {
                wantGo = false;
                res.addProperty("stayedPut", true);
                res.addProperty("note", "she is stationed, so I did this from "
                        + "where she stands rather than leaving the post. "
                        + "\"go\": true to send her anyway.");
            }
        }
        if (wantGo && a.has("at") && !a.has("__arrived")) {
            ghost.body.Body body = ghost.body.Bodies.find(server);
            if (body != null) {
                BlockPos site = pos(a, "at");
                double away = Math.sqrt(body.distanceToSqr(
                        site.getX() + 0.5, site.getY() + 0.5, site.getZ() + 0.5));
                if (away <= REACH) {
                    reachFor(body, site);      // close enough: reach, do not walk
                } else {
                    body.postTo(site);
                    pendingGo = a;
                    goDeadline = level.getGameTime() + TRAVEL_TIMEOUT;
                    res.addProperty("ok", true);
                    res.addProperty("travelling", true);
                    res.addProperty("blocksAway", Math.round(away));
                    res.addProperty("to", site.getX() + " " + site.getY() + " " + site.getZ());
                    return;
                }
            }
            // No body to send. Do it from here rather than refusing - the work
            // still needs doing, and saying so is better than silently pretending
            // she went.
            res.addProperty("noBody", true);
        }

        switch (what) {
            case "wait" -> {
                waitTicks = Math.max(0, a.get("ticks").getAsInt());
                res.addProperty("ok", true);
                res.addProperty("ticks", waitTicks);
            }
            case "command" -> {
                String cmd = a.get("cmd").getAsString();
                var src = server.createCommandSourceStack().withLevel(level);
                if (a.has("at")) {
                    BlockPos p = pos(a, "at");
                    src = src.withPosition(new Vec3(p.getX() + 0.5, p.getY(), p.getZ() + 0.5));
                }
                server.getCommands().performPrefixedCommand(src, cmd);
                res.addProperty("ok", true);
                res.addProperty("cmd", cmd);
            }
            case "break" -> {
                BlockPos p = pos(a, "at");
                BlockState st = level.getBlockState(p);
                // Report, do not refuse.
                //
                // buildinggadgets2:deny is a list for AREA tools - it stops a
                // Destruction Gadget swinging through a room and eating things,
                // which is a real risk because a gadget cannot be reasoned with.
                // Shelby is not a gadget: she is handed ONE block by someone who
                // typed the coordinate. Applying a tool's blacklist to her made
                // 400 ores unmineable to guard against a rare mistyped position,
                // which is the wrong trade - it broke the ordinary job to
                // prevent the unusual one.
                //
                // So she does the work and says loudly what it was. A wrong
                // assignment becomes VISIBLE rather than silently prevented,
                // which is the right shape for an assistant: check the job, then
                // do it, and be honest about what you touched.
                noteProtected(server, level, p, st, res, "break");
                Undo.begin(level, Undo.label("break", st));
                Undo.record(level, p);
                res.addProperty("was", blockId(st));
                boolean drop = !a.has("drop") || a.get("drop").getAsBoolean();
                res.addProperty("ok", level.destroyBlock(p, drop));
            }
            case "place" -> {
                // Accepts "block" or "item". They named the same thing to every
                // caller who was not reading the source, and asking for one by
                // the other's name threw a NullPointerException out of
                // getAsString() - a crash for a typo.
                JsonElement which = a.has("block") ? a.get("block")
                        : a.has("item") ? a.get("item") : null;
                if (which == null) {
                    res.addProperty("ok", false);
                    res.addProperty("error", "place needs \"block\" (or \"item\") naming what to place");
                } else {
                    ItemLookup.BlockResult found = ItemLookup.resolveBlock(which.getAsString());
                    if (!found.ok()) {
                        // Refusing matters more here than anywhere else: an
                        // unknown id used to resolve to AIR and go straight into
                        // setBlockAndUpdate, so a mistyped block DELETED whatever
                        // was standing there instead of placing anything.
                        res.addProperty("ok", false);
                        res.addProperty("error", found.error);
                        if (!found.candidates.isEmpty()) {
                            res.add("candidates", JsonParser.parseString(
                                    new Gson().toJson(found.candidates)));
                        }
                    } else {
                        BlockPos p = pos(a, "at");
                        net.minecraft.world.level.block.state.BlockState had =
                                level.getBlockState(p);
                        String was = blockId(had);
                        // The deny tag protects a block from being DESTROYED,
                        // and overwriting destroys it just as surely as mining
                        // it does. "break" honoured this and "place" did not, so
                        // a protected block could still be erased - just without
                        // ever yielding its drops. Found the expensive way: a
                        // break refused three coal ore and the place in the same
                        // batch overwrote them anyway, so they were lost with
                        // nothing collected. fill already reasoned this out;
                        // place never got the same treatment.
                        noteProtected(server, level, p, had, res, "overwrite");
                        Undo.begin(level, Undo.label("place over", had));
                        Undo.record(level, p);
                        // Break it first so it DROPS rather than being
                        // annihilated. setBlockAndUpdate over a chest destroys
                        // the chest and everything in it without a trace.
                        if (!had.isAir()) {
                            level.destroyBlock(p, true);
                        }
                        res.addProperty("ok",
                                level.setBlockAndUpdate(p, found.block.defaultBlockState()));
                        res.addProperty("was", was);
                        res.addProperty("block", blockId(level.getBlockState(p)));
                        if (found.resolvedFrom != null) {
                            res.addProperty("resolvedFrom", found.resolvedFrom);
                        }
                    }
                }
            }
            case "use" -> {
                BlockPos p = pos(a, "at");
                ServerPlayer fake = FakePlayerFactory.getMinecraft(level);
                ItemStack stack = stackOf(a);
                fake.setPos(p.getX() + 0.5, p.getY() + 1.0, p.getZ() + 0.5);
                fake.setItemInHand(InteractionHand.MAIN_HAND, stack);
                Direction face = a.has("face")
                        ? Direction.byName(a.get("face").getAsString()) : Direction.UP;
                BlockHitResult hit = new BlockHitResult(
                        new Vec3(p.getX() + 0.5, p.getY() + 1.0, p.getZ() + 0.5),
                        face == null ? Direction.UP : face, p, false);
                // Sneak, on request.
                //
                // Plenty of blocks put a SECOND action behind a crouched
                // right-click - Max's own portal dialer cycles its destination
                // that way, and a fake player never crouches, so that action was
                // simply unreachable from here. Reset in the finally below,
                // because FakePlayerFactory hands out a SHARED player: leaving
                // it crouched would silently change the meaning of every later
                // use in the batch.
                boolean sneak = a.has("sneak") && a.get("sneak").getAsBoolean();
                fake.setShiftKeyDown(sneak);

                // Vanilla's own order, which this skipped entirely.
                //
                // ItemStack.useOn runs the ITEM's behaviour on the block. An
                // empty hand has no item, so it returned PASS without ever
                // consulting the block - meaning "use with no item" could never
                // press a button, pull a lever, or open anything. It was a
                // guaranteed no-op that reported itself as a clean PASS, which
                // reads like the block declined rather than like nobody asked.
                //
                // ServerPlayerGameMode asks the block first and only then the
                // item, so that is what happens here.
                net.minecraft.world.level.block.state.BlockState st =
                        level.getBlockState(p);
                net.minecraft.world.InteractionResult out;
                var viaItem = st.useItemOn(stack, level, fake,
                        InteractionHand.MAIN_HAND, hit);
                if (viaItem == net.minecraft.world.ItemInteractionResult
                        .PASS_TO_DEFAULT_BLOCK_INTERACTION) {
                    out = st.useWithoutItem(level, fake, hit);
                    if (!out.consumesAction() && !stack.isEmpty()) {
                        out = stack.useOn(new UseOnContext(level, fake,
                                InteractionHand.MAIN_HAND, stack, hit));
                    }
                } else {
                    out = viaItem.result();
                }
                res.addProperty("ok", out.consumesAction());
                res.addProperty("result", out.toString());
                res.addProperty("block", blockId(st));
                if (!out.consumesAction()) {
                    // Say WHY nothing happened, because "PASS" alone is
                    // indistinguishable from a bug - as it was until now.
                    res.addProperty("note", "the block declined."
                            + (sneak ? "" : " Some blocks put a second action behind"
                            + " a crouched click - try \"sneak\": true.")
                            + " A block whose right-click opens a screen cannot be"
                            + " operated this way at all: there is nobody here to"
                            + " look at it.");
                }
                fake.setShiftKeyDown(false);
            }
            case "scan" -> {
                BlockPos from = pos(a, "from");
                BlockPos to = pos(a, "to");
                var data = Sampler.scan(level, from, to, a.has("detail")
                        && a.get("detail").getAsBoolean());
                res.add("scan", JsonParser.parseString(new Gson().toJson(data)));
                res.addProperty("ok", true);
            }
            case "goto" -> {
                // Send the body somewhere - and make it STICK.
                //
                // This used to set no posting at all, which meant the follow
                // logic simply won. It runs once a second, sees she is further
                // from the player than TELEPORT_AT, and steps her straight back.
                // So a warp really did happen, was really undone a second later,
                // and reported {"warped": true} - true at the instant it was
                // written and worthless by the time anyone looked. A confident
                // false success is worse than an error, so: post her first, then
                // move her.
                BlockPos p = pos(a, "at");
                ghost.body.Body body = ghost.body.Bodies.find(server);
                if (body == null) {
                    res.addProperty("ok", false);
                    res.addProperty("error", "no body - /ghost body first");
                    break;
                }
                boolean warpFlag = a.has("warp") && a.get("warp").getAsBoolean();
                if (body.level() != level) {
                    // A warp may cross; a walk may not. Refusing outright was
                    // too blunt - "he is in the mining dimension and she is in
                    // the overworld" is an ordinary situation, and a teleport
                    // that will not teleport across a dimension is not much of
                    // a teleport. Walking there is still impossible, so that
                    // still refuses.
                    if (!warpFlag) {
                        res.addProperty("ok", false);
                        res.addProperty("error", "she is in "
                                + body.level().dimension().location() + ", not "
                                + level.dimension().location()
                                + " - she cannot walk between worlds. Use "
                                + "\"warp\": true, or \"return\" and let her "
                                + "follow whoever is over there.");
                        break;
                    }
                    body.postTo(p, true);
                    // Deferred to the end of the tick and verified on arrival:
                    // changeDimension removes her BEFORE building the
                    // replacement, so a failed crossing would otherwise delete
                    // her and everything she carries.
                    body.crossTo(level, new net.minecraft.world.phys.Vec3(
                            p.getX() + 0.5, p.getY(), p.getZ() + 0.5));
                    res.addProperty("ok", true);
                    res.addProperty("crossing", true);
                    res.addProperty("from", body.level().dimension().location().toString());
                    res.addProperty("to", level.dimension().location().toString());
                    res.addProperty("note", "crossing worlds - it lands at the end "
                            + "of this tick, so check \"where\" next batch rather "
                            + "than trusting this line.");
                    break;
                }
                // Stationed, not an errand: an errand clears itself on arrival
                // and she would resume following, which for a target this far
                // away means walking straight back. "return" releases her.
                body.postTo(p, true);

                boolean warp = warpFlag;
                if (warp) {
                    body.teleportTo(p.getX() + 0.5, p.getY(), p.getZ() + 0.5);
                } else {
                    boolean started = body.getNavigation()
                            .moveTo(p.getX() + 0.5, p.getY(), p.getZ() + 0.5, 1.0);
                    res.addProperty("pathing", started);
                    if (!started) {
                        res.addProperty("note", "no path from here - she will keep "
                                + "trying and step across on her own if she cannot "
                                + "walk it");
                    }
                }
                // Report where she ACTUALLY is, not where she was told to go.
                // Every other claim in this reply is intent; this one is
                // observation, and it is the only one worth trusting.
                net.minecraft.core.BlockPos now = body.blockPosition();
                double off = Math.sqrt(body.distanceToSqr(
                        p.getX() + 0.5, p.getY(), p.getZ() + 0.5));
                res.addProperty("ok", true);
                res.addProperty("at", now.getX() + " " + now.getY() + " " + now.getZ());
                res.addProperty("blocksAway", Math.round(off));
                res.addProperty("stationed", true);
                if (warp) {
                    // Verified, not assumed. If a mod or a mixin refuses the
                    // teleport this says so instead of claiming victory.
                    res.addProperty("warped", off <= 2.0);
                    if (off > 2.0) {
                        res.addProperty("error", "the teleport did not take - she is "
                                + Math.round(off) + " blocks from the target");
                    }
                }
                res.addProperty("holds", "she stays here until \"return\"");
            }
            case "remember" -> {
                // Naming a place once turns every later instruction into the
                // sentence it always was. With no "at", it names wherever
                // Shelby is standing - which is how a person would do it:
                // walk there, say "this is the garden".
                String name = a.get("name").getAsString();
                Places.Place place = new Places.Place();
                BlockPos point;
                if (a.has("at")) {
                    point = pos(a, "at");
                } else {
                    ghost.body.Body body = ghost.body.Bodies.find(server);
                    point = body != null ? body.blockPosition() : anchor(server, level);
                }
                place.pos = new int[]{point.getX(), point.getY(), point.getZ()};
                place.dim = level.dimension().location().toString();
                if (a.has("from") && a.has("to")) {
                    BlockPos f = pos(a, "from");
                    BlockPos t = pos(a, "to");
                    place.from = new int[]{f.getX(), f.getY(), f.getZ()};
                    place.to = new int[]{t.getX(), t.getY(), t.getZ()};
                }
                if (a.has("note")) {
                    place.note = a.get("note").getAsString();
                }
                Places.remember(name, place);
                res.addProperty("ok", true);
                res.addProperty("remembered", name);
                res.addProperty("at", point.getX() + " " + point.getY() + " " + point.getZ());
                res.addProperty("hasArea", place.hasBox());
            }
            case "forget" -> {
                String name = a.get("name").getAsString();
                boolean had = Places.forget(name);
                res.addProperty("ok", had);
                if (!had) {
                    res.addProperty("error", "no place called \"" + name + "\"");
                    res.add("known", JsonParser.parseString(
                            new Gson().toJson(Places.suggest(name))));
                }
            }
            case "places" -> {
                res.add("places", JsonParser.parseString(new Gson().toJson(Places.all())));
                res.addProperty("count", Places.all().size());
                res.addProperty("ok", true);
            }
            case "where" -> {
                // Where is she standing right now - and is there only one of her.
                //
                // This used to search a single level with a giant box and no
                // isAlive filter, while every other verb used Bodies.find across
                // all levels. Two verbs could confidently describe two different
                // entities, and a position that nobody could see in-world looked
                // like a rendering fault rather than the answer being about
                // someone else. It now reports the census as well as the answer.
                java.util.List<ghost.body.Body> bodies = ghost.body.Bodies.all(server);
                ghost.body.Body body = bodies.isEmpty() ? null : bodies.get(0);
                res.addProperty("ok", body != null);
                res.addProperty("bodies", bodies.size());
                if (body == null) {
                    res.addProperty("error", "no live body in any dimension - "
                            + "/ghost body here");
                } else {
                    BlockPos bp = body.blockPosition();
                    res.add("pos", JsonParser.parseString(
                            "[" + bp.getX() + "," + bp.getY() + "," + bp.getZ() + "]"));
                    res.addProperty("dimension",
                            body.level().dimension().location().toString());
                    res.addProperty("navDone", body.getNavigation().isDone());
                    res.addProperty("alive", body.isAlive());
                    res.addProperty("id", body.getId());
                    // What she is TRYING to do. Without this a position is not
                    // diagnosable - a correct walk to a posting and an
                    // unexplained drift look exactly the same from outside, and
                    // every confusing movement tonight has been one or the other.
                    BlockPos post = body.post();
                    if (post != null) {
                        res.addProperty("post", post.getX() + " " + post.getY()
                                + " " + post.getZ());
                        res.addProperty("stationed", body.stationed());
                        double away = Math.sqrt(body.distanceToSqr(
                                post.getX() + 0.5, post.getY(), post.getZ() + 0.5));
                        res.addProperty("postDistance", Math.round(away));
                        res.addProperty("doing", away <= 3.0
                                ? "at her post"
                                : "travelling to her post");
                    } else if (body.followedId() != null) {
                        res.addProperty("doing", "following a player");
                    } else {
                        res.addProperty("doing", "idle");
                    }
                    if (body.hovering()) {
                        res.addProperty("hovering", true);
                    }
                    if (bodies.size() > 1) {
                        StringBuilder others = new StringBuilder();
                        for (ghost.body.Body b : bodies) {
                            if (others.length() > 0) {
                                others.append("; ");
                            }
                            others.append(b.level().dimension().location())
                                  .append(" ").append(b.blockPosition().toShortString());
                        }
                        res.addProperty("warning", "there are " + bodies.size()
                                + " bodies - verbs act on the first one found, "
                                + "which may not be the one you can see. "
                                + "/ghost body here in the dimension you want, "
                                + "then /ghost body away in the other.");
                        res.addProperty("allBodies", others.toString());
                    }
                }
            }
            case "say" -> {
                // A real answer from Shelby, written back into the game. Marks
                // the waiting questions as dealt with so the counter in the ack
                // reflects what is genuinely outstanding.
                Chat.broadcast(server, a.get("text").getAsString());
                if (!a.has("keepPending") || !a.get("keepPending").getAsBoolean()) {
                    Chat.clearPending();
                }
                res.addProperty("ok", true);
            }
            case "waitFor" -> {
                // Parked rather than run: the tick loop re-tests it until it is
                // satisfied or the timeout passes. Always bounded - a condition
                // that never comes true must not wedge the queue forever.
                pendingWait = a;
                long limit = a.has("timeout") ? a.get("timeout").getAsLong() : 1200L;
                waitDeadline = level.getGameTime() + Math.max(1L, limit);
                res.addProperty("ok", true);
                res.addProperty("parked", true);
            }
            case "entities" -> {
                BlockPos at = a.has("at") ? pos(a, "at") : anchor(server, level);
                int r = a.has("radius") ? a.get("radius").getAsInt() : 24;
                boolean detail = a.has("detail") && a.get("detail").getAsBoolean();
                res.add("entities", JsonParser.parseString(
                        new Gson().toJson(Entities.survey(level, at, r, detail,
                                a.has("type") ? a.get("type").getAsString() : null))));
                res.addProperty("ok", true);
            }
            case "post" -> {
                // Station her somewhere until told otherwise. Unlike "go", this
                // survives the end of the batch - for when the work is where she
                // should be, not a errand to run and come back from.
                ghost.body.Body body = ghost.body.Bodies.find(server);
                BlockPos site = a.has("at") ? pos(a, "at") : anchor(server, level);
                if (body == null) {
                    res.addProperty("ok", false);
                    res.addProperty("error", "no body to station");
                } else {
                    body.postTo(site, true);   // stationed until released
                    res.addProperty("ok", true);
                    res.addProperty("posted", site.getX() + " " + site.getY() + " " + site.getZ());
                }
            }
            case "return" -> {
                ghost.body.Body body = ghost.body.Bodies.find(server);
                if (body != null) {
                    body.clearPost();
                }
                res.addProperty("ok", true);
                res.addProperty("returning", true);
            }
            case "have" -> {
                // Counts from a POSITION, not from a player.
                //
                // The /ghost have command anchors on src.getPlayer(), which is
                // null when the command is run through this bridge - so it
                // reported "none within 16 blocks" for items that were plainly
                // there, and did it confidently. A verification that quietly
                // looks somewhere else is worse than none, because it gets
                // believed.
                BlockPos at = a.has("at") ? pos(a, "at") : anchor(server, level);
                int r = a.has("radius") ? a.get("radius").getAsInt() : 16;
                // An unrecognised id used to resolve to AIR and get counted,
                // so "essence" answered 0 next to a farm feeding that network.
                ItemLookup.Result found = ItemLookup.resolve(a.get("item").getAsString());
                if (!found.ok()) {
                    res.addProperty("ok", false);
                    res.addProperty("error", found.error);
                    if (!found.candidates.isEmpty()) {
                        res.add("candidates", JsonParser.parseString(
                                new Gson().toJson(found.candidates)));
                    }
                } else {
                    res.addProperty("ok", true);
                    res.addProperty("item", found.id);
                    if (found.resolvedFrom != null) {
                        res.addProperty("resolvedFrom", found.resolvedFrom);
                    }
                    res.addProperty("inNetwork", Storage.inNetworks(level, at, r, found.item));
                    res.addProperty("networks", Storage.networkCount(level, at, r));
                    res.addProperty("at", at.getX() + " " + at.getY() + " " + at.getZ());
                    res.addProperty("radius", r);
                }
            }
            case "cells" -> {
                // "Which cell has the blaze seeds" is a question players ask,
                // and dumping a drive's NBT cannot answer it - one unpartitioned
                // junk cell fills the whole budget before reaching cell two.
                // AE2 hands back each cell as a real inventory, so they are read
                // one at a time instead.
                BlockPos at = a.has("at") ? pos(a, "at") : anchor(server, level);
                int r = a.has("radius") ? a.get("radius").getAsInt() : 8;
                Item want = null;
                if (a.has("item")) {
                    ItemLookup.Result found = ItemLookup.resolve(a.get("item").getAsString());
                    if (!found.ok()) {
                        res.addProperty("ok", false);
                        res.addProperty("error", found.error);
                        if (!found.candidates.isEmpty()) {
                            res.add("candidates", JsonParser.parseString(
                                    new Gson().toJson(found.candidates)));
                        }
                        break;
                    }
                    want = found.item;
                    res.addProperty("item", found.id);
                }
                res.add("cells", JsonParser.parseString(new Gson().toJson(
                        Storage.cells(level, at, r, want))));
                res.addProperty("ok", true);
            }
            case "craft" -> {
                // The one verb that spends something. Everything else this
                // bridge does is either reversible or free; an autocrafting job
                // eats a network's stock and cannot be handed back, so it is
                // gated on rank rather than on trust.
                ServerPlayer who = requester(server, a);
                if (!Perms.allows(who, Perms.Ability.CRAFT)) {
                    res.addProperty("ok", false);
                    res.addProperty("error", "rank");
                    res.addProperty("rank", Perms.rank(who));
                    res.addProperty("detail", Perms.refusal(Perms.Ability.CRAFT));
                } else {
                    BlockPos at = a.has("at") ? pos(a, "at") : anchor(server, level);
                    int r = a.has("radius") ? a.get("radius").getAsInt() : 16;
                    long amount = a.has("count") ? a.get("count").getAsLong() : 1L;
                    ItemLookup.Result found = ItemLookup.resolve(a.get("item").getAsString());
                    if (!found.ok()) {
                        res.addProperty("ok", false);
                        res.addProperty("error", found.error);
                        if (!found.candidates.isEmpty()) {
                            res.add("candidates", JsonParser.parseString(
                                    new Gson().toJson(found.candidates)));
                        }
                        if (who != null) {
                            Chat.reply(who, found.error);
                        }
                        break;
                    }
                    Item want = found.item;
                    // "check": true plans the craft and reports what it would
                    // need, taking nothing. The safe way to approach a big
                    // build - find out what is missing before anything is
                    // spent, rather than discovering it halfway through.
                    boolean checkOnly = a.has("check") && a.get("check").getAsBoolean();
                    String status = Storage.craft(level, at, r, want, amount, who, checkOnly);
                    // Say it out loud as well as returning it. A craft that
                    // fails immediately - no network, no pattern - has its whole
                    // answer right here, and the person who asked in chat is
                    // standing there waiting for it. Only the slow path used to
                    // reach them, so a quick "no" arrived as silence.
                    if (who != null) {
                        Chat.reply(who, status);
                    }
                    res.addProperty("ok", true);
                    res.addProperty("status", status);
                    res.addProperty("rank", Perms.rank(who));
                    // The real answer lands in chat a tick or more from now.
                    res.addProperty("async", !checkOnly);
                    res.addProperty("check", checkOnly);
                }
            }
            case "blockmap" -> {
                // One pass, many systems. Drawing a base diagram needs every
                // network's blocks AND their positions; running "find" once per
                // mod means walking the same million blocks a dozen times.
                BlockPos a1 = pos(a, "from");
                BlockPos b1 = pos(a, "to");
                java.util.List<String> matches = new java.util.ArrayList<>();
                if (a.has("match")) {
                    for (JsonElement e : a.getAsJsonArray("match")) {
                        matches.add(e.getAsString());
                    }
                }
                if (matches.isEmpty()) {
                    res.addProperty("ok", false);
                    res.addProperty("error", "no match list given");
                } else {
                    boolean withNbt = a.has("nbt") && a.get("nbt").getAsBoolean();
                    res.add("map", JsonParser.parseString(new Gson().toJson(
                            BlockMap.of(level, a1, b1, matches, withNbt))));
                    res.addProperty("ok", true);
                }
            }
            case "find" -> {
                BlockPos at = a.has("at") ? pos(a, "at") : anchor(server, level);
                int r = a.has("radius") ? a.get("radius").getAsInt() : 32;
                res.add("found", JsonParser.parseString(new Gson().toJson(
                        Finder.findBlocks(level, at, a.get("block").getAsString(), r))));
                res.addProperty("ok", true);
            }
            case "read" -> {
                BlockPos p = pos(a, "at");
                BlockState st = level.getBlockState(p);
                res.addProperty("ok", true);
                res.addProperty("block", blockId(st));
                res.addProperty("state", st.toString());
                res.addProperty("light", level.getRawBrightness(p, 0));
            }
            case "fill", "clear" -> {
                // The two verbs that can wreck a base in one call, so they are
                // gated the same way craft is - on rank, not on trust - and
                // they both honour buildinggadgets2:deny block by block.
                ServerPlayer who = requester(server, a);
                if (!Perms.allows(who, Perms.Ability.WORLD)) {
                    res.addProperty("ok", false);
                    res.addProperty("error", "rank");
                    res.addProperty("rank", Perms.rank(who));
                    res.addProperty("detail", Perms.refusal(Perms.Ability.WORLD));
                    break;
                }
                BlockPos c1 = pos(a, "from");
                BlockPos c2 = pos(a, "to");
                java.util.Map<String, Object> done;
                if ("clear".equals(what)) {
                    boolean drop = !a.has("drop") || a.get("drop").getAsBoolean();
                    done = Bulk.clear(level, c1, c2, drop);
                } else {
                    JsonElement which = a.has("block") ? a.get("block")
                            : a.has("item") ? a.get("item") : null;
                    if (which == null) {
                        res.addProperty("ok", false);
                        res.addProperty("error",
                                "fill needs \"block\" naming what to fill with");
                        break;
                    }
                    ItemLookup.BlockResult found =
                            ItemLookup.resolveBlock(which.getAsString());
                    if (!found.ok()) {
                        // Same reasoning as place: an unresolved name must never
                        // fall through to AIR, or a typo becomes an excavation.
                        res.addProperty("ok", false);
                        res.addProperty("error", found.error);
                        if (!found.candidates.isEmpty()) {
                            res.add("candidates", JsonParser.parseString(
                                    new Gson().toJson(found.candidates)));
                        }
                        break;
                    }
                    boolean onlyAir = a.has("onlyAir") && a.get("onlyAir").getAsBoolean();
                    done = Bulk.fill(level, c1, c2, found.block, onlyAir);
                }
                res.add("result", JsonParser.parseString(new Gson().toJson(done)));
                res.addProperty("ok", Boolean.TRUE.equals(done.get("ok")));
            }
            case "withdraw", "deposit" -> {
                // Moving real network stock, so gated like craft rather than
                // like a read.
                ServerPlayer who = requester(server, a);
                if (!Perms.allows(who, Perms.Ability.CRAFT)) {
                    res.addProperty("ok", false);
                    res.addProperty("error", "rank");
                    res.addProperty("rank", Perms.rank(who));
                    res.addProperty("detail", Perms.refusal(Perms.Ability.CRAFT));
                    break;
                }
                ghost.body.Body body = ghost.body.Bodies.find(server);
                if (body == null) {
                    res.addProperty("ok", false);
                    res.addProperty("error", "no body - there is nothing to carry it in");
                    break;
                }
                BlockPos at = a.has("at") ? pos(a, "at") : body.blockPosition();
                int r = a.has("radius") ? a.get("radius").getAsInt() : 16;

                net.minecraft.world.item.Item want = null;
                if (a.has("item")) {
                    ItemLookup.Result found = ItemLookup.resolve(a.get("item").getAsString());
                    if (!found.ok()) {
                        res.addProperty("ok", false);
                        res.addProperty("error", found.error);
                        if (!found.candidates.isEmpty()) {
                            res.add("candidates", JsonParser.parseString(
                                    new Gson().toJson(found.candidates)));
                        }
                        break;
                    }
                    want = found.item;
                }

                java.util.Map<String, Object> done;
                if ("withdraw".equals(what)) {
                    if (want == null) {
                        res.addProperty("ok", false);
                        res.addProperty("error", "withdraw needs \"item\" - "
                                + "there is no sensible default for what to take out");
                        break;
                    }
                    int count = a.has("count") ? a.get("count").getAsInt() : 1;
                    done = Storage.withdraw(level, at, r, want, count, body.bag());
                } else {
                    // No item means everything carried, which is what "put this
                    // lot away" should mean.
                    done = Storage.deposit(level, at, r, want, body.bag());
                }
                res.add("result", JsonParser.parseString(new Gson().toJson(done)));
                res.addProperty("ok", Boolean.TRUE.equals(done.get("ok")));
            }
            case "undo" -> {
                // The mulligan. She no longer refuses to touch anything, so the
                // safety net is being able to take back the last thing she did
                // rather than being stopped from doing it.
                ServerPlayer who = requester(server, a);
                if (!Perms.allows(who, Perms.Ability.WORLD)) {
                    res.addProperty("ok", false);
                    res.addProperty("error", "rank");
                    res.addProperty("detail", Perms.refusal(Perms.Ability.WORLD));
                    break;
                }
                boolean peek = a.has("check") && a.get("check").getAsBoolean();
                java.util.Map<String, Object> done = peek
                        ? Undo.describe() : Undo.undo();
                res.add("result", JsonParser.parseString(new Gson().toJson(done)));
                res.addProperty("ok", peek || Boolean.TRUE.equals(done.get("ok")));
            }
            case "crouch", "jump" -> {
                // Real body states, not flags on a request. She physically
                // crouches or jumps, and the world gets to react to it the way
                // it would for anyone else standing there.
                ghost.body.Body body = ghost.body.Bodies.find(server);
                if (body == null) {
                    res.addProperty("ok", false);
                    res.addProperty("error", "no body to move");
                    break;
                }
                boolean up = "jump".equals(what);
                if (up) {
                    res.addProperty("jumped", body.hop());
                } else {
                    int ticks = a.has("ticks") ? a.get("ticks").getAsInt() : 20;
                    body.crouchFor(ticks);
                    res.addProperty("crouched", ticks);
                }
                res.addProperty("ok", true);

                // Standing on an elevator? Then this means what it means for a
                // player: jump for the floor above, crouch for the one below.
                if (body.level() instanceof ServerLevel lvl) {
                    BlockPos under = body.blockPosition().below();
                    // Say WHICH of the two "no" cases this is.
                    //
                    // destination() returns null both when she is not on an
                    // elevator and when she is on one with no floor that way,
                    // and reporting nothing for both made a correct search look
                    // like a broken one: a tester standing her exactly on a
                    // confirmed elevator block got the identical empty result
                    // they had been getting from being a block off.
                    boolean onOne = Elevators.isElevator(lvl.getBlockState(under));
                    res.addProperty("onElevator", onOne);
                    BlockPos floor = onOne ? Elevators.destination(lvl, under, up) : null;
                    if (onOne && floor == null) {
                        res.addProperty("elevator", up ? "no floor above" : "no floor below");
                        res.addProperty("note", "she is on "
                                + net.minecraft.core.registries.BuiltInRegistries.BLOCK
                                        .getKey(lvl.getBlockState(under).getBlock())
                                + " but there is no second elevator "
                                + (up ? "above" : "below") + " it within "
                                + Elevators.range() + " blocks. Try the other "
                                + "direction: jump goes up, crouch goes down.");
                    }
                    if (floor != null) {
                        BlockPos spot = Elevators.standingSpot(lvl, floor);
                        if (spot == null) {
                            res.addProperty("elevator", "blocked");
                            res.addProperty("note", "there is an elevator "
                                    + (up ? "above" : "below") + " but no room to "
                                    + "stand on it");
                        } else {
                            body.teleportTo(spot.getX() + 0.5, spot.getY(),
                                    spot.getZ() + 0.5);
                            lvl.playSound(null, spot,
                                    net.minecraft.sounds.SoundEvents.PLAYER_TELEPORT,
                                    net.minecraft.sounds.SoundSource.BLOCKS, 0.4F, 1.0F);
                            res.addProperty("elevator", "rode");
                            res.addProperty("to", spot.getX() + " " + spot.getY()
                                    + " " + spot.getZ());
                        }
                    }
                }
            }
            case "slots" -> {
                res.add("inventory", JsonParser.parseString(new Gson().toJson(
                        Slots.list(level, pos(a, "at")))));
                res.addProperty("ok", true);
            }
            case "worn" -> {
                ghost.body.Body body = ghost.body.Bodies.find(server);
                if (body == null) {
                    res.addProperty("ok", false);
                    res.addProperty("error", "I have no body here to be wearing anything");
                } else {
                    res.add("worn", JsonParser.parseString(new Gson().toJson(
                            Slots.worn(body))));
                    res.addProperty("ok", true);
                }
            }
            case "bag" -> {
                ghost.body.Body body = ghost.body.Bodies.find(server);
                if (body == null) {
                    res.addProperty("ok", false);
                    res.addProperty("error", "I have no body here to carry anything");
                } else {
                    res.add("bag", JsonParser.parseString(new Gson().toJson(
                            Slots.bag(body.bag()))));
                    res.addProperty("ok", true);
                }
            }
            case "take", "put" -> {
                ServerPlayer who = requester(server, a);
                if (!Perms.allows(who, Perms.Ability.WORLD)) {
                    res.addProperty("ok", false);
                    res.addProperty("error", "rank");
                    res.addProperty("rank", Perms.rank(who));
                    res.addProperty("detail", Perms.refusal(Perms.Ability.WORLD));
                    break;
                }
                ghost.body.Body body = ghost.body.Bodies.find(server);
                if (body == null) {
                    res.addProperty("ok", false);
                    res.addProperty("error", "I have no body here to carry anything");
                    break;
                }
                BlockPos at = pos(a, "at");
                int slot = a.has("slot") ? a.get("slot").getAsInt() : 0;
                int count = a.has("count") ? a.get("count").getAsInt() : 64;
                java.util.Map<String, Object> done;
                if ("take".equals(what)) {
                    done = Slots.take(level, at, slot, count, body.bag());
                } else {
                    net.minecraft.world.item.Item want = null;
                    if (a.has("item")) {
                        ItemLookup.Result found =
                                ItemLookup.resolve(a.get("item").getAsString());
                        if (!found.ok()) {
                            res.addProperty("ok", false);
                            res.addProperty("error", found.error);
                            break;
                        }
                        want = found.item;
                    }
                    done = Slots.put(level, at, slot, count, want, body.bag());
                }
                res.add("result", JsonParser.parseString(new Gson().toJson(done)));
                res.addProperty("ok", Boolean.TRUE.equals(done.get("ok")));
            }
            default -> {
                res.addProperty("ok", false);
                res.addProperty("error", "unknown action: " + what);
            }
        }
    }

    private static ItemStack stackOf(JsonObject a) {
        if (!a.has("item")) {
            return ItemStack.EMPTY;
        }
        Item item = BuiltInRegistries.ITEM.get(ResourceLocation.parse(a.get("item").getAsString()));
        int n = a.has("count") ? a.get("count").getAsInt() : 1;
        return new ItemStack(item, n);
    }

    private static String blockId(BlockState st) {
        return BuiltInRegistries.BLOCK.getKey(st.getBlock()).toString();
    }

    private static void finish(MinecraftServer server) {
        // The errand is over: come back. A body that stays where the last
        // action happened would drift across the base one job at a time and
        // never be where you are, which is the opposite of having one.
        //
        // But NOT while she is still walking there. A batch finishes in a tick
        // or two and a walk takes seconds, so clearing unconditionally here
        // cancelled the journey before she arrived - every short walk, the ones
        // under the teleport threshold that actually go on foot, was recalled
        // mid-transit and never completed. She now releases the post herself the
        // moment she arrives, and gives up on her own after 90 seconds if she
        // cannot, so nothing is left holding a stale posting either way.
        ghost.body.Body body = ghost.body.Bodies.find(server);
        if (body != null && !body.stationed() && !body.travelling()) {
            body.clearPost();
        }

        JsonObject out = new JsonObject();
        out.addProperty("finishedAt", java.time.OffsetDateTime.now().toString());
        out.addProperty("batch", batches);
        if (currentId != null) {
            out.addProperty("id", currentId);
        }
        out.add("results", RESULTS.deepCopy());
        write(out);
        while (RESULTS.size() > 0) {
            RESULTS.remove(0);
        }
    }

    private static void writeError(String msg) {
        JsonObject out = new JsonObject();
        out.addProperty("error", msg);
        out.addProperty("finishedAt", java.time.OffsetDateTime.now().toString());
        if (currentId != null) {
            out.addProperty("id", currentId);
        }
        write(out);
    }

    /**
     * Publish one answer three ways.
     *
     * <p>{@code outbox.json} stays the newest result, because that is what
     * everything already reads. {@code out/&lt;id&gt;.json} is the addressed
     * copy - the one a caller can poll for its OWN answer without racing
     * anyone. {@code outbox.jsonl} is the append-only history, so an answer
     * that arrived while nobody was looking is still there afterwards.
     */
    private static void write(JsonObject out) {
        try (Writer w = Files.newBufferedWriter(outbox(), StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
            GSON.toJson(out, w);
        } catch (IOException e) {
            Ghost.LOG.error("could not write outbox", e);
        }
        if (currentId != null) {
            try {
                Files.createDirectories(outDir());
                Path mine = outDir().resolve(currentId + ".json");
                try (Writer w = Files.newBufferedWriter(mine, StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
                    GSON.toJson(out, w);
                }
            } catch (Exception e) {
                Ghost.LOG.error("could not write addressed result for {}", currentId, e);
            }
        }
        try {
            // One line, no pretty printing - this file is read by tail, and a
            // multi-line record would break a reader that assumes one per line.
            Files.writeString(outLog(),
                    new com.google.gson.Gson().toJson(out) + System.lineSeparator(),
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            Ghost.LOG.error("could not append outbox log", e);
        }
        currentId = null;
    }
}
