#!/usr/bin/env python3
"""
Shared utilities for Ikanos version synchronization.
"""

import re
import json
import xml.etree.ElementTree as ET
import sys
from pathlib import Path

# Shared by bump-next-versions.py and unbump-current-version.py so the
# <revision> read/write logic lives in exactly one place - see
# read_pom_revision / write_pom_revision below.
POM_REVISION_PATTERN = re.compile(r"(<revision>)([^<]*)(</revision>)")


def read_pom_revision(pom_path="pom.xml"):
    """Reads the raw <revision> value from pom.xml, verbatim (no -SNAPSHOT
    stripping - use extract_version_from_pom for that). Exits with an error
    if the element is not found."""
    pom_path = Path(pom_path)
    content = pom_path.read_text(encoding="utf-8")

    match = POM_REVISION_PATTERN.search(content)
    if not match:
        print(f"[error] <revision> element not found in {pom_path}", file=sys.stderr)
        sys.exit(1)

    return match.group(2)


def write_pom_revision(pom_path, new_revision):
    """Writes new_revision into the <revision> element of pom.xml. Returns
    True if the file was changed, False if it was already at new_revision
    (clean no-op). Exits with an error if the element is not found."""
    pom_path = Path(pom_path)
    content = pom_path.read_text(encoding="utf-8")

    updated_content, count = POM_REVISION_PATTERN.subn(
        rf"\g<1>{new_revision}\g<3>", content, count=1
    )
    if count == 0:
        print(f"[error] <revision> element not found in {pom_path}", file=sys.stderr)
        sys.exit(1)

    if updated_content == content:
        return False

    pom_path.write_text(updated_content, encoding="utf-8")
    return True


def extract_version_from_pom(pom_path="pom.xml"):
    """Extracts the version from pom.xml and removes the -SNAPSHOT suffix."""
    try:
        tree = ET.parse(pom_path)
        root = tree.getroot()

        namespace = {'maven': 'http://maven.apache.org/POM/4.0.0'}
        version_elem = root.find('.//maven:properties/maven:revision', namespace)

        if version_elem is None:
            version_elem = root.find('.//version')

        if version_elem is None:
            raise ValueError("Version not found in pom.xml")

        version = version_elem.text.strip()
        clean_version = version.replace("-SNAPSHOT", "")

        print(f"[ok] Version extracted from pom.xml: {version}", file=sys.stderr)
        print(f"[ok] Cleaned version: {clean_version}", file=sys.stderr)

        return clean_version

    except Exception as e:
        print(f"[error] Error while reading pom.xml: {e}", file=sys.stderr)
        sys.exit(1)


def extract_version_from_schema(schema_path="modules/ikanos-spec/src/main/resources/schemas/ikanos-schema.json"):
    """Extracts the ikanos spec version from the JSON schema's properties.ikanos.const."""
    try:
        with open(schema_path, 'r', encoding='utf-8') as f:
            schema = json.load(f)

        version = schema.get('properties', {}).get('ikanos', {}).get('const')

        if version is None:
            raise ValueError("properties.ikanos.const not found in schema")

        print(f"[ok] Version extracted from schema: {version}", file=sys.stderr)

        return version

    except Exception as e:
        print(f"[error] Error while reading schema {schema_path}: {e}", file=sys.stderr)
        sys.exit(1)


def update_yaml_version(file_path, new_version):
    """Updates the version in a YAML file."""
    try:
        with open(file_path, 'r', encoding='utf-8') as f:
            content = f.read()

        # Matches both quoted (ikanos: "1.0.0") and unquoted (ikanos: 1.0.0)
        # values, on a single line, and preserves whichever quoting was used.
        pattern = re.compile(r'^(\s*ikanos:[ \t]*)("?)[^"\r\n]*?\2[ \t]*$', re.MULTILINE)

        def _replace(match):
            return f'{match.group(1)}{match.group(2)}{new_version}{match.group(2)}'

        updated_content = pattern.sub(_replace, content)

        if content != updated_content:
            with open(file_path, 'w', encoding='utf-8') as f:
                f.write(updated_content)
            return True

        return False

    except Exception as e:
        print(f"[error] Error while updating YAML {file_path}: {e}", file=sys.stderr)
        return False
