package ghost.body;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.SimpleContainer;
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
     * that a wall between them and the player is not a minute of confusion.
     */
    private static final int UNPATHABLE_LIMIT = 3;

    /** Who to keep up with; survives dimension changes and reloads. */
    private UUID followId;

    /**
     * How fast they closes on someone who is flying, in blocks per tick.
     *
     * <p>Deliberately below a creative-flight sprint. Matching it exactly would
     * park their inside the player's head; being a little slower means they trail
     * behind and reads as following rather than being attached.
     */
    private static final double HOVER_SPEED = 0.45;

    /** True while they are holding station in the air because their player is. */
    private boolean hovering;

    /**
     * What they are carrying.
     *
     * <p>Until now they could hold exactly one stack, in their hand, which meant
     * every errand that moved more than one kind of item had to be a separate
     * trip. A satchel is the difference between "fetch me that" and "go and
     * tidy that up" - and it is what {@code take} and {@code put} move things
     * into and out of.
     *
     * <p>A single chest's worth on purpose. Bigger would make their a mobile
     * storage system, which is a different thing from an assistant and one the
     * base already has better answers for.
     */
    private final SimpleContainer bag = new SimpleContainer(27);

    public SimpleContainer bag() {
        return bag;
    }

    private int keepUpCooldown;
    private int unpathableTicks;

    /**
     * A job site they have been sent to, or null when they are simply with you.
     *
     * <p>While posted they stop following. An assistant that trots after you
     * while supposedly away doing something is not away doing something, and
     * the point of sending their is that the work happens somewhere you are not.
     */
    private BlockPos post;

    /**
     * True when the posting was a deliberate stationing rather than a one-off
     * errand.
     *
     * <p>This used to be a {@code static boolean} on the bridge, which is the
     * wrong place for it twice over: it described the body but lived somewhere
     * else, and being static it reset to false on every restart. So a stationing
     * survived the save correctly and was then wiped by the first batch that ran
     * afterwards, because the bridge had forgotten it was ever deliberate. State
     * about the body belongs on the body.
     */
    private boolean postSticky;

    /** Game time the current errand posting was set, for the give-up timer. */
    private long postSince;

    /** Ticks left holding a crouch, so it is visible rather than instantaneous. */
    private int crouchTicks;

    public Body(EntityType<? extends PathfinderMob> type, Level level) {
        super(type, level);
        setPersistenceRequired();
        setCustomNameVisible(true);
        if (!hasCustomName()) {
            setCustomName(Component.literal("Shelby"));
        }
        guaranteeDrops();
    }

    /**
     * Anything they are wearing comes back if they are ever removed.
     *
     * <p>They can only be got rid of with {@code /kill}, and losing a set of
     * someone's good armour to a housekeeping command would be a nasty
     * surprise. Called from the constructor AND after every load, because the
     * load overwrites it - see {@link #readAdditionalSaveData}.
     */
    private void guaranteeDrops() {
        for (EquipmentSlot slot : EquipmentSlot.values()) {
            setGuaranteedDrop(slot);
        }
    }

    // --- being sent somewhere ---------------------------------------------

    /** Send them somewhere for the duration of a batch. */
    public void postTo(BlockPos site) {
        postTo(site, false);
    }

    /**
     * Send them to a job site.
     *
     * @param sticky true to station them until explicitly released; false for an
     *               errand that ends when the batch does
     */
    public void postTo(BlockPos site, boolean sticky) {
        this.post = site;
        this.postSticky = sticky;
        this.unpathableTicks = 0;
        this.postSince = level().getGameTime();
    }

    /**
     * Is they still on their way somewhere they were sent?
     *
     * <p>The bridge asks before recalling them at the end of a batch. A batch
     * finishes in a tick or two; a walk across the base takes seconds. Recalling
     * on batch-end therefore cancelled every errand before they arrived, and any
     * walk under the 24-block teleport threshold - the ones that go on foot -
     * never completed at all. Reported from in-game as "the walk keeps getting
     * recalled mid-transit", which is exactly what it was.
     */
    public boolean travelling() {
        return post != null && !postSticky && !arrived(ARRIVE_WITHIN);
    }

    /** How close counts as "there", for an errand. */
    private static final double ARRIVE_WITHIN = 3.0;

    /**
     * Ticks an errand may run before it is abandoned.
     *
     * <p>Without this, a post they can never reach would hold them off following
     * anyone, forever, with nothing left to clear it - the walk fix removes the
     * batch-end clear that used to (accidentally) do that job.
     */
    private static final int ERRAND_LIMIT = 20 * 90;

    /** True when they were stationed on purpose and must not be auto-recalled. */
    /**
     * Where they are trying to get to, or null if nowhere.
     *
     * <p>Exposed because every mystery about this body has been "why did they
     * move", and nothing reported what they were TRYING to do - only where they
     * ended up. A position without an intention is not diagnosable: an
     * unexplained drift and a perfectly correct walk to a posting look identical
     * from outside.
     */
    public BlockPos post() {
        return post;
    }

    /** True while holding station in the air. */
    public boolean hovering() {
        return hovering;
    }

    /** Who they are keeping up with, or null. */
    public java.util.UUID followedId() {
        return followId;
    }

    public boolean stationed() {
        return post != null && postSticky;
    }

    /** Release them; they go back to keeping up with whoever they follow. */
    public void clearPost() {
        this.post = null;
        this.postSticky = false;
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
     * Put clothes on them, or take them off.
     *
     * <p>Three gestures, chosen so nothing can be handed over by accident:
     *
     * <ul>
     *   <li>right-click holding <b>armour</b> - they wear it, and hands back
     *       whatever was in that slot</li>
     *   <li>right-click with an <b>empty hand</b> - they say what they are
     *       wearing. It reads out, it does not undress them.</li>
     *   <li><b>sneak</b> + empty hand - they take one thing off</li>
     *   <li><b>sneak</b> right-click holding anything else - they hold it</li>
     * </ul>
     *
     * <p>Non-armour deliberately does nothing on a plain right-click. Equipping
     * whatever happens to be in hand would mean walking up to say hello and
     * silently giving away your pickaxe.
     *
     * <p>Taking armour off <b>used to be the bare right-click</b>, which is the
     * single most common thing anyone does to a mob standing in front of them.
     * One absent-minded click removed a piece and put it in your pack; four
     * removed a whole suit, and if your inventory was full it went on the floor
     * instead. That is exactly how a set of armour goes missing without anyone
     * doing anything they would remember. Undressing now needs a sneak - the
     * same "sneak to undo" gesture the golems use for leaving a crew - and the
     * bare click became the harmless, useful one.
     */
    @Override
    protected InteractionResult mobInteract(Player player, InteractionHand hand) {
        if (level().isClientSide) {
            return InteractionResult.SUCCESS;
        }
        ItemStack held = player.getItemInHand(hand);

        if (held.isEmpty()) {
            if (player.isShiftKeyDown()) {
                return undress(player);
            }
            player.displayClientMessage(Component.literal(
                    "Shelby is wearing " + ghost.Slots.wornLine(this)
                            + ".  Sneak + right-click to take something off."), false);
            return InteractionResult.SUCCESS;
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
        // They wanders only while the bridge is armed. With it off they cannot
        // act on anything, and drifting around as though they might is the
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

    // --- what they looks like they are doing ---------------------------------

    /**
     * Posture as an honest status light.
     *
     * <p>Ghost's whole design refuses to look busier than it is, and this is the
     * same rule applied to a body. Rather than inventing idle animation for its
     * own sake, what they do with themselves reports the actual state of the
     * system:
     *
     * <ul>
     *   <li><b>settled</b> - the bridge is disarmed, nothing can reach them, and
     *       they are sitting it out. Visible from across the room without typing
     *       a command</li>
     *   <li><b>upright</b> - armed and available</li>
     *   <li><b>working</b> - actions in flight, or AE2 still thinking about a
     *       craft. Shown with a few particles, because "waiting on a background
     *       thread" has no natural pose</li>
     * </ul>
     *
     * <p>They never crouches while moving: a crouch-walk reads as sneaking
     * rather than resting, which would say the wrong thing entirely.
     */
    private void showState() {
        boolean busy = ghost.Bridge.busy() || ghost.Storage.craftPending();
        // Settled means genuinely idle: nothing in flight, nowhere being walked
        // to, and no posting to stand at. It used to mean only "the bridge is
        // disarmed", which was a status light nobody asked for - with the bridge
        // open they never sat down at all, which is every normal session.
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

    // --- the roster: staying findable while unloaded -----------------------

    /** Throttle. The record only has to be good enough to find them again. */
    private int rosterTick;

    /**
     * Keep {@link Roster} pointing at where they actually are.
     *
     * <p>Cheap on purpose - once every ten seconds, and {@link Roster#put}
     * returns without dirtying the file when nothing moved.
     */
    private void noteWhereIAm() {
        if (level().isClientSide || getServer() == null || followedId() == null) {
            return;
        }
        if (++rosterTick % 200 != 0) {
            return;
        }
        Roster.of(getServer()).put(followedId(), level().dimension(),
                blockPosition(), getUUID());
    }

    /**
     * Forget the record only when they are really gone.
     *
     * <p>The reason matters enormously. {@code UNLOADED_TO_CHUNK} and
     * {@code UNLOADED_WITH_PLAYER} are the everyday case this whole class exists
     * to survive - clearing on those would delete the record every time the
     * player walks away, which is the bug rather than the fix.
     * {@code CHANGED_DIMENSION} is also a removal: {@code Entity.changeDimension}
     * removes the entity and re-adds it on the other side, so treating that as
     * death would lose them the moment they followed anyone through a portal.
     */
    @Override
    public void remove(RemovalReason reason) {
        if (!level().isClientSide && getServer() != null && followedId() != null
                && (reason == RemovalReason.KILLED || reason == RemovalReason.DISCARDED)) {
            Roster.of(getServer()).clear(followedId());
        }
        super.remove(reason);
    }

    /**
     * Spill everything they are carrying onto the floor.
     *
     * <p>{@code discard()} is a silent removal - {@code setGuaranteedDrop} never
     * fires and nothing lands. That is correct for {@code body here}, which
     * carries the kit across to the new body, and it was quietly destructive for
     * {@code body away}, which carried it nowhere. Armour and a full satchel
     * simply ceased to exist.
     */
    /** What became of the things they were carrying. */
    public record Spilled(int given, int dropped) {
        public int total() {
            return given + dropped;
        }
    }

    /**
     * Take everything off them and put it somewhere safe.
     *
     * <p>{@code discard()} is a silent removal - {@code setGuaranteedDrop} never
     * fires and nothing lands - so {@code body away} used to delete a suit of
     * armour and a full satchel without a word.
     *
     * <p>Handed to the player rather than thrown on the floor, because the floor
     * is not safe. Dropping worked, and a Spider-Man set was still lost to it:
     * the items landed at Y=3 in the Twilight Forest on a five-minute despawn
     * timer while the player was reading chat. A command whose entire job is to
     * not lose someone's gear should not hand it to a timer. The floor stays as
     * the overflow when there is no room, and the message says which happened.
     *
     * <p>Logged by name, because Shelby's chat never reaches {@code latest.log}
     * - so "did the armour drop?" was previously answerable only by asking the
     * person who saw it.
     */
    public Spilled spill(net.minecraft.server.level.ServerPlayer to) {
        if (level().isClientSide) {
            return new Spilled(0, 0);
        }
        java.util.List<ItemStack> carried = new java.util.ArrayList<>();
        for (net.minecraft.world.entity.EquipmentSlot slot
                : net.minecraft.world.entity.EquipmentSlot.values()) {
            ItemStack st = getItemBySlot(slot);
            if (!st.isEmpty()) {
                carried.add(st.copy());
                setItemSlot(slot, ItemStack.EMPTY);
            }
        }
        for (int i = 0; i < bag.getContainerSize(); i++) {
            ItemStack st = bag.getItem(i);
            if (!st.isEmpty()) {
                carried.add(st.copy());
                bag.setItem(i, ItemStack.EMPTY);
            }
        }
        int given = 0;
        int dropped = 0;
        for (ItemStack st : carried) {
            String what = st.getCount() + "x " + st.getHoverName().getString();
            if (to != null) {
                to.getInventory().add(st);   // mutates: leaves any remainder
            }
            if (st.isEmpty()) {
                given++;
                ghost.Ghost.LOG.info("Shelby spill: {} -> {}", what, to.getName().getString());
            } else {
                spawnAtLocation(st);
                dropped++;
                ghost.Ghost.LOG.info("Shelby spill: {} -> floor at {}", what, blockPosition());
            }
        }
        return new Spilled(given, dropped);
    }


    // --- who we are following --------------------------------------------

    /** Bind to a particular player - used when someone addresses them in chat. */
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
        // A posting is an instruction that outlives the session that gave it.
        // Without this, "stay by the bed" survived exactly until the next
        // restart and then they quietly went back to following - which looks
        // like the order was ignored rather than forgotten.
        tag.put("Bag", bag.createTag(registryAccess()));
        if (post != null) {
            tag.putInt("PostX", post.getX());
            tag.putInt("PostY", post.getY());
            tag.putInt("PostZ", post.getZ());
            tag.putBoolean("PostSticky", postSticky);
        }
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        followId = tag.hasUUID("Follow") ? tag.getUUID("Follow") : null;
        post = tag.contains("PostX")
                ? new BlockPos(tag.getInt("PostX"), tag.getInt("PostY"), tag.getInt("PostZ"))
                : null;
        bag.fromTag(tag.getList("Bag", 10), registryAccess());
        // Re-assert the guaranteed drops.
        //
        // The constructor sets them, and then THIS METHOD's super call quietly
        // undoes it: Mob.readAdditionalSaveData overwrites the drop-chance array
        // from the saved ArmorDropChances. So every body loaded from disk came
        // back at the vanilla 0.085 default, and the protection the constructor
        // was written to provide had never once been in effect on a real,
        // saved-and-reloaded Shelby. Found by reading 0.085 out of a backup
        // while looking for a suit of armour that had gone missing.
        guaranteeDrops();
        postSticky = tag.getBoolean("PostSticky");
        // NoGravity is vanilla-persisted, and hovering is not. Quitting or
        // crashing mid-flight would otherwise restore a body that floats
        // forever with nothing left running to turn it off again.
        hovering = false;
        setNoGravity(false);
    }

    // --- keeping up -------------------------------------------------------

    @Override
    public void aiStep() {
        noteWhereIAm();
        super.aiStep();
        if (!level().isClientSide && hovering) {
            holdStation();
        }
        if (crouchTicks > 0 && --crouchTicks == 0) {
            setShiftKeyDown(false);
        }
    }

    /**
     * Crouch, and hold it long enough to be seen.
     *
     * <p>A crouch set and cleared in one tick is invisible to everyone watching,
     * which defeats the point of having a body at all. The renderer already
     * follows {@code isCrouching()}, so this is the whole mechanism.
     */
    public void crouchFor(int ticks) {
        crouchTicks = Math.max(1, ticks);
        setShiftKeyDown(true);
    }

    /** Jump, if there is ground to jump from. */
    public boolean hop() {
        if (!onGround()) {
            return false;
        }
        jumpFromGround();
        return true;
    }

    @Override
    protected void customServerAiStep() {
        super.customServerAiStep();
        showState();
        if (--keepUpCooldown > 0) {
            return;
        }
        keepUpCooldown = KEEP_UP_INTERVAL;

        // A posting outranks the player. They are at work.
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

        // Airborne players need a different answer entirely.
        //
        // Everything below this assumes the ground: the navigator paths over
        // walkable blocks, and stepAcrossTo hunts for a WALKABLE landing spot.
        // Neither exists under someone in creative flight, so the search fell
        // through to arriveAt() at the player's own feet and they dropped out of
        // the sky the instant they caught up - repeatedly, and from any height.
        if (airborne(p)) {
            beginHover();
            if (d2 > TELEPORT_AT * TELEPORT_AT) {
                stepAcrossToAir(p.position());
            }
            unpathableTicks = 0;
            return;
        }
        endHover();

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
     * Make their way to the job site, by the same rules they follow a player by:
     * walk when walking is reasonable, step across when it is not.
     */
    private void travelToPost() {
        if (arrived(ARRIVE_WITHIN)) {
            unpathableTicks = 0;
            // Arriving is what ends an errand. A stationed post is a standing
            // order and stays until it is explicitly released.
            if (!postSticky) {
                clearPost();
            }
            return;
        }
        // Reloaded mid-errand, or posted before this field existed.
        if (postSince == 0L) {
            postSince = level().getGameTime();
        }
        if (!postSticky && level().getGameTime() - postSince > ERRAND_LIMIT) {
            ghost.Ghost.LOG.warn("giving up on errand post at {} after {} ticks",
                    post, ERRAND_LIMIT);
            clearPost();
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
    /**
     * Cross to another world on purpose, rather than because someone walked.
     *
     * <p>Same machinery as following through a portal - snapshot first, verify
     * arrival, rebuild if the transfer eats them - just reached deliberately.
     * A warp that refuses to cross a dimension is not much of a warp, and
     * "he is in the mining dimension and they are not" is an ordinary Tuesday.
     */
    public void crossTo(ServerLevel dest, Vec3 at) {
        followThrough(dest, at);
    }

    private void followThrough(ServerLevel dest, Vec3 at) {
        MinecraftServer server = getServer();
        if (server == null) {
            return;
        }
        server.execute(() -> {
            if (!isAlive() || level() == dest) {
                return;
            }
            // Snapshot BEFORE the crossing, because the crossing is where they
            // can be lost.
            //
            // Entity.changeDimension removes this entity first and only then
            // tries to build its replacement. If that build returns null the
            // old one is already gone and nothing is ever added back - no
            // exception, no log line, just an entity that stops existing along
            // with everything it was wearing. That is not a theory: a full set
            // of Inferium armour went missing this way and there was not one
            // line in the log to find, because nothing here checked.
            CompoundTag snapshot = new CompoundTag();
            saveWithoutId(snapshot);
            stash(snapshot);

            Entity arrived = changeDimension(new DimensionTransition(
                    dest, at, Vec3.ZERO, getYRot(), getXRot(),
                    DimensionTransition.DO_NOTHING));

            if (arrived == null) {
                ghost.Ghost.LOG.error(
                        "body was LOST crossing into {} - rebuilding from snapshot",
                        dest.dimension().location());
                rebuild(dest, at, snapshot);
            }
        });
    }

    /**
     * Put them back together after a crossing that ate them.
     *
     * <p>Same identity, same inventory, same clothes - the snapshot is the
     * entity's own NBT, taken a moment before it vanished. The Dimension key is
     * dropped because it names where they came FROM.
     */
    private static void rebuild(ServerLevel dest, Vec3 at, CompoundTag snapshot) {
        try {
            Body fresh = Bodies.SHELBY.get().create(dest);
            if (fresh == null) {
                ghost.Ghost.LOG.error("could not recreate the body at all - "
                        + "snapshot is on disk at ghost/lastbody.snbt");
                return;
            }
            snapshot.remove("Dimension");
            snapshot.remove("UUID");   // a fresh one, or the level rejects the add
            fresh.load(snapshot);
            fresh.setPos(at.x, at.y, at.z);
            dest.addFreshEntity(fresh);
            ghost.Ghost.LOG.info("body rebuilt in {} at {}",
                    dest.dimension().location(), at);
        } catch (Exception e) {
            ghost.Ghost.LOG.error("rebuilding the body failed", e);
        }
    }

    /**
     * Keep the last pre-crossing snapshot on disk.
     *
     * <p>Belt and braces over the in-memory rebuild. Recovering the last lost
     * suit meant parsing region files out of a backup zip; one small file
     * written at the one moment things are known to go wrong turns that into
     * reading a text file.
     */
    private static void stash(CompoundTag snapshot) {
        try {
            java.nio.file.Files.writeString(
                    ghost.Sampler.dir().resolve("lastbody.snbt"),
                    snapshot.toString(),
                    java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            // Never let bookkeeping stop the crossing itself.
            ghost.Ghost.LOG.warn("could not write lastbody.snbt", e);
        }
    }

    /**
     * Appear near a position, preferring somewhere sensible to stand.
     *
     * <p>Same search vanilla uses for pets: ten tries at a walkable, uncrowded
     * spot a couple of blocks off, so they do not land underfoot. Unlike
     * vanilla's, this one does not silently fail - if every candidate is
     * rejected they arrive anyway. Being briefly inside a wall is recoverable
     * and they are invulnerable; being lost forever is the bug we are fixing.
     */
    /**
     * Is this player off the ground in a way they cannot walk to?
     *
     * <p>Creative flight and an elytra both count. Ordinary jumping and falling
     * do not - those resolve themselves in under a second, and switching them
     * into a hover for them would make every hop look like a glitch.
     */
    private static boolean airborne(Player p) {
        return p.getAbilities().flying || p.isFallFlying();
    }

    private void beginHover() {
        if (hovering) {
            return;
        }
        hovering = true;
        setNoGravity(true);
        // A ground path to a point in the sky is unreachable by definition, and
        // leaving it running means the navigator fights the station-keeping
        // below for control of their velocity every tick.
        getNavigation().stop();
    }

    private void endHover() {
        if (!hovering) {
            return;
        }
        hovering = false;
        setNoGravity(false);
    }

    /**
     * Keep station on a flying player. Runs every tick, unlike the keep-up
     * check, because a velocity nudge once a second is a series of lurches.
     */
    private void holdStation() {
        Player p = followed();
        if (p == null || p.level() != level() || !airborne(p)) {
            endHover();
            return;
        }
        // Slightly above eye level, so they are in view rather than underfoot.
        Vec3 gap = p.position().add(0.0, 0.6, 0.0).subtract(position());
        double away = gap.length();
        if (away < FOLLOW_STOP) {
            // Bleed off speed instead of stopping dead, or they jitters against
            // the stop radius every tick.
            setDeltaMovement(getDeltaMovement().scale(0.6));
        } else {
            setDeltaMovement(gap.normalize().scale(Math.min(HOVER_SPEED, away / 8.0)));
        }
        getLookControl().setLookAt(p, 30.0F, 30.0F);
        // Vanilla only clears fall distance on landing, and they never lands
        // while hovering - so without this they banks up a lethal number and
        // takes it all the moment gravity comes back on.
        fallDistance = 0.0F;
    }

    /** Arrive beside a flying player, in the air, rather than under them. */
    private void stepAcrossToAir(Vec3 at) {
        beginHover();
        double ang = random.nextDouble() * Math.PI * 2.0;
        arriveAt(at.x + Math.cos(ang) * 2.5, at.y + 0.6, at.z + Math.sin(ang) * 2.5);
        setDeltaMovement(Vec3.ZERO);
    }

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
        // Nothing walkable nearby, so look UP AND DOWN the column before
        // giving up.
        //
        // The blind fallback below buried them. It places them at the target
        // whatever is there, on the reasoning that being briefly inside a wall
        // beats being lost - which is right, except "briefly" turned out to mean
        // twenty minutes encased in a ceiling three blocks above the player,
        // invisible (Entity Culling cannot trace to a buried entity) and
        // reporting a confident position nobody could reconcile with the world.
        // A column scan costs nothing and finds the floor or the surface.
        for (int dy = 0; dy <= 12; dy++) {
            for (int sign : new int[]{-1, 1}) {
                int y = around.getY() + dy * sign;
                if (level().isOutsideBuildHeight(y)) {
                    continue;
                }
                if (tryStandAt(around.getX(), y, around.getZ())) {
                    return;
                }
                if (dy == 0) {
                    break;          // y+0 and y-0 are the same place
                }
            }
        }
        // Truly nowhere to stand. Place them anyway rather than lose them, but say
        // so - a silently buried body is what made this hard to find.
        ghost.Ghost.LOG.warn("no standable spot near {} - placing regardless; "
                + "they may be inside a block", around);
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
                return false;                 // they are at work
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
