#!/usr/bin/env python3
"""
Measure which model sizes can actually drive Ghost.

The schema guarantees syntactically valid JSON at any model size - that is the
whole point of constrained decoding, and it is NOT what this measures. What it
measures is the thing the README makes claims about and had never tested:

    can a model of size N pick the RIGHT verb, and read a result back?

Run with the game closed. Nothing here touches Minecraft; it is pure text in and
JSON out, scored against a fixed answer key.

    python bench/run.py qwen2.5:3b qwen2.5:7b

Writes bench/results-<model>.jsonl (every response, for auditing) and prints a
table. Read the jsonl before believing the table.
"""
import json
import os
import sys
import time
import urllib.error
import urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
OLLAMA = os.environ.get("OLLAMA_HOST", "http://localhost:11434")

SYSTEM = (
    "You drive a helper in a Minecraft world by emitting ONE action as JSON. "
    "Choose the single verb that best does what the user asked, and fill in its "
    "arguments. Positions are [x, y, z] arrays. Reply with the JSON object only."
)


def load():
    with open(os.path.join(HERE, "tasks.json"), encoding="utf-8") as f:
        return json.load(f)


def schema():
    # GHOST_SCHEMA lets the same task set run against the flat schema and the
    # per-verb one, which is the whole comparison.
    name = os.environ.get("GHOST_SCHEMA", "actions.schema.json")
    p = os.path.join(HERE, os.pardir, "docs", name)
    with open(p, encoding="utf-8") as f:
        return json.load(f)


def ask(model, messages, fmt, timeout=300):
    """One constrained completion. Returns (parsed_or_None, raw, seconds)."""
    body = json.dumps({
        "model": model,
        "messages": messages,
        "format": fmt,
        "stream": False,
        # Deterministic: this is a measurement, and a benchmark that changes
        # between runs cannot be checked by anyone else.
        "options": {"temperature": 0, "seed": 42},
    }).encode()
    req = urllib.request.Request(OLLAMA + "/api/chat", data=body,
                                 headers={"Content-Type": "application/json"})
    t0 = time.time()
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            raw = json.load(r)["message"]["content"]
    except Exception as e:                       # noqa: BLE001 - report, do not crash
        return None, "ERROR: %s" % e, time.time() - t0
    took = time.time() - t0
    try:
        return json.loads(raw), raw, took
    except json.JSONDecodeError:
        return None, raw, took


def first_action(obj):
    """
    The schema's ROOT is an array - a Ghost request is a LIST of actions, not
    one object. A scorer that read obj["do"] would have marked every model wrong
    for the harness author's misreading, which is the exact failure this whole
    benchmark exists to measure. Unwrap, and score the first action.
    """
    if isinstance(obj, list):
        return obj[0] if obj and isinstance(obj[0], dict) else None
    return obj if isinstance(obj, dict) else None


def verb_of(obj):
    act = first_action(obj)
    return act.get("do") if act else None


def score_one(task, obj):
    """Returns (verb_ok, args_ok, note)."""
    if obj is None:
        return False, False, "unparseable"
    act = first_action(obj)
    if act is None:
        return False, False, "no action object"
    extra = len(obj) - 1 if isinstance(obj, list) else 0
    v = act.get("do")
    verb_ok = v in task["accept"]
    missing = [k for k in task.get("requires", []) if k not in act]
    args_ok = not missing
    note = ""
    if not verb_ok:
        note = "chose %r" % v
    elif missing:
        note = "missing %s" % ",".join(missing)
    # Extra checks for the read-back set: did it actually USE the result?
    if verb_ok and args_ok and "expect_slot" in task:
        if act.get("slot") != task["expect_slot"]:
            args_ok = False
            note = "slot %r, result said %r" % (act.get("slot"), task["expect_slot"])
    if verb_ok and args_ok and "expect_at" in task:
        if list(act.get("at") or []) != task["expect_at"]:
            args_ok = False
            note = "at %r, result said %r" % (act.get("at"), task["expect_at"])
    if verb_ok and args_ok and extra:
        note = "correct, but emitted %d extra action(s)" % extra
    return verb_ok, args_ok, note


def run(model, data, fmt):
    rows = []
    # Schema name in the filename. Without it the per-verb run silently
    # truncated the flat-schema run's raw file - destroying one half of the
    # comparison it existed to make. Caught after it had already happened once.
    tag = os.environ.get("GHOST_SCHEMA", "actions.schema.json")
    tag = "flat" if tag == "actions.schema.json" else "perverb"
    out = os.path.join(HERE, "results-%s-%s.jsonl"
                       % (model.replace(":", "-").replace("/", "-"), tag))
    with open(out, "w", encoding="utf-8") as f:
        for task in data["tasks"]:
            msgs = [{"role": "system", "content": SYSTEM},
                    {"role": "user", "content": task["prompt"]}]
            obj, raw, took = ask(model, msgs, fmt)
            vo, ao, note = score_one(task, obj)
            row = {"id": task["id"], "tier": task["tier"], "kind": "select",
                   "prompt": task["prompt"], "verb_ok": vo, "args_ok": ao,
                   "note": note, "seconds": round(took, 1), "raw": raw}
            f.write(json.dumps(row) + "\n")
            rows.append(row)
            print("  %2d %-6s %s%s" % (task["id"], task["tier"],
                                       "PASS" if vo and ao else "fail",
                                       (" - " + note) if note else ""), flush=True)

        for task in data["readback"]:
            msgs = [
                {"role": "system", "content": SYSTEM},
                {"role": "user", "content": task["prompt"] + "\n\nResult:\n"
                 + json.dumps(task["result"])},
            ]
            obj, raw, took = ask(model, msgs, fmt)
            vo, ao, note = score_one(task, obj)
            row = {"id": task["id"], "tier": "readback", "kind": "readback",
                   "prompt": task["prompt"], "verb_ok": vo, "args_ok": ao,
                   "note": note, "seconds": round(took, 1), "raw": raw}
            f.write(json.dumps(row) + "\n")
            rows.append(row)
            print("  %2d %-8s %s%s" % (task["id"], "readback",
                                       "PASS" if vo and ao else "fail",
                                       (" - " + note) if note else ""), flush=True)
    return rows, out


def summarise(model, rows):
    def pct(sel):
        sel = list(sel)
        if not sel:
            return "-"
        n = sum(1 for r in sel if r["verb_ok"] and r["args_ok"])
        return "%d/%d" % (n, len(sel))

    tiers = ["1-3B", "7-8B", "14B+", "readback"]
    print("\n  %-10s %s" % (model, "  ".join(
        "%s %s" % (t, pct(r for r in rows if r["tier"] == t)) for t in tiers)))
    verb_only = sum(1 for r in rows if r["verb_ok"])
    both = sum(1 for r in rows if r["verb_ok"] and r["args_ok"])
    secs = sum(r["seconds"] for r in rows)
    print("  overall: verb right %d/%d, verb+args right %d/%d, %.0fs total"
          % (verb_only, len(rows), both, len(rows), secs))


def main():
    models = sys.argv[1:]
    if not models:
        print(__doc__)
        return 1
    data, fmt = load(), schema()
    n = len(data["tasks"]) + len(data["readback"])
    print("%d tasks x %d model(s). Constrained decoding is ON, so invalid JSON "
          "is not what is being tested - verb choice is.\n" % (n, len(models)))
    all_rows = {}
    for m in models:
        print("== %s" % m, flush=True)
        rows, out = run(m, data, fmt)
        all_rows[m] = rows
        print("  -> %s" % out)
    print("\n" + "=" * 60)
    for m in models:
        summarise(m, all_rows[m])
    print("\nEvery response is in the jsonl files. A table nobody can audit is "
          "just a claim with better formatting.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
