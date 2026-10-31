#!/usr/bin/env python3
"""Print the tests a pull request has to run, for the runner's GRUG_CI_TESTS (#755).

    affected-tests.py <base> <head> [--repo <dir>] [--write-table]

prints one comma separated line of repository-relative test paths, such as
``mods/buildcraft/tests/wooden_pipe-Test.grug,mods/coverage/tests/..., ...``, or nothing at all.
Nothing means "every test": the runner treats an empty GRUG_CI_TESTS as no selection. The reasons go
to stderr, so the CI log shows why a pull request got the selection it did.

The map has three static sources, and every doubt resolves to running more, never less:

1. A test names block, item and entity names and host functions; ``words_of`` from
   affected-mods.py, with grug comments stripped, gives those for each ``*-Test.grug``.
2. Every other file a mod ships names the symbols it ships (``shipped_name``) and the symbols of
   other mods it refers to. A changed file selects the tests that name what it ships, and,
   transitively, the tests that name what a file depending on it ships: a test placing
   ``grug:wooden_pipe`` reaches the pipe's block entity through the block file that names it.
3. A Java file under a loader, core or shared maps to the host API entries of its methods in the
   checked-in ``host_api_table.json``, and a change selects the tests calling those entries. The
   table is regenerated with ``--write-table`` and a test refuses a stale one. A Java file that
   implements no entry, such as the runner, the screenshots, adapter glue or a build file, selects
   every test.

The rules that keep it safe, from #755:

- Anything the map cannot attribute selects every test: a path outside ``mods/`` that is no Java
  source or ``mod_api.json``, a file directly in ``mods/``, a shared namespace, a Java file that
  implements no entry, an empty diff, an unreachable base, or any error in this script.
- ``mods/coverage``'s tests always run, because the coverage gate needs all of them on every run.
  A change to ``mod_api.json`` additionally selects every test calling a changed or removed
  entry; an added entry alone selects nothing beyond coverage.
- A path touching the runner, the test framework, ``GrugTestRunner``, the screenshot or render
  settle code, or a build file selects every test.

The table maps a file to its methods and their entries, not the file to a flat set, so adding a
method that serves only a new entry does not select the callers of the file's other entries.
"""

from __future__ import annotations

import argparse
import importlib.util
import json
import re
import sys
from pathlib import Path, PurePosixPath

SCRIPTS = Path(__file__).resolve().parent
SPEC = importlib.util.spec_from_file_location("affected_mods", SCRIPTS / "affected-mods.py")
affected = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(affected)

SelectEverything = affected.SelectEverything

ALWAYS = "coverage"
TEST_SUFFIX = "-Test.grug"
TABLE_NAME = "host_api_table.json"
TABLE_PATH = f".github/scripts/{TABLE_NAME}"
HOST_FUNCTIONS = "core/src/main/java/net/grug/minecraft/grug/HostFunctions.java"
JAVA_MAIN = re.compile(r"(?:core|loaders/[^/]+|shared/[^/]+)/src/main/java/.*\.java\Z")
# Changing any of these changes how every test runs or what a capture holds, whatever entries it
# seems to serve.
FALLBACK_NAMES = (
    "GrugTestRunner",
    "GrugTestBox",
    "GrugTestState",
    "GrugTestOrigin",
    "GrugScreenshot",
    "GrugScreenshots",
    "GrugFrameRequest",
    "GrugRunWindow",
    "GrugCoverage",
    "GrugRenderSettle",
)
MODIFIERS = "public|protected|private|static|final|abstract|default|synchronized|native|strictfp"
DECLARATION = re.compile(
    rf"(?m)^[ \t]*(?:{MODIFIERS})(?:[ \t]+(?:{MODIFIERS}))*[ \t]+"
    r"(?:<[^>\n]+>[ \t]*)?[A-Za-z_$][\w$.<>\[\],? \t]*[ \t]+(\w+)[ \t]*\("
)
CALL = re.compile(r"\b([a-z_$][\w$]*)[ \t]*\(")
IDENTIFIER = re.compile(r"\b([A-Za-z_$][\w$]*)\b")
FIELD = re.compile(
    rf"(?m)^[ \t]*(?:{MODIFIERS})(?:[ \t]+(?:{MODIFIERS}))*[ \t]+"
    r"[A-Za-z_$][\w$.<>\[\],? \t]*[ \t]+(\w+)[ \t]*(?:=|;)"
)
JAVA_KEYWORDS = {
    "assert",
    "break",
    "case",
    "catch",
    "continue",
    "default",
    "do",
    "else",
    "finally",
    "for",
    "if",
    "instanceof",
    "new",
    "return",
    "switch",
    "throw",
    "try",
    "while",
    "yield",
}


def blob_at(repo: Path, ref: str, path: str) -> bytes:
    return affected.git(repo, "cat-file", "blob", f"{ref}:{path}")


def tree_paths(repo: Path, ref: str) -> dict[str, str]:
    """Every blob under the Java main source roots and mods at ``ref``: path -> blob id."""
    out = affected.git(repo, "ls-tree", "-r", "-z", ref, "--", "core", "loaders", "shared", "mods")
    paths: dict[str, str] = {}
    for entry in out.split(b"\0"):
        if not entry:
            continue
        meta, _, raw = entry.partition(b"\t")
        _mode, kind, blob = meta.decode().split()
        if kind != "blob":
            continue
        path = raw.decode(errors="surrogateescape")
        if path.startswith("mods/") or (
            path.endswith(".java") and "/src/main/java/" in path and JAVA_MAIN.match(path)
        ):
            paths[path] = blob
    return paths


def matches(text: str, open_index: int) -> int:
    """The index of the bracket closing the one at ``open_index``, ignoring strings and comments."""
    depth = 0
    i = open_index
    in_string = in_char = in_line = in_block = False
    while i < len(text):
        char = text[i]
        nxt = text[i + 1] if i + 1 < len(text) else ""
        if in_line:
            if char == "\n":
                in_line = False
        elif in_block:
            if char == "*" and nxt == "/":
                in_block = False
                i += 1
        elif in_string:
            if char == "\\":
                i += 1
            elif char == '"':
                in_string = False
        elif in_char:
            if char == "\\":
                i += 1
            elif char == "'":
                in_char = False
        elif char == "/" and nxt == "/":
            in_line = True
            i += 1
        elif char == "/" and nxt == "*":
            in_block = True
            i += 1
        elif char == '"':
            in_string = True
        elif char == "'":
            in_char = True
        elif char in "({[":
            depth += 1
        elif char in ")}]":
            depth -= 1
            if depth == 0:
                return i
        i += 1
    return -1


def java_methods(text: str) -> list[dict]:
    """The methods a Java source declares, with the line range of their body.

    Interface declarations end at their semicolon and have an empty body, so their range is the
    declaration line. Constructors are left out: they are no entry and no adapter method."""
    methods = []
    for match in DECLARATION.finditer(text):
        name = match.group(1)
        if name in ("class", "interface"):
            continue
        header = text.find("(", match.start())
        close = matches(text, header)
        if close < 0:
            continue
        after = close + 1
        while after < len(text) and text[after].isspace():
            after += 1
        if text.startswith("throws", after):
            brace = text.find("{", after)
            semi = text.find(";", after)
            ends = [index for index in (brace, semi) if index >= 0]
            if not ends:
                continue
            after = min(ends)
        start_line = text.count("\n", 0, match.start()) + 1
        if text[after] == ";":
            end = after
            body = ""
        elif text[after] == "{":
            end = matches(text, after)
            if end < 0:
                continue
            body = text[after + 1 : end]
        else:
            continue
        methods.append(
            {
                "name": name,
                "start": start_line,
                "end": text.count("\n", 0, end) + 1,
                "body": body,
            }
        )
    return methods


def java_fields(text: str) -> list[dict]:
    """The class fields a Java source declares, one line each, for a change to a constant.

    A field's initializer on a following line is not tracked: a change there falls back, which only
    selects more."""
    fields = []
    for match in FIELD.finditer(text):
        name = match.group(1)
        if name in ("return", "new", "throw"):
            continue
        line = text.count("\n", 0, match.start()) + 1
        fields.append({"name": name, "line": line})
    return fields


def entries_at(repo: Path, ref: str) -> dict[str, object]:
    """Every host API entry at ``ref``, mapped to its definition, for equality across commits."""
    try:
        api = json.loads(blob_at(repo, ref, "mod_api.json"))
    except (SelectEverything, ValueError):
        raise SelectEverything("mod_api.json could not be read")
    entries: dict[str, object] = {name: value for name, value in api["host_functions"].items()}
    for entity, definition in api["entities"].items():
        for function in definition.get("export_functions", []):
            entries[f"{entity}.{function['name']}"] = function
        for kind in ("methods", "static_methods"):
            for name, value in definition.get(kind, {}).items():
                entries[f"{entity}.{name}"] = value
    for klass, definition in api["classes"].items():
        for kind in ("methods", "static_methods"):
            for name, value in definition.get(kind, {}).items():
                entries[f"{klass}.{name}"] = value
    return entries


def entry_of(method: str, entries: dict[str, object]) -> str | None:
    """The entry a HostFunctions method implements, or None for a helper."""
    if method in entries:
        return method
    if "_" in method:
        prefix, rest = method.split("_", 1)
        qualified = f"{prefix}.{rest}"
        if qualified in entries:
            return qualified
    return None


def build_table(repo: Path, ref: str) -> dict[str, dict[str, list[str]]]:
    """path -> method -> the entries that method serves, for every Java main source at ``ref``.

    HostFunctions.java serves the entries its methods implement and the entries whose bodies call
    its helpers. Every other file serves the entries whose bodies call a method it declares; the
    match is by name, so a collision only serves more entries and selects more tests."""
    entries = entries_at(repo, ref)
    paths = tree_paths(repo, ref)
    java = {path: blob for path, blob in paths.items() if path.endswith(".java")}
    blobs = affected.read_blobs(repo, set(java.values()))
    sources = {path: blobs[blob].decode(errors="replace") for path, blob in java.items()}
    methods = {path: java_methods(text) for path, text in sources.items()}

    callers: dict[str, set[str]] = {}
    for method in methods.get(HOST_FUNCTIONS, []):
        entry = entry_of(method["name"], entries)
        if entry is None:
            continue
        for call in CALL.findall(method["body"]):
            if call not in JAVA_KEYWORDS:
                callers.setdefault(call, set()).add(entry)
        for name in set(IDENTIFIER.findall(method["body"])):
            callers.setdefault(name, set()).add(entry)

    table: dict[str, dict[str, list[str]]] = {}
    for path, declared in methods.items():
        served: dict[str, list[str]] = {}
        for method in declared:
            entries_for_method = set(callers.get(method["name"], set()))
            if path == HOST_FUNCTIONS:
                own = entry_of(method["name"], entries)
                if own is not None:
                    entries_for_method.add(own)
            entries_for_method = {e for e in entries_for_method if e in entries}
            if entries_for_method:
                served[method["name"]] = sorted(entries_for_method)
        for field in java_fields(sources[path]):
            entries_for_field = {e for e in callers.get(field["name"], set()) if e in entries}
            if entries_for_field:
                served[field["name"]] = sorted(entries_for_field)
        if served:
            table[path] = served
    return table


def table_at(repo: Path, ref: str) -> dict[str, dict[str, list[str]]]:
    """The checked-in table at ``ref``, or one built from that commit when it has none yet.

    A commit from before this prototype has no table, which is the case the offline replay runs on;
    the table is data derived from that commit, so building it there measures the map rather than
    the absence of the file. A commit that has the file always uses it, and its test keeps it equal
    to what building it would give."""
    try:
        raw = blob_at(repo, ref, TABLE_PATH)
    except SelectEverything:
        return build_table(repo, ref)
    return json.loads(raw)


def paths_at(repo: Path, ref: str) -> dict[str, str]:
    return tree_paths(repo, ref)


def test_words(repo: Path, ref: str, paths: dict[str, str] | None = None) -> dict[str, set[str]]:
    """test path -> the words it names, at ``ref``."""
    paths = paths or paths_at(repo, ref)
    wanted = {
        blob
        for path, blob in paths.items()
        if path.startswith("mods/") and path.endswith(TEST_SUFFIX)
    }
    blobs = affected.read_blobs(repo, wanted)
    return {
        path: affected.words_of(path, blobs[blob])
        for path, blob in paths.items()
        if path.startswith("mods/") and path.endswith(TEST_SUFFIX)
    }


def mod_files(repo: Path, ref: str, paths: dict[str, str] | None = None) -> dict[str, set[str]]:
    """path -> the words of every non-test file a mod ships, at ``ref``."""
    paths = paths or paths_at(repo, ref)
    wanted = {
        blob
        for path, blob in paths.items()
        if path.startswith("mods/")
        and not path.endswith(TEST_SUFFIX)
        and affected.is_scanned(path.split("/", 2)[2])
    }
    blobs = affected.read_blobs(repo, wanted)
    return {
        path: affected.words_of(path, blobs[blob])
        for path, blob in paths.items()
        if path.startswith("mods/")
        and not path.endswith(TEST_SUFFIX)
        and affected.is_scanned(path.split("/", 2)[2])
    }


def owner(path: str) -> str:
    """The mod a path belongs to, or "" for a path outside mods/."""
    parts = PurePosixPath(path).parts
    return parts[1] if len(parts) > 2 and parts[0] == "mods" else ""


def ships_of(path: str) -> set[str]:
    """The names a file inside a mod ships, from its path."""
    parts = PurePosixPath(path).parts
    if len(parts) < 4:
        return set()
    name = affected.shipped_name("/".join(parts[2:]))
    return {name} if name else set()


def all_tests_at(repo: Path, ref: str, paths: dict[str, str] | None = None) -> list[str]:
    paths = paths or paths_at(repo, ref)
    return sorted(path for path in paths if path.startswith("mods/") and path.endswith(TEST_SUFFIX))


def dependents_of(files: dict[str, tuple[set[str], set[str]]], changed: str) -> set[str]:
    """Every file that depends on ``changed``, directly or transitively.

    A file names another by holding a word the other ships. The reverse index is built once for
    the names the changed file ships, then walked, so a change to a much named texture stays cheap.
    """
    users_by_name: dict[str, set[str]] = {}
    for path, (words, _ships) in files.items():
        for name in words:
            users_by_name.setdefault(name, set()).add(path)
    seen: set[str] = set()
    todo = [changed]
    while todo:
        current = todo.pop()
        for name in files.get(current, (set(), set()))[1]:
            for user in users_by_name.get(name, set()):
                if user not in seen:
                    seen.add(user)
                    todo.append(user)
    return seen


def named_tests(tests: dict[str, set[str]], names: set[str]) -> set[str]:
    return {test for test, words in tests.items() if words & names}


def java_entries_for_change(repo: Path, base: str, head: str, path: str) -> set[str] | None:
    """The entries a changed Java file serves, or None when the change is unattributable."""
    if any(name in path for name in FALLBACK_NAMES):
        return None
    table = table_at(repo, head)
    if path not in table:
        return None
    text = blob_at(repo, head, path).decode(errors="replace")
    methods = java_methods(text)
    fields = java_fields(text)
    hunks = affected.git(
        repo, "diff", "--unified=0", "--no-renames", "--no-ext-diff", base, head, "--", path
    ).decode(errors="replace")
    lines: set[int] = set()
    for match in re.finditer(r"^@@ -\d+(?:,\d+)? \+(\d+)(?:,(\d+))? @@", hunks, re.MULTILINE):
        start = int(match.group(1))
        count = int(match.group(2) or 1)
        if count == 0:
            return None  # a deletion changes behaviour without a head line to attribute it to
        lines.update(range(start, start + count))
    head_lines = text.split("\n")
    changed_names = set()
    for line in sorted(lines):
        stripped = head_lines[line - 1].strip() if line <= len(head_lines) else ""
        if not stripped or stripped.startswith(("//", "/*", "*")):
            continue  # a blank or comment line changes no behaviour
        enclosing = [m["name"] for m in methods if m["start"] <= line <= m["end"]]
        if enclosing:
            changed_names.update(enclosing)
            continue
        field = next((f["name"] for f in fields if f["line"] == line), None)
        if field is None:
            return None
        changed_names.add(field)
    if not changed_names:
        return None
    base_entries = entries_at(repo, base)
    head_entries = entries_at(repo, head)
    added_entries = set(head_entries) - set(base_entries)
    try:
        base_text = blob_at(repo, base, path).decode(errors="replace")
    except SelectEverything:
        base_text = ""
    base_names = {m["name"] for m in java_methods(base_text)}
    base_names.update(f["name"] for f in java_fields(base_text))
    served: set[str] = set()
    for name in changed_names:
        entries = table[path].get(name)
        if not entries:
            return None
        if name not in base_names and set(entries) <= added_entries:
            continue  # a declaration serving only a new entry cannot break an existing test
        served.update(entries)
    return served


def callers(tests: dict[str, set[str]], entries: set[str]) -> set[str]:
    """The tests naming any of these entries.

    A qualified entry is matched by its method name, since a test's receiver type is not statically
    known here; that only selects more."""
    names = {entry.split(".", 1)[-1] for entry in entries}
    return named_tests(tests, names)


def selected_tests(repo: Path, base: str, head: str) -> tuple[list[str] | None, list[str]]:
    """The tests to run, or None for every test, and the reasons for the selection."""
    paths = affected.changed_paths(repo, base, head)
    if not paths:
        raise SelectEverything("the diff is empty, which is more likely a mistake than a no-op")
    file_paths = paths_at(repo, head)
    all_tests = all_tests_at(repo, head, file_paths)
    tests = test_words(repo, head, file_paths)
    words = mod_files(repo, head, file_paths)
    files = {
        path: (words.get(path, set()), ships_of(path))
        for path in file_paths
        if path.startswith("mods/") and not path.endswith(TEST_SUFFIX)
    }
    # Only code files depend on one another in the signature sense this map needs: a block names
    # its block entity, and a test naming the block must reach it. Data, assets and tags name
    # generic words such as stone or gears, and following those would pull in half the suite.
    code_files = {
        path: value for path, value in files.items() if PurePosixPath(path).parts[2] == "code"
    }
    head_entries = entries_at(repo, head)
    base_entries = entries_at(repo, base)
    reasons: list[str] = []
    selected: set[str] = set()
    mod_ships: dict[str, set[str]] = {}
    known_ships: set[str] = set()
    for path, (_words, ships) in files.items():
        mod_ships.setdefault(owner(path), set()).update(ships)
        known_ships.update(ships)

    def all_of(mod: str) -> set[str]:
        return {test for test in all_tests if owner(test) == mod}

    for path in paths:
        parts = PurePosixPath(path).parts
        mod = owner(path)
        if mod == "":
            if path == "mod_api.json":
                changed = {
                    entry
                    for entry in head_entries
                    if entry in base_entries and head_entries[entry] != base_entries[entry]
                }
                removed = set(base_entries) - set(head_entries)
                if changed or removed:
                    selected |= callers(tests, changed | removed)
                    reasons.append(
                        f"{path} changes {', '.join(sorted(changed | removed))}, "
                        "running every caller"
                    )
                else:
                    reasons.append(f"{path} only adds entries, so only coverage's tests are needed")
                continue
            if path == "mod_api.md":
                reasons.append(
                    f"{path} is generated from mod_api.json, which decides the selection"
                )
                continue
            if "/src/test/java/" in path:
                reasons.append(f"{path} is a Java unit test, which runs separately")
                continue
            if JAVA_MAIN.match(path):
                entries = java_entries_for_change(repo, base, head, path)
                if entries is None:
                    raise SelectEverything(f"{path} is Java that implements no host API entry")
                selected |= callers(tests, entries)
                reasons.append(f"{path} serves {', '.join(sorted(entries))}")
                continue
            raise SelectEverything(f"{path} is not inside a mod directory")
        if len(parts) < 3 or not affected.MOD_NAME.fullmatch(mod):
            raise SelectEverything(f"{path} is in {mod!r}, which is no safe mod")
        section = parts[2]
        if (
            section in ("data", "assets")
            and len(parts) > 3
            and parts[3] != affected.PRIVATE_NAMESPACE
        ):
            raise SelectEverything(
                f"{path} is in the namespace {parts[3]!r}, which every mod shares with vanilla"
            )
        if path.endswith(TEST_SUFFIX):
            selected.add(path)
            continue
        if section not in affected.SHIPPED_DIRS:
            # about.json, the license, a golden capture: everything this mod and the names it
            # ships can reach, since an id or registration change moves what other mods see.
            selected |= all_of(mod) | named_tests(tests, mod_ships.get(mod, set()))
            continue
        names = ships_of(path)
        reached_by = dependents_of(code_files, path) if section == "code" else set()
        for reached in reached_by:
            names |= files.get(reached, (set(), set()))[1]
        matched = named_tests(tests, names)
        if section in ("data", "assets"):
            # A JSON key or a comment word names nothing; only a symbol another mod file ships
            # can reach a test.
            matched |= named_tests(tests, files.get(path, (set(), set()))[0] & known_ships)
        if not matched:
            matched = all_of(mod)
        selected |= matched

    if ALWAYS not in {owner(test) for test in all_tests}:
        raise SelectEverything(f"there is no {ALWAYS} mod to always run")
    selected |= all_of(ALWAYS)
    if selected == set(all_tests):
        return None, reasons
    return sorted(selected), reasons


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description=(__doc__ or "").split("\n\n")[0])
    parser.add_argument("base", help="the commit the pull request is compared with")
    parser.add_argument("head", help="the commit that is built")
    parser.add_argument("--repo", default=".", help="the git repository (default: the current one)")
    parser.add_argument(
        "--write-table", action="store_true", help=f"regenerate {TABLE_PATH} at head and stop"
    )
    args = parser.parse_args(argv)
    repo = Path(args.repo)
    if args.write_table:
        table = build_table(repo, args.head)
        (repo / TABLE_PATH).write_text(json.dumps(table, indent=1, sort_keys=True) + "\n")
        print(f"affected-tests: wrote {TABLE_PATH} for {args.head}", file=sys.stderr)
        return 0
    try:
        selection, reasons = selected_tests(repo, args.base, args.head)
        reason = "every test is needed" if selection is None else None
    except SelectEverything as e:
        selection, reasons, reason = None, [], str(e)
    except Exception as e:  # noqa: BLE001 - whatever went wrong, the answer is to run everything
        selection, reasons, reason = None, [], f"{type(e).__name__}: {e}"

    for line in reasons:
        print(f"affected-tests: {line}", file=sys.stderr)
    if selection is None:
        print(f"affected-tests: selecting every test: {reason}", file=sys.stderr)
        return 0
    print(
        f"affected-tests: selecting {len(selection)} test(s): {', '.join(selection)}",
        file=sys.stderr,
    )
    print(",".join(selection))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
