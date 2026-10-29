#!/usr/bin/env python3
"""Print the mods whose tests a pull request has to run, for the runner's GRUG_CI_MODS (#628).

    affected-mods.py <base> <head> [--repo <dir>]

prints one comma separated line, such as ``buildcraft,coverage``, or nothing at all. Nothing means
"every mod": the runner treats an empty GRUG_CI_MODS as no selection. The reasons go to stderr, so
the CI log shows why a pull request got the selection it did.

The rule is sound by construction, and every doubt resolves to running more, never less. A mod's
tests have to run when anything that mod can observe changed. A mod can observe:

- its own directory;
- every mod it references, found by a scan (see below), and so, transitively, what those reference;
- everything the game runs that is not in a mod directory: core/, shared/, loaders/, mod_api.json,
  test-saves/, the scripts, the Gradle files, the workflow and so on;
- the namespaces the mods share with vanilla: a mod's data/ or assets/ files outside its ``grug``
  namespace (``c`` and ``minecraft`` tags, say) change what every mod sees.

So only a change confined to the grug-namespace files of other mods lets a mod skip, and anything
else selects every mod: a file outside mods/, a file directly in mods/, a mod directory whose name
the runner could not take as a list entry, an empty diff, a diff that cannot be computed (an
unreachable base, a failing git) and any error in this script. ``coverage`` always runs, because the
coverage gate counts its tests (#626), and JEI, which lists every registered item, is a library of
the loader (loaders/1.20.6-forge/gradle.properties): a change there is outside mods/ and so selects
every mod, and a test of it belongs in ``coverage`` (AGENTS.md), which always runs.

Mods are not declared to depend on one another. Names are shared through the ``grug:`` namespace,
and a declared field could be forgotten without anything noticing, which would run less. The scan
cannot be forgotten: a mod N depends on a mod M when any file of N (code, data, assets or tests;
not about.json, the license or an image; the comments of a .grug file do not count) holds a word
that is the name of a file M ships in code/, data/ or assets/ (``wooden_pipe`` for
``wooden_pipe-Block.grug`` or ``wooden_pipe.json``). It does not require the ``grug:`` prefix, and a
word match over-reports (two mods both ship a ``crafting.png``), which only runs more. The
dependency is transitive. Both the base and the head tree are scanned and their edges joined, so a
deleted file that another mod still names keeps its edge.

The tests of a mod that was deleted are not selected, since the runner refuses a name that is no mod.
"""

from __future__ import annotations

import argparse
import re
import subprocess
import sys
from pathlib import Path, PurePosixPath

ALWAYS = "coverage"
# The namespace whose files only the mods that name them can see. Any other namespace under a mod's
# data/ or assets/ (c, minecraft, ...) is shared with vanilla and with every other mod.
PRIVATE_NAMESPACE = "grug"
# What a mod ships that others can name, by the directory it is in.
SHIPPED_DIRS = ("code", "data", "assets")
# A mod directory name the runner and run-loader.sh take as a list entry unchanged. Anything else
# (a comma, a space, a leading dot) selects every mod rather than risk a malformed list.
MOD_NAME = re.compile(r"[A-Za-z0-9_][A-Za-z0-9_.-]*")
WORD = re.compile(rb"[A-Za-z0-9_]+")
# Files that cannot name a mod's entity, or that say nothing about what a mod observes.
NOT_SCANNED_SUFFIXES = {".png", ".jpg", ".jpeg", ".gif", ".ogg", ".wav", ".zip", ".ico"}
NOT_SCANNED_NAMES = {"about.json"}


class SelectEverything(Exception):
    """The selection cannot be narrowed: the message says why, and the answer is every mod."""


def git(repo: Path, *args: str, data: bytes | None = None) -> bytes:
    result = subprocess.run(
        ["git", "-C", str(repo), *args], input=data, capture_output=True, check=False
    )
    if result.returncode != 0:
        raise SelectEverything(
            f"git {' '.join(args)} failed: {result.stderr.decode(errors='replace').strip()}"
        )
    return result.stdout


def changed_paths(repo: Path, base: str, head: str) -> list[str]:
    """Every path the diff touches. Renames are off, so a moved file is a delete and an add and
    both of its paths count. -z keeps a path with a space, a newline or a quote in one piece."""
    for ref in (base, head):
        git(repo, "cat-file", "-e", f"{ref}^{{commit}}")
    out = git(repo, "diff", "--name-only", "-z", "--no-renames", "--no-ext-diff", base, head)
    return [p.decode(errors="surrogateescape") for p in out.split(b"\0") if p]


def tree_files(repo: Path, ref: str) -> dict[str, list[tuple[str, str]]]:
    """The files under mods/ at ``ref``: mod name -> [(path inside the mod, blob id)].

    A mod is a directory directly in mods/. A mod directory holding no file is not in git, so it is
    no mod here either, which agrees with the checkout the runner loads from."""
    out = git(repo, "ls-tree", "-r", "-z", ref, "--", "mods")
    mods: dict[str, list[tuple[str, str]]] = {}
    for entry in out.split(b"\0"):
        if not entry:
            continue
        meta, _, raw_path = entry.partition(b"\t")
        mode, kind, blob = meta.decode().split()
        parts = PurePosixPath(raw_path.decode(errors="surrogateescape")).parts
        if len(parts) < 3 or kind != "blob" or mode == "160000":
            # A file straight in mods/ is no mod's, and a submodule has no content to read.
            continue
        mods.setdefault(parts[1], []).append(("/".join(parts[2:]), blob))
    return mods


def read_blobs(repo: Path, blobs: set[str]) -> dict[str, bytes]:
    """The content of each blob, from one ``git cat-file --batch`` process."""
    ordered = sorted(blobs)
    if not ordered:
        return {}
    out = git(repo, "cat-file", "--batch", data=("\n".join(ordered) + "\n").encode())
    contents = {}
    position = 0
    for blob in ordered:
        end = out.index(b"\n", position)
        header = out[position:end].split()
        if len(header) != 3 or header[0].decode() != blob:
            raise SelectEverything(f"git cat-file --batch did not return {blob}")
        size = int(header[2])
        contents[blob] = out[end + 1 : end + 1 + size]
        position = end + 1 + size + 1
    return contents


def shipped_name(path: str) -> str | None:
    """The name a file inside a mod ships, or None when it ships none: ``code/x-Block.grug`` ships
    ``x``, ``assets/grug/lang/en_us.json`` ships ``en_us``, ``data/emptyns/.keep`` ships nothing."""
    parts = path.split("/")
    if len(parts) < 2 or parts[0] not in SHIPPED_DIRS:
        return None
    return re.split(r"[-.]", parts[-1])[0] or None


def is_scanned(path: str) -> bool:
    name = path.rsplit("/", 1)[-1]
    if name in NOT_SCANNED_NAMES or name.startswith("LICENSE"):
        return False
    return PurePosixPath(name).suffix.lower() not in NOT_SCANNED_SUFFIXES


def strip_grug_comments(text: str) -> str:
    """The text of a .grug file without its comments. A ``#`` outside a string starts a comment that
    runs to the end of the line, and a name in a comment refers to nothing. A ``#`` inside a string
    stays, since it can be a tag reference such as ``"#c:gears"``. A string ends at its line's end,
    so an unbalanced quote cannot hide the lines after it."""
    lines = []
    for line in text.split("\n"):
        in_string = False
        i = 0
        while i < len(line):
            char = line[i]
            if in_string:
                if char == "\\":
                    i += 1
                elif char == '"':
                    in_string = False
            elif char == '"':
                in_string = True
            elif char == "#":
                line = line[:i]
                break
            i += 1
        lines.append(line)
    return "\n".join(lines)


def words_of(path: str, content: bytes) -> set[str]:
    if path.endswith(".grug"):
        content = strip_grug_comments(content.decode(errors="replace")).encode()
    return {w.decode() for w in WORD.findall(content)}


def tree_facts(repo: Path, ref: str):
    """For each mod at ``ref``: the names it ships and the words its files hold."""
    files = tree_files(repo, ref)
    wanted = {blob for paths in files.values() for path, blob in paths if is_scanned(path)}
    contents = read_blobs(repo, wanted)
    facts = {}
    for mod, paths in files.items():
        ships: set[str] = set()
        words: set[str] = set()
        for path, blob in paths:
            name = shipped_name(path)
            if name is not None:
                ships.add(name)
            if is_scanned(path):
                words.update(words_of(path, contents[blob]))
        facts[mod] = (ships, words)
    return facts


def dependencies(repo: Path, base: str, head: str):
    """(mods at head, dependents) where dependents maps a mod to every mod that depends on it,
    directly or not, by the scan of the base and the head tree together."""
    base_facts = tree_facts(repo, base)
    head_facts = tree_facts(repo, head)
    ships: dict[str, set[str]] = {}
    words: dict[str, set[str]] = {}
    for facts in (base_facts, head_facts):
        for mod, (mod_ships, mod_words) in facts.items():
            ships.setdefault(mod, set()).update(mod_ships)
            words.setdefault(mod, set()).update(mod_words)

    direct = {mod: set() for mod in ships}  # mod -> the mods that name it
    for user, user_words in words.items():
        for mod, mod_ships in ships.items():
            if user != mod and user_words & mod_ships:
                direct[mod].add(user)

    dependents = {}
    for mod in ships:
        seen: set[str] = set()
        todo = [mod]
        while todo:
            for user in direct[todo.pop()]:
                if user not in seen:
                    seen.add(user)
                    todo.append(user)
        dependents[mod] = seen
    return set(head_facts), dependents


def affected_mods(repo: Path, base: str, head: str):
    """The sorted mods to run, or None for every mod. Raises SelectEverything with the reason."""
    paths = changed_paths(repo, base, head)
    if not paths:
        raise SelectEverything("the diff is empty, which is more likely a mistake than a no-op")

    changed: set[str] = set()
    for path in paths:
        parts = PurePosixPath(path).parts
        if len(parts) < 3 or parts[0] != "mods":
            raise SelectEverything(f"{path} is not inside a mod directory")
        mod = parts[1]
        if not MOD_NAME.fullmatch(mod):
            raise SelectEverything(f"{path} is in {mod!r}, which is no safe mod list entry")
        if parts[2] in ("data", "assets") and len(parts) > 3 and parts[3] != PRIVATE_NAMESPACE:
            raise SelectEverything(
                f"{path} is in the namespace {parts[3]!r}, which every mod shares with vanilla"
            )
        changed.add(mod)

    head_mods, dependents = dependencies(repo, base, head)
    if ALWAYS not in head_mods:
        raise SelectEverything(f"there is no {ALWAYS} mod to always run")
    selected = {ALWAYS}
    for mod in changed:
        selected.add(mod)
        selected.update(dependents.get(mod, ()))
    selected &= head_mods  # a deleted mod has no tests, and the runner refuses its name
    for name in selected:
        if not MOD_NAME.fullmatch(name):
            raise SelectEverything(f"the mod {name!r} is no safe mod list entry")
    if selected == head_mods:
        return None
    return sorted(selected)


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description=(__doc__ or "").split("\n\n")[0])
    parser.add_argument("base", help="the commit the pull request is compared with")
    parser.add_argument("head", help="the commit that is built")
    parser.add_argument("--repo", default=".", help="the git repository (default: the current one)")
    args = parser.parse_args(argv)
    try:
        selection = affected_mods(Path(args.repo), args.base, args.head)
        reason = "every mod's tests are needed" if selection is None else None
    except SelectEverything as e:
        selection, reason = None, str(e)
    except Exception as e:  # noqa: BLE001 - whatever went wrong, the answer is to run everything
        selection, reason = None, f"{type(e).__name__}: {e}"

    if selection is None:
        print(f"affected-mods: selecting every mod: {reason}", file=sys.stderr)
        return 0
    print(f"affected-mods: selecting {', '.join(selection)}", file=sys.stderr)
    print(",".join(selection))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
