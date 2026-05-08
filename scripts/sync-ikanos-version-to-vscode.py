#!/usr/bin/env python3
"""
Script to synchronize the Ikanos version from pom.xml into the VS Code extension package.json.
"""

import argparse
import json
from pathlib import Path
import sys

sys.path.insert(0, str(Path(__file__).parent))
from ikanos_version import extract_version_from_pom


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
    parser = argparse.ArgumentParser(description="Sync Ikanos version to VS Code extension package.json")
    parser.add_argument("--pom", required=True, help="Path to ikanos pom.xml")
    parser.add_argument("--target", required=True, help="Path to extensions/naftiko-vscode/package.json")
    args = parser.parse_args()

    version = extract_version_from_pom(args.pom)

    target = Path(args.target)
    if update_package_json_version(target, version):
        print(f"   ✓ {target}", file=sys.stderr)
    else:
        print(f"   - {target} (no change)", file=sys.stderr)

    # Output clean version for workflow capture
    print(version)


if __name__ == "__main__":
    main()
