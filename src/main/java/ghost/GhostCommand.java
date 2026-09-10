package ghost;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;

import java.nio.file.Path;
import java.util.Map;

/**
 * {@code /ghost ...}
 *
 * <p>Permission level 0 on purpose. This only reads, and requiring op would
 * mean it silently does nothing in a single-player world with cheats off -
 * which is exactly the world it is most useful in.
 */
public final class GhostCommand {

    private GhostCommand() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> d) {
        LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal("ghost")
                .requires(s -> s.hasPermission(0));

        root.then(Commands.literal("here")
                .then(Commands.argument("radius", IntegerArgumentType.integer(1, 128))
                        .then(Commands.argument("height", IntegerArgumentType.integer(1, 64))
                                .executes(ctx -> here(ctx.getSource(),
                                        IntegerArgumentType.getInteger(ctx, "radius"),
                                        IntegerArgumentType.getInteger(ctx, "height"))))
                        .executes(ctx -> here(ctx.getSource(),
                                IntegerArgumentType.getInteger(ctx, "radius"), 4))));

        root.then(Commands.literal("box")
                .then(Commands.argument("from", BlockPosArgument.blockPos())
                        .then(Commands.argument("to", BlockPosArgument.blockPos())
                                .executes(ctx -> box(ctx.getSource(),
                                        BlockPosArgument.getLoadedBlockPos(ctx, "from"),
                                        BlockPosArgument.getLoadedBlockPos(ctx, "to"))))));

        root.then(Commands.literal("watch")
                .then(Commands.argument("radius", IntegerArgumentType.integer(1, 128))
                        .then(Commands.argument("minutes", IntegerArgumentType.integer(1, 240))
                                .executes(ctx -> watch(ctx.getSource(),
                                        IntegerArgumentType.getInteger(ctx, "radius"),
                                        IntegerArgumentType.getInteger(ctx, "minutes"))))));

        root.then(Commands.literal("produce")
                .then(Commands.literal("off").executes(ctx -> {
                    Throughput.stop();
                    ctx.getSource().sendSuccess(() -> Component.literal(
                            "Shelby: stopped watching production"), false);
                    return 1;
                }))
                .then(Commands.argument("item",
                        com.mojang.brigadier.arguments.StringArgumentType.string())
                        .executes(ctx -> produce(ctx.getSource(),
                                com.mojang.brigadier.arguments.StringArgumentType
                                        .getString(ctx, "item"), 16, 10, 45))
                        .then(Commands.argument("radius", IntegerArgumentType.integer(1, 64))
                                .then(Commands.argument("everyMinutes", IntegerArgumentType.integer(1, 240))
                                        .then(Commands.argument("quietMinutes", IntegerArgumentType.integer(1, 1440))
                                                .executes(ctx -> produce(ctx.getSource(),
                                                        com.mojang.brigadier.arguments.StringArgumentType
                                                                .getString(ctx, "item"),
                                                        IntegerArgumentType.getInteger(ctx, "radius"),
                                                        IntegerArgumentType.getInteger(ctx, "everyMinutes"),
                                                        IntegerArgumentType.getInteger(ctx, "quietMinutes"))))))));

        root.then(Commands.literal("have")
                .then(Commands.argument("item", com.mojang.brigadier.arguments.StringArgumentType.string())
                        .executes(ctx -> have(ctx.getSource(),
                                com.mojang.brigadier.arguments.StringArgumentType.getString(ctx, "item"), 16))
                        .then(Commands.argument("radius", IntegerArgumentType.integer(1, 48))
                                .executes(ctx -> have(ctx.getSource(),
                                        com.mojang.brigadier.arguments.StringArgumentType.getString(ctx, "item"),
                                        IntegerArgumentType.getInteger(ctx, "radius"))))));

        root.then(Commands.literal("can")
                .then(Commands.argument("item", com.mojang.brigadier.arguments.StringArgumentType.string())
                        .executes(ctx -> can(ctx.getSource(),
                                com.mojang.brigadier.arguments.StringArgumentType.getString(ctx, "item"), 16))
                        .then(Commands.argument("radius", IntegerArgumentType.integer(1, 48))
                                .executes(ctx -> can(ctx.getSource(),
                                        com.mojang.brigadier.arguments.StringArgumentType.getString(ctx, "item"),
                                        IntegerArgumentType.getInteger(ctx, "radius"))))));

        root.then(Commands.literal("light")
                .then(Commands.argument("level", IntegerArgumentType.integer(1, 15))
                        .executes(ctx -> lights(ctx.getSource(),
                                IntegerArgumentType.getInteger(ctx, "level"), 0))
                        .then(Commands.argument("radius", IntegerArgumentType.integer(0, 48))
                                .executes(ctx -> lights(ctx.getSource(),
                                        IntegerArgumentType.getInteger(ctx, "level"),
                                        IntegerArgumentType.getInteger(ctx, "radius"))))));

        root.then(Commands.literal("entities")
                .then(Commands.argument("radius", IntegerArgumentType.integer(1, 128))
                        .executes(ctx -> entities(ctx.getSource(),
                                IntegerArgumentType.getInteger(ctx, "radius"), null))
                        .then(Commands.argument("type", com.mojang.brigadier.arguments.StringArgumentType.string())
                                .executes(ctx -> entities(ctx.getSource(),
                                        IntegerArgumentType.getInteger(ctx, "radius"),
                                        com.mojang.brigadier.arguments.StringArgumentType.getString(ctx, "type"))))));

        root.then(Commands.literal("find")
                .then(Commands.argument("block", com.mojang.brigadier.arguments.StringArgumentType.string())
                        .executes(ctx -> find(ctx.getSource(),
                                com.mojang.brigadier.arguments.StringArgumentType.getString(ctx, "block"), 32))
                        .then(Commands.argument("radius", IntegerArgumentType.integer(1, 64))
                                .executes(ctx -> find(ctx.getSource(),
                                        com.mojang.brigadier.arguments.StringArgumentType.getString(ctx, "block"),
                                        IntegerArgumentType.getInteger(ctx, "radius"))))));

        root.then(Commands.literal("homes")
                .then(Commands.argument("radius", IntegerArgumentType.integer(1, 128))
                        .executes(ctx -> homes(ctx.getSource(),
                                IntegerArgumentType.getInteger(ctx, "radius"), null))
                        .then(Commands.argument("type", com.mojang.brigadier.arguments.StringArgumentType.string())
                                .executes(ctx -> homes(ctx.getSource(),
                                        IntegerArgumentType.getInteger(ctx, "radius"),
                                        com.mojang.brigadier.arguments.StringArgumentType.getString(ctx, "type"))))));

        root.then(Commands.literal("stop").executes(ctx -> {
            Watch.stop();
            ctx.getSource().sendSuccess(() -> Component.literal("Shelby: watch stopped."), false);
            return 1;
        }));

        // /ghost body [here|away] - stand Shelby up, or send her away again.
        root.then(Commands.literal("body")
                .executes(ctx -> body(ctx.getSource(), "here"))
                .then(Commands.literal("here").executes(ctx -> body(ctx.getSource(), "here")))
                .then(Commands.literal("away").executes(ctx -> body(ctx.getSource(), "away"))));

        root.then(Commands.literal("status").executes(ctx -> {
            ctx.getSource().sendSuccess(() -> Component.literal(
                    "Shelby: " + Watch.status() + " | " + Throughput.status()
                    + " | bridge " + Bridge.status()), false);
            return 1;
        }));

        // Arming is a separate, deliberate act. The bridge can break blocks, so
        // it starts disarmed on every server start and never arms itself.
        root.then(Commands.literal("bridge")
                .then(Commands.literal("on").executes(ctx -> {
                    Bridge.arm(true);
                    ctx.getSource().sendSuccess(() -> Component.literal(
                            "Shelby: bridge open. I am reading ghost/inbox.json."), true);
                    return 1;
                }))
                .then(Commands.literal("off").executes(ctx -> {
                    Bridge.arm(false);
                    ctx.getSource().sendSuccess(() -> Component.literal(
                            "Shelby: bridge closed, queue cleared."), true);
                    return 1;
                })));

        // The mod is Ghost; the thing you talk to is Shelby. Both work, because
        // typing /ghost and being answered by "Shelby:" is a small, constant
        // irritation - and nobody should have to remember which name the
        // command uses.
        var node = d.register(root);
        d.register(Commands.literal("shelby")
                .requires(s -> s.hasPermission(0))
                .redirect(node));
    }

    @SuppressWarnings("unchecked")
    private static int entities(CommandSourceStack src, int radius, String type) {
        BlockPos p = BlockPos.containing(src.getPosition());
        var data = Entities.survey(src.getLevel(), p, radius, false, type);
        int total = (Integer) data.getOrDefault("total", 0);
        src.sendSuccess(() -> Component.literal("Shelby: " + total
                + " entities within " + radius), false);
        var counts = (Map<String, Integer>) data.get("counts");
        counts.entrySet().stream()
                .sorted((a, b) -> Integer.compare(b.getValue(), a.getValue()))
                .limit(12)
                .forEach(e -> src.sendSuccess(() -> Component.literal(
                        "   " + e.getValue() + "x " + e.getKey()), false));
        if (data.containsKey("onFloor")) {
            var floor = (Map<String, Integer>) data.get("onFloor");
            src.sendSuccess(() -> Component.literal("   on the floor: "
                    + data.get("itemEntities") + " item entities"), false);
            floor.entrySet().stream()
                    .sorted((a, b) -> Integer.compare(b.getValue(), a.getValue()))
                    .limit(5)
                    .forEach(e -> src.sendSuccess(() -> Component.literal(
                            "      " + e.getValue() + "x " + e.getKey()), false));
        }
        return 1;
    }

    @SuppressWarnings("unchecked")
    private static int find(CommandSourceStack src, String match, int radius) {
        BlockPos p = BlockPos.containing(src.getPosition());
        var data = Finder.findBlocks(src.getLevel(), p, match, radius);
        int total = (Integer) data.getOrDefault("total", 0);
        if (total == 0) {
            src.sendSuccess(() -> Component.literal(
                    "Shelby: no '" + match + "' within " + radius), false);
            return 1;
        }
        var near = (java.util.List<Integer>) data.get("nearest");
        src.sendSuccess(() -> Component.literal("Shelby: " + total + "x '" + match
                + "', nearest at " + near.get(0) + " " + near.get(1) + " " + near.get(2)), false);
        ((Map<String, Integer>) data.get("byId")).entrySet().stream()
                .sorted((a, b) -> Integer.compare(b.getValue(), a.getValue()))
                .limit(8)
                .forEach(e -> src.sendSuccess(() -> Component.literal(
                        "   " + e.getValue() + "x " + e.getKey()), false));
        return 1;
    }

    @SuppressWarnings("unchecked")
    private static int homes(CommandSourceStack src, int radius, String type) {
        BlockPos p = BlockPos.containing(src.getPosition());
        var d = Entities.homes(src.getLevel(), p, radius, type == null ? "golem" : type);
        int scanned = (Integer) d.get("scanned");
        int homeless = (Integer) d.get("homeless");
        src.sendSuccess(() -> Component.literal("Shelby: " + scanned + " found across "
                + d.get("homes") + " homes"
                + (homeless > 0 ? ", " + homeless + " with no home" : "")), false);
        var occ = (Map<String, Integer>) d.get("occupancy");
        var res = (Map<String, java.util.List<String>>) d.get("residents");
        occ.entrySet().stream()
                .sorted((a, b) -> Integer.compare(b.getValue(), a.getValue()))
                .limit(10)
                .forEach(e -> {
                    var names = res.get(e.getKey());
                    String head = String.join(", ", names.subList(0, Math.min(4, names.size())));
                    final String who = names.size() > 4
                            ? head + " +" + (names.size() - 4) : head;
                    src.sendSuccess(() -> Component.literal(
                            "   " + e.getValue() + " at " + e.getKey() + "  (" + who + ")"), false);
                });
        return 1;
    }

    private static int lights(CommandSourceStack src, int level, int radius) {
        var found = Query.lights(src.getLevel(), src.getPlayer(), level, radius);
        long owned = found.stream().filter(l -> l.owned() > 0).count();
        src.sendSuccess(() -> Component.literal("Shelby: " + found.size()
                + " blocks emit light " + level + "+"
                + (radius > 0 ? "  (" + owned + " within " + radius + " blocks)" : "")), false);
        // Chat is narrow and six hundred mods is a lot of lamps. Show what is
        // owned plus a few of the rest; the full list goes to a file.
        int shown = 0;
        for (Query.Lamp l : found) {
            if (shown >= 12) {
                break;
            }
            if (radius > 0 && l.owned() == 0 && shown >= owned + 4) {
                break;
            }
            String mark = l.owned() > 0 ? "  x" + l.owned() + " HAVE" : "";
            src.sendSuccess(() -> Component.literal(
                    "   [" + l.light() + "] " + l.id() + mark), false);
            shown++;
        }
        if (found.size() > shown) {
            int rest = found.size() - shown;
            src.sendSuccess(() -> Component.literal("   ...and " + rest
                    + " more - full list in ghost/lights.txt"), false);
        }
        try {
            StringBuilder sb = new StringBuilder();
            for (Query.Lamp l : found) {
                sb.append(l.light()).append('\t').append(l.id());
                if (l.owned() > 0) {
                    sb.append('\t').append(l.owned()).append(" owned");
                }
                sb.append(System.lineSeparator());
            }
            java.nio.file.Files.writeString(Sampler.dir().resolve("lights.txt"), sb.toString());
        } catch (Exception e) {
            Ghost.LOG.error("could not write lights.txt", e);
        }
        return 1;
    }

    private static int have(CommandSourceStack src, String id, int radius) {
        var item = Finder.item(id);
        if (item == net.minecraft.world.item.Items.AIR) {
            src.sendFailure(Component.literal("Shelby: I know of no such item - " + id));
            return 0;
        }
        if (src.getPlayer() == null) {
            // Run from the console or through the bridge, there is nobody to
            // count around. Say so instead of counting from the world origin
            // and reporting a confident zero.
            src.sendFailure(Component.literal(
                    "Shelby: run that as a player, or use the bridge's \"have\" "
                            + "action, which takes a position."));
            return 0;
        }
        Finder.Found f = Finder.count(src.getLevel(), src.getPlayer(), item, radius);
        if (f.grandTotal() == 0) {
            src.sendSuccess(() -> Component.literal(
                    "Shelby: no " + id + " within " + radius + " blocks"), false);
            return 1;
        }
        src.sendSuccess(() -> Component.literal("Shelby: " + f.grandTotal() + "x " + id), false);
        if (f.inNetwork() > 0) {
            src.sendSuccess(() -> Component.literal("   " + f.inNetwork() + " in ME network"), false);
        }
        if (f.inPlayer() > 0) {
            src.sendSuccess(() -> Component.literal("   " + f.inPlayer() + " on you"), false);
        }
        for (Finder.Hit h : f.hits().subList(0, Math.min(6, f.hits().size()))) {
            src.sendSuccess(() -> Component.literal("   " + h.count() + " in " + h.container()
                    + " at " + h.pos().getX() + " " + h.pos().getY() + " " + h.pos().getZ()), false);
        }
        return 1;
    }

    private static int can(CommandSourceStack src, String id, int radius) {
        var item = Finder.item(id);
        if (item == net.minecraft.world.item.Items.AIR) {
            src.sendFailure(Component.literal("Shelby: I know of no such item - " + id));
            return 0;
        }
        var needs = Finder.canCraft(src.getLevel(), src.getPlayer(), item, radius);
        if (needs.isEmpty()) {
            src.sendSuccess(() -> Component.literal(
                    "Shelby: I can find no crafting recipe for " + id), false);
            return 1;
        }
        boolean all = needs.stream().allMatch(Finder.Need::satisfied);
        src.sendSuccess(() -> Component.literal(all
                ? "Shelby: yes, you have everything for " + id
                : "Shelby: not yet. " + id + " is missing:"), false);
        for (Finder.Need n : needs) {
            if (!all && n.satisfied()) {
                continue;                    // only list what is short
            }
            src.sendSuccess(() -> Component.literal("   " + n.ingredient()
                    + "  need " + n.required() + ", have " + n.have()), false);
        }
        return 1;
    }

    private static int here(CommandSourceStack src, int radius, int height) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        BlockPos p = BlockPos.containing(src.getPosition());
        return box(src, p.offset(-radius, -height, -radius), p.offset(radius, height, radius));
    }

    private static int box(CommandSourceStack src, BlockPos a, BlockPos b) {
        ServerLevel level = src.getLevel();
        long t0 = System.currentTimeMillis();
        try {
            Map<String, Object> data = Sampler.scan(level, a, b, true);
            if (data.containsKey("error")) {
                src.sendFailure(Component.literal("Shelby: " + data.get("error")));
                return 0;
            }
            Path out = Sampler.writeScan(data, level.getGameTime());
            long ms = System.currentTimeMillis() - t0;
            int crops = (Integer) data.getOrDefault("totalCrops", 0);
            src.sendSuccess(() -> Component.literal(
                    "Shelby: " + crops + " crops, " + data.get("blocksScanned")
                            + " blocks in " + ms + "ms -> ghost/" + out.getFileName()), false);
            return 1;
        } catch (Exception e) {
            Ghost.LOG.error("scan failed", e);
            src.sendFailure(Component.literal("Shelby: the scan failed - " + e));
            return 0;
        }
    }

    /**
     * Stand the body up next to the caller, or take it away.
     *
     * <p>Exactly one at a time, on purpose. Two Shelbys following you around
     * would be a bug that looks like a feature until one of them is standing in
     * a wall - so "here" removes any that already exist before placing a new
     * one, which also makes it the way to recall a body that has got itself
     * stuck somewhere.
     */
    private static int body(CommandSourceStack src, String mode) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        net.minecraft.server.level.ServerLevel level = src.getLevel();
        // EVERY dimension, not just this one.
        //
        // Clearing only the current level meant a body left behind in another
        // dimension survived "body here" and quietly became a second Shelby.
        // The verbs act on the first one found, which is not necessarily the one
        // you are standing next to - so "where" reports a confident position for
        // a body you cannot see, and every instruction goes to the wrong one.
        // This is therefore also the recovery command for exactly that mess.
        int removed = 0;
        // Keep what she was wearing and carrying.
        //
        // "body here" discarded every existing body and stood up a blank one,
        // which DESTROYED her armour and satchel - discard is a silent removal,
        // so setGuaranteedDrop never fires and nothing lands on the floor. A
        // command called "here" reads as "come here", not "delete her and build
        // a replacement", and losing a suit of someone's armour to that is not
        // a defensible default. Reported the hard way: a full Spider-Man set,
        // twice in one evening.
        // ONE SHELBY PER PLAYER, and never touch anyone else's.
        //
        // This used to discard every body in every dimension and carry the kit
        // from whichever one it happened to find first. With a single body that
        // is merely blunt; with two it is destructive, because discard is a
        // silent removal - the second body's armour and satchel are deleted
        // with nothing dropped and nothing said. On a server it would mean one
        // player's "body here" quietly stripping another player's Shelby.
        //
        // So: only bodies this player owns are candidates, exactly one of them
        // is replaced, and anything else is left standing and reported.
        net.minecraft.server.level.ServerPlayer owner = src.getPlayer();
        java.util.UUID ownerId = owner == null ? null : owner.getUUID();
        // A body with no owner is adoptable - every body predating this rule
        // has none, and refusing them would strand them and their gear.
        java.util.List<ghost.body.Body> mine =
                ghost.body.Bodies.owned(src.getServer(), ownerId, true);
        int othersLeft = ghost.body.Bodies.notOwned(src.getServer(), ownerId).size();

        net.minecraft.nbt.CompoundTag kit = null;
        for (ghost.body.Body b : mine) {
            if (kit == null) {
                kit = new net.minecraft.nbt.CompoundTag();
                b.saveWithoutId(kit);
                b.discard();
                removed++;
            } else {
                // A second body of your own: leave it alone rather than delete
                // it. "where" reports the count so it can be dealt with on
                // purpose, in the dimension it is actually standing in.
                break;
            }
        }
        if ("away".equals(mode)) {
            kit = null;              // away means gone; nothing to carry across
        }
        final int strays = Math.max(0, mine.size() - removed);
        final int others = othersLeft;
        if ("away".equals(mode)) {
            final int n = removed;
            src.sendSuccess(() -> Component.literal(n > 0
                    ? "Shelby: body away (" + n + ")" + tail(strays, others)
                    : "Shelby: there is no body of yours to send away."
                      + tail(strays, others)), false);
            return 1;
        }
        ghost.body.Body b = ghost.body.Bodies.SHELBY.get().create(level);
        if (b == null) {
            src.sendFailure(Component.literal("Shelby: I could not create a body."));
            return 0;
        }
        net.minecraft.world.phys.Vec3 p = src.getPosition();
        b.moveTo(p.x, p.y, p.z, src.getRotation().y, 0.0F);
        if (!level.addFreshEntity(b)) {
            src.sendFailure(Component.literal("Shelby: I could not place a body here."));
            return 0;
        }
        // Claim it. This is what makes "one Shelby per player" true rather than
        // aspirational: from here on she is only ever a candidate for this
        // player's own "body here", and never for anyone else's.
        if (ownerId != null) {
            b.setFollowed(ownerId);
        }
        // Move the kit across. Position, posting and dimension deliberately are
        // NOT carried - the whole point of the command is to put her somewhere
        // else - so only the things she owns come with her.
        final int moved = carryOver(kit, b);
        src.sendSuccess(() -> Component.literal((moved > 0
                ? "Shelby: standing up (brought " + moved + " item" + (moved == 1 ? "" : "s") + ")"
                : "Shelby: standing up") + tail(strays, others)), false);
        return 1;
    }

    /**
     * Carry equipment and satchel from the old body to the new one.
     *
     * @return how many stacks came across, for the confirmation message
     */
    /**
     * What was deliberately NOT touched.
     *
     * <p>Silence here is how a second body becomes a mystery: the verbs act on
     * one of them, "where" reports a confident position for a body you cannot
     * see, and nothing ever said there was more than one.
     */
    private static String tail(int strays, int others) {
        StringBuilder sb = new StringBuilder();
        if (strays > 0) {
            sb.append(" - ").append(strays).append(" more of yours still standing elsewhere");
        }
        if (others > 0) {
            sb.append(strays > 0 ? "," : " -").append(' ')
              .append(others).append(" belonging to someone else, left alone");
        }
        return sb.toString();
    }

    private static int carryOver(net.minecraft.nbt.CompoundTag kit, ghost.body.Body fresh) {
        if (kit == null) {
            return 0;
        }
        int moved = 0;
        try {
            // Equipment lives in the vanilla Mob lists; the satchel is ours.
            for (String key : new String[]{"ArmorItems", "HandItems", "Bag"}) {
                if (kit.contains(key, 9)) {
                    moved += kit.getList(key, 10).size();
                }
            }
            net.minecraft.nbt.CompoundTag keep = new net.minecraft.nbt.CompoundTag();
            for (String key : new String[]{"ArmorItems", "HandItems", "ArmorDropChances",
                    "HandDropChances", "Bag", "CustomName"}) {
                if (kit.contains(key)) {
                    keep.put(key, kit.get(key).copy());
                }
            }
            // readAdditionalSaveData ONLY.
            //
            // Entity.load() was here too, and it reads Pos, Motion and Rotation
            // from the tag it is given. This tag deliberately carries none of
            // those - the point of the command is to put her somewhere NEW - so
            // load() set her position to 0, 0, 0 and flung her to the world
            // origin. She appeared beside the player and vanished in the same
            // breath. It was also redundant: load() calls
            // readAdditionalSaveData itself, so the kit was being read twice and
            // the position destroyed for nothing.
            fresh.readAdditionalSaveData(keep);
        } catch (Exception e) {
            ghost.Ghost.LOG.error("could not carry her kit to the new body", e);
            return 0;
        }
        return moved;
    }

    /**
     * Watch a number that only goes up while the base is working.
     *
     * <p>Anchored on the player's position, because the ME network you mean is
     * the one you are standing in - and asking for coordinates would be one
     * more thing to get wrong at the moment you are trying to leave.
     */
    private static int produce(CommandSourceStack src, String item, int radius,
                               int everyMinutes, int quietMinutes)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        if (!Storage.ae2Loaded()) {
            src.sendFailure(Component.literal(
                    "Shelby: no AE2 here, so there is no network total to watch."));
            return 0;
        }
        ItemLookup.Result found = ItemLookup.resolve(item);
        if (!found.ok()) {
            // Naming the near misses matters more here than anywhere: a typo
            // would otherwise arm a monitor that watches nothing and stays
            // silent, which reads exactly like everything being fine.
            String detail = found.candidates.isEmpty() ? ""
                    : " Did you mean: " + String.join(", ", found.candidates) + "?";
            src.sendFailure(Component.literal("Shelby: " + found.error + detail));
            return 0;
        }
        BlockPos p = BlockPos.containing(src.getPosition());
        // Refuse to watch a number that is already zero.
        //
        // A monitor armed on an item the network never holds cannot tell
        // "production stopped" from "this was never the right item" - it just
        // waits out the quiet window and cries wolf. Nearly happened here:
        // inferium essence reads 0 in this base at all times because the system
        // converts it upward on arrival, so watching it would have alerted
        // within the hour on a perfectly healthy garden. Watch the thing that
        // accumulates.
        long now = Storage.inNetworks(src.getLevel(), p, radius, found.item);
        if (now == 0) {
            src.sendFailure(Component.literal(
                    "Shelby: there is no " + found.id + " in reach of "
                    + p.getX() + " " + p.getY() + " " + p.getZ() + " (radius "
                    + radius + "), so a flatline there would mean nothing. If it "
                    + "is consumed as fast as it arrives, watch what it becomes "
                    + "instead - that is the number that only goes up."));
            return 0;
        }
        Throughput.start(src.getLevel(), p, radius, found.item, everyMinutes, quietMinutes);
        src.sendSuccess(() -> Component.literal(
                "Shelby: " + Throughput.status() + " -> ghost/alerts.jsonl"), false);
        return 1;
    }

    private static int watch(CommandSourceStack src, int radius, int minutes) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        BlockPos p = BlockPos.containing(src.getPosition());
        Watch.start(src.getLevel(), p.offset(-radius, -4, -radius), p.offset(radius, 4, radius), minutes);
        src.sendSuccess(() -> Component.literal(
                "Shelby: " + Watch.status() + " -> ghost/series.jsonl"), false);
        return 1;
    }
}
