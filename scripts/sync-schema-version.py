#!/usr/bin/env python3
"""
Script to synchronize the schema version across all YAML files.
"""

import re
from pathlib import Path
import sys

sys.path.insert(0, str(Path(__file__).parent))
from ikanos_version import extract_version_from_schema, update_yaml_version

def find_files(base_paths):
    """Finds all YAML files in the specified paths."""
    files = []

    for base_path in base_paths:
        path = Path(base_path)

        if path.is_file():
            files.append(path)
        elif path.is_dir():
            files.extend(path.rglob("*.yml"))
            files.extend(path.rglob("*.yaml"))
        else:
            print(f"[warn] path not found: {base_path}")

    return list(set(files))  # Deduplicate


def main():
    """Main function."""
    print("=" * 60)
    print("Ikanos schema version synchronization")
    print("=" * 60)

    version = extract_version_from_schema()

    # Systematic discovery - no per-module whitelist. Picks up every module's
    # test fixtures and every shipped examples directory, so a new module or
    # example folder is covered automatically.
    search_paths = [
        "modules/ikanos-docs/tutorial",
    ]
    repo_root = Path(".")
    search_paths += [str(p) for p in repo_root.glob("modules/*/src/test/resources") if p.is_dir()]
    search_paths += [
        str(p) for p in repo_root.rglob("schemas/examples")
        if p.is_dir() and "target" not in p.parts
    ]
    search_paths = sorted(set(search_paths))

    print(" Searching for YAML files in:")
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