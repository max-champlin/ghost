package ghost;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Making an Elevator ID block do for the body what it does for a player.
 *
 * <p>The mod cannot help here, and it is worth being precise about why.
 * {@code ElevatorHandler} reads {@code jumping} and {@code shiftKeyDown} off
 * {@link net.minecraft.client.player.Input} on a {@code LocalPlayer}, works out
 * a destination, and sends the server a {@code TeleportPacket}. Every part of
 * the trigger and the search lives on the client, and the body has no client.
 * The server half of that packet does take a plain {@code Player} and does a
 * normal teleport - but nothing server-side ever decides *where*.
 *
 * <p>So the search is reimplemented here rather than called. Be honest about
 * what that means: these are the mod's rules as its config states them, not its
 * code. If the mod changes how it picks a floor, this will not follow.
 *
 * <p>Matched by registry namespace rather than by class, so Elevator ID stays a
 * soft dependency - without it installed nothing here ever matches and the jump
 * is just a jump.
 */
final class Elevators {

    private Elevators() {
    }

    private static final String NAMESPACE = "elevatorid:";

    /**
     * How far to look for the next floor.
     *
     * <p>The mod's own {@code range} default, and the value in this instance's
     * config. Not read from the config file at runtime: this is a courtesy
     * feature, and reading someone else's config to be 4% more correct is not
     * worth a hard coupling to their file format.
     */
    private static final int RANGE = 384;

    /** The search distance, so a caller can say how far it looked. */
    static int range() {
        return RANGE;
    }

    static boolean isElevator(BlockState state) {
        return BuiltInRegistries.BLOCK.getKey(state.getBlock())
                .toString().startsWith(NAMESPACE);
    }

    /**
     * The floor a jump (or a crouch) from here would reach, or null.
     *
     * @param from the elevator being stood on
     * @param up   true for jump, false for crouch
     */
    static BlockPos destination(ServerLevel level, BlockPos from, boolean up) {
        if (!isElevator(level.getBlockState(from))) {
            return null;
        }
        int step = up ? 1 : -1;
        BlockPos.MutableBlockPos cursor = from.mutable();
        for (int i = 1; i <= RANGE; i++) {
            int y = from.getY() + i * step;
            if (level.isOutsideBuildHeight(y)) {
                break;
            }
            cursor.setY(y);
            if (isElevator(level.getBlockState(cursor))) {
                // sameColor is false in this pack, so any colour answers. If it
                // were true this is where the dye comparison would go.
                return cursor.immutable();
            }
        }
        return null;
    }

    /**
     * Where the body should end up, standing on that floor.
     *
     * <p>Returns null when there is no room to stand, rather than posting them
     * into a ceiling - the mod does its own safety check and so should this.
     */
    static BlockPos standingSpot(ServerLevel level, BlockPos elevator) {
        BlockPos feet = elevator.above();
        if (!level.getBlockState(feet).isAir()
                || !level.getBlockState(feet.above()).isAir()) {
            return null;
        }
        return feet;
    }
}
