"""Create an exact-byte release manifest; signing happens in a separate step."""
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
import zipfile

from release import ReleaseError, parse_tag, sha256_file, validate_jar

DEPENDENCIES = {'minecraft', 'java', 'fabricloader', 'fabric-api', 'fabric-language-kotlin'}

def create_manifest(jar: Path, tag: str, output: Path) -> None:
    version = parse_tag(tag)
    if '-' in version:
        raise ReleaseError('Only stable releases have an automatic-update manifest.')
    validate_jar(jar, version)
    with zipfile.ZipFile(jar) as archive:
        metadata_bytes = archive.read('fabric.mod.json')
        metadata = json.loads(metadata_bytes)
        if metadata.get('environment') != 'client' or set(metadata.get('depends', {})) != DEPENDENCIES:
            raise ReleaseError('Unexpected runtime dependency contract.')
        if 'updates/odyssey-update-helper.jar' not in archive.namelist():
            raise ReleaseError('Missing standalone updater.')
    if jar.stat().st_size > 32 * 1024 * 1024:
        raise ReleaseError('Release JAR exceeds the updater limit.')
    fields = {
        'format': '1', 'version': version,
        'url': f'https://github.com/alps-vale/odyssey-mod/releases/download/{tag}/odyssey-mod.jar',
        'size': str(jar.stat().st_size), 'sha256': sha256_file(jar),
        'metadataSha256': hashlib.sha256(metadata_bytes).hexdigest(),
        **{f'requires.{key}': value for key, value in metadata['depends'].items()},
    }
    if any(not isinstance(value, str) or '\n' in value or '\r' in value for value in fields.values()):
        raise ReleaseError('Invalid manifest value.')
    output.write_bytes(''.join(f'{key}={value}\n' for key, value in sorted(fields.items())).encode('utf-8'))

if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--jar', type=Path, required=True)
    parser.add_argument('--tag', required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    create_manifest(args.jar, args.tag, args.output)
