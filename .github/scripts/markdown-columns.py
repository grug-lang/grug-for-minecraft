#!/usr/bin/env python3
"""Fails a markdown line the writing rule would have wrapped, so a rewrap costs no pull request.

``AGENTS.md`` wraps its prose at about 100 columns. Nothing enforced that, so #811 and #816 left a
152-column and a 118-column line and #815 and #817 rewrapped them: two pull requests, two merges and
two push-to-main runs (#827). Run by pre-commit over every changed markdown file it is given.

A table row is exempt, because markdown cannot wrap one and the renderer decides its width. So is a
line whose longest word is over the limit, which is a URL or a path that no wrap can shorten.

The limit is 110 rather than 100 because the rule says "about 100" and a wrap can still leave a
word to overflow: today's file has prose at 106 columns, while the two lines that cost the rewrap
pull requests were 118 and 152.
"""

import sys
from pathlib import Path

# "About 100 columns" in AGENTS.md's Writing section: the overflow of a word the wrapper kept.
LIMIT = 110


def long_lines(text: str, limit: int = LIMIT) -> list:
    """(line number, columns) for each line that is over the limit and could have been wrapped."""
    over = []
    for number, line in enumerate(text.split("\n"), start=1):
        if line.lstrip().startswith("|"):
            continue
        if max((len(word) for word in line.split()), default=0) > limit:
            continue
        if len(line) > limit:
            over.append((number, len(line)))
    return over


def main(paths: list) -> int:
    failed = False
    for name in paths:
        for number, columns in long_lines(Path(name).read_text(encoding="utf-8")):
            print(f"{name}:{number}: {columns} columns, over the {LIMIT} a wrapped line may take")
            failed = True
    if failed:
        print("Wrap the prose at about 100 columns; a table row is exempt.")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
