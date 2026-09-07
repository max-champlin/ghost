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
        if base.endswith("-flat"):
            model, schema = base[:-5], "flat"
        elif base.endswith("-perverb"):
            model, schema = base[:-8], "per-verb"
        else:
            continue
        rows = [json.loads(l) for l in open(path, encoding="utf-8") if l.strip()]
        if rows:
            out[(model.replace("qwen2.5-", "qwen2.5:"), schema)] = rows
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
        for s in ("flat", "per-verb"):
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
        f, p = data.get((m, "flat")), data.get((m, "per-verb"))
        if not (f and p):
            continue
        fo, n = tally(f)
        po, _ = tally(p)
        fv = sum(1 for r in f if r["verb_ok"])
        pv = sum(1 for r in p if r["verb_ok"])
        print("%s: complete actions %d/%d -> %d/%d; verb choice %d -> %d "
              "(argument errors %d -> %d)"
              % (m, fo, n, po, n, fv, pv, fv - fo, pv - po))


if __name__ == "__main__":
    main()
