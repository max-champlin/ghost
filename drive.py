#!/usr/bin/env python3
"""
Drive Ghost with a local model. No API key, no cloud, no account.

The mod ships no model and no driver: it reads `ghost/in/<id>.json` and writes
`ghost/out/<id>.json`, and something outside decides what to put in those files.
This is that something, in one file with no dependencies beyond a Python that
can open a URL.

    ollama pull qwen2.5:7b
    python drive.py --ghost "<instance>/ghost" --model qwen2.5:7b

Then talk to Shelby in chat. Questions addressed to her land in `asks.jsonl`;
this watches that file, asks the model what to do, sends the action, waits for
the result, and hands the result back to the model to answer in chat.

One-shot, without touching the game:

    python drive.py --ghost ... --once "how much inferium is in the network?"

Defaults chosen from measurement, not taste: the per-verb schema, and the verb
glossary in the system prompt. Together those took qwen2.5:3b from 7/34 to 30/34
on the same tasks - past a 14B running the setup this repo used to ship. See
bench/ for the numbers and the raw responses.
"""
import argparse
import json
import os
import sys
import time
import urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))

SYSTEM = """You are Shelby, a helper standing in a modded Minecraft world.

You act by emitting JSON actions - an array, usually of one. Choose the single
verb that does what was asked and fill in only that verb's arguments.

Rules that matter:
- Positions are [x, y, z] integer arrays.
- If you do not know a coordinate, do not invent one. Use a remembered place
  name, or ask with `say`.
- After a result comes back, ANSWER THE PERSON with `say`. A result nobody is
  told about is not an answer.
- Prefer one action at a time. You will see the result before the next step."""


def glossary(schema):
    """
    The verb list as prose, for the system prompt.

    The schema carries a description on every verb and the model never sees one:
    llama.cpp compiles JSON Schema into a GBNF grammar, and a grammar encodes
    structure, not documentation. Measured, moving these same descriptions into
    the prompt took qwen2.5:3b from 17/34 to 30/34 - the single largest effect in
    bench/. Without it the model is choosing among 34 opaque names.
    """
    branches = schema.get("$defs", {}).get("action", {}).get("oneOf")
    if not branches:
        return ""                      # flat schema: nothing per-verb to say
    lines = ["", "The verbs, and what each one is for:"]
    for b in branches:
        req = [r for r in b.get("required", []) if r != "do"]
        lines.append("  %-9s %s%s" % (
            b.get("title", "?"), b.get("description", ""),
            (" (needs %s)" % ", ".join(req)) if req else ""))
    return chr(10).join(lines)


def post(url, payload, timeout=600):
    req = urllib.request.Request(
        url, data=json.dumps(payload).encode(),
        headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=timeout) as r:
        return json.load(r)


def think(host, model, schema, messages):
    """One constrained completion -> a list of actions."""
    out = post(host + "/api/chat", {
        "model": model, "messages": messages, "format": schema,
        "stream": False, "options": {"temperature": 0},
    })
    raw = out["message"]["content"]
    try:
        acts = json.loads(raw)
    except json.JSONDecodeError:
        # With a schema attached this effectively does not happen; if it does,
        # say so rather than silently skipping a turn.
        print("  ! model emitted unparseable JSON: %s" % raw[:200])
        return []
    return acts if isinstance(acts, list) else [acts]


def send(ghost, actions, timeout=120):
    """Write a request, wait for its own answer file, return the results."""
    rid = "drive-%d" % int(time.time() * 1000)
    indir, outdir = os.path.join(ghost, "in"), os.path.join(ghost, "out")
    os.makedirs(indir, exist_ok=True)
    tmp = os.path.join(indir, rid + ".tmp")
    with open(tmp, "w", encoding="utf-8") as f:
        json.dump(actions, f)
    # Rename into place so the mod never reads a half-written file.
    os.replace(tmp, os.path.join(indir, rid + ".json"))

    answer = os.path.join(outdir, rid + ".json")
    deadline = time.time() + timeout
    while time.time() < deadline:
        if os.path.exists(answer):
            for _ in range(10):                  # let the write settle
                try:
                    with open(answer, encoding="utf-8") as f:
                        return json.load(f)
                except (json.JSONDecodeError, OSError):
                    time.sleep(0.1)
        time.sleep(0.2)
    return {"error": "no answer within %ds - is the game running and the "
                     "bridge on? /ghost bridge on" % timeout}


def turn(host, model, schema, ghost, request, steps=4):
    """One request, carried through until the model has answered."""
    messages = [{"role": "system", "content": SYSTEM + glossary(schema)},
                {"role": "user", "content": request}]
    for step in range(steps):
        actions = think(host, model, schema, messages)
        if not actions:
            return
        verbs = ", ".join(a.get("do", "?") for a in actions)
        print("  -> %s" % verbs)
        result = send(ghost, actions)
        print("  <- %s" % json.dumps(result)[:220])
        # `say` is the model telling the person something; nothing follows it.
        if all(a.get("do") == "say" for a in actions):
            return
        messages.append({"role": "assistant", "content": json.dumps(actions)})
        messages.append({"role": "user",
                         "content": "Result:\n" + json.dumps(result)
                         + "\n\nNow answer the person with `say`, or take the "
                           "next single action if one is genuinely needed."})
    print("  ! gave up after %d steps" % steps)


def watch(host, model, schema, ghost):
    """Follow asks.jsonl and answer each new question."""
    path = os.path.join(ghost, "asks.jsonl")
    print("watching %s (ctrl-c to stop)" % path)
    # Start at the END - answering an evening of backlog on startup is rude.
    pos = os.path.getsize(path) if os.path.exists(path) else 0
    while True:
        try:
            if os.path.exists(path) and os.path.getsize(path) > pos:
                with open(path, encoding="utf-8") as f:
                    f.seek(pos)
                    for line in f:
                        line = line.strip()
                        if not line:
                            continue
                        try:
                            ask = json.loads(line)
                        except json.JSONDecodeError:
                            continue
                        who = ask.get("player", "someone")
                        text = ask.get("text", "")
                        print("\n%s: %s" % (who, text))
                        turn(host, model, schema, ghost,
                             "%s asked: %s" % (who, text))
                    pos = f.tell()
            time.sleep(1.0)
        except KeyboardInterrupt:
            print("\nstopped")
            return


def main():
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--ghost", required=True,
                    help="the instance's ghost/ directory")
    ap.add_argument("--model", default="qwen2.5:7b")
    ap.add_argument("--host", default=os.environ.get("OLLAMA_HOST",
                                                     "http://localhost:11434"))
    ap.add_argument("--schema", default=os.path.join(HERE, "docs",
                                                     "actions.v2.schema.json"),
                    help="per-verb schema by default; see the README for why")
    ap.add_argument("--once", metavar="REQUEST",
                    help="handle one request and exit")
    a = ap.parse_args()

    if not os.path.isdir(a.ghost):
        print("no such directory: %s" % a.ghost)
        return 1
    with open(a.schema, encoding="utf-8") as f:
        schema = json.load(f)

    if a.once:
        turn(a.host, a.model, schema, a.ghost, a.once)
    else:
        watch(a.host, a.model, schema, a.ghost)
    return 0


if __name__ == "__main__":
    sys.exit(main())
