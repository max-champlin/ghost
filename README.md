# Ghost

**An in-process bridge that gives an AI agent real access to a *modded* Minecraft world — including mod APIs a protocol-level bot cannot reach.**

---

# This is not an AI NPC.

Ghost does not think. It ships no model, no API key, and no prompt. It is the
**hands and eyes** — a body in the world and a contract for driving it. The brain
is whatever agent you point at it: Claude Code, a local model, your own script.

If you want a companion that talks to you out of the box, this is the wrong mod.
If you want your own agent to be able to *actually operate your base*, read on.

---

## Why this exists

Almost every AI-in-Minecraft project is built on a headless bot that connects
over the network protocol as if it were a player. That design has a hard ceiling:
**a protocol-level bot only sees what a client sees.**

Ghost is a server-side mod running *inside* the game, so it can call mod APIs
directly. The difference is not incremental:

| | protocol bot | Ghost |
|---|---|---|
| read a chest | yes | yes |
| read an **ME network's contents** | no — the data never crosses the wire in usable form | yes, real `KeyCounter` |
| **submit an autocrafting job** | only by driving the terminal GUI, slot by slot | yes, `ICraftingService` directly |
| survive a modded pack | fragile | it is the target |

Nobody needs help chopping vanilla trees. People genuinely do lose an AE2
controller inside their own base.

## Applied Energistics 2 — working, not planned

This is the part that does not exist elsewhere. Ghost talks to AE2 through AE2's
own supported API, not by pretending to be a player at a terminal.

**Reading a network** — `Ae2.java`. Networks are found through AE2's
`ME_STORAGE` block capability, so anything a cable reaches is a valid door in:
terminal, interface, drive, controller. Results are deduplicated *by storage
object* rather than by position, because one network answers through every block
attached to it — otherwise a room full of terminals reports the same 4,000 certus
quartz a dozen times over.

```java
MEStorage storage = level.getCapability(AECapabilities.ME_STORAGE, pos, dir);
for (var entry : storage.getAvailableStacks()) {
    if (entry.getKey() instanceof AEItemKey key && key.getItem() == want) {
        total += entry.getLongValue();
    }
}
```

**Crafting** — `Ae2Craft.java`. A real autocrafting job on a real network:

```json
{"do": "craft", "item": "minecraft:iron_ingot", "count": 64, "radius": 16}
```

Two things make this honest rather than decorative:

**It is asynchronous, and correctly so.** AE2 plans a craft on a background
thread and hands back a `Future`. A bridge action runs inside one server tick, so
waiting on that would freeze the server. Ghost returns immediately and submits the
finished plan on a later tick — the outcome arrives in chat when it is actually
known.

**It reports the difference between the ways a craft fails**, because "sure,
crafting it" for all of them is worse than useless:

| outcome | what it says |
|---|---|
| no network in range | `no ME network within 16 blocks of there` |
| no pattern for the item | `the network has no pattern for Iron Ingot` |
| short on ingredients | `cannot make 64x Iron Ingot - short of 12x Certus Quartz, 3x Redstone` |
| success | `crafting 64x Iron Ingot - job submitted (1204 bytes)` |

It uses `REPORT_MISSING_ITEMS`, not `CRAFT_LESS`. A job that silently makes 3 of
the 64 you asked for is not a success.

## Permissions

Ghost can break blocks, place them, run commands, and spend the contents of an ME
network. On a server, anyone able to type in chat can ask it to. Rank gates what
an ask is allowed to become:

| ability | needs | covers |
|---|---|---|
| `LOOK` | anyone | scan, find, read, counts |
| `MOVE` | anyone | goto, say |
| `CRAFT` | op 2 | spending the network |
| `WORLD` | op 2 | break, place, use |
| `COMMAND` | op 4 | arbitrary commands — this is the console |

Built on vanilla operator levels deliberately: every server has them, they need
no dependency, and a safety floor that depends on an optional mod disappears when
that mod does.

Crafts additionally run under `IActionSource.ofPlayer(requester)`, so **AE2's own
security terminal rules apply on top** — Ghost can never do anything on a network
that the person asking could not do standing at it.

The single-player host is always permitted everything. With cheats off the host
sits at permission level 0 despite owning the save, and locking someone out of
their own assistant on their own world would be absurd.

## The body

An in-world presence you can walk up to, talk at, and watch follow you. It is
harmless by construction: it cannot be hurt except by `/kill`, cannot be pushed,
attacks nothing and never despawns. Anything it *does* still goes through the
bridge, on the server thread, under the same caps.

- Follows across **dimensions** — through portals, not just within one world
- Walks when the distance is walkable, steps across when it is not
- Can be **dressed**: right-click with armour to equip, empty hand to remove,
  sneak-right-click to hand it something to hold. Everything worn is a guaranteed
  drop, so gear is never lost

## How the bridge works

A JSON file contract. No sockets, no API keys, no vendor:

- the agent writes `ghost/inbox.json` — a list of actions
- the mod runs them on the server thread and writes `ghost/outbox.json`
- chat addressed to Shelby lands in `ghost/asks.jsonl`, tagged with the asker's rank

```json
[
  {"do": "scan", "from": [100, 60, 200], "to": [140, 80, 240]},
  {"do": "craft", "item": "ae2:certus_quartz_crystal", "count": 128},
  {"do": "say", "text": "started"}
]
```

**The bridge disarms itself on every start.** It does nothing until someone with
access to the game types `/ghost bridge on`. A mod that let an external process
act on a world the moment it launched would be a hole in the wall.

## Requirements

- Minecraft 1.21.1, NeoForge
- An AI agent of your choosing, running alongside the game with filesystem access
- Applied Energistics 2 (**optional**) — every AE2 feature is guarded and the mod
  runs fine without it

## Building

Ghost compiles against AE2's API but does **not** redistribute it. Drop your own
copy into `libs/`:

```
libs/appliedenergistics2-19.2.17.jar
```

Then:

```
gradle build
```

## Status

Working and in daily use on a 613-mod Minecraft 1.21.1 pack. Not yet released to
Modrinth or CurseForge.

## Licence

MIT. See [LICENSE](LICENSE).
