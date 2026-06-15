#!/usr/bin/env python3
"""
Script to synchronize the pom.xml version across all YAML and JSON files.
"""

import re
import json
from pathlib import Path
import sys

sys.path.insert(0, str(Path(__file__).parent))
from ikanos_version import extract_version_from_pom, update_yaml_version


def update_json_version(file_path, new_version):
    """Updates version in JSON (ikanos.const + $id URL)."""
    try:
        with open(file_path, 'r', encoding='utf-8') as f:
            data = json.load(f)

        updated = False

        if (
            "properties" in data and
            "ikanos" in data["properties"] and
            "const" in data["properties"]["ikanos"]
        ):
            if data["properties"]["ikanos"]["const"] != new_version:
                data["properties"]["ikanos"]["const"] = new_version
                updated = True

        if "$id" in data and isinstance(data["$id"], str):
            old_id = data["$id"]

            # Replace version inside URL
            new_id = re.sub(
                r'/v[^/]+/', 
                f'/v{new_version}/', 
                old_id
            )

            if new_id != old_id:
                data["$id"] = new_id
                updated = True

        if updated:
            with open(file_path, 'w', encoding='utf-8') as f:
                json.dump(data, f, indent=2)
            return True

        return False

    except Exception as e:
        print(f"[error] while updating JSON {file_path}: {e}", file=sys.stderr)
        return False


def find_files(base_paths):
    """Finds all YAML and JSON files in the specified paths."""
    files = []

    for base_path in base_paths:
        path = Path(base_path)

        if path.is_file():
            files.append(path)
        elif path.is_dir():
            files.extend(path.rglob("*.yml"))
            files.extend(path.rglob("*.yaml"))
            files.extend(path.rglob("*.json"))
        else:
            print(f"[warn] path not found: {base_path}")

    return list(set(files))  # Deduplicate


def main():
    """Main function."""
    print("=" * 60)
    print("Ikanos version synchronization")
    print("=" * 60)

    version = extract_version_from_pom()

    # Systematic discovery - no per-module whitelist. Picks up every module's
    # test fixtures and every shipped examples directory, so a new module or
    # example folder is covered automatically.
    search_paths = [
        "ikanos-spec/src/main/resources/schemas/ikanos-schema.json",
        "ikanos-docs/tutorial",
    ]
    repo_root = Path(".")
    search_paths += [str(p) for p in repo_root.glob("*/src/test/resources") if p.is_dir()]
    search_paths += [
        str(p) for p in repo_root.rglob("schemas/examples")
        if p.is_dir() and "target" not in p.parts
    ]
    search_paths = sorted(set(search_paths))

    print(" Searching for YAML/JSON files in:")
    for path in search_paths:
        print(f"   - {path}")

    files = find_files(search_paths)

    if not files:
        print("\n[warn] No files found!")
        return

    print(f" {len(files)} file(s) found")

    updated_count = 0

    for file in files:
        if file.suffix in [".yml", ".yaml"]:
            updated = update_yaml_version(file, version)
        elif file.suffix == ".json":
            updated = update_json_version(file, version)
        else:
            continue

        if updated:
            print(f"   [updated] {file}")
            updated_count += 1
        else:
            print(f"   [no change] {file}")

    print("\n" + "=" * 60)
    print(f" Done! {updated_count}/{len(files)} file(s) updated")
    print(f"   Synchronized version: {version}")
    print("=" * 60)


if __name__ == "__main__":
    main()