package ghost;

import net.minecraft.core.registries.Registries;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * The mod's items. There is one.
 *
 * <p>Ghost is otherwise all plumbing and no content, and it should stay that
 * way - but pointing at a block beats typing three integers at an agent that
 * cannot see, so the marker earns its place.
 */
public final class GhostItems {

    private GhostItems() {
    }

    public static final DeferredRegister.Items ITEMS =
            DeferredRegister.createItems(Ghost.ID);

    public static final DeferredHolder<Item, Item> MARKER =
            ITEMS.registerItem("marker", MarkerItem::new,
                    new Item.Properties().stacksTo(1));

    public static void register(IEventBus modBus) {
        ITEMS.register(modBus);
        // Tools, because that is where a player looks for something they point
        // at blocks with.
        modBus.addListener((BuildCreativeModeTabContentsEvent e) -> {
            if (e.getTabKey() == CreativeModeTabs.TOOLS_AND_UTILITIES) {
                e.accept(MARKER.get());
            }
        });
    }
}
