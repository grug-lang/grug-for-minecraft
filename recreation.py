#!/usr/bin/env python3
"""Reference artifact handling for a mod's recreation block.

A grug port is verified against committed goldens, which needs nothing but the port. Regenerating
those goldens is the only part that needs the reference artifact, and that is deliberately a
separate, best-effort tier: losing the artifact degrades regenerating, never verifying.

The artifact's ``sha256`` is the authority, not its ``url``. A moved url, a mirror, or a re-upload
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


def sha256_of(path: Path) -> str:
    digest = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def verify_artifact(recreation: dict, artifact_path: Path) -> list:
    """Checks downloaded bytes against the pinned hash and size, returning every mismatch."""
    artifact = recreation.get("artifact")
    if artifact is None:
        return ["This recreation block has no artifact to verify."]

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


def download_artifact(recreation: dict, destination: Path) -> None:
    """Fetches the artifact from its url. The caller verifies it against the pinned hash after."""
    artifact = recreation["artifact"]
    request = urllib.request.Request(artifact["url"], headers={"User-Agent": USER_AGENT})
    with urllib.request.urlopen(request) as response, open(destination, "wb") as out:
        shutil.copyfileobj(response, out)


def install_plan(recreation: dict) -> str:
    """A human-readable description of what installing and running the reference involves."""
    runtime = recreation["runtime"]
    install = recreation["install"]
    operation = INSTALL_SUMMARIES[install["type"]]

    lines = [
        f"Runtime: Minecraft {runtime['minecraft']} with {runtime['loader']}"
        f" {runtime['loader_version']} on Java {runtime['java']}.",
        f"Reference: {recreation['reference']['name']} at {recreation['reference']['revision']}.",
        f"Install ({install['type']}): {operation}",
    ]
    for field in ("files", "libs", "jvm_args"):
        for value in install.get(field, []):
            lines.append(f"  {field}: {value}")
    if "config" in install:
        lines.append(f"  config: {json.dumps(install['config'], sort_keys=True)}")

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


def stage_install(recreation: dict, artifact_path: Path, destination: Path) -> list:
    """Writes the install into destination, returning the paths it created.

    Only a mods_folder install is automated. The other types need a loader, a launcher, or a build
    this script cannot drive, so they are reported rather than half-done.
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
        target = destination / name
        if not (target.exists() and target.samefile(artifact_path)):
            shutil.copyfile(artifact_path, target)
        created.append(target)
    return created


def stage_command(recreation: dict, args) -> int:
    """Stages an already downloaded artifact, verifying it before it touches the destination."""
    if args.destination is None:
        print("FAILED: stage needs --destination.", file=sys.stderr)
        return 1

    artifact_path = args.artifact if args.artifact is not None else args.output
    if not artifact_path.is_file():
        print(
            f"FAILED: {artifact_path} is not a file. Run verify first, or pass --artifact.",
            file=sys.stderr,
        )
        return 1

    errors = verify_artifact(recreation, artifact_path)
    if errors:
        print(f"FAILED: {artifact_path} does not match the pinned artifact:", file=sys.stderr)
        for error in errors:
            print(f"  - {error}", file=sys.stderr)
        return 1

    try:
        created = stage_install(recreation, artifact_path, args.destination)
    except ValueError as error:
        print(f"FAILED: {error}", file=sys.stderr)
        return 1

    runtime = recreation["runtime"]
    for path in created:
        print(f"Staged {path}")
    print(
        "Run the reference with GRUG_REFERENCE=1 and GRUG_MODS_DIR pointing at a harness-only"
        " mods directory, using the loader the runtime names:"
        f" Minecraft {runtime['minecraft']} with {runtime['loader']} {runtime['loader_version']}."
    )
    return 0


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "command",
        choices=["plan", "verify", "stage"],
        help=(
            "plan prints what running the reference involves; verify downloads and hashes the"
            " artifact; stage writes the install into --destination"
        ),
    )
    parser.add_argument("about", type=Path, help="path to a mod's about.json")
    parser.add_argument(
        "--output",
        type=Path,
        default=Path("reference-artifact"),
        help="where verify writes the downloaded artifact",
    )
    parser.add_argument(
        "--artifact",
        type=Path,
        help="an already downloaded artifact for stage, verified before it is used",
    )
    parser.add_argument(
        "--destination",
        type=Path,
        help="the reference runtime's mods folder for stage",
    )
    args = parser.parse_args()

    recreation = load_recreation(args.about)
    if recreation is None:
        print(f"FAILED: {args.about} has no recreation block.", file=sys.stderr)
        return 1

    if args.command == "plan":
        print(install_plan(recreation))
        return 0

    artifact = recreation.get("artifact")
    if artifact is None:
        print(f"FAILED: {args.about} has no artifact to verify.", file=sys.stderr)
        return 1

    if args.command == "stage":
        return stage_command(recreation, args)

    try:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        download_artifact(recreation, args.output)
    except Exception as error:
        # A missing or moved artifact degrades regenerating the goldens, never verifying the port,
        # so this reports and exits rather than tracing back.
        print(f"FAILED: could not download {artifact['url']}: {error}", file=sys.stderr)
        return 1

    errors = verify_artifact(recreation, args.output)
    if errors:
        print(f"FAILED: {args.output} does not match the pinned artifact:", file=sys.stderr)
        for error in errors:
            print(f"  - {error}", file=sys.stderr)
        return 1

    print(f"Verified {args.output} against {artifact['url']}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
