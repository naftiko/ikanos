#!/usr/bin/env python3
"""
Script to synchronize the Ikanos schema version from ikanos-schema.json into Warden backstage skeleton templates.
"""

import argparse
from pathlib import Path
import sys

sys.path.insert(0, str(Path(__file__).parent))
from ikanos_version import extract_version_from_schema, update_yaml_version


def main():
    parser = argparse.ArgumentParser(description="Sync Ikanos schema version to Warden backstage skeletons")
    parser.add_argument("--schema", required=True, help="Path to ikanos-schema.json")
    parser.add_argument("--target", required=True, help="Path to skeleton capabilities directory")
    args = parser.parse_args()

    version = extract_version_from_schema(args.schema)

    target = Path(args.target)
    files = list(target.glob("*.ikanos.yml"))

    if not files:
        print(f"⚠ No .ikanos.yml files found in {target}", file=sys.stderr)
    else:
        for file in files:
            if update_yaml_version(file, version):
                print(f"   ✓ {file}", file=sys.stderr)
            else:
                print(f"   - {file} (no change)", file=sys.stderr)

    # Output clean version for workflow capture
    print(version)


if __name__ == "__main__":
    main()
