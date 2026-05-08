#!/usr/bin/env python3
"""
Script to synchronize the Ikanos version from pom.xml into Backstage skeleton templates.
"""

import argparse
from pathlib import Path
import sys

sys.path.insert(0, str(Path(__file__).parent))
from ikanos_version import extract_version_from_pom, update_yaml_version


def main():
    parser = argparse.ArgumentParser(description="Sync Ikanos version to Backstage skeletons")
    parser.add_argument("--pom", required=True, help="Path to ikanos pom.xml")
    parser.add_argument("--target", required=True, help="Path to skeleton capabilities directory")
    args = parser.parse_args()

    version = extract_version_from_pom(args.pom)

    target = Path(args.target)
    files = list(target.glob("*.naftiko.yml"))

    if not files:
        print(f"⚠ No .naftiko.yml files found in {target}", file=sys.stderr)
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
