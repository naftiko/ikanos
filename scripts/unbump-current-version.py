#!/usr/bin/env python3
"""
Script to remove the "-SNAPSHOT" suffix from the Ikanos engine version
(pom.xml `revision`), turning the current snapshot into a release version.

This is the counterpart of `bump-next-versions.py`: that script moves the
`revision` *forward* to the next snapshot, this one *drops* the snapshot
suffix from the current `revision` in place (e.g. "1.0.0-beta5-SNAPSHOT" ->
"1.0.0-beta5"). It does not touch `ikanos-schema.json` - the schema version
is not suffixed with "-SNAPSHOT" and is bumped independently.
"""

import argparse
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))
from ikanos_version import read_pom_revision, write_pom_revision


def unbump_pom_revision(pom_path):
    """Removes the "-SNAPSHOT" suffix from the <revision> element in pom.xml.
    Mirrors update_pom_revision's no-op behavior in bump-next-versions.py: a
    revision that already has no -SNAPSHOT suffix is a clean no-op (returns
    False), not a failure - the same underlying "nothing to change" situation
    the twin script already treats as success."""
    current_revision = read_pom_revision(pom_path)

    if not current_revision.endswith("-SNAPSHOT"):
        print(
            f"[ok] {pom_path} already at revision {current_revision} (no -SNAPSHOT suffix)",
            file=sys.stderr,
        )
        return False

    new_revision = current_revision[: -len("-SNAPSHOT")]
    write_pom_revision(pom_path, new_revision)
    print(f"[ok] {pom_path}: revision {current_revision} -> {new_revision}", file=sys.stderr)
    return True


def main():
    parser = argparse.ArgumentParser(
        description="Remove the -SNAPSHOT suffix from the current Ikanos engine version (pom.xml revision)."
    )
    parser.add_argument("--pom", default="pom.xml", help="Path to pom.xml")
    args = parser.parse_args()

    print("=" * 60)
    print("Ikanos version unbump")
    print("=" * 60)

    unbump_pom_revision(args.pom)

    print("=" * 60)


if __name__ == "__main__":
    main()
