package ghost;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * How much of Shelby a given player is allowed to use.
 *
 * <p>On a single-player world this is nearly moot - the one player owns
 * everything and can already type any command. On a server it is the difference
 * between a helpful assistant and a hole in the wall: the bridge can break
 * blocks, place them, run commands and now spend the contents of an ME network,
 * and anyone able to type in chat can ask it to. Rank has to gate what the ask
 * is allowed to become.
 *
 * <p>Deliberately built on vanilla operator levels rather than on a permissions
 * mod. Every server has them, they need no dependency, and an admin who wants
 * finer control already has a tool for it. FTB Ranks is installed on this
 * instance and could be layered on later, but making the safety floor depend on
 * an optional mod would mean the floor disappears when that mod does.
 *
 * <p>The single-player host is always allowed everything. Otherwise a world
 * with cheats off would lock Max out of his own assistant, because the host is
 * operator level 0 there - the trap this class exists to not fall into.
 */
public final class Perms {

    private Perms() {
    }

    /** What an action costs, coarsely. Ordered least to most dangerous. */
    public enum Ability {
        /** Reading the world: scan, find, read, where, entities, counts. */
        LOOK(0),
        /** Going somewhere and talking: goto, say. Cosmetic, essentially. */
        MOVE(0),
        /** Spending network resources: AE2 crafting jobs. */
        CRAFT(2),
        /** Changing the world: break, place, use. */
        WORLD(2),
        /** Arbitrary server commands. This is the console; treat it as such. */
        COMMAND(4);

        private final int level;

        Ability(int level) {
            this.level = level;
        }

        public int level() {
            return level;
        }
    }

    /** True when this player may have Shelby do that on their behalf. */
    public static boolean allows(ServerPlayer player, Ability ability) {
        if (player == null) {
            return false;
        }
        if (host(player)) {
            return true;
        }
        return player.hasPermissions(ability.level());
    }

    /**
     * The owner of a single-player world, who is not necessarily an operator.
     * With cheats off the host sits at permission level 0 despite owning the
     * save outright.
     */
    private static boolean host(ServerPlayer player) {
        MinecraftServer server = player.getServer();
        return server != null
                && !server.isDedicatedServer()
                && server.isSingleplayerOwner(player.getGameProfile());
    }

    /**
     * A short word for what this player may ask for, recorded alongside every
     * request so the agent on the other side can see the ceiling it is working
     * under rather than discovering it by being refused.
     */
    public static String rank(ServerPlayer player) {
        if (player == null) {
            return "unknown";
        }
        if (host(player)) {
            return "host";
        }
        if (player.hasPermissions(Ability.COMMAND.level())) {
            return "admin";
        }
        if (player.hasPermissions(Ability.WORLD.level())) {
            return "op";
        }
        return "player";
    }

    /** Why a refusal happened, in words meant for chat rather than a log. */
    public static String refusal(Ability ability) {
        return switch (ability) {
            case CRAFT -> "I can look things up for you, but committing the network "
                    + "to a craft is above your clearance.";
            case WORLD -> "Altering the world on your behalf is above your clearance.";
            case COMMAND -> "Server commands require administrator clearance, I am afraid.";
            default -> "That is above your clearance.";
        };
    }
}
