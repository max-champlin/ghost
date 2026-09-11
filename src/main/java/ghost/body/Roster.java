package ghost.body;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Who owns a body, and where it was last seen - <b>including while unloaded</b>.
 *
 * <h2>Why this exists</h2>
 *
 * <p>Ownership alone did not make "one Shelby per player" true, and the world
 * proved it. {@link Bodies#owned} is built on {@link Bodies#all}, which is built
 * on {@code level.getEntities} - and that only sees <b>loaded chunks</b>.
 * Measured 2026-09-10 by polling {@code where} across a dimension change, a body
 * stops being visible within 2.6 seconds of the player leaving it behind.
 *
 * <p>So {@code /ghost body here} looked for the player's existing body, found
 * nothing because it was unloaded, concluded they had none, and stood up a fresh
 * one. Do that a few times and you have three of them at nearly the same spot -
 * observed, not theorised. On a server that is a body per player per trip, which
 * is precisely the flood the ownership rule was written to prevent.
 *
 * <p>A guarantee that only holds for loaded chunks is not a guarantee. This is
 * the record that survives unloading, so the question "do you already have one?"
 * can be answered without needing it in memory.
 *
 * <h2>What it deliberately is not</h2>
 *
 * <p>Not the source of truth for the body itself - the entity still owns its own
 * state, its armour and its satchel. This holds only enough to <i>find</i> it:
 * dimension, position, entity id. If the record turns out to be stale, the
 * entity wins and the record is rewritten.
 */
public final class Roster extends SavedData {

    private static final String NAME = "ghost_roster";

    /** Where one player's body was last known to be. */
    public record Entry(ResourceKey<Level> dimension, BlockPos pos, UUID entity) {
    }

    /**
     * The key for a body nobody owns.
     *
     * <p>Bodies that predate the ownership rule have no {@code Follow} uuid, so
     * {@link Body#noteWhereIAm} had nothing to file them under and simply did
     * not record them. They then ticked forever unregistered, and
     * {@code /ghost body here} would stand another one up beside one it could
     * not see - the same shape as the loaded-chunk assumption this class was
     * written to fix, which is a pattern worth noticing.
     *
     * <p>Filed under a fixed nil uuid instead. Deliberately NOT auto-assigned
     * to whoever is nearest: on a server that would hand a body to whoever
     * walked past. Ownership is taken by standing next to them and running
     * {@code body here}, which claims the body it can actually see.
     */
    public static final UUID UNOWNED = new UUID(0L, 0L);

    private final Map<UUID, Entry> byOwner = new HashMap<>();

    public static Roster of(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(
                new Factory<>(Roster::new, Roster::load, DataFixTypes.LEVEL), NAME);
    }

    public Entry get(UUID owner) {
        return owner == null ? null : byOwner.get(owner);
    }

    public void put(UUID owner, ResourceKey<Level> dim, BlockPos pos, UUID entity) {
        if (owner == null) {
            return;
        }
        Entry old = byOwner.get(owner);
        Entry now = new Entry(dim, pos.immutable(), entity);
        if (old != null && old.equals(now)) {
            return;                       // nothing to write, do not dirty the file
        }
        byOwner.put(owner, now);
        setDirty();
    }

    public void clear(UUID owner) {
        if (owner != null && byOwner.remove(owner) != null) {
            setDirty();
        }
    }

    /**
     * Load the body a record points at, pulling its chunk in if it is not there.
     *
     * <p>One chunk, on a command, is a fair price for not silently cloning
     * someone.
     *
     * <p><b>Null means "could not verify", NOT "there is nothing there".</b>
     * The caller must refuse and keep the record. Reading null as staleness is
     * precisely the bug this method's logging exists to prevent: the record was
     * cleared, a second body was stood up, and the evidence went with it.
     * {@code /ghost body forget} is how a record is dropped - deliberately, by
     * a person, never by an inference.
     *
     * <p>Every failure path logs what it looked for and what it found, so the
     * next person to ask "why did it not find the body?" reads one line instead
     * of an hour of guesswork.
     */
    public Body resolve(MinecraftServer server, UUID owner) {
        Entry e = get(owner);
        if (e == null) {
            return null;
        }
        // SAY WHAT HAPPENED.
        //
        // The first version of this returned a bare null on every failure, and
        // the caller read that null as "the record is stale", cleared it, and
        // built a duplicate body. Diagnosing that afterwards meant reading a
        // .dat file and guessing at entity-loading internals, because nothing
        // anywhere had recorded which step failed.
        //
        // A failure that cannot explain itself costs an hour every time it
        // happens. Each branch below names what it looked for and what it got.
        ServerLevel level = server.getLevel(e.dimension());
        if (level == null) {
            ghost.Ghost.LOG.warn("Roster: cannot resolve body for {} - dimension {} "
                    + "is not loaded on this server. Record kept.",
                    owner, e.dimension().location());
            return null;
        }
        int cx = net.minecraft.core.SectionPos.blockToSectionCoord(e.pos().getX());
        int cz = net.minecraft.core.SectionPos.blockToSectionCoord(e.pos().getZ());
        level.getChunk(cx, cz);
        net.minecraft.world.entity.Entity found = level.getEntity(e.entity());
        if (found == null) {
            ghost.Ghost.LOG.warn("Roster: chunk [{}, {}] in {} was loaded, but entity {} "
                    + "is still not registered. Entities load on a deferred schedule since "
                    + "1.17, so this is expected within the same tick - the caller must "
                    + "REFUSE, not assume the record is stale. Record kept.",
                    cx, cz, e.dimension().location(), e.entity());
            return null;
        }
        if (!(found instanceof Body body)) {
            ghost.Ghost.LOG.warn("Roster: entity {} in {} resolved to {} and not a body. "
                    + "Record kept - something else now owns that id.",
                    e.entity(), e.dimension().location(), found.getType());
            return null;
        }
        if (!body.isAlive()) {
            ghost.Ghost.LOG.warn("Roster: body {} in {} resolved but is not alive. "
                    + "Record kept; /ghost body forget will drop it.",
                    e.entity(), e.dimension().location());
            return null;
        }
        ghost.Ghost.LOG.info("Roster: resolved body for {} in {} at {}",
                owner, e.dimension().location(), e.pos());
        return body;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag list = new ListTag();
        for (Map.Entry<UUID, Entry> en : byOwner.entrySet()) {
            CompoundTag row = new CompoundTag();
            row.putUUID("Owner", en.getKey());
            row.putUUID("Entity", en.getValue().entity());
            row.putString("Dim", en.getValue().dimension().location().toString());
            row.putInt("X", en.getValue().pos().getX());
            row.putInt("Y", en.getValue().pos().getY());
            row.putInt("Z", en.getValue().pos().getZ());
            list.add(row);
        }
        tag.put("Bodies", list);
        return tag;
    }

    private static Roster load(CompoundTag tag, HolderLookup.Provider registries) {
        Roster r = new Roster();
        ListTag list = tag.getList("Bodies", 10);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag row = list.getCompound(i);
            if (!row.hasUUID("Owner") || !row.hasUUID("Entity")) {
                continue;
            }
            ResourceLocation dim = ResourceLocation.tryParse(row.getString("Dim"));
            if (dim == null) {
                continue;
            }
            r.byOwner.put(row.getUUID("Owner"), new Entry(
                    ResourceKey.create(Registries.DIMENSION, dim),
                    new BlockPos(row.getInt("X"), row.getInt("Y"), row.getInt("Z")),
                    row.getUUID("Entity")));
        }
        return r;
    }
}
