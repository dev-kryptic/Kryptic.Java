#!/usr/bin/env python3
"""Resolve the Maven Central version for dev.kryptic:daemon-client.

Patch (third number) auto-increments from the latest Maven Central release when
the incoming pom.xml version keeps the same major and minor.

If this commit already changed major or minor (1.1.0, 2.0.0, …), that version
is published as-is. The first publish (artifact not on Central yet) also
keeps the incoming version.
"""

from __future__ import annotations

import re
import sys
import urllib.error
import urllib.request
import xml.etree.ElementTree as ET
from pathlib import Path

GROUP_ID = "dev.kryptic"
ARTIFACT_ID = "daemon-client"
METADATA_URL = (
    "https://repo1.maven.org/maven2/"
    + GROUP_ID.replace(".", "/")
    + f"/{ARTIFACT_ID}/maven-metadata.xml"
)
POM = Path("pom.xml")
STABLE = re.compile(r"^(\d+)\.(\d+)\.(\d+)$")
PROJECT_VERSION = re.compile(
    r"(<artifactId>daemon-client</artifactId>\s*<version>)([^<]+)(</version>)",
    re.DOTALL,
)


def parse(version: str) -> tuple[int, int, int] | None:
    match = STABLE.match(version.strip())
    if not match:
        return None
    return int(match.group(1)), int(match.group(2)), int(match.group(3))


def read_pom_version() -> str:
    text = POM.read_text(encoding="utf-8")
    match = PROJECT_VERSION.search(text)
    if not match:
        raise SystemExit(f"No project version found in {POM}")
    return match.group(2).strip()


def latest_published() -> tuple[int, int, int] | None:
    try:
        with urllib.request.urlopen(METADATA_URL) as response:
            root = ET.fromstring(response.read())
    except urllib.error.HTTPError as error:
        if error.code == 404:
            return None
        raise
    versions = [
        parsed
        for version_elem in root.findall(".//versioning/versions/version")
        if (parsed := parse(version_elem.text or ""))
    ]
    return max(versions) if versions else None


def render(version: tuple[int, int, int]) -> str:
    return f"{version[0]}.{version[1]}.{version[2]}"


def main() -> None:
    incoming_raw = sys.argv[1] if len(sys.argv) > 1 and sys.argv[1] else read_pom_version()
    incoming = parse(incoming_raw)
    if incoming is None:
        raise SystemExit(f"Incoming version must be major.minor.patch, got: {incoming_raw}")

    published = latest_published()
    if published is None:
        resolved = incoming
        reason = "first publish, keep incoming version"
    elif incoming[0] != published[0] or incoming[1] != published[1]:
        resolved = incoming
        reason = "major or minor changed, keep incoming version"
    else:
        resolved = (published[0], published[1], published[2] + 1)
        reason = "same major.minor, bump patch"

    print(f"incoming={render(incoming)}", file=sys.stderr)
    print(
        f"published={render(published) if published else '(none)'}",
        file=sys.stderr,
    )
    print(f"reason={reason}", file=sys.stderr)
    print(render(resolved))


if __name__ == "__main__":
    main()
