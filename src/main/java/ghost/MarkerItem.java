package ghost;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.entity.player.Player;

/**
 * Point at a thing and give it a name.
 *
 * <p>Every coordinate in this bridge has, until now, been typed by hand or read
 * off F3 and relayed. That is the single biggest source of wasted time in
 * driving it: an agent cannot see, so it either guesses a position or asks, and
 * a mistyped digit looks exactly like a bug in whatever verb used it. An evening
 * was lost to "is she on the elevator or one block off".
 *
 * <p>So: name this in an anvil, right-click a block, and that block becomes a
 * named place the agent can use by name forever. {@code {"do":"goto","at":"elevator"}}
 * rather than three integers nobody can verify.
 *
 * <p>Writes straight into {@link Places}, which every verb already resolves
 * through - so a place marked here works everywhere without a single verb
 * changing.
 *
 * <ul>
 *   <li><b>right-click</b> a block - that is the place's position</li>
 *   <li><b>sneak</b> right-click twice - the two opposite corners of a box,
 *       for verbs that take a region ({@code scan}, {@code blockmap},
 *       {@code fill}, {@code clear})</li>
 * </ul>
 */
public class MarkerItem extends Item {

    public MarkerItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResult useOn(UseOnContext ctx) {
        Player player = ctx.getPlayer();
        if (ctx.getLevel().isClientSide || player == null) {
            return InteractionResult.SUCCESS;
        }
        ItemStack stack = ctx.getItemInHand();

        // The item's own name IS the place name. An anvil is a naming tool
        // everybody already owns, and it means no GUI and no chat prompt.
        if (!stack.has(DataComponents.CUSTOM_NAME)) {
            say(player, ChatFormatting.YELLOW,
                    "Name this in an anvil first - whatever you call it becomes "
                    + "the name of the place. \"garden\", \"elevator\", \"drives\".");
            return InteractionResult.SUCCESS;
        }
        String name = stack.getHoverName().getString().trim();
        if (name.isEmpty()) {
            say(player, ChatFormatting.YELLOW, "That name is blank.");
            return InteractionResult.SUCCESS;
        }

        BlockPos at = ctx.getClickedPos();
        String dim = ctx.getLevel().dimension().location().toString();

        Places.Place place = Places.get(name);
        boolean isNew = place == null;
        if (isNew) {
            place = new Places.Place();
            place.pos = new int[]{at.getX(), at.getY(), at.getZ()};
            place.dim = dim;
        }

        if (player.isShiftKeyDown()) {
            // Two sneak-clicks make a box. Starting over is implicit: once a box
            // is complete the next sneak-click begins a new one, so there is no
            // "clear" gesture to remember and no way to end up with a stale
            // half-box you cannot see.
            if (place.from == null || place.to != null) {
                place.from = new int[]{at.getX(), at.getY(), at.getZ()};
                place.to = null;
                place.dim = dim;
                Places.remember(name, place);
                say(player, ChatFormatting.AQUA, "\"" + name + "\" corner 1 at "
                        + at.getX() + " " + at.getY() + " " + at.getZ()
                        + " - sneak-click the opposite corner.");
            } else {
                place.to = new int[]{at.getX(), at.getY(), at.getZ()};
                Places.remember(name, place);
                long w = Math.abs(place.from[0] - place.to[0]) + 1L;
                long h = Math.abs(place.from[1] - place.to[1]) + 1L;
                long d = Math.abs(place.from[2] - place.to[2]) + 1L;
                say(player, ChatFormatting.GREEN, "\"" + name + "\" box set: "
                        + w + " x " + h + " x " + d + " (" + (w * h * d) + " blocks).");
            }
        } else {
            place.pos = new int[]{at.getX(), at.getY(), at.getZ()};
            place.dim = dim;
            Places.remember(name, place);
            say(player, ChatFormatting.GREEN, (isNew ? "Marked \"" : "Moved \"")
                    + name + "\" to " + at.getX() + " " + at.getY() + " " + at.getZ()
                    + (place.hasBox() ? " (box kept)" : ""));
        }
        return InteractionResult.SUCCESS;
    }

    private static void say(Player player, ChatFormatting colour, String text) {
        player.displayClientMessage(Component.literal("[Shelby] ")
                .withStyle(ChatFormatting.AQUA)
                .append(Component.literal(text).withStyle(colour)), false);
    }
}
