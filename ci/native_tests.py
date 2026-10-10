"""Execute Linux-compiled updater checks on any supported Java 21 platform."""
from __future__ import annotations

import os
from pathlib import Path
import subprocess
import sys
import tempfile
import zipfile


def main() -> int:
    bundle = Path("build/native-tests/native-updater-tests.zip")
    reports = Path("build/test-results/native").resolve()
    with tempfile.TemporaryDirectory(prefix="odyssey-native-") as directory:
        root = Path(directory)
        with zipfile.ZipFile(bundle) as archive:
            archive.extractall(root)
        libraries = sorted((root / "lib").glob("*.jar"))
        launcher, = (root / "lib").glob("junit-platform-console-standalone-*.jar")
        classes = root / "classes"
        classpath = os.pathsep.join(map(str, [classes, *libraries]))
        return subprocess.run([
            "java", f"-Dodyssey.helper.jar={classes / 'updates/odyssey-update-helper.jar'}",
            "-jar", str(launcher), "execute", "--class-path", classpath,
            "--select-package", "org.odyssey.mod.update", "--include-engine", "junit-jupiter",
            "--fail-if-no-tests", "--reports-dir", str(reports), "--details", "summary",
            "--disable-ansi-colors",
        ], check=False).returncode


if __name__ == "__main__":
    sys.exit(main())
