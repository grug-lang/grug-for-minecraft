#!/usr/bin/env python3
"""Print whether a diff can change what the Windows boot jobs test (#826), ``true`` or ``false``.

    windows-needed.py <base> <head> [--repo <dir>]

The Windows boot jobs run the same suite as the Linux loader jobs, boot-only, on a runner whose
native is an MSVC-built adapter.dll, and then run the universal jar installed the way a player's
launcher installs it (#530, #632). A change that boots on Linux cannot fail to boot on Windows
without failing on Linux first, so the answer is ``false`` unless a changed path is one only those
jobs test:

- the native path: ``core/src/main/native/`` (the adapter.c and adapter_shared.h MSVC compiles into
  adapter.dll), the generator that writes the C they compile (core/generate.py), the task that
  builds and names the library and pins the grug-rs revision (core/build.gradle.kts), and the class
  that picks the native by the running OS and extracts it (GrugNative.java);
- the boot-only stop they take instead of running the tests (GrugBootOnly.java);
- the runner and launcher they go through, whose Windows branch, boot-only wait and production
  install only they exercise (run-loader.sh, launcher.sh, launch-production.sh and
  report-native-abort.sh);
- the universal jar the installed job boots (universal_jar.py);
- the workflow itself, whose job steps and conditions are the jobs;
- a loader the diff adds, which #632 exists to boot on Windows before its layer ships.

Everything else the same run exercises on Linux: the mods, the loaders' adapters (none of them reads
the OS), core's runtime, mod_api.json and the rest of the pipeline. Every doubt answers ``true``,
which only runs the jobs: an empty diff (more likely a mistake than a no-op), a diff that cannot be
computed (an unreachable base, a failing git) and any error in this script. The reasons go to
stderr, so the CI log says why.

Only a dispatch asks this, and only when it names a base commit: a dispatch with no base is the full
run a release requires, and a push to main asks added-loaders.py instead.
"""

from __future__ import annotations

import argparse
import importlib.util
import sys
from pathlib import Path

# The paths only the Windows boot jobs test, as prefixes. Directories end in a slash; a file is named
# whole, so a new file beside one of them is not matched by accident. build.yml is here because the
# jobs are their steps and conditions: a change to the workflow can change what they run, and a
# change that moves them somewhere else is a build.yml change too, so this list cannot go stale
# without that change being one the list matches.
WINDOWS_PATHS = (
    "core/src/main/native/",
    "core/src/main/java/net/grug/minecraft/grug/GrugNative.java",
    "core/src/main/java/net/grug/minecraft/grug/GrugBootOnly.java",
    "core/generate.py",
    "core/build.gradle.kts",
    ".github/scripts/run-loader.sh",
    ".github/scripts/launcher.sh",
    ".github/scripts/launch-production.sh",
    ".github/scripts/report-native-abort.sh",
    ".github/scripts/universal_jar.py",
    ".github/workflows/build.yml",
)


def sibling(name: str):
    """One of the scripts beside this one, so every script reads a diff and a tree the same way."""
    spec = importlib.util.spec_from_file_location(
        name.replace("-", "_"), Path(__file__).resolve().parent / f"{name}.py"
    )
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def reason_to_run(path: str) -> str | None:
    """Why this changed path can change what the Windows jobs test, or None when it cannot."""
    if path.startswith(WINDOWS_PATHS):
        return f"{path} is an input of the Windows boot jobs"
    return None


def needed(repo: Path, base: str, head: str) -> tuple[bool, str]:
    """Whether the Windows jobs can see anything the diff changed, and the reason for the answer."""
    paths = sibling("affected-mods").changed_paths(repo, base, head)
    if not paths:
        return True, "the diff is empty, which is more likely a mistake than a no-op"
    added = sibling("added-loaders").added_dirs(repo, base, head)
    if added:
        return True, f"the diff adds {', '.join(added)}, which has to boot on Windows"
    for path in paths:
        reason = reason_to_run(path)
        if reason is not None:
            return True, reason
    return False, (
        f"none of the {len(paths)} changed paths is an input of the Windows boot jobs, which test"
        " the native path and the installed jar, and the same run runs everything else on Linux"
    )


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description=(__doc__ or "").split("\n\n")[0])
    parser.add_argument("base", help="the commit the diff is taken from")
    parser.add_argument("head", help="the commit that is built")
    parser.add_argument("--repo", default=".", help="the git repository (default: the current one)")
    args = parser.parse_args(argv)
    try:
        run, reason = needed(Path(args.repo), args.base, args.head)
    except Exception as e:  # noqa: BLE001 - whatever went wrong, the answer is to run the jobs
        run, reason = True, f"{type(e).__name__}: {e}"
    verdict = "running" if run else "skipping"
    print(f"windows-needed: {verdict} the Windows boot jobs: {reason}", file=sys.stderr)
    print("true" if run else "false")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
