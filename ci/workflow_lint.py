#!/usr/bin/env python3
"""Run the pinned workflow linters with native executable discovery."""

from __future__ import annotations

import subprocess


def main() -> int:
    for command in (["actionlint", ".github/workflows/odyssey.yml"],
                    ["zizmor", ".github/workflows/odyssey.yml"]):
        result = subprocess.run(command, check=False)
        if result.returncode:
            return result.returncode
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
