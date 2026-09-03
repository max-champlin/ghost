package ghost.body;

import ghost.Ghost;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.MobRenderer;
import net.minecraft.client.renderer.entity.layers.HumanoidArmorLayer;
import net.minecraft.client.renderer.entity.layers.ItemInHandLayer;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;

/**
 * The body's one piece of client code.
 *
 * <p>Everything else in Ghost is server side by design, so this is fenced into
 * {@link Dist#CLIENT} and its own class: a dedicated server never loads it, and
 * the mod keeps working headless exactly as it always has.
 */
@EventBusSubscriber(modid = Ghost.ID, value = Dist.CLIENT, bus = EventBusSubscriber.Bus.MOD)
public final class BodyClient {

    private BodyClient() {
    }

    @SubscribeEvent
    public static void onRegisterRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerEntityRenderer(Bodies.SHELBY.get(), BodyRenderer::new);
    }

    /**
     * Draws Shelby as a translucent player.
     *
     * <p>The vanilla player model rather than a bespoke one, because the point
     * is that she reads as a person standing there - a custom shape would just
     * be another mob. The see-through comes from two things together: alpha
     * baked into the skin, and a translucent render type. Either alone gives a
     * solid figure.
     */
    public static class BodyRenderer extends MobRenderer<Body, PlayerModel<Body>> {

        private static final ResourceLocation SKIN =
                ResourceLocation.fromNamespaceAndPath(Ghost.ID, "textures/entity/shelby.png");

        public BodyRenderer(EntityRendererProvider.Context ctx) {
            super(ctx, new PlayerModel<>(ctx.bakeLayer(ModelLayers.PLAYER), false), 0.0F);
            // Armour draws solid rather than translucent like the rest of her.
            // The point of putting clothes on is seeing them, and see-through
            // gear over a see-through body would read as nothing at all.
            addLayer(new HumanoidArmorLayer<>(this,
                    new HumanoidModel<>(ctx.bakeLayer(ModelLayers.PLAYER_INNER_ARMOR)),
                    new HumanoidModel<>(ctx.bakeLayer(ModelLayers.PLAYER_OUTER_ARMOR)),
                    ctx.getModelManager()));
            addLayer(new ItemInHandLayer<>(this, ctx.getItemInHandRenderer()));
        }

        @Override
        public ResourceLocation getTextureLocation(Body entity) {
            return SKIN;
        }

        @Override
        protected RenderType getRenderType(Body entity, boolean bodyVisible,
                                           boolean translucent, boolean glowing) {
            // Always translucent, never the solid cutout the super would pick.
            return RenderType.entityTranslucent(getTextureLocation(entity));
        }

        /**
         * Make the crouch actually show.
         *
         * <p>Setting {@code Pose.CROUCHING} server-side makes
         * {@code isCrouching()} true, and that is where it stops:
         * {@code HumanoidModel.crouching} is the field that bends the model, and
         * <b>nothing in the vanilla renderer ever assigns it from the entity</b>.
         * {@code LivingEntityRenderer} sets {@code model.riding} for passengers
         * and the only other assignment in the client is one model copying
         * another. A player crouches because the player renderer sets the flag
         * itself; a {@link net.minecraft.client.renderer.entity.MobRenderer}
         * wearing a {@link PlayerModel} has nobody to do that for it.
         *
         * <p>So the pose was correct all along and the body simply stood there.
         */
        @Override
        public void render(Body entity, float entityYaw, float partialTicks,
                           com.mojang.blaze3d.vertex.PoseStack poseStack,
                           net.minecraft.client.renderer.MultiBufferSource buffer,
                           int packedLight) {
            getModel().crouching = entity.isCrouching();
            super.render(entity, entityYaw, partialTicks, poseStack, buffer, packedLight);
        }

        @Override
        protected boolean shouldShowName(Body entity) {
            return entity.hasCustomName();
        }
    }
}
