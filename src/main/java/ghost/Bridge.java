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

    /** How close counts as "there". */
    private static final double ARRIVED_WITHIN = 3.5;

    /** Longest she may spend travelling before the action happens anyway. */
    private static final int TRAVEL_TIMEOUT = 600;

    private static final Deque<JsonObject> QUEUE = new ArrayDeque<>();
    private static final JsonArray RESULTS = new JsonArray();
    private static int waitTicks = 0;
    private static int batches = 0;

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

    public static void tick(MinecraftServer server) {
        if (!armed) {
            return;
        }
        if (waitTicks > 0) {
            waitTicks--;
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
            JsonObject res = new JsonObject();
            res.addProperty("action", act.has("do") ? act.get("do").getAsString() : "?");
            res.addProperty("travelled", true);
            res.addProperty("arrived", there);
            try {
                run(server, act, res);
            } catch (Exception e) {
                res.addProperty("ok", false);
                res.addProperty("error", String.valueOf(e));
                Ghost.LOG.error("bridge action failed after travel: {}", act, e);
            }
            RESULTS.add(res);
            if (QUEUE.isEmpty()) {
                finish(server);
            }
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
            if (QUEUE.isEmpty()) {
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
        try {
            run(server, act, res);
        } catch (Exception e) {
            res.addProperty("ok", false);
            res.addProperty("error", String.valueOf(e));
            Ghost.LOG.error("bridge action failed: {}", act, e);
        }
        RESULTS.add(res);
        if (QUEUE.isEmpty()) {
            finish(server);
        }
    }

    private static void pickUpInbox() {
        Path in = inbox();
        if (!Files.exists(in)) {
            return;
        }
        try {
            // Consume before running: a batch must never be able to replay
            // itself on the next load because the file was still sitting there.
            Path taken = Sampler.dir().resolve("inbox.running.json");
            Files.deleteIfExists(taken);
            Files.move(in, taken);
            try (Reader r = Files.newBufferedReader(taken, StandardCharsets.UTF_8)) {
                JsonElement root = JsonParser.parseReader(r);
                JsonArray arr = root.isJsonArray()
                        ? root.getAsJsonArray()
                        : root.getAsJsonObject().getAsJsonArray("actions");
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

    private static ServerLevel level(MinecraftServer server, JsonObject a) {
        if (a.has("dim")) {
            for (ServerLevel l : server.getAllLevels()) {
                if (l.dimension().location().toString().equals(a.get("dim").getAsString())) {
                    return l;
                }
            }
        }
        ServerPlayer p = server.getPlayerList().getPlayers().stream().findFirst().orElse(null);
        return p != null ? p.serverLevel() : server.overworld();
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
        ghost.body.Body body = level.getEntitiesOfClass(ghost.body.Body.class,
                        new net.minecraft.world.phys.AABB(BlockPos.ZERO).inflate(3.0E7))
                .stream().findFirst().orElse(null);
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

        // "go": true - send her there first, and run this when she arrives.
        if (a.has("go") && a.get("go").getAsBoolean()
                && a.has("at") && !a.has("__arrived")) {
            ghost.body.Body body = ghost.body.Bodies.find(server);
            if (body != null) {
                BlockPos site = pos(a, "at");
                body.postTo(site);
                pendingGo = a;
                goDeadline = level.getGameTime() + TRAVEL_TIMEOUT;
                res.addProperty("ok", true);
                res.addProperty("travelling", true);
                res.addProperty("to", site.getX() + " " + site.getY() + " " + site.getZ());
                return;
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
                if (st.is(DENY)) {
                    res.addProperty("ok", false);
                    res.addProperty("refused", "block is in buildinggadgets2:deny");
                    res.addProperty("block", blockId(st));
                    break;
                }
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
                        String was = blockId(level.getBlockState(p));
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
                var out = stack.useOn(new UseOnContext(level, fake,
                        InteractionHand.MAIN_HAND, stack, hit));
                res.addProperty("ok", out.consumesAction());
                res.addProperty("result", out.toString());
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
                // Walk the body somewhere. Pathing, not teleporting: she should
                // arrive by crossing the ground the player crosses, so "go and
                // look" means something. Teleport is available with "warp" for
                // when a wall or a drop makes that impossible.
                BlockPos p = pos(a, "at");
                ghost.body.Body body = level.getEntitiesOfClass(ghost.body.Body.class,
                                new net.minecraft.world.phys.AABB(BlockPos.ZERO).inflate(3.0E7))
                        .stream().findFirst().orElse(null);
                if (body == null) {
                    res.addProperty("ok", false);
                    res.addProperty("error", "no body - /ghost body first");
                } else if (a.has("warp") && a.get("warp").getAsBoolean()) {
                    body.teleportTo(p.getX() + 0.5, p.getY(), p.getZ() + 0.5);
                    res.addProperty("ok", true);
                    res.addProperty("warped", true);
                } else {
                    boolean started = body.getNavigation()
                            .moveTo(p.getX() + 0.5, p.getY(), p.getZ() + 0.5, 1.0);
                    res.addProperty("ok", started);
                    res.addProperty("distance",
                            Math.round(Math.sqrt(body.distanceToSqr(
                                    p.getX() + 0.5, p.getY(), p.getZ() + 0.5))));
                    if (!started) {
                        res.addProperty("error", "no path from here");
                    }
                }
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
                // Where is she standing right now - so a report can say so.
                ghost.body.Body body = level.getEntitiesOfClass(ghost.body.Body.class,
                                new net.minecraft.world.phys.AABB(BlockPos.ZERO).inflate(3.0E7))
                        .stream().findFirst().orElse(null);
                res.addProperty("ok", body != null);
                if (body != null) {
                    BlockPos bp = body.blockPosition();
                    res.add("pos", JsonParser.parseString(
                            "[" + bp.getX() + "," + bp.getY() + "," + bp.getZ() + "]"));
                    res.addProperty("navDone", body.getNavigation().isDone());
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
        ghost.body.Body body = ghost.body.Bodies.find(server);
        if (body != null && !body.stationed()) {
            body.clearPost();
        }

        JsonObject out = new JsonObject();
        out.addProperty("finishedAt", java.time.OffsetDateTime.now().toString());
        out.addProperty("batch", batches);
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
        write(out);
    }

    private static void write(JsonObject out) {
        try (Writer w = Files.newBufferedWriter(outbox(), StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
            GSON.toJson(out, w);
        } catch (IOException e) {
            Ghost.LOG.error("could not write outbox", e);
        }
    }
}
