package ghost.body;

import ghost.Ghost;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.entity.EntityAttributeCreationEvent;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * Registration for the body.
 *
 * <p>Kept apart from the rest of Ghost, which is command-and-file plumbing with
 * no registry objects at all. If the body is ever cut, this package goes with
 * it and nothing else notices.
 */
public final class Bodies {

    private Bodies() {
    }

    public static final DeferredRegister<EntityType<?>> ENTITIES =
            DeferredRegister.create(Registries.ENTITY_TYPE, Ghost.ID);

    public static final DeferredHolder<EntityType<?>, EntityType<Body>> SHELBY =
            ENTITIES.register("shelby", () -> EntityType.Builder
                    .of(Body::new, MobCategory.MISC)
                    // Player-ish. The renderer uses the vanilla player model, so
                    // anything else would leave the nameplate and the hitbox
                    // disagreeing with what is drawn.
                    .sized(0.6F, 1.8F)
                    .eyeHeight(1.62F)
                    .clientTrackingRange(10)
                    .fireImmune()
                    .build(ResourceLocation.fromNamespaceAndPath(Ghost.ID, "shelby").toString()));

    public static void register(IEventBus modBus) {
        ENTITIES.register(modBus);
        modBus.addListener((EntityAttributeCreationEvent e) ->
                e.put(SHELBY.get(), Body.attributes().build()));
    }

    /**
     * The body, wherever it is.
     *
     * <p>Searches every dimension, not just the one the asker is standing in.
     * Callers used to look only in the player's own level, which reported "no
     * body" the moment she was a portal away - and then said so in chat, which
     * is worse than saying nothing.
     */
    public static Body find(net.minecraft.server.MinecraftServer server) {
        if (server == null) {
            return null;
        }
        for (net.minecraft.server.level.ServerLevel level : server.getAllLevels()) {
            for (Body body : level.getEntities(
                    net.minecraft.world.level.entity.EntityTypeTest.forClass(Body.class),
                    b -> b.isAlive())) {
                return body;
            }
        }
        return null;
    }
}
