from __future__ import annotations

import json
import sys
import tempfile
import unittest
import zipfile
from copy import deepcopy
from email.parser import BytesParser
from email.policy import default
from unittest.mock import patch
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

    def test_ordered_card_bounds_notes_and_attaches_only_jar_after_version_links(self) -> None:
        body = "@everyone <@123456> " + ("release notes " * 1000)
        release_url = "https://github.com/alps-vale/odyssey-mod/releases/tag/v1.2.3"
        jar_url = "https://github.com/alps-vale/odyssey-mod/releases/download/v1.2.3/odyssey-mod.jar"
        payload = release.discord_payload("v1.2.3", body, release_url, jar_url)
        release.verify_payload(payload)
        self.assertEqual(payload["allowed_mentions"], {"parse": [], "users": [], "roles": []})
        self.assertEqual(payload["username"], "Wayfinder")
        self.assertEqual(payload["avatar_url"], release.WAYFINDER_AVATAR)
        self.assertEqual(payload["flags"], release.COMPONENTS_V2)
        card = payload["components"][0]["components"]
        self.assertLessEqual(sum(len(c["content"]) for c in card if c["type"] == 10), 4000)
        self.assertIn("Full release notes", card[0]["content"])
        self.assertIn(jar_url, card[-2]["content"])
        self.assertEqual(card[-1], {"type": 13, "file": {"url": "attachment://odyssey-mod.jar"}})
        self.assertEqual([item["filename"] for item in payload["attachments"]], [release.JAR_NAME])
        content_type, data = release._multipart(payload, b"real fixture bytes")
        parsed = BytesParser(policy=default).parsebytes(
            f"Content-Type: {content_type}\r\nMIME-Version: 1.0\r\n\r\n".encode() + data)
        files = [part for part in parsed.iter_parts() if part.get_filename()]
        self.assertEqual([part.get_filename() for part in files], [release.JAR_NAME])
        self.assertEqual(files[0].get_payload(decode=True), b"real fixture bytes")

    def test_readback_rejects_wrong_order_extra_attachment_or_different_jar(self) -> None:
        url = "https://github.com/alps-vale/odyssey-mod/releases/tag/v1.2.3"
        jar_url = "https://github.com/alps-vale/odyssey-mod/releases/download/v1.2.3/odyssey-mod.jar"
        message = release.discord_payload("v1.2.3", "Changes", url, jar_url)
        message.update({"channel_id": release.CHANNEL_ID, "mention_everyone": False,
                        "mentions": [], "mention_roles": [],
                        "author": {"username": "Wayfinder", "avatar": "verified-avatar"}})
        message["attachments"][0]["url"] = "https://cdn.discordapp.com/fixture.jar"
        # Discord adds IDs and resolves attachment:// to the uploaded file's CDN URL.
        message["components"][0]["id"] = 1
        message["components"][0]["components"][-1]["file"]["url"] = message["attachments"][0]["url"]
        def verify(candidate):
            release.verify_message(candidate, expected_jar=b"jar", release_url=url, jar_url=jar_url,
                                   tag="v1.2.3", expected_body="Changes")
        with patch.object(release, "_download", return_value=b"jar"):
            verify(message)
            components_only = deepcopy(message)
            components_only["attachments"] = []
            components_only["components"][0]["components"][-1]["name"] = release.JAR_NAME
            verify(components_only)
            wrong_order = deepcopy(message)
            wrong_order["components"][0]["components"].reverse()
            extra_file = deepcopy(message)
            extra_file["attachments"].append({"filename": "unnecessary.md", "url": "https://example.invalid"})
            wrong_sender = deepcopy(message)
            wrong_sender["author"]["username"] = "Wrong bot"
            missing_avatar = deepcopy(message)
            missing_avatar["author"]["avatar"] = None
            for invalid in (wrong_order, extra_file, wrong_sender, missing_avatar):
                with self.assertRaises(release.ReleaseError): verify(invalid)
        with patch.object(release, "_download", return_value=b"different jar"):
            with self.assertRaises(release.ReleaseError): verify(message)

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
