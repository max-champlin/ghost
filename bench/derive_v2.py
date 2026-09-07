#!/usr/bin/env python3
"""
Build a per-verb schema from Bridge.java, and check the result by measurement.

The shipped schema is a bare enum of 34 verb names plus one flat bag of 31
optional properties, with only `do` required. Nothing in it says which arguments
belong to which verb, or what any verb does. Constrained decoding therefore
guarantees only that the JSON parses - and JSON parsing was never the hard part.

Measured on qwen2.5:3b: right verb 17/34, right verb AND arguments 7/34. The
failures are not confusion about the request, they are the model putting good
values in the wrong keys:

    "Break the stone at 44 12 -8" -> {"do":"break","block":"[44, 12, -8]"}
    "Go to the garden"            -> {"do":"goto","to":[2,64,3]}

A `oneOf` over per-verb objects makes those literally ungrammatical: with the
discriminator pinned by `const`, the decoder cannot emit `block` inside a
`break`. `if`/`then` would express the same thing more compactly and is NOT
supported by llama.cpp's schema-to-GBNF converter, which is what Ollama uses -
so `oneOf` it is.

Per-verb arguments are PARSED OUT OF Bridge.java rather than hand-listed, for
the same reason checkVerbs exists: a hand-written table drifts, and the drift is
invisible until someone benchmarks it.
"""
import collections
import json
import os
import re

HERE = os.path.dirname(os.path.abspath(__file__))
BRIDGE = os.path.join(HERE, os.pardir, "src", "main", "java", "ghost", "Bridge.java")
BASE = os.path.join(HERE, os.pardir, "docs", "actions.schema.json")
OUT = os.path.join(HERE, os.pardir, "docs", "actions.v2.schema.json")

# What each verb is FOR. The bare enum told the model nothing; these are the
# descriptions that go next to each branch so it can choose between them.
WHAT = {
    "say": "Speak a line in chat.",
    "scan": "Summarise the blocks in a region as counts.",
    "blockmap": "A detailed block census of a region.",
    "find": "Locate the nearest block of a given kind.",
    "read": "Report the single block at one position.",
    "entities": "Report mobs, players and dropped items nearby.",
    "have": "Count how much of an item is nearby or in the ME network.",
    "craft": "Ask the ME network to craft an item.",
    "where": "Report her own position, dimension and what she is doing.",
    "goto": "Travel to a position, optionally teleporting.",
    "post": "Station her at a position until released.",
    "return": "Release a post and stop waiting there.",
    "break": "Break the block at one position.",
    "place": "Place a block at one position.",
    "use": "Right-click the block at one position.",
    "command": "Run a server command.",
    "wait": "Pause for a number of ticks.",
    "waitFor": "Pause until a condition holds.",
    "remember": "Save a named place.",
    "forget": "Delete a named place.",
    "places": "List every remembered place.",
    "cells": "Report ME storage cell usage.",
    "slots": "List the contents of a container, slot by slot.",
    "bag": "List what is in her satchel.",
    "worn": "Report the armour she is wearing.",
    "take": "Move items from a container slot into her satchel.",
    "put": "Move items from her satchel into a container slot.",
    "fill": "Fill a box with one kind of block.",
    "clear": "Empty a box of blocks.",
    "withdraw": "Pull items out of the ME network into her satchel.",
    "deposit": "Push items from her satchel into the ME network.",
    "crouch": "Crouch for a moment.",
    "jump": "Jump.",
    "undo": "Take back the last destructive action.",
}

# Arguments every verb may carry, so they are not repeated 34 times.
UNIVERSAL = ["dim", "go", "as"]


def case_blocks(src):
    """Map each verb to the source text of its own case arm."""
    starts = []
    for m in re.finditer(r'^\s*case\s+("[^"]+"(?:\s*,\s*"[^"]+")*)\s*->', src, re.M):
        verbs = re.findall(r'"([^"]+)"', m.group(1))
        starts.append((m.start(), verbs))
    blocks = {}
    for i, (pos, verbs) in enumerate(starts):
        end = starts[i + 1][0] if i + 1 < len(starts) else len(src)
        for v in verbs:
            blocks[v] = src[pos:end]
    return blocks


def main():
    src = open(BRIDGE, encoding="utf-8").read()
    base = json.load(open(BASE, encoding="utf-8"))
    props = base["$defs"]["action"]["properties"]
    verbs = base["$defs"]["action"]["properties"]["do"]["enum"]
    blocks = case_blocks(src)

    branches = []
    report = []
    for v in verbs:
        body = blocks.get(v, "")
        # Two ways an argument is read. Missing the second one made `at`,
        # `from` and `to` vanish from every verb that uses them - positions go
        # through the pos(a, "at") helper, not a.get("at").
        used = set()
        for m in re.finditer(
                r'a\.(?:has|get|getAsJsonArray|getAsJsonObject)\("([A-Za-z_]+)"\)'
                r'|pos\(\s*a\s*,\s*"([A-Za-z_]+)"\)', body):
            used.add(m.group(1) or m.group(2))
        used = {u for u in used if not u.startswith("_") and u in props}
        # Required = read WITHOUT a has() guard and without a ternary default.
        required = sorted(
            k for k in used
            if (re.search(r'a\.get\("%s"\)' % k, body)
                or re.search(r'pos\(\s*a\s*,\s*"%s"\)' % k, body))
            and not re.search(r'a\.has\("%s"\)' % k, body))
        allowed = sorted(used | set(UNIVERSAL))
        branch = collections.OrderedDict()
        branch["title"] = v
        branch["description"] = WHAT.get(v, "")
        branch["type"] = "object"
        p = collections.OrderedDict()
        p["do"] = {"const": v}
        for k in allowed:
            p[k] = props[k]
        branch["properties"] = p
        branch["required"] = ["do"] + required
        branch["additionalProperties"] = False
        branches.append(branch)
        report.append((v, required, [a for a in allowed if a not in UNIVERSAL]))

    out = collections.OrderedDict()
    out["$schema"] = base["$schema"]
    out["title"] = "Ghost inbox (per-verb)"
    out["description"] = (
        "Same contract as actions.schema.json, but every verb carries only its "
        "own arguments. The flat version guaranteed the JSON parsed and left the "
        "model free to put a position in `block` or a destination in `to`; this "
        "makes those ungrammatical. oneOf rather than if/then because llama.cpp's "
        "schema-to-GBNF converter, which Ollama uses, does not support if/then.")
    out["type"] = "array"
    out["minItems"] = 1
    out["maxItems"] = base.get("maxItems", 256)
    out["items"] = {"$ref": "#/$defs/action"}
    out["$defs"] = {"pos": base["$defs"]["pos"],
                    "action": {"oneOf": branches}}
    json.dump(out, open(OUT, "w", encoding="utf-8", newline="\n"),
              indent=1, ensure_ascii=False)

    print("wrote %s" % os.path.relpath(OUT))
    print("%d verbs, %d branches\n" % (len(verbs), len(branches)))
    print("%-10s %-22s %s" % ("verb", "required", "optional"))
    for v, req, opt in report:
        print("%-10s %-22s %s" % (v, ",".join(req) or "-", ",".join(opt) or "-"))


if __name__ == "__main__":
    main()
