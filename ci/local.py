#!/usr/bin/env python3
"""Explicit local PrismLauncher install/menu/launch conveniences."""

from __future__ import annotations

import os
import subprocess
import sys
import urllib.error
import urllib.request
from pathlib import Path


INSTANCE = os.environ["ODYSSEY_PRISM_INSTANCE"]
PRISM_ROOT = Path(os.environ["PRISM_ROOT"])
MODS = PRISM_ROOT / "instances" / INSTANCE / "minecraft" / "mods"
DEPENDENCIES = {
    "fabric-api-0.141.6+1.21.11.jar": (
        "https://maven.fabricmc.net/net/fabricmc/fabric-api/fabric-api/"
        "0.141.6+1.21.11/fabric-api-0.141.6+1.21.11.jar"
    ),
    "fabric-language-kotlin-1.13.13+kotlin.2.4.10.jar": (
        "https://maven.fabricmc.net/net/fabricmc/fabric-language-kotlin/"
        "1.13.13+kotlin.2.4.10/fabric-language-kotlin-1.13.13+kotlin.2.4.10.jar"
    ),
}


def install() -> None:
    MODS.mkdir(parents=True, exist_ok=True)
    for pattern, expected in (("fabric-api-*.jar", next(iter(DEPENDENCIES))),
                              ("fabric-language-kotlin-*.jar", list(DEPENDENCIES)[1])):
        for candidate in MODS.glob(pattern):
            if candidate.name != expected:
                candidate.unlink()
    for filename, url in DEPENDENCIES.items():
        destination = MODS / filename
        if destination.exists():
            continue
        request = urllib.request.Request(url, headers={"User-Agent": "Odyssey-Mise-Installer"})
        try:
            with urllib.request.urlopen(request, timeout=45) as response:
                destination.write_bytes(response.read())
        except (urllib.error.URLError, TimeoutError, OSError):
            destination.unlink(missing_ok=True)
            raise
    jar = Path("build/libs/odyssey-mod.jar").resolve()
    link = MODS / "odyssey-mod.jar"
    link.unlink(missing_ok=True)
    link.symlink_to(jar)
    print(f"Installed Odyssey into {MODS}")


def main() -> int:
    if len(sys.argv) != 2 or sys.argv[1] not in {"install", "menu", "launch"}:
        print("usage: python ci/local.py {install|menu|launch}", file=sys.stderr)
        return 2
    action = sys.argv[1]
    if action == "install":
        install()
    elif action == "menu":
        subprocess.run(["prismlauncher", "--launch", INSTANCE, "--show-window"], check=True)
    elif action == "launch":
        subprocess.run(["prismlauncher", "--launch", INSTANCE, "--server",
                        "play.wynncraft.com", "--show-window"], check=True)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
