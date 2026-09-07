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

**Use [`docs/actions.v2.schema.json`](docs/actions.v2.schema.json), not the flat
one. It is worth more than doubling your model.**

Ghost is driven by JSON, and constrained decoding means the sampler is not
*asked* to produce a valid action, it is made **incapable** of producing anything
else. That much was always true. What this README used to claim beyond it was
not: that malformed JSON is the thing small models fail at.

Measured, it is not even close. Across 34 tasks neither qwen2.5:3b nor 7b emitted
a single unparseable response — that failure mode was gone the moment a schema
was attached. They failed in two other places entirely: choosing the wrong verb,
and putting the right value in the wrong key. The flat schema does nothing about
either, because it is a bare list of verb names plus one shared bag of optional
properties, with no statement of which argument belongs to which verb.

The per-verb schema fixes the second one outright — argument errors fell from 10
to 0 on the 3B — and a 3B using it beats a 7B using the flat one. Numbers, method
and the raw responses are below in
[What a small model actually does](#what-a-small-model-actually-does---measured).

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

Both schemas cover all 34 verbs and the position format, so no model can invent
a verb that does not exist. Only the **v2** schema states which fields belong to
which action — the flat one lists every argument as optional on every verb, which
is exactly why a model that had understood the request still put a position in
`block`. With v2 the model also cannot ask for `craft` without an item.

Sizing, prompt shape, and which verbs need more model than others are further
down in [Running it on a local model](#running-it-on-a-local-model).

---

## What it looks like in practice

Everything below has been done, watched happening, and measured — not designed
and hoped for:

- **Rode a modded elevator** and landed on the same block the mod sends a player
  to. Elevator ID computes its destination entirely client-side, so there is no
  server call to make — the search is reimplemented and the destination checked
  against a real player's ride.
- **Pulled items out of an ME network** into a satchel, carried them, and put
  them back. Not by driving a terminal GUI slot by slot — through AE2's storage
  API.
- **Followed a player into another dimension**, and crossed on request.
- **Wears armour you hand her**, and reports its durability when you ask.
- **Gets picked up with Carry On** and carried to a job.

That last one is not a feature anybody wrote. It works because she is an entity
in the world rather than a client pretending to be one, and that difference is
the whole point.

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

### What a small model actually does - measured

The tiers here used to be reasoned from what each step seemed to demand. They
were never run, and when finally run they were **wrong**. Replaced with numbers.

34 tasks: 30 single-shot requests plus 4 that hand the model a result and ask it
to act on what the result says. Scored on whether the right verb came out *and*
the arguments were usable. Temperature 0, fixed seed; every response is in
`bench/results-*.jsonl` so the table can be checked rather than believed. Method
and limits: [`bench/README.md`](bench/README.md).

| model | schema | verb right | verb + args | 1-3B | 7-8B | 14B+ | readback |
|---|---|---|---|---|---|---|---|
| `qwen2.5:3b` | flat | 17/34 | **7/34** | 0/8 | 4/16 | 2/6 | 1/4 |
| `qwen2.5:3b` | per-verb | 17/34 | **17/34** | 2/8 | 9/16 | 3/6 | 3/4 |
| `qwen2.5:7b` | flat | 20/34 | **13/34** | 0/8 | 7/16 | 3/6 | 3/4 |
| `qwen2.5:7b` | per-verb | 20/34 | **19/34** | 2/8 | 11/16 | 4/6 | 2/4 |

- qwen2.5:3b: complete actions 7/34 -> 17/34; verb choice 17 -> 17 (argument errors 10 -> 0)
- qwen2.5:7b: complete actions 13/34 -> 19/34; verb choice 20 -> 20 (argument errors 7 -> 1)

**Read the middle two columns together.** Verb choice is *identical* within each
model across the two schemas - 17 and 17, 20 and 20. The schema changed nothing
about what the model decides. What it changed is whether the model can express
that decision: argument errors went 10 to 0 on the 3B, and 7 to 1 on the 7B.

Which makes the honest headline not "you need a 7B" but **"the schema was doing a
third of the job it claimed."** A 3B on the per-verb schema (17/34) beats a 7B on
the flat one (13/34). Fixing the artifact bought more than doubling the model.

The failure it removes looks like this - right verb, right values, wrong keys:

```
flat      "Break the stone at 44 12 -8"  ->  {"do":"break","block":"[44, 12, -8]"}
per-verb  same request, same model       ->  {"do":"break","at":[44,12,-8]}
```

[`docs/actions.v2.schema.json`](docs/actions.v2.schema.json) gives every verb its
own `oneOf` branch with a `const` discriminator, only its own properties,
`additionalProperties: false`, and a description of what it is for. The wrong key
stops being a mistake and becomes ungrammatical. It is generated from
`Bridge.java` by `bench/derive_v2.py` rather than hand-written, because a
hand-maintained table drifts and the drift stays invisible until someone
benchmarks it.

`oneOf` rather than the more natural `if`/`then`, because llama.cpp's
schema-to-GBNF converter - what Ollama uses - does not support `if`/`then` and
would have quietly produced a weaker grammar.

### What is left is verb choice, and that is where size shows

With the per-verb schema the 3B has **zero** argument failures. All 17 remaining
misses are the wrong verb:

```
"What armour are you wearing?"  ->  entities
"What is in your satchel?"      ->  use
"Where are you right now?"      ->  find
```

No schema fixes that. Two of the seventeen are near-misses between adjacent verbs
(`wait` for `waitFor`, `put` for `deposit`), so it is not seventeen wild guesses -
but choosing correctly among 34 verbs is the real capability floor. Not emitting
JSON, and not filling arguments.

**So: 7B is a sane starting point, and a 3B is genuinely usable for narrow,
templated work** - roughly where the old guess landed, for entirely the wrong
reasons. The part that was flatly wrong is the claim that a 1-3B handles simple
lookups: both models scored 0/8 on that tier against the shipped schema, and only
2/8 with the fixed one.

Two models of one family, 34 tasks, single-turn, CPU inference. Enough to
falsify a wrong claim, which is what it was built for. Not enough to rank models,
and nobody should use it that way.

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

## Design: report and undo, do not refuse

Destructive verbs do **not** carry a can't-touch list. An earlier version
honoured `buildinggadgets2:deny` on single-block `break`, which sounded prudent
and meant she could not mine any of the 400 ores that tag covers.

The reasoning that replaced it: that tag exists for **area tools** — a
Destruction Gadget sweeps a room and cannot be reasoned with, so a blacklist is
right. A single block someone typed a coordinate for is aimed at by definition.
You do not drop a pin for a lawnmower and then have it refuse the grass.

So instead:

- `break` and `place` do the job, and **announce in chat** when the block was
  something normally protected. A wrong instruction becomes visible rather than
  silently prevented.
- **`undo` takes back the last action** — blocks and their contents, so undoing
  a broken chest returns the chest and what was in it.
- `fill` and `clear` **do** still skip protected blocks. Those are the area case,
  and nobody aims at each block in a 4096-block sweep.

`undo` is deliberately **one step**. Not a stack, not a timeline, and it does not
recall what the original action dropped. A mulligan, not a time machine —
consequences stay real, they just stop being permanent.

## Knowing when the base stops

`/ghost produce <item> [radius] [everyMinutes] [quietMinutes]`

Watches how much of one item has reached the ME network and says something when
that number stops going up. Crops, golems, chests, pipes and machines all exist
to move that total, so a flatline catches a break anywhere in the chain -
including the parts nobody thought to instrument.

```
/ghost produce mysticalagriculture:inferium_essence 32 10 45
```

Sample every 10 minutes; if 45 minutes pass with no increase, alert. Defaults are
radius 16, every 10, quiet 45. `/ghost produce off` stops it; `/ghost status`
shows it.

It alerts on the **transition**, not every sample, and says so again when the
count recovers - an alert with no all-clear teaches you to ignore the next one.

**What it cannot tell you**, stated because a monitor that overclaims is worse
than none: this reads a total, not a production rate. A flat count means
production stopped **or** you are consuming faster than you produce, and it
cannot separate those. The alert says exactly that. It is built to make you look,
not to be believed.

### Getting the alert off the machine

**The mod sends nothing itself.** No webhook, no SMTP, no API key in a config
file for someone to leak. It appends one line to `ghost/alerts.jsonl`:

```json
{"at":"2026-09-07T08:20:11-04:00","alert":"flatline",
 "item":"mysticalagriculture:inferium_essence","count":184320,"peak":184320,
 "quietMinutes":47,"dimension":"minecraft:overworld","radius":32,
 "meaning":"no increase seen - production stopped, OR consumption exceeds it."}
```

Delivery is whatever tails that file, and the choice is yours:

- **A Claude session** driving the bridge already watches this directory. It can
  push to your phone through your own account, with no credentials configured
  anywhere. This is how the author runs it. The catch: it only fires while a
  session or scheduled task is alive to see the line.
- **[ntfy.sh](https://ntfy.sh)** - no account, no credentials. Pick a long random
  topic, install the app, and tail the file:
  `tail -F ghost/alerts.jsonl | while read l; do curl -d "$l" ntfy.sh/<topic>; done`.
  Note the topic is readable by anyone who guesses it, so keep coordinates out of
  what you send.
- **A Discord webhook** - richer, and scrollable history. The URL is a shared
  secret; keep it out of the repo.
- **Anything else that reads a file.** That is the entire interface.

Deliberately not built in: outbound network calls from the mod. A blocking send
on the server thread stalls the tick, and a monitoring feature that costs you TPS
is a bad trade. Doing it outside keeps the game loop clean and your secrets out
of a Minecraft config.

## Status

Working and in daily use on a 613-mod Minecraft 1.21.1 pack. Not yet released to
Modrinth or CurseForge.

**Verified by observation**, with a second agent session driving the bridge and a
player watching: reads, travel and the action delay, the satchel round trip,
`withdraw`/`deposit`, `goto`/`warp`, `crouch`/`jump`, the elevator ride
(destination compared against a real player's own), `fill` on both its
`onlyAir` branches, `clear`, and the break-for-drops path shared by `break`,
`place` and `fill`. None of those were accepted on the returned counts - each
was confirmed by reading the blocks and the dropped items back afterwards.

That discipline earned its keep. Two drop tests appeared to fail and did not:
the items had fallen down an open shaft left by an earlier `clear`, and the
entity scan's default radius could not see that far. The verbs were correct and
the instrument was too small - which is exactly the confusion an ambiguous
result creates, arriving this time in the test rather than the code.

`undo` has now been run too: its peek (`check: true`) reads without consuming,
a second call honestly reports nothing left rather than inventing a success, a
broken chest comes back **with its contents intact**, and breaking a block on
the protection list goes through and says so in chat.

The test that mattered was the awkward one. A cleared volume with a torch, a
lever and a redstone torch standing in it came back reporting `restored: 10`
with two of the three attachments missing. `undo` was not at fault - it restored
every position it had been given. `clear` had never given it those two:
`BlockPos.betweenClosed` walks y after x, so a support block is destroyed before
whatever stands on it, and the torch above pops before the walk ever reaches its
position. It was then counted as "already air". The number that looked routine
was the loss. Fixed by snapshotting the whole box up front, and by reporting
collateral as `collapsed` instead of folding it into `alreadyAir` - **fix not
yet re-tested**.

Worth recording which half of the process found what: three of the four real
bugs here were found by reading the code, and this one only ever surfaced under
a test built to be inconvenient. A chest alone passes it, and did.

**Not yet exercised:** the cross-dimension warp, and the deadman check - whose
two fixes have themselves never executed.

Worth knowing what shook out of that testing, because it is the honest shape of
the project rather than the marketing: **eleven separate cases where a result
meant two things at once** — a teleport reporting success it had not caused, an
exception treated as proof nothing happened when the work had already landed, a
verb returning PASS because the block was never asked, an elevator reporting
nothing both for "not on one" and "on one with nowhere to go". Every one was
found by somebody refusing to accept a green result. Results now report what was
*measured* rather than what was attempted, and name which outcome occurred — but
that is a habit the codebase had to be taught, and new verbs will need the same
scrutiny.

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
