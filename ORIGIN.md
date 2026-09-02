# Ghost — where it came from

Ghost was not designed. It was excavated, one problem at a time, from a bowling
mod that would not behave.

---

## It started with a strike that came too easily

There was a bowling alley in a modded world, and the bowling was wrong. Spin did
nothing. A ball rolled dead straight down the middle struck every single time.
Something in the physics was off, and there was no way to find out what.

The loop for fixing it went like this. Close the game. Change one number.
Rebuild. Launch — six hundred mods, four minutes. Walk to the lane. Bowl a
frame. Squint. Decide it felt *maybe* slightly better. Close the game.

One sample per shutdown. You cannot find anything that way. You cannot tell
"hooks nicely" from "got lucky twice."

So the question stopped being *what is wrong with the physics* and became
**how do I measure anything at all.**

---

## First it needed to see

A command that threw the same ball a hundred times and counted pins. Then a
command that read the world — not the save file on disk, which is always an
autosave behind, but the live world, right now, mid-tick.

That turned out to be the useful part. A save file tells you what the game
remembered. Reading the running world tells you what is actually true, and the
difference between those two had already cost an evening.

It found things immediately. A farmland namespace that had been silently
breaking every survey. Growth accelerators measured, and proved linear. A farm
that turned out not to be growth-limited at all.

---

## Then it needed hands

Seeing was not enough. A test that needs a human to place seven blocks and reset
a rack is a test that runs once a night.

The obvious way to give a program hands in Minecraft is a bot that connects over
the network, the way every existing tool does it. On a vanilla server that
works. On six hundred mods it does not: the handshake expects a client carrying
the same mods, registry IDs are handed out fresh every boot, and half the
packets belong to mods the bot has never heard of.

The way through was to stop going around. Run **inside** the game instead of
talking to it, and there is no protocol, because there is no gap. A modded block
is not an ID to resolve. It is an object already sitting in memory.

After that it could place, break, use, and run commands. Experiments that had
cost a shutdown each started costing a command and eight minutes of not being
there.

---

## Then it started listening

Chat was easy — it was already there, in the same process. Once every line was
being written down, with who said it and where they stood, the thing had ears.

And once it had ears, it needed a voice, and a voice needs a name.

---

## Shelby

**Ghost** is what it is: something in your world with eyes and hands that is
never actually present. It sees the deck, it moves the pins, it answers when
spoken to — and there is nobody there. It is not a player. It does not stand
anywhere. It visits.

**Shelby** is what it answers to, because "hey ghost" is a strange thing to type
at three in the morning and "hey shelby" is not.

The distinction is deliberate and it is kept honest in the code. Say something to
Shelby and the reply tells you what is actually going to happen — including
*"my bridge is OFF, so I cannot go and look."* Not a performance of
understanding. Nothing is sitting there watching your game. It answers when
something reads the file, and it says so, because a thing that looks like it is
listening and is not is worse than a mailbox that admits what it is.

---

## Then it stood up

For a long time the sentence above was literally true: it did not stand
anywhere. Answers arrived from nowhere and actions happened at coordinates.

That turned out to be the thing it was missing. Not intelligence — a *place*.
Something you can walk up to, talk at, and watch walk away to go and look at what
you asked about. So Shelby got a body: translucent, harmless, unkillable except
on purpose, following you through portals and across dimensions.

The body changed one thing that mattered more than it should have. When the chat
line said "walking over", it now had to be true. The first version could only
follow within thirty-two blocks and had no idea dimensions existed — step through
a portal and it was stranded where it stood, still cheerfully promising it was on
its way. A body makes the promise checkable, and a checkable promise is the only
kind worth making.

It can also be dressed, which is not useful in the slightest and is the single
most requested thing about it.

## Then it learned to spend

Reading a modded world was always the point. Acting on one properly came later,
and Applied Energistics is where the difference shows.

A bot outside the game cannot read an ME network. That data never crosses the
wire in a form you can use — a terminal is a GUI, and the contents are a view
built for a screen. From inside the process it is just an object: ask the network
for its stacks and count them.

Autocrafting went the same way. Not clicking slots in a terminal, but handing
AE2 a real crafting request and waiting for the plan. It plans on a background
thread, which means the answer cannot be given in the same tick it was asked for
— so the request returns immediately and the outcome arrives when it is actually
known. Including the outcome nobody wants to hear: *cannot make 64, short of 12x
Certus Quartz.*

That was also the first thing it could do that costs something. Reading is free
and reversible. Spending a network's stock is neither, so that is where ranks
came in, and where the answer to "will you do this for me" started depending on
who was asking.

## What it turned into

A bowling mod got fixed. Four real bugs, none of them tuning: pins tunnelling
through each other at speed, pins lying down modelled as pins standing up, no
kickback walls for the corner pins, and a test harness that counted the deck
mid-cascade and logged strikes as zeroes.

But the instrument outlived the question. What is left is a way for something
outside a modded Minecraft world to see inside it, act on it, and be spoken to —
on any pack, without a client, without a protocol, without asking anyone to
install a thing.

It exists because the bowling was bad and nobody could prove why.
