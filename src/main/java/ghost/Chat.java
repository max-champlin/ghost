package ghost;

import com.google.gson.JsonObject;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Locale;

/**
 * The in-game half of a conversation.
 *
 * <p>Everything said in chat is appended to {@code ghost/chat.jsonl} so it
 * can be read later; anything addressed to Shelby is additionally recorded in
 * {@code ghost/asks.jsonl} and answered **immediately** with status.
 *
 * <p>The instant reply is deliberately honest about what it is. Shelby does not
 * run alongside the game - it only exists while answering a message in the
 * terminal - so a question asked here cannot be answered here in the moment.
 * What the game can do is confirm the message was captured, say how many are
 * waiting, and report the things the mod genuinely knows: whether the bridge is
 * armed, whether a watch is running, how many samples exist. Pretending
 * otherwise would produce a assistant that appears to be listening and is not.
 */
public final class Chat {

    private Chat() {
    }

    /** Anything starting with these is treated as addressed to Shelby. */
    private static final String[] TRIGGERS = {
            "hey shelby", "shelby,", "shelby ", "@shelby", "hi shelby", "ok shelby",
            // Kept so a player who knows what is behind the curtain can still
            // get its attention.
            "hey claude", "@claude",
    };

    private static int pending = 0;

    public static int pending() {
        return pending;
    }

    public static void clearPending() {
        pending = 0;
    }

    private static void append(String file, JsonObject o) {
        try {
            Path p = Sampler.dir().resolve(file);
            Files.writeString(p, o + System.lineSeparator(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (Exception e) {
            Ghost.LOG.error("could not append {}", file, e);
        }
    }

    /**
     * @return true when the message was addressed to Shelby
     */
    public static boolean onChat(ServerPlayer player, String text) {
        JsonObject o = new JsonObject();
        o.addProperty("time", java.time.OffsetDateTime.now().toString());
        o.addProperty("player", player.getGameProfile().getName());
        o.addProperty("text", text);
        o.addProperty("dim", player.serverLevel().dimension().location().toString());
        o.addProperty("pos", player.blockPosition().getX() + " "
                + player.blockPosition().getY() + " " + player.blockPosition().getZ());
        // Recorded on every line so the agent reading these knows what this
        // person is allowed to ask for before it starts planning something it
        // will only be refused at the last step.
        o.addProperty("rank", Perms.rank(player));
        append("chat.jsonl", o);

        String low = text.toLowerCase(Locale.ROOT).trim();
        boolean addressed = false;
        for (String t : TRIGGERS) {
            if (low.startsWith(t)) {
                addressed = true;
                break;
            }
        }
        if (!addressed) {
            return false;
        }
        pending++;
        append("asks.jsonl", o);
        reply(player, ack(player, text));
        return true;
    }

    /**
     * The holding reply, which has to earn its place.
     *
     * <p>It used to say "saved for the next time we talk" and then recite the
     * watch status - which told the player nothing about whether the thing they
     * just asked for could actually happen. The two facts that matter are
     * whether the question was understood, and whether anything is able to go
     * and answer it. So it echoes the ask back, and says plainly when the bridge
     * is off, because a disarmed bridge means the answer is not coming.
     */
    private static String ack(ServerPlayer player, String text) {
        String asked = strip(text);
        if (asked.length() > 60) {
            asked = asked.substring(0, 57) + "...";
        }
        StringBuilder sb = new StringBuilder();
        if (!Bridge.armed()) {
            sb.append("heard you - \"").append(asked).append("\"");
            sb.append("  |  but my bridge is OFF, so I cannot go and look. /ghost bridge on");
            return sb.toString();
        }
        sb.append("on it - \"").append(asked).append("\"");
        sb.append("  |  ").append(approach(player));
        if (pending > 1) {
            sb.append("  |  ").append(pending).append(" queued");
        }
        return sb.toString();
    }

    /**
     * What the body is actually about to do, said accurately.
     *
     * <p>This line used to be a flat "walking over" whenever a body existed
     * anywhere, which was a promise it frequently could not keep - it could not
     * cross dimensions at all, and gave up past 64 blocks. Now that it can
     * always get there, the wording follows how: walking is only claimed when
     * walking is what will happen.
     *
     * <p>Also binds her to whoever just spoke, so she keeps up with the person
     * having the conversation rather than the nearest body heat.
     */
    private static String approach(ServerPlayer player) {
        ghost.body.Body body = ghost.body.Bodies.find(player.getServer());
        if (body == null) {
            return "no body, looking from here";
        }
        body.setFollowed(player.getUUID());
        if (body.level() != player.level()) {
            return "coming through from "
                    + body.level().dimension().location().getPath();
        }
        double away = Math.sqrt(body.distanceToSqr(player));
        if (away < 6.0) {
            return "right here";
        }
        return away <= 24.0
                ? "walking over"
                : "stepping across - " + Math.round(away) + " blocks out";
    }

    /** Drop the "Hey Shelby," lead-in so the echo is the actual request. */
    private static String strip(String text) {
        String s = text.trim();
        for (String t : TRIGGERS) {
            if (s.toLowerCase(Locale.ROOT).startsWith(t)) {
                s = s.substring(t.length());
                break;
            }
        }
        return s.replaceFirst("^[,\\s]+", "").trim();
    }

    /** Print a line to one player, tagged so it is never mistaken for a real player. */
    public static void reply(ServerPlayer player, String text) {
        player.sendSystemMessage(Component.literal("[Shelby] ")
                .withStyle(ChatFormatting.AQUA)
                .append(Component.literal(text).withStyle(ChatFormatting.GRAY)));
    }

    /** Print to everyone - used by the bridge "say" action for real answers. */
    public static void broadcast(net.minecraft.server.MinecraftServer server, String text) {
        Component msg = Component.literal("[Shelby] ")
                .withStyle(ChatFormatting.AQUA)
                .append(Component.literal(text).withStyle(ChatFormatting.WHITE));
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            p.sendSystemMessage(msg);
        }
    }
}
