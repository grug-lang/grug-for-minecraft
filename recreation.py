#!/usr/bin/env python3
"""Reference artifacts handling for the recreation fields of a mod's first derived_from entry.

A grug port is verified against committed goldens, which needs nothing but the port. Regenerating
those goldens is the only part that needs the reference artifacts, and that is deliberately a
separate, best-effort tier: losing the artifacts degrades regenerating, never verifying.

Each artifact's ``sha256`` is the authority, not its ``url``. A moved url, a mirror, or a re-upload
is fine as long as the bytes hash the same, so this downloads and hashes rather than trusting
anything about where the bytes came from.
"""

import argparse
import hashlib
import json
import shutil
import sys
import urllib.request
from pathlib import Path

USER_AGENT = "grug-for-minecraft recreation"

# What each install type does, so ``plan`` can tell a reader what running the reference involves
# without executing anything. The union is extended here as new runtimes turn up.
INSTALL_SUMMARIES = {
    "mods_folder": "Copy files into the reference runtime's mods folder.",
    "jarmod": "Place files as jar mods for the reference runtime.",
    "coremod": "Install files as coremods for the reference runtime.",
    "javaagent": "Add files to the JVM as -javaagent arguments.",
    "tweak_class": "Add files as tweak classes.",
    "launcher_profile": "Create a launcher profile from config.",
    "server_plugin": "Copy files into the server's plugins folder.",
    "datapack": "Copy files into the world's datapacks folder.",
    "installer": "Run the installer in files headlessly.",
    "source_build": "Build the reference from config, then install the result.",
}

# The fields that belong to the build a port recreates. The first derived_from entry is that build
# and the only one that may carry them; about_schema.py holds the same list for the rules it
# enforces, and the schema itself requires four of them on any entry that carries one.
RECREATION_FIELDS = ("artifacts", "runtime", "install", "deviations")


def load_recreation(about_path: Path) -> dict | None:
    """The first derived_from entry of an about.json, or None when the mod recreates nothing.

    The first entry is the build this port recreates, and the only one that carries the recreation
    fields; later entries are that build's own sources.
    """
    data = json.loads(about_path.read_text(encoding="utf-8"))
    derived_from = data.get("derived_from")
    if not isinstance(derived_from, list) or not derived_from:
        return None
    entry = derived_from[0]
    if not isinstance(entry, dict):
        return None
    if not any(field in entry for field in RECREATION_FIELDS):
        return None
    return entry


def artifacts_of(entry: dict) -> list:
    """The entry's artifacts, one per file the reference ships."""
    artifacts = entry.get("artifacts")
    return artifacts if isinstance(artifacts, list) else []


def artifact_named(entry: dict, name: str) -> dict | None:
    """The artifact staged as name, or None when no artifact claims that name."""
    for artifact in artifacts_of(entry):
        if artifact.get("name") == name:
            return artifact
    return None


def sha256_of(path: Path) -> str:
    digest = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def verify_artifact(artifact: dict, artifact_path: Path) -> list:
    """Checks downloaded bytes against the pinned hash and size, returning every mismatch."""
    errors = []
    actual_size = artifact_path.stat().st_size
    if actual_size != artifact["size"]:
        errors.append(
            f"size is {actual_size} bytes but the recreation entry pins {artifact['size']}"
        )

    actual_sha256 = sha256_of(artifact_path)
    if actual_sha256 != artifact["sha256"]:
        errors.append(
            f"sha256 is {actual_sha256} but the recreation entry pins {artifact['sha256']}"
        )

    return errors


def download_artifact(artifact: dict, destination: Path) -> None:
    """Fetches the artifact from its url. The caller verifies it against the pinned hash after."""
    request = urllib.request.Request(artifact["url"], headers={"User-Agent": USER_AGENT})
    with urllib.request.urlopen(request) as response, open(destination, "wb") as out:
        shutil.copyfileobj(response, out)


def install_plan(entry: dict) -> str:
    """A human-readable description of what installing and running the reference involves."""
    runtime = entry["runtime"]
    install = entry["install"]
    operation = INSTALL_SUMMARIES[install["type"]]

    revision = entry.get("revision")
    reference = f"Reference: {entry['name']}"
    if isinstance(revision, str):
        reference += f" at {revision}"

    lines = [
        f"Runtime: Minecraft {runtime['minecraft']}, loader directory {runtime['loader']},"
        f" loader version {runtime['loader_version']}, Java {runtime['java']}.",
        reference + ".",
        f"Install ({install['type']}): {operation}",
    ]
    for field in ("files", "libs", "jvm_args"):
        for value in install.get(field, []):
            lines.append(f"  {field}: {value}")
    if "config" in install:
        lines.append(f"  config: {json.dumps(install['config'], sort_keys=True)}")

    for artifact in artifacts_of(entry):
        lines.append(
            f"  artifact: {artifact['name']} {artifact['url']} sha256 {artifact['sha256']}"
        )

    deviations = entry.get("deviations", [])
    if deviations:
        lines.append(f"Intentional deviations: {len(deviations)} (see the recreation entry).")

    return "\n".join(lines)


def stage_install(entry: dict, artifacts_dir: Path, destination: Path) -> list:
    """Writes the install into destination, returning the paths it created.

    Every install file names an artifact, whose copy in artifacts_dir is verified before it is
    copied as that name. Only a mods_folder install is automated. The other types need a loader, a
    launcher, or a build this script cannot drive, so they are reported rather than half-done.
    """
    install = entry["install"]
    if install["type"] != "mods_folder":
        raise ValueError(
            f"Staging a {install['type']} install is not automated;"
            " recreation.py plan says what it involves."
        )

    destination.mkdir(parents=True, exist_ok=True)
    created = []
    for name in install["files"]:
        artifact = artifact_named(entry, name)
        if artifact is None:
            raise ValueError(
                f"{name} names no artifact in the recreation entry;"
                " every install file must name an artifact."
            )

        source = artifacts_dir / name
        if not source.is_file():
            raise ValueError(f"{source} is missing; verify downloads every artifact by name.")
        errors = verify_artifact(artifact, source)
        if errors:
            raise ValueError(f"{source} does not match the pinned {name}: {'; '.join(errors)}")

        target = destination / name
        if not (target.exists() and target.samefile(source)):
            shutil.copyfile(source, target)
        created.append(target)
    return created


def stage_command(entry: dict, args) -> int:
    """Stages already downloaded artifacts, verifying each before it touches the destination."""
    if args.destination is None:
        print("FAILED: stage needs --destination.", file=sys.stderr)
        return 1
    if not args.artifacts.is_dir():
        print(
            f"FAILED: {args.artifacts} is not a directory. Run verify first, or pass --artifacts.",
            file=sys.stderr,
        )
        return 1

    try:
        created = stage_install(entry, args.artifacts, args.destination)
    except ValueError as error:
        print(f"FAILED: {error}", file=sys.stderr)
        return 1

    runtime = entry["runtime"]
    for path in created:
        print(f"Staged {path}")
    print(
        "Run the reference with GRUG_REFERENCE=1 and GRUG_MODS_DIR pointing at a harness-only"
        f" mods directory: .github/scripts/run-loader.sh {runtime['loader']} run"
        f" (Minecraft {runtime['minecraft']}, loader version {runtime['loader_version']},"
        f" Java {runtime['java']})."
    )
    return 0


def recreation_mods(mods_dir: Path) -> list:
    """Every mod under mods_dir with a recreation entry, as (about.json path, entry) pairs."""
    mods = []
    for about_path in sorted(mods_dir.glob("*/about.json")):
        entry = load_recreation(about_path)
        if entry is not None:
            mods.append((about_path, entry))
    return mods


def verify_artifacts(entry: dict, output: Path) -> list:
    """Downloads every artifact into output and checks its pinned size and hash.

    Returns (url, message) pairs for the artifacts that could not be verified, so the caller can
    report them alongside whichever mod they belong to.
    """
    output.mkdir(parents=True, exist_ok=True)
    failures = []
    for artifact in artifacts_of(entry):
        target = output / artifact["name"]
        try:
            download_artifact(artifact, target)
        except Exception as error:
            # A missing or moved artifact degrades regenerating the goldens, never verifying the
            # port, so this reports and exits rather than tracing back.
            failures.append((artifact["url"], f"could not download it: {error}"))
            continue

        errors = verify_artifact(artifact, target)
        if errors:
            failures.append((artifact["url"], "; ".join(errors)))
            continue

        print(f"Verified {target} against {artifact['url']}")

    return failures


def report_failures(failures: list) -> int:
    """Prints the verification failures and returns the failure exit code."""
    print("FAILED: some artifacts do not match the recreation entry:", file=sys.stderr)
    for url, message in failures:
        print(f"  - {url}: {message}", file=sys.stderr)
    return 1


def verify_command(entry: dict, output: Path) -> int:
    """Downloads every artifact of one mod into output and checks its pinned size and hash."""
    failures = verify_artifacts(entry, output)
    if failures:
        return report_failures(failures)
    return 0


def verify_all_command(mods_dir: Path, output: Path) -> int:
    """Verifies every recreation artifact of every mod under mods_dir.

    Each mod gets its own subdirectory of output, named after the mod, since artifact names are only
    unique within a mod. Every artifact is a network fetch, so at scale this should be scoped to the
    mods a pull request changes; for now it walks them all so a moved or corrupted artifact is
    caught wherever it lives.
    """
    mods = recreation_mods(mods_dir)
    if not mods:
        print(f"No recreation entries found under {mods_dir}.", file=sys.stderr)
        return 1

    failures = []
    for about_path, entry in mods:
        print(f"Verifying {about_path} ({about_path.parent.name})")
        for url, message in verify_artifacts(entry, output / about_path.parent.name):
            failures.append((f"{about_path}: {url}", message))

    if failures:
        return report_failures(failures)
    return 0


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "command",
        choices=["plan", "verify", "verify-all", "stage"],
        help=(
            "plan prints what running the reference involves; verify downloads and hashes one mod's"
            " artifacts; verify-all does that for every mod under a mods directory; stage writes"
            " the install into --destination"
        ),
    )
    parser.add_argument(
        "path",
        type=Path,
        help="path to a mod's about.json, or the mods directory for verify-all",
    )
    parser.add_argument(
        "--output",
        type=Path,
        default=Path("reference-artifacts"),
        help=(
            "directory verify writes the downloaded artifacts into, one per artifact name;"
            " verify-all makes one mod-named subdirectory per mod"
        ),
    )
    parser.add_argument(
        "--artifacts",
        type=Path,
        default=Path("reference-artifacts"),
        help="directory of already downloaded artifacts for stage, verified before they are used",
    )
    parser.add_argument(
        "--destination",
        type=Path,
        help="the reference runtime's mods folder for stage",
    )
    args = parser.parse_args()

    if args.command == "verify-all":
        return verify_all_command(args.path, args.output)

    entry = load_recreation(args.path)
    if entry is None:
        print(f"FAILED: {args.path} has no recreation entry.", file=sys.stderr)
        return 1

    if args.command == "plan":
        print(install_plan(entry))
        return 0

    if args.command == "stage":
        return stage_command(entry, args)

    if not artifacts_of(entry):
        print(f"FAILED: {args.path} has no artifacts to verify.", file=sys.stderr)
        return 1

    return verify_command(entry, args.output)


if __name__ == "__main__":
    sys.exit(main())
