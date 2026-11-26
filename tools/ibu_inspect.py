#!/usr/bin/env python3
"""
Lightweight debug log inspector for Ibuprobridging (.jsonl.gz or .jsonl).
Usage:
  python3 tools/ibu_inspect.py <path-to-log.jsonl.gz> [--events] [--channels] [--slow]
"""

import gzip
import json
import sys
from collections import Counter
from pathlib import Path


def open_log(path: Path):
    if path.suffix == ".gz":
        return gzip.open(path, "rt", encoding="utf-8", errors="replace")
    return path.open("r", encoding="utf-8", errors="replace")


def main() -> int:
    if len(sys.argv) < 2:
        print("Usage: python3 tools/ibu_inspect.py <log.jsonl.gz> [--all]")
        return 1

    path = Path(sys.argv[1])
    show_all = "--all" in sys.argv[2:]
    if not path.exists():
        print(f"File not found: {path}")
        return 1

    counts = Counter()
    events = []
    channels = []
    skills = []
    slow_ticks = []
    first_tick = None
    last_tick = None

    with open_log(path) as f:
        for line in f:
            line = line.strip()
            if not line:
                continue
            try:
                rec = json.loads(line)
            except json.JSONDecodeError:
                counts["malformed"] += 1
                continue
            k = rec.get("k", "unknown")
            counts[k] += 1
            t = rec.get("t")
            if isinstance(t, int):
                if first_tick is None or (t > 0 and (first_tick == 0 or t < first_tick)):
                    first_tick = t
                if last_tick is None or t > last_tick:
                    last_tick = t
            if k == "ch":
                channels.append(rec)
            elif k == "skill":
                skills.append(rec)
            elif k == "slow":
                slow_ticks.append(rec)
            elif k != "hb":
                events.append(rec)

    size_kb = path.stat().st_size / 1024.0
    print(f"=== Ibuprobridging Debug Log: {path.name} ({size_kb:.1f} KB on disk) ===")
    print(f"Ticks: {first_tick} -> {last_tick} | Records: {dict(counts)}")

    if slow_ticks:
        worst = max(slow_ticks, key=lambda r: r.get("ms", 0))
        print(f"Slow ticks (>20ms): {len(slow_ticks)} | Worst: {worst.get('ms')} ms at tick {worst.get('t')} ({worst.get('d')})")
    else:
        print("Slow ticks (>20ms): 0")

    print("\n--- Match & System Events ---")
    for e in (events if show_all else events[:40]):
        detail = f" ({e['d']})" if e.get("d") else ""
        print(f"  [t={e.get('t')}] {e.get('k')}: {e.get('msg')}{detail}")
    if not show_all and len(events) > 40:
        print(f"  ... ({len(events) - 40} more; pass --all to view)")

    if channels:
        print("\n--- Channel Handoffs ---")
        for c in (channels if show_all else channels[:30]):
            print(f"  [t={c.get('t')}] {c.get('ch')}: {c.get('from')} -> {c.get('to')} (why: {c.get('why')})")

    if skills:
        print("\n--- Skill Outcomes ---")
        for s in (skills if show_all else skills[:30]):
            print(f"  [t={s.get('t')}] {s.get('s')}: {s.get('out')} ({s.get('why')})")

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
