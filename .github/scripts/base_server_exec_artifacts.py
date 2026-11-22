#!/usr/bin/env python3
"""Which of the base run's server-exec-<loader> artifacts the coverage job downloads (#1602).

    base_server_exec_artifacts.py < <the base run's artifacts listing, as JSON>

prints one `<artifact name> <download url>` line per live artifact on stdout and says on stderr
what was found: no server-exec artifact (the base run ran no dedicated leg, as a push to main does
not), or only expired ones (retention is 7 days, so a base run older than that carries nothing).
Either way the answer is no lines, never an error: the carry-over is a source of exec data for a
promoted class, and without it the gate counts what the run's own legs recorded, as before.
"""

from __future__ import annotations

import json
import re
import sys

NAME = re.compile(r"^server-exec-(.+)$")


def pick(listing: dict) -> tuple[list[tuple[str, str]], str]:
    """The live (name, url) pairs of the listing's server-exec artifacts, and a sentence on why."""
    named = [a for a in listing.get("artifacts", []) if NAME.match(a.get("name", ""))]
    live = [a for a in named if not a.get("expired", False)]
    if not named:
        return [], "the base run uploaded no server-exec artifact: it ran no dedicated leg"
    if not live:
        return [], (
            f"the base run's {len(named)} server-exec artifact(s) expired (retention is 7 days): "
            "nothing is carried over"
        )
    pairs = sorted((a["name"], a["archive_download_url"]) for a in live)
    return pairs, f"the base run has {len(pairs)} live server-exec artifact(s)"


def main() -> int:
    try:
        listing = json.load(sys.stdin)
    except ValueError:
        print(
            "the base run's artifact listing is not JSON: nothing is carried over", file=sys.stderr
        )
        return 0
    pairs, why = pick(listing)
    print(why, file=sys.stderr)
    for name, url in pairs:
        print(name, url)
    return 0


if __name__ == "__main__":
    sys.exit(main())
