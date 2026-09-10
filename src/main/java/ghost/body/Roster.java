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
     * someone. Returns null if the record is stale - the world moved on, the
     * entity was removed by something else, or the dimension no longer exists -
     * and the caller is expected to drop the record when that happens.
     */
    public Body resolve(MinecraftServer server, UUID owner) {
        Entry e = get(owner);
        if (e == null) {
            return null;
        }
        ServerLevel level = server.getLevel(e.dimension());
        if (level == null) {
            return null;
        }
        // Touching the chunk is what makes the entity resolvable at all; without
        // it getEntity returns null for exactly the bodies this class exists for.
        level.getChunk(net.minecraft.core.SectionPos.blockToSectionCoord(e.pos().getX()),
                net.minecraft.core.SectionPos.blockToSectionCoord(e.pos().getZ()));
        net.minecraft.world.entity.Entity found = level.getEntity(e.entity());
        return found instanceof Body body && body.isAlive() ? body : null;
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
