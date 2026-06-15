#!/usr/bin/env python3
"""
Shared utilities for Ikanos version synchronization.
"""

import re
import xml.etree.ElementTree as ET
import sys


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
