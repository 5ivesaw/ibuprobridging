#!/usr/bin/env python3
"""
Lightweight debug log inspector for Ibuprobridging (.jsonl.gz or .jsonl).
Usage:
  python3 tools/ibu_inspect.py <path-to-log.jsonl.gz> [--all]
"""

import gzip
import json
import sys
import zlib
from collections import Counter
from pathlib import Path


def iter_log_lines(path: Path):
    if path.suffix == ".gz":
        try:
            with gzip.open(path, "rt", encoding="utf-8", errors="replace") as f:
                for line in f:
                    yield line
            return
        except (EOFError, OSError):
            # Recover complete lines from an unclosed gzip stream (e.g., hard process kill)
            decomp = zlib.decompressobj(16 + zlib.MAX_WBITS)
            data = decomp.decompress(path.read_bytes())
            for line in data.decode("utf-8", errors="replace").splitlines():
                yield line
            return
    with path.open("r", encoding="utf-8", errors="replace") as f:
        for line in f:
            yield line


def format_event(r: dict) -> str:
    t = r.get("t", 0)
    k = r.get("k", "?")
    if k in ("session_start", "session_end"):
        return f"[t={t}] {k}: {r.get('msg')} ({r.get('d', '')})"
    if k == "lifecycle":
        return f"[t={t}] lifecycle: {r.get('act')} ({r.get('d', '')})"
    if k == "phase":
        return f"[t={t}] phase: {r.get('from', '')} -> {r.get('to', '')}"
    if k == "team":
        return f"[t={t}] team: {r.get('tm')}"
    if k == "bed_broken":
        return f"[t={t}] BED BROKEN ({r.get('tm')}): {r.get('msg')}"
    if k == "bed_scan":
        return (
            f"[t={t}] BED SCAN ({r.get('tm')}): {r.get('lay')}L hardest={r.get('hard')} "
            f"cnt={r.get('cnt')} clearTicks={r.get('clr')}"
        )
    if k == "kill":
        tag = "FINAL " if r.get("fin") else ""
        vtag = "VOID " if r.get("void") else ""
        return f"[t={t}] {tag}{vtag}KILL: {r.get('v')} by {r.get('killer') or 'self'} | {r.get('msg')}"
    if k == "trap":
        return f"[t={t}] TRAP: {r.get('msg')}"
    if k == "upgrade":
        return f"[t={t}] UPGRADE ({r.get('by')}): {r.get('msg')}"
    if k == "enemy":
        return (
            f"[t={t}] ENEMY {r.get('n')} pos={r.get('p')} arm={r.get('arm')} "
            f"swd={r.get('swd')} util={r.get('ut')}"
        )
    if k == "ch":
        return f"[t={t}] CH {r.get('ch')}: {r.get('from')} -> {r.get('to')} (why: {r.get('why')})"
    if k == "skill":
        return f"[t={t}] SKILL {r.get('s')}: {r.get('out')} ({r.get('why')})"
    if k == "slow":
        return f"[t={t}] SLOW {r.get('ms')} ms ({r.get('d')})"
    return f"[t={t}] {k}: {r.get('msg', r)}"


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

    for line in iter_log_lines(path):
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

    print("\n--- Match & Memory Events ---")
    for e in (events if show_all else events[:60]):
        print(" ", format_event(e))
    if not show_all and len(events) > 60:
        print(f"  ... ({len(events) - 60} more; pass --all to view)")

    if channels:
        print("\n--- Channel Handoffs ---")
        for c in (channels if show_all else channels[:30]):
            print(" ", format_event(c))

    if skills:
        print("\n--- Skill Outcomes ---")
        for s in (skills if show_all else skills[:30]):
            print(" ", format_event(s))

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
