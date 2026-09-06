package ghost;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * Notice when nobody is listening, and say so.
 *
 * <p>The mod ships no model. Something outside it - an agent session, a script,
 * a local model - watches {@code asks.jsonl} and writes back. When that watcher
 * dies, it dies quietly: the player asks a question, gets Shelby's cheerful
 * acknowledgement, and then nothing. From where he is standing that is
 * indistinguishable from being ignored, and there is no way for him to tell
 * "still thinking" from "nobody home".
 *
 * <p>{@code OPERATING.md} has warned about this since the beginning - "Shelby
 * has cheerfully said 'very good' in chat while Max's questions sit unanswered"
 * - and it happened again today: a background monitor stopped with no
 * notification and a real request went unread for minutes.
 *
 * <p>So the mod watches the one thing it genuinely knows: how long an ask has
 * been sitting unanswered. Past a threshold it says so, once, and stops. It
 * cannot diagnose why, and does not pretend to - it reports the fact and lets
 * the player decide whether to go and prod something.
 */
final class Deadman {

    private Deadman() {
    }

    /**
     * How long an ask may sit before Shelby admits nobody has picked it up.
     *
     * <p>Long enough that a slow model or a long batch is not accused of being
     * dead - a real answer can take a while - and short enough that the player
     * is not left wondering for the rest of the evening.
     */
    private static final long QUIET_MS = 90_000L;

    /** Only once per silence, so it is a warning and not a nag. */
    private static boolean told;

    /** Checked once a second; there is nothing here worth doing per tick. */
    private static int cooldown;

    static void tick(MinecraftServer server) {
        if (--cooldown > 0) {
            return;
        }
        cooldown = 20;

        long oldest = Chat.oldestAskAt();
        if (oldest == 0L) {
            told = false;          // nothing waiting: re-arm for next time
            return;
        }
        if (told) {
            return;
        }
        long waited = System.currentTimeMillis() - oldest;
        if (waited < QUIET_MS) {
            return;
        }
        told = true;

        int n = Chat.pending();
        String how = !Bridge.armed()
                ? "my bridge is closed, so nothing can reach me at all - "
                  + "/ghost bridge on"
                : "my bridge is open but nothing has come back for "
                  + (waited / 1000) + " seconds. Whatever does my thinking may "
                  + "have stopped.";
        String msg = (n == 1 ? "I still have your question. " : "I still have "
                + n + " questions. ") + how;

        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            p.sendSystemMessage(Component.literal("[Shelby] ")
                    .withStyle(ChatFormatting.AQUA)
                    .append(Component.literal(msg).withStyle(ChatFormatting.YELLOW)));
        }
        Ghost.LOG.warn("{} ask(s) unanswered for {}s - the driving side may be down",
                n, waited / 1000);
    }
}
