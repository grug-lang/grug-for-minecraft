#!/usr/bin/env python3
import json
import sys
from pathlib import Path

import jsonschema


def recreation_errors(data: dict) -> list:
    """The recreation rules jsonschema cannot express.

    Returns (json_path, message) pairs, matching how the schema errors are reported.
    """
    errors = []
    recreation = data.get("recreation")
    if not isinstance(recreation, dict):
        return errors

    artifact = recreation.get("artifact")
    if isinstance(artifact, dict):
        redistribution = artifact.get("redistribution")
        allowed = isinstance(redistribution, dict) and redistribution.get("allowed") is True
        if artifact.get("mirrors") and not allowed:
            errors.append(
                (
                    "/recreation/artifact/mirrors",
                    "a mirror redistributes the artifact, so mirrors requires"
                    " /recreation/artifact/redistribution/allowed to be true.",
                )
            )

    if recreation.get("fidelity") == "exact" and not isinstance(artifact, dict):
        errors.append(
            (
                "/recreation/artifact",
                "an exact recreation needs the artifact to diff against;"
                " add /recreation/artifact or lower /recreation/fidelity.",
            )
        )

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
