#!/usr/bin/env python3
"""Choose the loaders whose ``layer`` job runs, as build.yml's layer matrix (#169).

    layer-selection.py <event> <matrix-json> <dedicated-loaders-json>

prints a JSON list. A push, a dispatch and the schedule build the layer of every loader, which the
universal jar assembles, so they answer the whole loader matrix. A pull request assembles no jar,
and the only thing it needs a layer for is the dedicated-server exec data of an Ornithe loader:
combine-coverage.py analyses that loader's server classes from its ``layer-<loader>`` artifact and
refuses exec data without one. So a pull request answers the Ornithe loaders (the ones in
``combine-coverage.ORNITHE_LOADERS``) whose dedicated leg runs, and no other loader: a loader whose
leg did not run uploads no server exec, takes the carried-over data of the base run instead (#1602),
which needs no layer jar, and costs no job. An empty list becomes a skipped job in build.yml.
"""

from __future__ import annotations

import json
import sys

# The loaders combine-coverage.py analyses from a layer jar (its ORNITHE_LOADERS).
LAYER_SERVER_LOADERS = ("a1.1.2_01-ornithe", "b1.7.3-ornithe")


def select(event: str, matrix: list[str], dedicated: list[str]) -> list[str]:
    if event != "pull_request":
        return matrix
    return [loader for loader in matrix if loader in dedicated and loader in LAYER_SERVER_LOADERS]


def main(argv: list[str]) -> int:
    if len(argv) != 4:
        print(__doc__, file=sys.stderr)
        return 2
    print(json.dumps(select(argv[1], json.loads(argv[2]), json.loads(argv[3]))))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
