#!/usr/bin/env python3
"""Invoke the Gradle wrapper with equivalent arguments on Unix and Windows."""

from __future__ import annotations

import os
import subprocess
import sys


def main() -> int:
    if len(sys.argv) != 2 or sys.argv[1] not in {"test", "build"}:
        print("usage: python ci/gradle.py {test|build}", file=sys.stderr)
        return 2
    wrapper = ["cmd.exe", "/d", "/c", "gradlew.bat"] if os.name == "nt" else ["./gradlew"]
    backend = os.environ.get("ODYSSEY_BACKEND_URL", "https://odyssey.notes.supply")
    version = os.environ.get("ODYSSEY_VERSION") or "0.2.0-SNAPSHOT"
    command = [*wrapper, sys.argv[1], f"-Pbackend_url={backend}", f"-Pmod_version={version}"]
    return subprocess.run(command, check=False).returncode


if __name__ == "__main__":
    raise SystemExit(main())
