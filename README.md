# Ghost

**An in-process bridge that gives an AI agent real access to a *modded* Minecraft world — including mod APIs a protocol-level bot cannot reach.**

> **Status: work in progress, and usable today.** This runs daily on a 618-mod
> 1.21.1 pack and does real work there — it planned and executed a 3,087-item
> crafting tree this week, and audited 3,092 farmland columns to find 38 gaps.
> It is *not* packaged for Modrinth or CurseForge, the API will move without
> ceremony, and every destructive verb should be treated as something to test
> on a copy of your world first. Build it, point an agent at it, read
> [`docs/actions.v2.schema.json`](docs/actions.v2.schema.json), and expect
> sharp edges. See [Status](#status) for exactly what has been verified by
> observation and what has not.

---

# This is not an AI NPC.

Ghost does not think. It ships no model, no API key, and no prompt. It is the
**hands and eyes** — a body in the world and a contract for driving it. The brain
is whatever agent you point at it: Claude Code, a local model, your own script.

If you want a companion that talks to you out of the box, this is the wrong mod.
If you want your own agent to be able to *actually operate your base*, read on.

---

## Running a local model? Start here: [`docs/actions.schema.json`](docs/actions.schema.json)

**Two changes are worth more than four times the model: use
[`docs/actions.v2.schema.json`](docs/actions.v2.schema.json), and put the verb
descriptions in your system prompt.** Measured, `qwen2.5:3b` goes from 7/34 to
30/34 - past a 14B running the setup this repo used to ship.

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

The per-verb schema fixes the wrong-key half outright — argument errors fell to
zero at every size. The wrong-verb half is fixed in the prompt, not the schema:
llama.cpp compiles a schema into a grammar, and a grammar carries no
descriptions, so the documentation in the schema never reaches the model at all.
`drive.py` does both by default. Numbers, method and the raw responses:
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
- **Wears armour you hand them**, and reports its durability when you ask.
- **Gets picked up with Carry On** and carried to a job.

That last one is not a feature anybody wrote. It works because they are an entity
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

### End to end, from nothing

Four commands. No API key, no account, no cloud.

```bash
# 1. a runtime and a model
winget install Ollama.Ollama          # or: brew install ollama
ollama pull qwen2.5:7b

# 2. the mod
#    drop ghost-1.1.0.jar in mods/, start the world, then in chat:
/ghost bridge on

# 3. the driver
python drive.py --ghost "<instance>/ghost" --model qwen2.5:7b
```

Then talk to them in chat. `drive.py` watches `ghost/asks.jsonl`, asks the model
what to do, writes the action, waits for the result, and hands the result back so
the model can answer you. It is one file, no dependencies, ~180 lines - short
enough to read before you trust it with a world.

To try it without the game running at all:

```bash
python drive.py --ghost "<instance>/ghost" --once "how much inferium is in the network?"
```

It defaults to [`docs/actions.v2.schema.json`](docs/actions.v2.schema.json), and
the [measurements below](#what-a-small-model-actually-does---measured) are why.

### Why it fits in a small context


Ghost is unusually small-model friendly, and that is a property of the contract
rather than luck. The agent is handed one situation at a time and answers with
one list of actions - it is not holding a long tool-calling conversation, so the
context never grows the way an agent loop's does.

Measured: the system prompt from `drive.py`, and **527 real results** taken from
one instance's `ghost/outbox.jsonl` rather than estimated.

| what the model holds | tokens |
|---|---|
| system prompt + 34-verb glossary (`drive.py`) | ~630 |
| an incoming question from chat | ~15 |
| a result to interpret - median | **37** |
| the same, 90th percentile | 79 |
| the JSON action it writes back | ~30 |
| **a normal turn, end to end** | **~750** |

So a **2k context window is enough** for ordinary work, and 4k is comfortable.
Results are far smaller than they look: half are under 40 tokens, because most
verbs answer with a number or a short list.

The tail is what needs care, not the median. The largest single result in those
527 was **2,654 tokens** - a `find` across a large area - and `slots` on a full
storage container reached 2,535. A `blockmap` with `nbt` over a whole base is
worse again. Give those a larger-context model, narrow the radius, or filter the
JSON before it reaches the prompt.

An earlier version of this table said ~1,600 tokens for the briefing and
150-650 per result. Both were wrong: the briefing it described has since grown to
~3,700 tokens, and real results are roughly an order of magnitude smaller than
the range quoted. The numbers above come from counting.

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
were never run, and when run they were wrong in the most useful way: **the model
size was barely the variable.**

34 tasks - 30 single-shot requests plus 4 that hand the model a result and ask it
to act on what the result says. Scored on whether the right verb came out *and*
the arguments were usable. Temperature 0, fixed seed; every response is in
`bench/results-*.jsonl`. Method and limits: [`bench/README.md`](bench/README.md).

<!-- BENCH:START - generated by bench/table.py, do not edit by hand -->
| model | schema | verb right | verb + args | 1-3B | 7-8B | 14B+ | readback |
|---|---|---|---|---|---|---|---|
| `llama3.1:8b` | flat (as shipped) | 16/34 | **11/34** | 1/8 | 5/16 | 2/6 | 3/4 |
| `llama3.1:8b` | per-verb | 17/34 | **17/34** | 2/8 | 9/16 | 3/6 | 3/4 |
| `llama3.1:8b` | per-verb + glossary | 33/34 | **33/34** | 8/8 | 16/16 | 6/6 | 3/4 |
| `mistral:7b` | flat (as shipped) | 11/34 | **5/34** | 2/8 | 2/16 | 0/6 | 1/4 |
| `mistral:7b` | per-verb | 11/34 | **11/34** | 2/8 | 6/16 | 1/6 | 2/4 |
| `mistral:7b` | per-verb + glossary | 28/34 | **28/34** | 8/8 | 12/16 | 6/6 | 2/4 |
| `qwen2.5:14b` | flat (as shipped) | 20/34 | **11/34** | 2/8 | 5/16 | 2/6 | 2/4 |
| `qwen2.5:14b` | per-verb | 20/34 | **20/34** | 3/8 | 11/16 | 3/6 | 3/4 |
| `qwen2.5:14b` | per-verb + glossary | 33/34 | **33/34** | 8/8 | 16/16 | 6/6 | 3/4 |
| `qwen2.5:3b` | flat (as shipped) | 17/34 | **7/34** | 0/8 | 4/16 | 2/6 | 1/4 |
| `qwen2.5:3b` | per-verb | 17/34 | **17/34** | 2/8 | 9/16 | 3/6 | 3/4 |
| `qwen2.5:3b` | per-verb + glossary | 30/34 | **30/34** | 8/8 | 13/16 | 6/6 | 3/4 |
| `qwen2.5:7b` | flat (as shipped) | 20/34 | **13/34** | 0/8 | 7/16 | 3/6 | 3/4 |
| `qwen2.5:7b` | per-verb | 20/34 | **19/34** | 2/8 | 11/16 | 4/6 | 2/4 |
| `qwen2.5:7b` | per-verb + glossary | 32/34 | **31/34** | 8/8 | 15/16 | 6/6 | 2/4 |

- `llama3.1:8b`: 11/34 as shipped -> 17/34 with per-verb arguments -> **33/34** with the glossary in the prompt
- `mistral:7b`: 5/34 as shipped -> 11/34 with per-verb arguments -> **28/34** with the glossary in the prompt
- `qwen2.5:14b`: 11/34 as shipped -> 20/34 with per-verb arguments -> **33/34** with the glossary in the prompt
- `qwen2.5:3b`: 7/34 as shipped -> 17/34 with per-verb arguments -> **30/34** with the glossary in the prompt
- `qwen2.5:7b`: 13/34 as shipped -> 19/34 with per-verb arguments -> **31/34** with the glossary in the prompt
<!-- BENCH:END -->

Three columns, one variable each, and the model never changes across a row.

**Every model roughly tripled without being swapped for anything.** Five models,
three families, no exceptions:

- The **weakest model with both fixes** (`mistral:7b`, 28/34) beats the **best
  model without them** (`qwen2.5:7b`, 13/34) more than twice over.
- Argument errors went to **zero on every single model** once arguments were
  bound to their verb.
- Verb choice barely moved with the schema alone - 11 to 11, 16 to 17, 17 to 17,
  20 to 20, 20 to 20 - and then jumped 11-17 points when the glossary reached the
  prompt.
- The lookup tier that scored 0-2/8 as shipped is **8/8 on all five** once fixed.

Read as a sizing guide, the shipped setup made a 14B look barely adequate. Fixed,
a 3B at under 2GB does the job three tasks behind it.

### The two things that were actually broken

**1. The flat schema never bound arguments to verbs.** It is a bare enum of 34
names plus one shared bag of optional properties, `do` the only required field.
So a model that understood the request perfectly still failed:

```
flat      "Break the stone at 44 12 -8"  ->  {"do":"break","block":"[44, 12, -8]"}
per-verb  same request, same model       ->  {"do":"break","at":[44,12,-8]}
```

[`docs/actions.v2.schema.json`](docs/actions.v2.schema.json) gives every verb its
own `oneOf` branch with a `const` discriminator, only its own properties, and
`additionalProperties: false`. The wrong key stops being a mistake and becomes
ungrammatical. Argument errors went to **zero** at every size.

It is generated from `Bridge.java` by `bench/derive_v2.py` rather than written by
hand, because a hand-maintained table drifts and the drift stays invisible until
someone benchmarks it. `oneOf` rather than the more natural `if`/`then`, because
llama.cpp's schema-to-GBNF converter does not support `if`/`then` and would have
quietly produced a weaker grammar.

**2. The schema's descriptions never reached the model.** This is the one worth
carrying somewhere else.

The v2 schema carries a `description` on every verb. The model never saw a single
one. **llama.cpp compiles JSON Schema into a GBNF grammar, and a grammar encodes
structure, not documentation** - descriptions are discarded on the way in. So all
three sizes were choosing among 34 opaque names (`bag`, `worn`, `slots`, `cells`,
`places`) with no glossary, and they failed *the same eleven prompts* regardless
of size. That identical failure set is what gave the finding away: three models
scoring alike is not a plateau, it is three models guessing blind.

Putting the same descriptions in the system prompt - where the model can read
them - is the single largest effect measured here. **If you rely on schema
`description` fields to steer a grammar-constrained model, they are doing
nothing.**

### So how small can you go?

Small. `qwen2.5:3b` at 30/34 is a usable assistant on a home machine, and it is
Q4 at under 2GB. 7B buys one task, 14B three, and both cost several times the
memory and inference time - on the CPU used here, 206s for the 3B against 901s
for the 14B over the same 34 tasks.

Family matters more than size at the low end: `mistral:7b` finishes at 28/34,
below a 3B less than a third its size. But it also gained the most of anyone
(5/34 to 28/34), which is the part that generalises - the fixes are not
compensating for a particular tokenizer, they are removing a tax every model was
paying.

The honest headline is not a size recommendation at all: **the number you measure
says more about your schema and prompt than about the model.** Anyone
benchmarking small models for tool-calling should suspect their harness before
their model, because we shipped a harness that made a 14B look barely adequate.

What is left at the top is genuinely the model: the last few failures are verb
choices a person could argue about (`wait` for `waitFor`, `put` for `deposit`)
and one read-back task that needs arithmetic on a result.

Two caveats, kept in view: five models across three families is enough to show a
pattern but not to rank anything, and 34 tasks means a single task is three
points. This is enough to falsify a wrong claim, which is what it was built
for. It is not enough to rank models, and nobody should use it that way.

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
and meant they could not mine any of the 400 ores that tag covers.

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

**It refuses to arm on an item the network does not currently hold.** Found the
useful way: inferium essence reads 0 in the base it was written for, at all
times, because the system converts it upward the moment it arrives. A monitor on
that number would have alerted inside the hour on a perfectly healthy garden.
Watch what the thing *becomes*, not what passes through - the number that only
goes up.

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

Working and in daily use on a 618-mod Minecraft 1.21.1 pack. Not yet released to
Modrinth or CurseForge, and the verb contract is still free to change.

### Multiplayer: one Shelby per player, and NOT yet tested on a server

Every hour Ghost has ever run has been single-player. Treat multiplayer as
unverified — not "probably fine", **untested**.

One rule is now enforced rather than assumed: **a body belongs to the player who
stood it up.** Ownership is the `Follow` UUID the body already persisted, and
`/ghost body here` will only ever replace one of *your* bodies. It will not touch
anyone else's, and it no longer discards extra bodies at all.

That last part was a real bug, not a precaution. `body here` used to clear every
body in every dimension while carrying the kit from whichever it found first —
so a second body somewhere else had its armour and satchel **deleted silently**,
because `discard()` never fires `setGuaranteedDrop` and nothing lands on the
floor. With one body that is merely blunt. With two it destroys gear, and on a
server it would have been one player's command stripping another player's
Shelby. Now the extras are left standing and reported in the response.

What is still single-driver, and the reason a server needs real testing before
anyone trusts it:

- **The bridge is a singleton pipeline.** One inbox directory, and `pendingGo` /
  `pendingAct` / `pendingWait` are static — *one* action in flight for the whole
  server. Two players driving two bodies contend for one slot.
- **Most verbs still call `Bodies.find(server)`**, which returns the first live
  body in level-iteration order rather than the caller's. Ownership exists; the
  verbs do not consult it yet.
- **`anchor()` takes position from the body and dimension from the player.**
  They agree today because both resolve to the same place. They are not
  guaranteed to.

Per-player request queues are the next piece of work. Until then, run one driver.

### Crafting a whole tree, and the dry run that paid for itself

`craft` no longer stops one recipe down. Asked for something the network cannot
pay for directly, it resolves the entire tree to items the network actually
holds **before anything moves** - through a reservation ledger, so two branches
cannot both spend the same diamonds, and a cycle guard, because Mystical
Agriculture converts essence in *both* directions and a naive planner descends
forever.

Verified at scale: **3,087 Inferium Growth Accelerators in 37 steps**, walking
Insanium down five tiers to Inferium and diamonds out to Prosperity Gemstones.
Measured against a before/after reading of the network, it consumed exactly what
it predicted - 4,116 stone, 2,058 diamonds, 6 Insanium - and touched nothing
else.

The `check: true` dry run is not decoration. The same craft attempted an hour
earlier, when the network was 1,720 stone short, came back `ok` - and the step
list showed it intended to cover the gap by *manufacturing diorite, granite and
andesite*, because the recipe's stone slot is the `#c:stones` tag and those are
cobblestone plus **nether quartz**. It would have quietly eaten a quartz supply
to make decorative rock. Nothing had moved, because nothing was asked to move
yet. Read the step list, not just the `ok`.

That is the general lesson this bridge keeps relearning: an answer that is
*true* can still be the wrong thing to act on. See also the `undo` restore-order
bug below, and `report measured, not intended` throughout.

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

**Verified against a live world since:** the cross-dimension warp (real crossing,
arrival confirmed by a follow-up `where` rather than asserted), and the whole
local-model loop end to end - `drive.py` driving `llama3.1:8b` on a home CPU,
through a real request, a real result, and an answer spoken in chat.

That first live run immediately found a bug 510 scored benchmark responses had
missed: the model emitted `dim` as `"0"`, because `dim` was typed as a bare
string and the description explaining the real format never reaches a
grammar-constrained model. None of the 34 benchmark tasks names a dimension, so
the argument was never exercised. A green benchmark is not coverage.

**Not yet exercised:** `/ghost produce` end to end (armed and disarmed, never
left running long enough to alert), and the deadman check - whose two fixes have
themselves never executed.

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
