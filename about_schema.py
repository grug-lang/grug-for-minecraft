#!/usr/bin/env python3
import json
import sys
from pathlib import Path

import jsonschema

# The loaders/ directory names mapped to the Minecraft version each is for, so a recreation block
# cannot pin a loader and a Minecraft version that disagree. Every loader directory belongs here.
LOADER_MINECRAFT = {
    "1.2.5-forge": "1.2.5",
    "1.20.6-forge": "1.20.6",
    "a1.1.2_01-ornithe": "a1.1.2_01",
    "b1.7.3-ornithe": "b1.7.3",
    "b1.7.3-stationapi": "b1.7.3",
}

# The Java versions the loaders are tested on, so a recreation block cannot quietly name another.
JAVA_VERSIONS = ("8", "17", "21")


def runtime_errors(runtime: dict, loaders_dir: Path | None = None) -> list:
    """The runtime rules that need the repository, not only the recreation block.

    The loader has to be a real ``loaders/`` directory, and its Minecraft and Java versions have to
    be ones that loader actually targets, so a recreation block cannot point at a runtime that
    cannot exist.
    """
    errors = []
    if loaders_dir is None:
        loaders_dir = Path(__file__).resolve().parent / "loaders"

    loader = runtime.get("loader")
    if isinstance(loader, str):
        if not (loaders_dir / loader).is_dir():
            errors.append(
                (
                    "/recreation/runtime/loader",
                    f"{loader!r} is not a directory under {loaders_dir.name}/;"
                    " runtime.loader must name the loader directory the reference runs on.",
                )
            )
        elif loader not in LOADER_MINECRAFT:
            errors.append(
                (
                    "/recreation/runtime/loader",
                    f"{loader!r} has no Minecraft version in LOADER_MINECRAFT;"
                    " add the loader to that table so minecraft can be checked.",
                )
            )
        else:
            minecraft = runtime.get("minecraft")
            expected = LOADER_MINECRAFT[loader]
            if isinstance(minecraft, str) and minecraft != expected:
                errors.append(
                    (
                        "/recreation/runtime/minecraft",
                        f"minecraft is {minecraft!r} but loader {loader!r} is for {expected!r}.",
                    )
                )

    java = runtime.get("java")
    if isinstance(java, str) and java not in JAVA_VERSIONS:
        errors.append(
            (
                "/recreation/runtime/java",
                f"java is {java!r} but must be one of {', '.join(JAVA_VERSIONS)}.",
            )
        )

    return errors


def recreation_errors(data: dict) -> list:
    """The recreation rules jsonschema cannot express.

    Returns (json_path, message) pairs, matching how the schema errors are reported.
    """
    errors = []
    recreation = data.get("recreation")
    if not isinstance(recreation, dict):
        return errors

    artifacts = recreation.get("artifacts")
    if not isinstance(artifacts, list):
        artifacts = []

    artifact_names = {}
    for index, artifact in enumerate(artifacts):
        if not isinstance(artifact, dict):
            continue

        redistribution = artifact.get("redistribution")
        allowed = isinstance(redistribution, dict) and redistribution.get("allowed") is True
        if artifact.get("mirrors") and not allowed:
            errors.append(
                (
                    f"/recreation/artifacts/{index}/mirrors",
                    "a mirror redistributes the artifact, so mirrors requires"
                    f" /recreation/artifacts/{index}/redistribution/allowed to be true.",
                )
            )

        name = artifact.get("name")
        if not isinstance(name, str):
            continue
        if name in artifact_names:
            errors.append(
                (
                    f"/recreation/artifacts/{index}/name",
                    f"artifact names must be unique; {name!r} is already used by"
                    f" /recreation/artifacts/{artifact_names[name]}/name.",
                )
            )
        else:
            artifact_names[name] = index

    if recreation.get("fidelity") == "exact" and not artifacts:
        errors.append(
            (
                "/recreation/artifacts",
                "an exact recreation needs artifacts to diff against;"
                " add /recreation/artifacts or lower /recreation/fidelity.",
            )
        )

    install = recreation.get("install")
    files = install.get("files") if isinstance(install, dict) else None
    if isinstance(files, list):
        for name in files:
            if name not in artifact_names:
                errors.append(
                    (
                        "/recreation/install/files",
                        f"{name!r} is not the name of any artifact;"
                        " every install file must name an artifact.",
                    )
                )

    runtime = recreation.get("runtime")
    if isinstance(runtime, dict):
        errors.extend(runtime_errors(runtime))

    return errors


def main() -> int:
    repo_root = Path(__file__).resolve().parent
    schema_path = repo_root / "about_schema.json"
    mods_dir = repo_root / "mods"

    if not schema_path.is_file():
        print(f"Error: Schema file not found at {schema_path}", file=sys.stderr)
        return 1

    try:
        with open(schema_path, "r", encoding="utf-8") as f:
            schema = json.load(f)
    except Exception as e:
        print(f"Error reading schema file {schema_path}: {e}", file=sys.stderr)
        return 1

    validator_cls = jsonschema.validators.validator_for(schema)
    validator_cls.check_schema(schema)
    validator = validator_cls(schema)

    about_files = sorted(mods_dir.rglob("about.json"))
    if not about_files:
        print(f"Warning: No about.json files found under {mods_dir}", file=sys.stderr)
        return 0

    has_error = False
    for file_path in about_files:
        rel_path = file_path.relative_to(repo_root)
        try:
            with open(file_path, "r", encoding="utf-8") as f:
                data = json.load(f)
        except Exception as e:
            print(f"FAILED: {rel_path}\n  JSON Decode Error: {e}\n", file=sys.stderr)
            has_error = True
            continue

        errors = list(validator.iter_errors(data))
        recreation_problems = recreation_errors(data)
        if errors or recreation_problems:
            has_error = True
            print(f"FAILED: {rel_path}", file=sys.stderr)
            for err in errors:
                json_path = "/" + "/".join(str(p) for p in err.path) if err.path else "/"
                print(f"  - At '{json_path}': {err.message}", file=sys.stderr)
            for json_path, message in recreation_problems:
                print(f"  - At '{json_path}': {message}", file=sys.stderr)
            print("", file=sys.stderr)
        else:
            print(f"PASSED: {rel_path}")

    if has_error:
        print(
            "Validation failed: one or more about.json files do not conform to schema.",
            file=sys.stderr,
        )
        return 1

    print("All about.json files validated successfully.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
