#!/usr/bin/env python3
"""
Migrate Ikanos capability YAML files from alpha3 `functions:` to `flows:`.

Usage:
    python scripts/migrate_aggregate_flows.py <path> [<path> ...]

Where <path> is a file or a directory. Directories are searched recursively
for *.yml and *.yaml files. The script is idempotent: files that already use
`flows:` are left unchanged.

This script targets the breaking rename introduced in story #463:
    aggregates.<ns>.functions: → aggregates.<ns>.flows:

It uses a regex-based approach rather than a full YAML parser so that
comments and formatting are preserved verbatim.

WARNING — Known false positive: Spectral configuration files (`.spectral.yaml`,
`.spectral.yml`) use `functions:` as a built-in keyword to register custom
JavaScript validation plugins. This pattern is NOT related to Ikanos aggregates
and must NOT be migrated. Exclude Spectral configuration files when running this
script, or review each match manually before accepting it. Example Spectral usage
that must be preserved:

    # .spectral.yaml
    functions:
      - check-binds-location-scheme
"""

import re
import sys
from pathlib import Path

# Pattern: 'functions:' at any indentation level inside an 'aggregates' block.
# We use a simple heuristic: replace every line that matches /^(\s+)functions:/
# with the same indentation but 'flows:'.  This is intentionally conservative —
# it only replaces lines where 'functions:' is the sole mapping key on that line,
# which is the only valid form in an Ikanos capability document.
PATTERN = re.compile(r'^(\s+)functions:', re.MULTILINE)
REPLACEMENT = r'\1flows:'


def migrate_file(path: Path) -> bool:
    """Return True if the file was modified."""
    original = path.read_text(encoding='utf-8')
    updated = PATTERN.sub(REPLACEMENT, original)
    if updated != original:
        path.write_text(updated, encoding='utf-8')
        return True
    return False


def migrate(paths: list[str]) -> None:
    total = 0
    modified = 0
    for raw in paths:
        p = Path(raw)
        if p.is_dir():
            candidates = list(p.rglob('*.yml')) + list(p.rglob('*.yaml'))
        elif p.is_file():
            candidates = [p]
        else:
            print(f'Warning: {raw} does not exist, skipping.', file=sys.stderr)
            continue

        for candidate in candidates:
            total += 1
            if migrate_file(candidate):
                modified += 1
                print(f'  migrated: {candidate}')

    print(f'\nDone — {modified}/{total} file(s) updated.')


if __name__ == '__main__':
    if len(sys.argv) < 2:
        print(f'Usage: {sys.argv[0]} <path> [<path> ...]', file=sys.stderr)
        sys.exit(1)
    migrate(sys.argv[1:])
