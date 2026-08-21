#!/usr/bin/env python3
"""
Script to bump the Ikanos engine version (pom.xml `revision`) and, optionally,
the Ikanos schema version (`ikanos-schema.json`).

The schema version bump updates two targeted spots - the versioned `$id` URL
and `properties.ikanos.const` - each matched by an anchored regex. A version
string appearing anywhere else in the file (a description, an example) is
deliberately left untouched.

Synchronizing the new schema version across the rest of the repository (YAML
test fixtures, examples, tutorial capabilities) is NOT done by this script -
run `scripts/sync-schema-version.py` afterwards, same as
`.github/workflows/bump-next-versions.yml` does.
"""

import argparse
import re
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))
from ikanos_version import extract_version_from_schema

VERSION_PATTERN = re.compile(r"^\d+\.\d+\.\d+(-[0-9A-Za-z.-]+)?$")


def validate_version_format(version, label):
    """Validates that version matches the expected semver-like shape
    (e.g. '1.0.0-beta6') and does not already carry the -SNAPSHOT suffix
    (update_pom_revision appends it automatically). Exits with an error
    before any file is touched if the format is wrong, catching typos/
    swapped inputs early."""
    if version.endswith("-SNAPSHOT"):
        print(
            f"[error] {label} '{version}' must not include the -SNAPSHOT suffix "
            f"- it is appended automatically",
            file=sys.stderr,
        )
        sys.exit(1)
    if not VERSION_PATTERN.match(version):
        print(
            f"[error] {label} '{version}' does not match the expected format "
            f"<major>.<minor>.<patch>[-<label>] (e.g. 1.0.0-beta6)",
            file=sys.stderr,
        )
        sys.exit(1)


def update_pom_revision(pom_path, new_engine_version):
    """Updates the <revision> element in pom.xml to '<new_engine_version>-SNAPSHOT'."""
    pom_path = Path(pom_path)
    content = pom_path.read_text(encoding="utf-8")

    new_revision = f"{new_engine_version}-SNAPSHOT"
    pattern = re.compile(r"(<revision>)[^<]*(</revision>)")

    if not pattern.search(content):
        print(f"[error] <revision> element not found in {pom_path}", file=sys.stderr)
        sys.exit(1)

    updated_content = pattern.sub(rf"\g<1>{new_revision}\g<2>", content, count=1)

    if updated_content == content:
        print(f"[ok] {pom_path} already at revision {new_revision}", file=sys.stderr)
        return False

    pom_path.write_text(updated_content, encoding="utf-8")
    print(f"[ok] {pom_path}: revision -> {new_revision}", file=sys.stderr)
    return True


def update_schema_version(schema_path, new_schema_version):
    """Updates the schema version in two targeted spots: the versioned `$id` URL
    and `properties.ikanos.const`. Each is matched by a precise, anchored regex
    (mirroring update_pom_revision) rather than a blind string replace, so a
    version string that happens to appear elsewhere in the file (a description,
    an example, a comment) is never touched."""
    schema_path = Path(schema_path)
    old_version = extract_version_from_schema(str(schema_path))

    if old_version == new_schema_version:
        print(f"[ok] {schema_path} already at schema version {new_schema_version}", file=sys.stderr)
        return False

    content = schema_path.read_text(encoding="utf-8")
    old_version_re = re.escape(old_version)

    # Targets the versioned $id URL, e.g. "$id": "https://ikanos.io/schemas/v1.0.0-beta5/ikanos.json"
    id_pattern = re.compile(
        r'("\$id":\s*"https://ikanos\.io/schemas/v)' + old_version_re + r'(/ikanos\.json")'
    )
    # Targets only properties.ikanos.const, not any other "const" field in the schema
    # (e.g. TunnelZiti.type or ExposesRest.type also use "const"). Anchored only on
    # the "ikanos" key and the first following "const" - not on the exact key order/
    # adjacency of "type"/"description" - so it stays robust to a future reordering
    # or a description reworded to include a quoted term, while [^{}] keeps the
    # match scoped to the "ikanos" object so sibling "const" fields stay out of reach.
    const_pattern = re.compile(
        r'("ikanos":\s*\{(?:[^{}]*?)"const":\s*")' + old_version_re + r'(")'
    )

    updated_content, id_count = id_pattern.subn(rf"\g<1>{new_schema_version}\g<2>", content)
    updated_content, const_count = const_pattern.subn(rf"\g<1>{new_schema_version}\g<2>", updated_content)

    if id_count == 0:
        print(f"[error] versioned $id URL for '{old_version}' not found in {schema_path}", file=sys.stderr)
        sys.exit(1)
    if const_count == 0:
        print(f"[error] properties.ikanos.const '{old_version}' not found in {schema_path}", file=sys.stderr)
        sys.exit(1)

    schema_path.write_text(updated_content, encoding="utf-8")
    print(f"[ok] {schema_path}: {old_version} -> {new_schema_version}", file=sys.stderr)
    return True


def main():
    parser = argparse.ArgumentParser(description="Bump the Ikanos engine (and optionally schema) version.")
    parser.add_argument("--pom", default="pom.xml", help="Path to pom.xml")
    parser.add_argument(
        "--schema",
        default="modules/ikanos-spec/src/main/resources/schemas/ikanos-schema.json",
        help="Path to ikanos-schema.json",
    )
    parser.add_argument("--next-engine-version", default=None, help="Next engine version, e.g. 1.0.0-beta6")
    parser.add_argument("--next-schema-version", default=None, help="Next schema version, e.g. 1.0.0-beta5")
    args = parser.parse_args()

    print("=" * 60)
    print("Ikanos version bump")
    print("=" * 60)

    if not args.next_engine_version and not args.next_schema_version:
        print(
            "[error] at least one of --next-engine-version or --next-schema-version must be provided",
            file=sys.stderr,
        )
        sys.exit(1)

    if args.next_engine_version:
        validate_version_format(args.next_engine_version, "--next-engine-version")
    if args.next_schema_version:
        validate_version_format(args.next_schema_version, "--next-schema-version")

    if args.next_engine_version:
        update_pom_revision(args.pom, args.next_engine_version)
    else:
        print("[ok] no engine version provided, skipping engine bump", file=sys.stderr)

    if args.next_schema_version:
        update_schema_version(args.schema, args.next_schema_version)
    else:
        print("[ok] no schema version provided, skipping schema bump", file=sys.stderr)

    print("=" * 60)


if __name__ == "__main__":
    main()
