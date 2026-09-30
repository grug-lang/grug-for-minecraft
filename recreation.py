#!/usr/bin/env python3
"""Reference artifacts handling for a mod's recreation block.

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


def load_recreation(about_path: Path) -> dict | None:
    """The recreation block of an about.json, or None when the mod has none."""
    data = json.loads(about_path.read_text(encoding="utf-8"))
    recreation = data.get("recreation")
    return recreation if isinstance(recreation, dict) else None


def artifacts_of(recreation: dict) -> list:
    """The recreation block's artifacts, one per file the reference ships."""
    artifacts = recreation.get("artifacts")
    return artifacts if isinstance(artifacts, list) else []


def artifact_named(recreation: dict, name: str) -> dict | None:
    """The artifact staged as name, or None when no artifact claims that name."""
    for artifact in artifacts_of(recreation):
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
            f"size is {actual_size} bytes but the recreation block pins {artifact['size']}"
        )

    actual_sha256 = sha256_of(artifact_path)
    if actual_sha256 != artifact["sha256"]:
        errors.append(
            f"sha256 is {actual_sha256} but the recreation block pins {artifact['sha256']}"
        )

    return errors


def download_artifact(artifact: dict, destination: Path) -> None:
    """Fetches the artifact from its url. The caller verifies it against the pinned hash after."""
    request = urllib.request.Request(artifact["url"], headers={"User-Agent": USER_AGENT})
    with urllib.request.urlopen(request) as response, open(destination, "wb") as out:
        shutil.copyfileobj(response, out)


def install_plan(recreation: dict) -> str:
    """A human-readable description of what installing and running the reference involves."""
    runtime = recreation["runtime"]
    install = recreation["install"]
    operation = INSTALL_SUMMARIES[install["type"]]

    lines = [
        f"Runtime: Minecraft {runtime['minecraft']}, loader directory {runtime['loader']},"
        f" loader version {runtime['loader_version']}, Java {runtime['java']}.",
        f"Reference: {recreation['reference']['name']} at {recreation['reference']['revision']}.",
        f"Install ({install['type']}): {operation}",
    ]
    for field in ("files", "libs", "jvm_args"):
        for value in install.get(field, []):
            lines.append(f"  {field}: {value}")
    if "config" in install:
        lines.append(f"  config: {json.dumps(install['config'], sort_keys=True)}")

    for artifact in artifacts_of(recreation):
        lines.append(
            f"  artifact: {artifact['name']} {artifact['url']} sha256 {artifact['sha256']}"
        )

    capture = recreation["capture"]
    diff = capture.get("diff", {})
    lines.append(
        f"Capture: {capture['width']}x{capture['height']} after {capture['ticks']} ticks,"
        f" diff mode {diff.get('mode', 'exact')}."
    )

    deviations = recreation.get("deviations", [])
    if deviations:
        lines.append(f"Intentional deviations: {len(deviations)} (see the recreation block).")

    return "\n".join(lines)


def stage_install(recreation: dict, artifacts_dir: Path, destination: Path) -> list:
    """Writes the install into destination, returning the paths it created.

    Every install file names an artifact, whose copy in artifacts_dir is verified before it is
    copied as that name. Only a mods_folder install is automated. The other types need a loader, a
    launcher, or a build this script cannot drive, so they are reported rather than half-done.
    """
    install = recreation["install"]
    if install["type"] != "mods_folder":
        raise ValueError(
            f"Staging a {install['type']} install is not automated;"
            " recreation.py plan says what it involves."
        )

    destination.mkdir(parents=True, exist_ok=True)
    created = []
    for name in install["files"]:
        artifact = artifact_named(recreation, name)
        if artifact is None:
            raise ValueError(
                f"{name} names no artifact in the recreation block;"
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


def stage_command(recreation: dict, args) -> int:
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
        created = stage_install(recreation, args.artifacts, args.destination)
    except ValueError as error:
        print(f"FAILED: {error}", file=sys.stderr)
        return 1

    runtime = recreation["runtime"]
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
    """Every mod under mods_dir with a recreation block, as (about.json path, recreation) pairs."""
    mods = []
    for about_path in sorted(mods_dir.glob("*/about.json")):
        recreation = load_recreation(about_path)
        if recreation is not None:
            mods.append((about_path, recreation))
    return mods


def verify_artifacts(recreation: dict, output: Path) -> list:
    """Downloads every artifact into output and checks its pinned size and hash.

    Returns (url, message) pairs for the artifacts that could not be verified, so the caller can
    report them alongside whichever mod they belong to.
    """
    output.mkdir(parents=True, exist_ok=True)
    failures = []
    for artifact in artifacts_of(recreation):
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
    print("FAILED: some artifacts do not match the recreation block:", file=sys.stderr)
    for url, message in failures:
        print(f"  - {url}: {message}", file=sys.stderr)
    return 1


def verify_command(recreation: dict, output: Path) -> int:
    """Downloads every artifact of one mod into output and checks its pinned size and hash."""
    failures = verify_artifacts(recreation, output)
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
        print(f"No recreation blocks found under {mods_dir}.", file=sys.stderr)
        return 1

    failures = []
    for about_path, recreation in mods:
        print(f"Verifying {about_path} ({about_path.parent.name})")
        for url, message in verify_artifacts(recreation, output / about_path.parent.name):
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

    recreation = load_recreation(args.path)
    if recreation is None:
        print(f"FAILED: {args.path} has no recreation block.", file=sys.stderr)
        return 1

    if args.command == "plan":
        print(install_plan(recreation))
        return 0

    if args.command == "stage":
        return stage_command(recreation, args)

    if not artifacts_of(recreation):
        print(f"FAILED: {args.path} has no artifacts to verify.", file=sys.stderr)
        return 1

    return verify_command(recreation, args.output)


if __name__ == "__main__":
    sys.exit(main())
