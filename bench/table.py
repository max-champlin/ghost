#!/usr/bin/env python3
"""
Build the README's results table from the result files.

Typing numbers out of a console into a document is where a wrong number gets in,
and a wrong number in the section about not trusting unverified claims would be
its own kind of joke. This reads the jsonl and prints the markdown.

    python bench/table.py
"""
import glob
import json
import os

HERE = os.path.dirname(os.path.abspath(__file__))
TIERS = ["1-3B", "7-8B", "14B+", "readback"]


def load():
    out = {}
    for path in sorted(glob.glob(os.path.join(HERE, "results-*.jsonl"))):
        base = os.path.basename(path)[len("results-"):-len(".jsonl")]
        for suffix, label in (("-perverb-glossary", "per-verb + glossary"),
                              ("-perverb", "per-verb"),
                              ("-flat", "flat (as shipped)")):
            if base.endswith(suffix):
                model, schema = base[:-len(suffix)], label
                break
        else:
            continue
        rows = [json.loads(l) for l in open(path, encoding="utf-8") if l.strip()]
        if rows:
            # run.py writes ':' as '-' in filenames; put it back on the LAST
            # hyphen so llama3.1-8b and mistral-7b come back correctly too,
            # rather than only the family this was first written against.
            name = model.rsplit("-", 1)
            out[(":".join(name) if len(name) == 2 else model, schema)] = rows
    return out


def tally(rows, tier=None):
    sel = [r for r in rows if tier is None or r["tier"] == tier]
    return sum(1 for r in sel if r["verb_ok"] and r["args_ok"]), len(sel)


def main():
    data = load()
    if not data:
        print("no results yet")
        return
    models = sorted({m for m, _ in data})

    print("| model | schema | verb right | verb + args | " +
          " | ".join(TIERS) + " |")
    print("|---|---|---|---|" + "---|" * len(TIERS))
    for m in models:
        for s in ("flat (as shipped)", "per-verb", "per-verb + glossary"):
            rows = data.get((m, s))
            if not rows:
                continue
            ok, n = tally(rows)
            verb = sum(1 for r in rows if r["verb_ok"])
            cells = " | ".join("%d/%d" % tally(rows, t) for t in TIERS)
            print("| `%s` | %s | %d/%d | **%d/%d** | %s |" %
                  (m, s, verb, n, ok, n, cells))

    print()
    for m in models:
        f = data.get((m, "flat (as shipped)"))
        p = data.get((m, "per-verb"))
        g = data.get((m, "per-verb + glossary"))
        if not (f and p and g):
            continue
        fo, n = tally(f)
        po, _ = tally(p)
        go, _ = tally(g)
        print("- `%s`: %d/%d as shipped -> %d/%d with per-verb arguments -> "
              "**%d/%d** with the glossary in the prompt" % (m, fo, n, po, n, go, n))


if __name__ == "__main__":
    main()
