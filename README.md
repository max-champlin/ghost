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

## Running a local model? Start here: [`docs/actions.schema.json`](docs/actions.schema.json)

**This one file is the difference between "needs a 70B" and "runs on 12GB of VRAM."**

Ghost is driven by JSON. Emitting valid JSON is the single thing small models
reliably fail at — a 7B will get the verbs right and then forget a brace, quote a
number, or invent a field, and every one of those is a dead request. Constrained
decoding removes that failure mode completely: the sampler is not *asked* to
produce valid JSON, it is made **incapable** of producing anything else.

So hand your runtime the schema and stop parsing apologies:

```bash
# Ollama - jq builds the body so the schema is embedded as JSON, not as a
# string. Passing it with bare shell quoting is where this usually goes wrong.
jq -n --argjson schema "$(cat docs/actions.schema.json)" \
   --arg ask "Shelby, what is in the ME system?" \
   '{model: "qwen2.5:7b-instruct", stream: false, format: $schema,
     messages: [{role: "user", content: $ask}]}' \
| curl -s localhost:11434/api/chat -d @-
```

```bash
# llama.cpp server
jq -n --argjson schema "$(cat docs/actions.schema.json)" \
   '{prompt: "...", json_schema: $schema}' \
| curl -s localhost:8080/completion -d @-
```

```bash
# vLLM
--guided-json docs/actions.schema.json
```

The schema covers all 29 verbs, the position format, and which fields belong to
which action, so the model also cannot ask for `craft` without an item or invent
a verb that does not exist. Malformed output stops being a class of bug.

Sizing, prompt shape, and which verbs need more model than others are further
down in [Running it on a local model](#running-it-on-a-local-model).

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
`IN_WORLD_GRID_NODE_HOST` capability, which **every** grid-connected block
exposes — drive, controller, cable, terminal, interface — and that leads to the
`IGrid`, whose `IStorageService` owns the real inventory. Results are
deduplicated by the grid itself, because one network answers through every block
attached to it; otherwise a room full of terminals reports the same 4,000 certus
quartz a dozen times over.

```java
IInWorldGridNodeHost host = level.getCapability(
        AECapabilities.IN_WORLD_GRID_NODE_HOST, pos, null);
IGridNode node = host.getGridNode(face);
MEStorage inv = node.getGrid().getService(IStorageService.class).getInventory();
long held = inv.getAvailableStacks().get(AEItemKey.of(want));
```

> **A wrong turn worth documenting.** This first used the `ME_STORAGE` block
> capability, which reads like the obvious way in and is exposed by only a
> handful of blocks — **a drive is not one of them, and neither is a
> controller**. Standing directly on a drive beside an online controller, it
> reported *zero networks*, indistinguishable from a network holding none of
> what you asked about. If you are writing AE2 integration, go through the grid
> node, not the storage capability.

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

## Running it on a local model

Ghost is unusually small-model friendly, and that is a property of the contract
rather than luck. The agent is handed one situation at a time and answers with
one list of actions - it is not holding a long tool-calling conversation, so the
context never grows the way an agent loop's does.

Measured on a live 615-mod instance:

| what the model holds | tokens |
|---|---|
| the operating briefing (system prompt) | ~1,600 |
| an incoming question from chat | ~50 |
| a typical result to interpret | 150-650 |
| the JSON action it writes back | ~50 |
| **a normal turn, end to end** | **~2,500-3,000** |

So a **4k context window is enough** for ordinary work: answering questions,
running scans, reporting what is in a container, crafting. The exception is a
`blockmap` with `nbt` over a whole base, which can reach 25k tokens - either
give that one a large-context model or filter the JSON before it reaches the
prompt.

### The part that actually decides whether a small model works

Not reasoning - **valid JSON**. A 3B model that understands the request
perfectly will still hand you a trailing comma. That is a solved problem: every
serious local runtime can constrain output to a schema, and one ships with this
mod at [`docs/actions.schema.json`](docs/actions.schema.json).

```bash
# Ollama
curl localhost:11434/api/chat -d '{"model":"qwen2.5:7b","format":<schema>,...}'

# llama.cpp server
./llama-server -m model.gguf --json-schema-file docs/actions.schema.json
```

With the schema enforced, malformed actions stop being a failure mode entirely
and model size becomes a question of judgement rather than syntax.

### Rough tiers

These are reasoned from what each step demands, not benchmarked - treat them as
a starting point and expect your own mileage to differ:

- **1-3B** - can drive templated single actions (`say`, a scan at given
  coordinates) with the schema enforced. Will not choose sensibly between
  twenty-nine verbs or interpret a block census. Usable as a command parser, not
  as an assistant.
- **7-8B** (Llama 3.1 8B, Qwen2.5 7B, Mistral 7B) - the realistic floor for
  useful autonomous work. Picks the right verb, reads a result, answers in
  chat. This is where most home labs should start.
- **14B** (Qwen2.5 14B, Phi-4) - comfortable. Handles multi-step work: find a
  thing, read it, act on what it said.
- **32B+** - good. Worth it if you want it reasoning about *modded* systems
  rather than reporting them.

### What actually works on barebones

Verb by verb, so you can size honestly rather than discovering it in your base.
The line that matters is not "can it emit the JSON" - the schema guarantees that
at any size - it is **"can it choose the right verb and read the answer".**

| tier | verbs it can be trusted with | why |
|---|---|---|
| **1-3B** | `say` `where` `read` `find` `have` `places` `worn` `bag` | literal lookups where *you* named the thing. It transcribes, it does not decide. |
| **7-8B** | the above plus `goto` `post` `return` `slots` `scan` `entities` `take` `put` `craft` (item named explicitly) | picks a verb from a request, reads a result back, answers in chat. The realistic floor. |
| **14B+** | plus `blockmap` `cells` `waitFor` and multi-step chains | holds a census in context and reasons over it; decides *which* verb without being told. |

**Do not point a small model at the destructive four.** `break`, `place`, `fill`
and `clear` are gated on op 2 in the mod for a reason. A 3B that transposes two
digits of a coordinate on `clear` removes a 16x16x16 chunk of somebody's base,
and no amount of schema validity prevents that - the JSON is perfectly well
formed, it just means something you did not ask for. `command` is worse again.
Give those a model you would trust with a rollback, or keep them behind a human.

`fill`/`clear` do cap at 4096 blocks and skip anything tagged
`buildinggadgets2:deny`, so the blast radius is bounded - but bounded is not the
same as safe.

**What size does not fix:** a local model will not know what a storage bus or
Insanium farmland is. It can still report exact counts and positions and let you
draw the conclusion - which is most of the value - but do not expect a 7B to
explain your AE2 subnet to you. Domain knowledge is a training-data question,
not a parameter-count one.

## Requirements

- Minecraft 1.21.1, NeoForge
- An AI agent of your choosing, running alongside the game with filesystem access
- Applied Energistics 2 (**optional**) — every AE2 feature is guarded and the mod
  runs fine without it

## Building

```
gradle build
```

That is the whole of it. AE2 is pulled from Maven Central as an **API-only,
compile-only** dependency, which is exactly what that artifact is published for:

```gradle
compileOnly 'org.appliedenergistics:appliedenergistics2:19.2.17:api'
```

Nothing of AE2 is vendored into this repository or bundled into the built jar -
verified, the jar contains zero `appeng` classes. AE2 is never needed at runtime
either; players install it the normal way, and every AE2 call here is guarded by
a `ModList` check so Ghost runs fine without it.

## Status

Working and in daily use on a 613-mod Minecraft 1.21.1 pack. Not yet released to
Modrinth or CurseForge.

## Credits

**[Applied Energistics 2](https://github.com/AppliedEnergistics/Applied-Energistics-2)**
by Team Applied Energistics (LGPL-3.0). Ghost integrates with AE2 through its
published API and ships none of its code. The AE2 integration here exists because
that team publishes a clean, documented API artifact for other mods to build
against — this would have been guesswork otherwise.

## Licence

MIT. See [LICENSE](LICENSE).

Ghost's own code only. AE2 remains under its own licence and is not redistributed
here in any form.
