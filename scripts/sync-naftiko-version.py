#!/usr/bin/env python3
"""
Script to synchronize the pom.xml version across all YAML and JSON files.
"""

import re
import json
from pathlib import Path
import sys

sys.path.insert(0, str(Path(__file__).parent))
from naftiko_version import extract_version_from_pom, update_yaml_version


def update_json_version(file_path, new_version):
    """Updates version in JSON (naftiko.const + $id URL)."""
    try:
        with open(file_path, 'r', encoding='utf-8') as f:
            data = json.load(f)

        updated = False

        if (
            "properties" in data and
            "naftiko" in data["properties"] and
            "const" in data["properties"]["naftiko"]
        ):
            if data["properties"]["naftiko"]["const"] != new_version:
                data["properties"]["naftiko"]["const"] = new_version
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
        print(f" Error while updating JSON {file_path}: {e}", file=sys.stderr)
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
            print(f"⚠ Path not found: {base_path}")

    return list(set(files))  # Deduplicate


def main():
    """Main function."""
    print("=" * 60)
    print("Naftiko version synchronization")
    print("=" * 60)

    version = extract_version_from_pom()

    search_paths = [
        "src/main/resources/schemas/naftiko-schema.json",
        "src/main/resources/tutorial",
        "src/test/resources",
    ]

    print(" Searching for YAML/JSON files in:")
    for path in search_paths:
        print(f"   - {path}")

    files = find_files(search_paths)

    if not files:
        print("\n⚠ No files found!")
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
            print(f"   ✓ {file}")
            updated_count += 1
        else:
            print(f"   - {file} (no change)")

    print("\n" + "=" * 60)
    print(f" Done! {updated_count}/{len(files)} file(s) updated")
    print(f"   Synchronized version: {version}")
    print("=" * 60)


if __name__ == "__main__":
    main()