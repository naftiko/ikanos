#!/usr/bin/env python3
"""
Script to synchronize the Ikanos engine version from pom.xml into the Naftiko Crafter package.json.
"""

import argparse
import json
from pathlib import Path
import re
import sys

sys.path.insert(0, str(Path(__file__).parent))
from ikanos_version import extract_version_from_pom


def convert_pom_version_to_crafter_version(pom_version):
    """Converts a pom.xml version string to a Crafter-compatible version format.

    - If the version is composed of 1 to 4 numbers separated by dots, and each
      number is between 0 and 2147483647, the version is returned as-is.
    - If the version ends with "-betaX" (X being a number), returns "0.X.0".
    - Otherwise, prints an error message and exits with status 1.
    """
    parts = pom_version.split(".")
    if 1 <= len(parts) <= 4 and all(
        part.isdigit() and 0 <= int(part) <= 2147483647 for part in parts
    ):
        return pom_version

    match = re.search(r"-beta(\d+)$", pom_version)
    if match:
        return f"0.{match.group(1)}.0"

    print(f"Impossible to convert pom version {pom_version} to crafter version format", file=sys.stderr)
    sys.exit(1)


def update_package_json_version(file_path, new_version):
    """Updates the version field in a package.json file."""
    try:
        with open(file_path, 'r', encoding='utf-8') as f:
            data = json.load(f)

        if data.get("version") == new_version:
            return False

        data["version"] = new_version

        with open(file_path, 'w', encoding='utf-8') as f:
            json.dump(data, f, indent=2, ensure_ascii=False)
            f.write('\n')

        return True

    except Exception as e:
        print(f"✗ Error while updating {file_path}: {e}", file=sys.stderr)
        sys.exit(1)


def main():
    parser = argparse.ArgumentParser(description="Sync Ikanos engine version to Naftiko Crafter package.json")
    parser.add_argument("--pom", required=True, help="Path to ikanos pom.xml")
    parser.add_argument("--target", required=True, help="Path to extensions/naftiko-crafter/package.json")
    args = parser.parse_args()

    version = extract_version_from_pom(args.pom)
    crafter_version = convert_pom_version_to_crafter_version(version)

    target = Path(args.target)
    if update_package_json_version(target, crafter_version):
        print(f"   ✓ {target}", file=sys.stderr)
    else:
        print(f"   - {target} (no change)", file=sys.stderr)

    # Output clean version for workflow capture
    print(crafter_version)


if __name__ == "__main__":
    main()
