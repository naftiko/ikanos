#!/usr/bin/env python3
"""
Shared utilities for Naftiko version synchronization.
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
        version_elem = root.find('.//maven:version', namespace)

        if version_elem is None:
            version_elem = root.find('.//version')

        if version_elem is None:
            raise ValueError("Version not found in pom.xml")

        version = version_elem.text.strip()
        clean_version = version.replace("-SNAPSHOT", "")

        print(f"✓ Version extracted from pom.xml: {version}", file=sys.stderr)
        print(f"✓ Cleaned version: {clean_version}", file=sys.stderr)

        return clean_version

    except Exception as e:
        print(f"✗ Error while reading pom.xml: {e}", file=sys.stderr)
        sys.exit(1)


def update_yaml_version(file_path, new_version):
    """Updates the version in a YAML file."""
    try:
        with open(file_path, 'r', encoding='utf-8') as f:
            content = f.read()

        pattern = r'(naftiko:\s*")[^"]*(")'
        replacement = rf'\g<1>{new_version}\g<2>'

        updated_content = re.sub(pattern, replacement, content)

        if content != updated_content:
            with open(file_path, 'w', encoding='utf-8') as f:
                f.write(updated_content)
            return True

        return False

    except Exception as e:
        print(f"✗ Error while updating YAML {file_path}: {e}", file=sys.stderr)
        return False
