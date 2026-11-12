#!/usr/bin/env python3
"""Seeds the Ploceus meta answers a loader's build needs from its ci/osl-cache/ fixture.

    seed-ploceus-meta.py <fixture directory> <fabric-loom cache directory> <minecraft version>

Ploceus resolves OSL's module base versions, each module's version for the game, and the game's
library upgrades from the Ornithe meta at configure time, and keeps the answers in
~/.gradle/caches/fabric-loom, which the toolchain cache entry holds once a run has filled it. A
fresh key holds none of them, so every configure that needs an answer fails while the meta refuses
connections (#1241). The fixture records what the pinned OSL version and game resolve to, and only
an answer the restored cache lacks is filled in, so a complete restored entry keeps its bytes.

Ploceus also reads a cache file only while its modification time is under a day old: an older one
sends the configure to the meta first, through a request with no timeout, and uses the cache only
after that request fails (#1250). A restored entry carries the archived times rather than the time
of the restore, so every file the script leaves in place is touched, which is what makes a complete
restored entry look current.
"""

from __future__ import annotations

import json
import pathlib
import sys

NAMES = ("osl-base-versions.json", "osl-versions.json", "library-upgrades.json")


def filled(value):
    """Whether value is an answer rather than a gap Ploceus reads as a cache miss."""
    return value not in (None, {}, [])


def merge(restored, wanted):
    """Keep every answer the restored cache holds and fill only what it lacks."""
    if isinstance(restored, dict) and isinstance(wanted, dict):
        out = dict(restored)
        for key, value in wanted.items():
            if key not in out or not filled(out[key]):
                out[key] = value
            else:
                out[key] = merge(out[key], value)
        return out
    return restored if filled(restored) else wanted


def seed(fixture, minecraft, name, destination):
    wanted = json.loads((fixture / name).read_text())
    try:
        restored = json.loads(destination.read_text())
        valid = True
    except (OSError, json.JSONDecodeError):
        restored = {}
        valid = False
    merged = merge(restored, wanted)
    # A file that is not JSON is rewritten even when there is nothing to fill in: touching it would
    # leave Ploceus a cache file it cannot read. A missing one reads as invalid too.
    if not valid or merged != restored:
        destination.parent.mkdir(parents=True, exist_ok=True)
        destination.write_text(json.dumps(merged, indent=2) + "\n")
        print(f"Seeded {name} for {minecraft}.")
    else:
        destination.touch()
        print(f"Kept the restored {name} for {minecraft}.")


def main(argv):
    if len(argv) != 4:
        raise SystemExit(
            f"usage: {argv[0]} <fixture directory> <fabric-loom cache directory> <minecraft version>"
        )
    fixture = pathlib.Path(argv[1])
    loom = pathlib.Path(argv[2])
    minecraft = argv[3]
    missing = [name for name in NAMES if not (fixture / name).is_file()]
    if missing:
        raise SystemExit(f"{fixture} is missing {', '.join(missing)}.")
    seed(fixture, minecraft, "osl-base-versions.json", loom / "osl-base-versions.json")
    seed(fixture, minecraft, "osl-versions.json", loom / minecraft / "osl-versions.json")
    seed(fixture, minecraft, "library-upgrades.json", loom / minecraft / "library-upgrades.json")


if __name__ == "__main__":
    main(sys.argv)
