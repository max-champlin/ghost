package ghost.body;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.item.ItemStack;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal;
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal;
import net.minecraft.world.entity.ai.goal.WaterAvoidingRandomStrollGoal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.pathfinder.PathType;
import net.minecraft.world.level.pathfinder.WalkNodeEvaluator;
import net.minecraft.world.level.portal.DimensionTransition;
import net.minecraft.world.phys.Vec3;

import java.util.EnumSet;
import java.util.UUID;

/**
 * Shelby, standing up.
 *
 * <p>The bridge already gives an outside program eyes and hands in this world;
 * what it never had was a PLACE to be. Answers arrived from nowhere and actions
 * happened at coordinates. This is the body those belong to - something the
 * player can walk up to, talk at, and watch follow them around.
 *
 * <p>Deliberately harmless. It cannot be hurt, cannot be pushed, holds nothing,
 * attacks nothing and never despawns. It is a presence, not a mob: anything it
 * actually DOES still comes through the bridge, on the server thread, under the
 * same caps and the same disarm-on-start rule as before.
 *
 * <h2>Keeping up</h2>
 *
 * <p>The first version could only follow within 32 blocks and gave up entirely
 * past 64, with no idea dimensions existed. Walk off far enough - or step through
 * a portal - and the body was orphaned where it stood, strolling in circles
 * forever while chat cheerfully promised it was "walking over". That promise is
 * the whole point of having a body, so arrival is now guaranteed rather than
 * attempted:
 *
 * <ul>
 *   <li>same place, close by - it walks, because that is what makes the body
 *       mean anything</li>
 *   <li>too far to be worth watching, or no path exists - it steps across</li>
 *   <li>a different dimension entirely - it follows through</li>
 * </ul>
 */
public class Body extends PathfinderMob {

    /** How close it settles when following, and how far before it bothers. */
    private static final double FOLLOW_STOP = 3.0;
    private static final double FOLLOW_START = 6.0;

    /**
     * Pathfinding headroom, not a leash. Generous so that walking is tried
     * properly before giving up on it - the teleport is the fallback, and a
     * fallback that fires early would make the body feel like it cheats.
     */
    private static final double FOLLOW_RANGE = 48.0;

    /** Beyond this it stops trying to walk and simply steps across. */
    private static final double TELEPORT_AT = 24.0;

    /** Ticks between the little puffs that mean "still working on it". */
    private static final int WORKING_PARTICLE_INTERVAL = 8;

    /** How often the keep-up check runs. Once a second is plenty. */
    private static final int KEEP_UP_INTERVAL = 20;

    /**
     * Consecutive keep-up checks with no usable path before it gives up on
     * walking. Three seconds of standing still is unmistakable, and short enough
     * that a wall between her and the player is not a minute of confusion.
     */
    private static final int UNPATHABLE_LIMIT = 3;

    /** Who to keep up with; survives dimension changes and reloads. */
    private UUID followId;

    private int keepUpCooldown;
    private int unpathableTicks;

    /**
     * A job site she has been sent to, or null when she is simply with you.
     *
     * <p>While posted she stops following. An assistant that trots after you
     * while supposedly away doing something is not away doing something, and
     * the point of sending her is that the work happens somewhere you are not.
     */
    private BlockPos post;

    public Body(EntityType<? extends PathfinderMob> type, Level level) {
        super(type, level);
        setPersistenceRequired();
        setCustomNameVisible(true);
        if (!hasCustomName()) {
            setCustomName(Component.literal("Shelby"));
        }
        // Anything she is wearing comes back if she is ever removed. She can
        // only be got rid of with /kill, and losing a set of someone's good
        // armour to a housekeeping command would be a nasty surprise.
        for (EquipmentSlot slot : EquipmentSlot.values()) {
            setGuaranteedDrop(slot);
        }
    }

    // --- being sent somewhere ---------------------------------------------

    /** Send her to a job site. She will stay there until released. */
    public void postTo(BlockPos site) {
        this.post = site;
        this.unpathableTicks = 0;
    }

    /** Release her; she goes back to keeping up with whoever she follows. */
    public void clearPost() {
        this.post = null;
    }

    public boolean posted() {
        return post != null;
    }

    /** Close enough to the job to be considered there. */
    public boolean arrived(double within) {
        return post != null
                && distanceToSqr(post.getX() + 0.5, post.getY(), post.getZ() + 0.5)
                        <= within * within;
    }

    // --- getting dressed --------------------------------------------------

    /**
     * Put clothes on her, or take them off.
     *
     * <p>Three gestures, chosen so nothing can be handed over by accident:
     *
     * <ul>
     *   <li>right-click holding <b>armour</b> - she wears it, and hands back
     *       whatever was in that slot</li>
     *   <li>right-click with an <b>empty hand</b> - she takes one thing off</li>
     *   <li><b>sneak</b> right-click holding anything else - she holds it</li>
     * </ul>
     *
     * <p>Non-armour deliberately does nothing on a plain right-click. Equipping
     * whatever happens to be in hand would mean walking up to say hello and
     * silently giving away your pickaxe.
     */
    @Override
    protected InteractionResult mobInteract(Player player, InteractionHand hand) {
        if (level().isClientSide) {
            return InteractionResult.SUCCESS;
        }
        ItemStack held = player.getItemInHand(hand);

        if (held.isEmpty()) {
            return player.isShiftKeyDown() ? InteractionResult.PASS : undress(player);
        }

        EquipmentSlot slot = getEquipmentSlotForItem(held);
        boolean armour = slot.getType() == EquipmentSlot.Type.HUMANOID_ARMOR;
        if (!armour) {
            if (!player.isShiftKeyDown()) {
                return InteractionResult.PASS;   // no accidental hand-offs
            }
            slot = EquipmentSlot.MAINHAND;
        }

        ItemStack worn = getItemBySlot(slot);
        setItemSlot(slot, held.copyWithCount(1));
        held.shrink(1);
        giveBack(player, worn);
        return InteractionResult.SUCCESS;
    }

    /** Take off one item, from the top down, and hand it over. */
    private InteractionResult undress(Player player) {
        for (EquipmentSlot slot : new EquipmentSlot[]{
                EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS,
                EquipmentSlot.FEET, EquipmentSlot.MAINHAND, EquipmentSlot.OFFHAND}) {
            ItemStack worn = getItemBySlot(slot);
            if (!worn.isEmpty()) {
                setItemSlot(slot, ItemStack.EMPTY);
                giveBack(player, worn);
                return InteractionResult.SUCCESS;
            }
        }
        return InteractionResult.PASS;
    }

    /** Into the player's inventory, or at their feet if it is full. */
    private void giveBack(Player player, ItemStack stack) {
        if (stack.isEmpty()) {
            return;
        }
        if (!player.getInventory().add(stack)) {
            player.drop(stack, false);
        }
    }

    public static AttributeSupplier.Builder attributes() {
        return Mob.createMobAttributes()
                .add(Attributes.MAX_HEALTH, 20.0)
                .add(Attributes.MOVEMENT_SPEED, 0.32)
                .add(Attributes.FOLLOW_RANGE, FOLLOW_RANGE)
                .add(Attributes.STEP_HEIGHT, 1.0);
    }

    @Override
    protected void registerGoals() {
        goalSelector.addGoal(0, new FloatGoal(this));
        goalSelector.addGoal(1, new FollowPlayerGoal(this));
        // She wanders only while the bridge is armed. With it off she cannot
        // act on anything, and drifting around as though she might is the
        // wrong impression to give.
        goalSelector.addGoal(2, new WaterAvoidingRandomStrollGoal(this, 0.7) {
            @Override
            public boolean canUse() {
                return ghost.Bridge.armed() && super.canUse();
            }
        });
        goalSelector.addGoal(3, new LookAtPlayerGoal(this, Player.class, 10.0F));
        goalSelector.addGoal(4, new RandomLookAroundGoal(this));
    }

    // --- what she looks like she is doing ---------------------------------

    /**
     * Posture as an honest status light.
     *
     * <p>Ghost's whole design refuses to look busier than it is, and this is the
     * same rule applied to a body. Rather than inventing idle animation for its
     * own sake, what she does with herself reports the actual state of the
     * system:
     *
     * <ul>
     *   <li><b>settled</b> - the bridge is disarmed, nothing can reach her, and
     *       she is sitting it out. Visible from across the room without typing
     *       a command</li>
     *   <li><b>upright</b> - armed and available</li>
     *   <li><b>working</b> - actions in flight, or AE2 still thinking about a
     *       craft. Shown with a few particles, because "waiting on a background
     *       thread" has no natural pose</li>
     * </ul>
     *
     * <p>She never crouches while moving: a crouch-walk reads as sneaking
     * rather than resting, which would say the wrong thing entirely.
     */
    private void showState() {
        boolean busy = ghost.Bridge.busy() || ghost.Storage.craftPending();
        // Settled means genuinely idle: nothing in flight, nowhere being walked
        // to, and no posting to stand at. It used to mean only "the bridge is
        // disarmed", which was a status light nobody asked for - with the bridge
        // open she never sat down at all, which is every normal session.
        boolean settled = !busy && post == null && getNavigation().isDone();
        Pose wanted = settled ? Pose.CROUCHING : Pose.STANDING;
        if (getPose() != wanted) {
            setPose(wanted);
        }

        if (busy && tickCount % WORKING_PARTICLE_INTERVAL == 0
                && level() instanceof ServerLevel server) {
            server.sendParticles(ParticleTypes.ENCHANT,
                    getX(), getY() + 1.9, getZ(), 4, 0.25, 0.15, 0.25, 0.0);
        }
    }

    // --- who we are following --------------------------------------------

    /** Bind to a particular player - used when someone addresses her in chat. */
    public void setFollowed(UUID id) {
        this.followId = id;
    }

    /**
     * The player to keep up with, looked up across every dimension.
     *
     * <p>Falls back to the nearest player here, then to anyone at all on the
     * server, so a body that loses its bound player still attaches to someone
     * rather than becoming scenery.
     */
    public Player followed() {
        MinecraftServer server = getServer();
        if (server == null) {
            return null;
        }
        if (followId != null) {
            ServerPlayer bound = server.getPlayerList().getPlayer(followId);
            if (bound != null && bound.isAlive() && !bound.isSpectator()) {
                return bound;
            }
        }
        // -1 means no distance limit, unlike the old 32-block search.
        Player near = level().getNearestPlayer(this, -1.0);
        if (near != null && near.isAlive() && !near.isSpectator()) {
            return near;
        }
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            if (p.isAlive() && !p.isSpectator()) {
                return p;
            }
        }
        return null;
    }

    @Override
    public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        if (followId != null) {
            tag.putUUID("Follow", followId);
        }
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        followId = tag.hasUUID("Follow") ? tag.getUUID("Follow") : null;
    }

    // --- keeping up -------------------------------------------------------

    @Override
    protected void customServerAiStep() {
        super.customServerAiStep();
        showState();
        if (--keepUpCooldown > 0) {
            return;
        }
        keepUpCooldown = KEEP_UP_INTERVAL;

        // A posting outranks the player. She is at work.
        if (post != null) {
            travelToPost();
            return;
        }

        Player p = followed();
        if (p == null) {
            unpathableTicks = 0;
            return;
        }

        // Through the portal. The goals cannot express this at all - they only
        // ever see one level - so it has to happen out here.
        if (p.level() != level()) {
            if (p.level() instanceof ServerLevel dest) {
                followThrough(dest, p.position());
            }
            unpathableTicks = 0;
            return;
        }

        double d2 = distanceToSqr(p);
        if (d2 < FOLLOW_START * FOLLOW_START) {
            unpathableTicks = 0;
            return;
        }
        if (d2 > TELEPORT_AT * TELEPORT_AT) {
            stepAcrossTo(p.blockPosition());
            unpathableTicks = 0;
            return;
        }
        // Close enough that walking is the right answer - but only if walking
        // is actually happening. An idle navigator here means no path exists:
        // a wall, a drop, a locked door.
        if (getNavigation().isDone()) {
            if (++unpathableTicks >= UNPATHABLE_LIMIT) {
                stepAcrossTo(p.blockPosition());
                unpathableTicks = 0;
            }
        } else {
            unpathableTicks = 0;
        }
    }

    /**
     * Make her way to the job site, by the same rules she follows a player by:
     * walk when walking is reasonable, step across when it is not.
     */
    private void travelToPost() {
        if (arrived(3.0)) {
            unpathableTicks = 0;
            return;
        }
        double d2 = distanceToSqr(post.getX() + 0.5, post.getY(), post.getZ() + 0.5);
        if (d2 > TELEPORT_AT * TELEPORT_AT) {
            stepAcrossTo(post);
            unpathableTicks = 0;
            return;
        }
        if (getNavigation().isDone()) {
            if (++unpathableTicks >= UNPATHABLE_LIMIT) {
                stepAcrossTo(post);
                unpathableTicks = 0;
            } else {
                getNavigation().moveTo(post.getX() + 0.5, post.getY(), post.getZ() + 0.5, 1.0);
            }
        }
    }

    /**
     * Cross into another dimension after the player.
     *
     * <p>Deferred to the end of the tick rather than done inline, because
     * changing dimension removes this entity and adds a new one, and doing that
     * partway through the level's own iteration over its entities is how you get
     * a concurrent modification that only shows up on someone else's machine.
     */
    private void followThrough(ServerLevel dest, Vec3 at) {
        MinecraftServer server = getServer();
        if (server == null) {
            return;
        }
        server.execute(() -> {
            if (isAlive() && level() != dest) {
                changeDimension(new DimensionTransition(
                        dest, at, Vec3.ZERO, getYRot(), getXRot(),
                        DimensionTransition.DO_NOTHING));
            }
        });
    }

    /**
     * Appear near a position, preferring somewhere sensible to stand.
     *
     * <p>Same search vanilla uses for pets: ten tries at a walkable, uncrowded
     * spot a couple of blocks off, so she does not land underfoot. Unlike
     * vanilla's, this one does not silently fail - if every candidate is
     * rejected she arrives anyway. Being briefly inside a wall is recoverable
     * and she is invulnerable; being lost forever is the bug we are fixing.
     */
    private void stepAcrossTo(BlockPos around) {
        for (int i = 0; i < 10; i++) {
            int dx = random.nextIntBetweenInclusive(-3, 3);
            int dz = random.nextIntBetweenInclusive(-3, 3);
            if (Math.abs(dx) >= 2 || Math.abs(dz) >= 2) {
                int dy = random.nextIntBetweenInclusive(-1, 1);
                if (tryStandAt(around.getX() + dx, around.getY() + dy, around.getZ() + dz)) {
                    return;
                }
            }
        }
        arriveAt(around.getX() + 0.5, around.getY(), around.getZ() + 0.5);
    }

    private boolean tryStandAt(int x, int y, int z) {
        BlockPos at = new BlockPos(x, y, z);
        if (WalkNodeEvaluator.getPathTypeStatic(this, at) != PathType.WALKABLE) {
            return false;
        }
        if (!level().noCollision(this, getBoundingBox().move(at.subtract(blockPosition())))) {
            return false;
        }
        arriveAt(x + 0.5, y, z + 0.5);
        return true;
    }

    private void arriveAt(double x, double y, double z) {
        moveTo(x, y, z, getYRot(), getXRot());
        getNavigation().stop();
    }

    // --- a presence, not a participant -----------------------------------

    @Override
    public boolean isInvulnerableTo(DamageSource source) {
        // Killable by /kill so it can always be got rid of, immune to
        // everything else - a body that could be shot by a skeleton would
        // become one more thing to look after.
        return !source.is(net.minecraft.tags.DamageTypeTags.BYPASSES_INVULNERABILITY)
                || super.isInvulnerableTo(source);
    }

    @Override
    public boolean isPushable() {
        return false;
    }

    @Override
    protected void doPush(net.minecraft.world.entity.Entity entity) {
        // walks through crowds instead of shoving the crew around
    }

    @Override
    public boolean removeWhenFarAway(double distance) {
        return false;
    }

    @Override
    public boolean canBeLeashed() {
        return false;
    }

    /**
     * Trails the followed player, and stops well short of them.
     *
     * <p>Vanilla has no "follow a player" goal that is not tied to taming or
     * breeding, so this is the smallest thing that does the job. It repaths on
     * a timer rather than every tick - the lesson from the golems, where eight
     * goals ran a fresh A* twenty times a second each.
     *
     * <p>It no longer has an upper range of its own. Deciding when following is
     * hopeless belongs to the keep-up check, which can do something about it;
     * a goal that quietly stops is what stranded the body in the first place.
     */
    private static class FollowPlayerGoal extends Goal {
        private static final int REPATH_INTERVAL = 10;

        private final Body body;
        private Player target;
        private int repathTicks;

        FollowPlayerGoal(Body body) {
            this.body = body;
            setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
        }

        @Override
        public boolean canUse() {
            if (body.posted()) {
                return false;                 // she is at work
            }
            Player p = body.followed();
            if (p == null || p.isSpectator() || p.level() != body.level()) {
                return false;
            }
            if (body.distanceToSqr(p) < FOLLOW_START * FOLLOW_START) {
                return false;
            }
            target = p;
            return true;
        }

        @Override
        public boolean canContinueToUse() {
            return !body.posted()
                    && target != null && target.isAlive() && !target.isSpectator()
                    && target.level() == body.level()
                    && body.distanceToSqr(target) > FOLLOW_STOP * FOLLOW_STOP;
        }

        @Override
        public void start() {
            repathTicks = 0;
        }

        @Override
        public void stop() {
            target = null;
            body.getNavigation().stop();
        }

        @Override
        public void tick() {
            if (target == null) {
                return;
            }
            body.getLookControl().setLookAt(target, 30.0F, 30.0F);
            if (repathTicks-- <= 0 || body.getNavigation().isDone()) {
                repathTicks = REPATH_INTERVAL;
                body.getNavigation().moveTo(target, 1.0);
            }
        }
    }
}
