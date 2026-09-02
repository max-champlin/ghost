package ghost;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Reports the live world to a file.
 *
 * <p>Exists because reading the save only ever shows what has been flushed to
 * disk - chunks sit in server memory until an autosave, so a survey of the save
 * can be an hour behind what the player is looking at.
 *
 * <p>Strictly read-only. It never sets a block.
 */
@Mod(Ghost.ID)
public class Ghost {

    public static final String ID = "ghost";
    public static final Logger LOG = LogManager.getLogger("Ghost");

    public Ghost(IEventBus modBus) {
        ghost.body.Bodies.register(modBus);
        NeoForge.EVENT_BUS.addListener(this::onRegisterCommands);
        NeoForge.EVENT_BUS.addListener(this::onServerTick);
        NeoForge.EVENT_BUS.addListener(this::onChat);
        NeoForge.EVENT_BUS.addListener(
                (net.neoforged.neoforge.event.server.ServerStartedEvent e)
                        -> Session.started(e.getServer()));
        NeoForge.EVENT_BUS.addListener(
                (net.neoforged.neoforge.event.server.ServerStoppingEvent e)
                        -> Session.stopping(e.getServer()));
    }

    /**
     * Captures chat without altering it. The message is never cancelled - a mod
     * that silently swallowed what you typed would be far more annoying than
     * one that simply listens.
     */
    private void onChat(net.neoforged.neoforge.event.ServerChatEvent event) {
        try {
            Chat.onChat(event.getPlayer(), event.getRawText());
        } catch (Exception e) {
            LOG.error("chat capture failed", e);
        }
    }

    private void onRegisterCommands(RegisterCommandsEvent event) {
        GhostCommand.register(event.getDispatcher());
    }

    private void onServerTick(ServerTickEvent.Post event) {
        Watch.tick(event.getServer());
        Bridge.tick(event.getServer());
        // AE2 plans crafts on a background thread; this is where a finished
        // plan gets submitted and the result said out loud.
        Storage.tickCrafting(event.getServer());
    }
}
