from __future__ import annotations

import json
import sys
import tempfile
import unittest
import zipfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))
import release
import update_manifest


class ReleaseValidationTests(unittest.TestCase):
    def test_update_manifest_binds_exact_jar_metadata_and_stable_compatibility(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            jar = Path(temp_dir) / 'odyssey-mod.jar'
            self._jar(jar)
            with zipfile.ZipFile(jar) as archive:
                metadata = json.loads(archive.read('fabric.mod.json'))
            metadata['environment'] = 'client'
            metadata['depends'] = {key: '>=1' for key in update_manifest.DEPENDENCIES}
            with zipfile.ZipFile(jar, 'w') as archive:
                archive.writestr('fabric.mod.json', json.dumps(metadata))
                archive.writestr('odyssey.mixins.json', '{}')
                archive.writestr(release.ENTRYPOINT_CLASS, b'class bytes')
                archive.writestr('updates/odyssey-update-helper.jar', b'helper fixture')
            output = Path(temp_dir) / 'update.manifest'
            update_manifest.create_manifest(jar, 'v1.2.3', output)
            fields = dict(line.split('=', 1) for line in output.read_text().splitlines())
            self.assertEqual(fields['sha256'], release.sha256_file(jar))
            self.assertEqual(int(fields['size']), jar.stat().st_size)
            self.assertEqual(fields['url'], 'https://github.com/alps-vale/odyssey-mod/releases/download/v1.2.3/odyssey-mod.jar')
            self.assertEqual(fields['requires.java'], '>=1')
            with self.assertRaises(release.ReleaseError):
                update_manifest.create_manifest(jar, 'v1.2.3-rc.1', output)

    def test_semver_tags_and_prerelease(self) -> None:
        self.assertEqual(release.parse_tag("v1.2.3"), "1.2.3")
        self.assertEqual(release.parse_tag("v0.1.0-rc.2"), "0.1.0-rc.2")
        for tag in ("1.2.3", "v01.2.3", "v1.2", "v1.2.3-01"):
            with self.subTest(tag=tag), self.assertRaises(release.ReleaseError):
                release.parse_tag(tag)

    def _jar(self, path: Path, version: str = "1.2.3") -> None:
        manifest = {
            "id": "odyssey",
            "version": version,
            "entrypoints": {"client": [{"adapter": "kotlin", "value": "org.odyssey.mod.OdysseyMod"}]},
            "mixins": ["odyssey.mixins.json"],
        }
        with zipfile.ZipFile(path, "w") as archive:
            archive.writestr("fabric.mod.json", json.dumps(manifest))
            archive.writestr("odyssey.mixins.json", "{}")
            archive.writestr("org/odyssey/mod/OdysseyMod.class", b"class bytes")

    def test_only_expected_remapped_jar_and_manifest_version_are_accepted(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            jar = Path(temp_dir) / "odyssey-mod.jar"
            self._jar(jar)
            self.assertEqual(release.validate_jar(jar, "1.2.3")["mod_id"], "odyssey")
            with self.assertRaises(release.ReleaseError):
                release.validate_jar(jar, "2.0.0")
            dev_jar = jar.with_name("odyssey-mod-dev.jar")
            dev_jar.write_bytes(jar.read_bytes())
            with self.assertRaises(release.ReleaseError):
                release.validate_jar(dev_jar, "1.2.3")

    def test_embed_is_bounded_preserves_full_notes_as_attachment_and_disables_mentions(self) -> None:
        body = "@everyone <@123456> " + ("release notes " * 1000)
        payload = release.discord_payload("v1.2.3", body, "https://github.com/alps-vale/odyssey-mod/releases/tag/v1.2.3", "a" * 40)
        release.verify_embed(payload)
        self.assertEqual(payload["allowed_mentions"], {"parse": [], "users": [], "roles": []})
        self.assertLessEqual(len(payload["embeds"][0]["description"]), 4096)
        self.assertIn(release.JAR_NAME, {item["filename"] for item in payload["attachments"]})
        self.assertIn(release.NOTES_NAME, {item["filename"] for item in payload["attachments"]})

    def test_pending_or_mismatched_receipt_never_authorizes_a_repost(self) -> None:
        base = {"tag": "v1.2.3", "source_sha": "abc", "jar_sha256": "def",
                "notes_sha256": "ghi", "channel_id": release.CHANNEL_ID}
        sent = {**base, "state": "sent", "message_id": "discord-id"}
        self.assertEqual(release.receipt_allows_reuse(sent, tag="v1.2.3", source_sha="abc",
                                                      jar_sha="def", notes_sha="ghi"), "discord-id")
        for receipt in ({**base, "state": "pending"}, {**sent, "source_sha": "other"}, {**base, "state": "sent"}):
            with self.subTest(receipt=receipt), self.assertRaises(release.ReleaseError):
                release.receipt_allows_reuse(receipt, tag="v1.2.3", source_sha="abc",
                                             jar_sha="def", notes_sha="ghi")

    def test_webhook_must_be_bound_to_the_expected_release_channel(self) -> None:
        original = release._request_json
        try:
            release._request_json = lambda _url: {"channel_id": release.CHANNEL_ID}
            self.assertEqual(release._webhook_channel("https://discord.com/api/webhooks/id/token"),
                             release.CHANNEL_ID)
            release._request_json = lambda _url: {"channel_id": "wrong"}
            with self.assertRaises(release.ReleaseError):
                release._webhook_channel("https://discord.com/api/webhooks/id/token")
        finally:
            release._request_json = original


if __name__ == "__main__":
    unittest.main()
