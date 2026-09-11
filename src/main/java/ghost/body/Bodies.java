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
     * Every live body in a <b>loaded chunk</b>, in any dimension.
     *
     * <h2>Read that qualifier carefully</h2>
     *
     * <p>This walks {@code server.getAllLevels()}, so it is not limited to the
     * asker's own dimension - callers used to look only there, and reported "no
     * body" the moment they were a portal away. But {@code level.getEntities}
     * sees <b>loaded chunks only</b>, and measured 2026-09-10 by polling
     * {@code where} across a dimension change, a body stops being visible
     * <b>within 2.6 seconds</b> of the player leaving it behind.
     *
     * <p>So an empty result means <i>"none loaded right now"</i> and never
     * <i>"none exist"</i>. Three separate bugs came from reading it the second
     * way in a single day: {@code where} announcing "no live body in any
     * dimension" about one we had been looking at a minute earlier,
     * {@code body here} deciding the player had none and standing up a
     * duplicate, and a claim that one-per-player was enforced by construction
     * when it was enforced only among the loaded.
     *
     * <p>{@link Roster} is the answer when the question is "does one exist?".
     * This is only ever the answer to "which can I touch right now?".
     *
     * <p>There should be exactly one. There can be more: {@code /ghost body
     * here} only clears bodies in the level it was run in, so one left behind in
     * another dimension survives - and three different call sites used to look
     * bodies up three different ways, one of which did not even filter for being
     * alive. Two verbs could therefore be talking about two different entities
     * while both reported confidently.
     *
     * <p>Reported by {@code where} so that "they are at X" can be checked against
     * "and there is only one of them".
     */
    public static java.util.List<Body> all(net.minecraft.server.MinecraftServer server) {
        java.util.List<Body> out = new java.util.ArrayList<>();
        if (server == null) {
            return out;
        }
        for (net.minecraft.server.level.ServerLevel level : server.getAllLevels()) {
            for (Body body : level.getEntities(
                    net.minecraft.world.level.entity.EntityTypeTest.forClass(Body.class),
                    b -> b.isAlive())) {
                out.add(body);
            }
        }
        return out;
    }

    /**
     * The first live body in a loaded chunk, in level-iteration order.
     *
     * <p><b>Ownership-blind and order-dependent.</b> Overworld comes first, so
     * with a body in each of two dimensions this answers with the Overworld one
     * regardless of who asked or where they are standing. Prefer
     * {@link #owned} - {@code Bridge.body(server, a)} wraps it - and keep this
     * for the bootstrap case where the owner is not yet known.
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

    /**
     * The bodies belonging to one player - <b>one Shelby each</b>.
     *
     * <p>Ownership is the {@code Follow} UUID, which the body already persists.
     * A body with no owner is treated as <i>adoptable</i> rather than
     * everyone's: every body that existed before this rule was written is
     * ownerless, and refusing to claim them would have stranded them and their
     * armour permanently.
     *
     * <p>Deliberately reads {@link Body#followedId()} and not
     * {@link Body#followed()}. The latter falls back to the nearest player and
     * then to <i>anyone on the server</i>, so a body whose owner is offline
     * would appear to belong to whoever happened to be standing closest - which
     * is precisely the confusion this is meant to end.
     */
    public static java.util.List<Body> owned(net.minecraft.server.MinecraftServer server,
                                             java.util.UUID owner, boolean includeUnowned) {
        java.util.List<Body> out = new java.util.ArrayList<>();
        for (Body b : all(server)) {
            java.util.UUID id = b.followedId();
            if (id == null ? includeUnowned : id.equals(owner)) {
                out.add(b);
            }
        }
        return out;
    }

    /** Bodies belonging to somebody else. Never to be discarded on their behalf. */
    public static java.util.List<Body> notOwned(net.minecraft.server.MinecraftServer server,
                                                java.util.UUID owner) {
        java.util.List<Body> out = new java.util.ArrayList<>();
        for (Body b : all(server)) {
            java.util.UUID id = b.followedId();
            if (id != null && !id.equals(owner)) {
                out.add(b);
            }
        }
        return out;
    }
}
