#!/usr/bin/env python3
"""Validate and announce the exact remapped Odyssey release artifact."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import subprocess
import sys
import tempfile
import urllib.error
import urllib.parse
import urllib.request
import zipfile
from datetime import datetime, timezone
from pathlib import Path
from typing import Any


SEMVER = re.compile(
    r"^v(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)"
    r"(?:-((?:0|[1-9]\d*|\d*[A-Za-z-][0-9A-Za-z-]*)"
    r"(?:\.(?:0|[1-9]\d*|\d*[A-Za-z-][0-9A-Za-z-]*))*))?$"
)
MOD_ID = "odyssey"
ENTRYPOINT_CLASS = "org/odyssey/mod/OdysseyMod.class"
RECEIPT_NAME = "discord-announcement.json"
JAR_NAME = "odyssey-mod.jar"
CHANNEL_ID = "1558187887579627600"
# Verified against the live Odyssey backend's Discord bot, not a separate release identity.
WAYFINDER_NAME = "Wayfinder"
WAYFINDER_AVATAR = (
    "https://cdn.discordapp.com/avatars/1533129281956347925/"
    "a_488f6d48c534b12dfe5c36c87bd40a70.gif?size=1024"
)
COMPONENTS_V2 = 1 << 15
RUNTIME_REQUIREMENTS = (
    "Minecraft 1.21.11 · Java 21+ · Fabric Loader 0.19.3+ · "
    "Fabric API 0.141.6+1.21.11 · "
    "Fabric Language Kotlin 1.13.13+kotlin.2.4.10"
)


class ReleaseError(Exception):
    """Raised when release inputs or external readback do not match expectations."""


def parse_tag(tag: str) -> str:
    """Return the SemVer version represented by a v-prefixed release tag."""
    match = SEMVER.fullmatch(tag)
    if match is None:
        raise ReleaseError(f"Invalid release tag {tag!r}; expected vX.Y.Z[-prerelease].")
    return ".".join(match.group(index) for index in (1, 2, 3)) + (
        f"-{match.group(4)}" if match.group(4) else ""
    )


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for block in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def validate_jar(path: Path, version: str) -> dict[str, Any]:
    """Validate the exact remapped client mod and return its identity fields."""
    if path.name != JAR_NAME:
        raise ReleaseError(f"Expected remapped {JAR_NAME}, got {path.name!r}.")
    try:
        with zipfile.ZipFile(path) as archive:
            names = set(archive.namelist())
            if "fabric.mod.json" not in names or ENTRYPOINT_CLASS not in names:
                raise ReleaseError("JAR is missing the Fabric manifest or Odyssey client entrypoint.")
            manifest = json.loads(archive.read("fabric.mod.json"))
            mixins = manifest.get("mixins", [])
            if not isinstance(mixins, list) or not mixins:
                raise ReleaseError("Fabric metadata does not declare mixin configuration.")
            for mixin in mixins:
                if not isinstance(mixin, str) or mixin not in names:
                    raise ReleaseError(f"Declared mixin configuration {mixin!r} is missing.")
    except (OSError, zipfile.BadZipFile, json.JSONDecodeError) as error:
        raise ReleaseError(f"Cannot inspect remapped JAR: {error}") from error
    if manifest.get("id") != MOD_ID:
        raise ReleaseError(f"Unexpected Fabric mod id: {manifest.get('id')!r}.")
    if manifest.get("version") != version:
        raise ReleaseError(
            f"JAR version {manifest.get('version')!r} does not match requested {version!r}."
        )
    entrypoints = manifest.get("entrypoints", {}).get("client", [])
    if not any(
        isinstance(entrypoint, dict)
        and entrypoint.get("value") == "org.odyssey.mod.OdysseyMod"
        for entrypoint in entrypoints
    ):
        raise ReleaseError("Fabric metadata has no Odyssey client entrypoint.")
    return {
        "name": path.name,
        "mod_id": MOD_ID,
        "version": version,
        "sha256": sha256_file(path),
    }


def discord_payload(tag: str, body: str, release_url: str, jar_url: str) -> dict[str, Any]:
    """A single ordered card: changelog first, version links and attached JAR last."""
    title = f"## Odyssey {tag} released"
    description = body.strip() or "See the release notes for details."
    suffix = f"\n\n[Full release notes]({release_url})"
    links = (f"[Download {JAR_NAME}]({jar_url}) · [Release notes]({release_url})\n"
             f"-# {RUNTIME_REQUIREMENTS}")
    description_limit = 4000 - len(title) - len(links) - len(suffix) - 3
    if description_limit < 64:
        raise ReleaseError("Release metadata leaves no room for the Discord changelog.")
    if len(description) > description_limit:
        description = description[:description_limit].rstrip() + "…" + suffix
    return {
        "username": WAYFINDER_NAME,
        "avatar_url": WAYFINDER_AVATAR,
        "flags": COMPONENTS_V2,
        "allowed_mentions": {"parse": [], "users": [], "roles": []},
        "components": [
            {
                "type": 17,
                "accent_color": 0x42B8B5,
                "components": [
                    {"type": 10, "content": f"{title}\n\n{description}"},
                    {"type": 14, "divider": True, "spacing": 1},
                    {"type": 10, "content": links},
                    {"type": 13, "file": {"url": f"attachment://{JAR_NAME}"}},
                ],
            }
        ],
        "attachments": [
            {"id": 0, "filename": JAR_NAME, "description": "Runnable remapped Fabric mod"},
        ],
    }


def verify_payload(payload: dict[str, Any]) -> None:
    texts = [component["content"] for component in payload["components"][0]["components"]
             if component["type"] == 10]
    if sum(map(len, texts)) > 4000:
        raise ReleaseError("Discord release card exceeds its text limits.")
    if payload.get("allowed_mentions") != {"parse": [], "users": [], "roles": []}:
        raise ReleaseError("Discord payload does not explicitly suppress all mention parsing.")


def _gh(*arguments: str, capture: bool = True) -> str:
    environment = os.environ.copy()
    result = subprocess.run(
        ["gh", *arguments],
        check=False,
        text=True,
        stdout=subprocess.PIPE if capture else None,
        stderr=subprocess.PIPE if capture else None,
        env=environment,
    )
    if result.returncode:
        detail = (result.stderr or "").strip() if capture else ""
        raise ReleaseError(f"gh {' '.join(arguments[:3])} failed ({result.returncode}): {detail}")
    return result.stdout or ""


def _request_json(url: str, *, method: str = "GET", data: bytes | None = None,
                  headers: dict[str, str] | None = None) -> dict[str, Any]:
    request_headers = {"User-Agent": "OdysseyRelease/1.0 (+https://github.com/alps-vale/odyssey-mod)"}
    request_headers.update(headers or {})
    request = urllib.request.Request(url, data=data, headers=request_headers, method=method)
    try:
        with urllib.request.urlopen(request, timeout=30) as response:
            return json.loads(response.read())
    except (urllib.error.URLError, TimeoutError, json.JSONDecodeError) as error:
        raise ReleaseError(f"Discord HTTP {method} request failed: {error}") from error


def _webhook_url() -> str:
    value = os.environ.get("DISCORD_RELEASE_WEBHOOK_URL", "")
    parsed = urllib.parse.urlsplit(value)
    if parsed.scheme != "https" or parsed.hostname != "discord.com":
        raise ReleaseError("DISCORD_RELEASE_WEBHOOK_URL must be an HTTPS discord.com webhook URL.")
    return value


def _discord_message_url(webhook_url: str, message_id: str) -> str:
    parsed = urllib.parse.urlsplit(webhook_url)
    base_path = parsed.path.rstrip("/")
    query = urllib.parse.parse_qsl(parsed.query, keep_blank_values=True)
    query = [(key, value) for key, value in query if key != "wait"]
    return urllib.parse.urlunsplit(
        (parsed.scheme, parsed.netloc, f"{base_path}/messages/{message_id}",
         urllib.parse.urlencode(query), "")
    )


def _discord_readback(webhook_url: str, message_id: str) -> dict[str, Any]:
    return _request_json(_discord_message_url(webhook_url, message_id))


def _webhook_channel(webhook_url: str) -> str:
    metadata = _request_json(webhook_url)
    channel_id = metadata.get("channel_id")
    if channel_id != CHANNEL_ID:
        raise ReleaseError("Configured Discord webhook does not belong to the expected release channel.")
    return channel_id


def _download(url: str) -> bytes:
    try:
        request = urllib.request.Request(
            url, headers={"User-Agent": "OdysseyRelease/1.0 (+https://github.com/alps-vale/odyssey-mod)"}
        )
        with urllib.request.urlopen(request, timeout=45) as response:
            return response.read()
    except (urllib.error.URLError, TimeoutError) as error:
        raise ReleaseError(f"Discord attachment download failed: {error}") from error


def verify_message(message: dict[str, Any], *, expected_jar: bytes,
                   release_url: str, jar_url: str, tag: str, expected_body: str,
                   expected_username: str = WAYFINDER_NAME) -> None:
    expected = discord_payload(tag, expected_body, release_url, jar_url)
    cards = message.get("components", [])
    if (not message.get("flags", 0) & COMPONENTS_V2 or message.get("embeds")
            or len(cards) != 1 or cards[0].get("type") != 17
            or cards[0].get("accent_color") != expected["components"][0]["accent_color"]):
        raise ReleaseError("Discord readback does not contain the expected ordered release card.")
    components = cards[0].get("components", [])
    expected_components = expected["components"][0]["components"]
    if (len(components) != len(expected_components)
            or any(actual.get("type") != wanted["type"]
                   or (wanted["type"] == 10 and actual.get("content") != wanted["content"])
                   for actual, wanted in zip(components, expected_components))):
        raise ReleaseError("Discord readback changelog, version links, or component order differs.")
    author = message.get("author", {})
    if author.get("username") != expected_username:
        raise ReleaseError("Discord announcement did not use the expected sender name.")
    if expected_username == WAYFINDER_NAME and not author.get("avatar"):
        raise ReleaseError("Discord announcement did not use Wayfinder's avatar.")
    if message.get("channel_id") != CHANNEL_ID:
        raise ReleaseError("Discord readback message is not in the configured release channel.")
    if message.get("mention_everyone") is not False or message.get("mentions") or message.get("mention_roles"):
        raise ReleaseError("Discord readback reports an unintended mention.")
    attachments = {entry.get("filename"): entry for entry in message.get("attachments", [])}
    if attachments and (len(message.get("attachments", [])) != 1 or set(attachments) != {JAR_NAME}):
        raise ReleaseError("Discord message must attach only the runnable JAR.")
    # Components V2 files may live only in the File component, not message.attachments.
    displayed = components[-1]
    attachment = attachments.get(JAR_NAME, {})
    if displayed.get("name", attachment.get("filename")) != JAR_NAME:
        raise ReleaseError("Discord release card does not display the runnable JAR last.")
    download_url = displayed.get("file", {}).get("url")
    if download_url == f"attachment://{JAR_NAME}":
        download_url = attachment.get("url")
    if not download_url or (attachment and download_url != attachment.get("url")):
        raise ReleaseError("Discord file component and attachment metadata disagree.")
    if _download(download_url) != expected_jar:
        raise ReleaseError("Discord-attached JAR bytes differ from the promoted release artifact.")


def receipt_allows_reuse(receipt: dict[str, Any], *, tag: str, source_sha: str,
                         jar_sha: str, notes_sha: str) -> str:
    """Return a sent message ID, or fail closed for every non-reusable receipt."""
    if (receipt.get("tag") != tag or receipt.get("source_sha") != source_sha
            or receipt.get("jar_sha256") != jar_sha or receipt.get("notes_sha256") != notes_sha
            or receipt.get("channel_id") != CHANNEL_ID):
        raise ReleaseError("Discord receipt belongs to a different build; refusing to announce twice.")
    if receipt.get("state") == "pending":
        raise ReleaseError(
            "Discord announcement receipt is pending with no verified message ID; "
            "inspect Discord and reconcile it manually before retrying."
        )
    message_id = receipt.get("message_id")
    if receipt.get("state") != "sent" or not isinstance(message_id, str) or not message_id:
        raise ReleaseError("Existing Discord receipt has an unknown state; refusing to repost.")
    return message_id


def _load_release(repository: str, tag: str) -> dict[str, Any]:
    output = _gh("release", "view", tag, "--repo", repository, "--json", "body,url,assets")
    try:
        return json.loads(output)
    except json.JSONDecodeError as error:
        raise ReleaseError("gh release view returned invalid JSON.") from error


def _receipt_asset(repository: str, tag: str, release: dict[str, Any]) -> dict[str, Any] | None:
    assets = release.get("assets", [])
    if not any(asset.get("name") == RECEIPT_NAME for asset in assets):
        return None
    with tempfile.TemporaryDirectory(prefix="odyssey-release-receipt-") as temp_dir:
        _gh("release", "download", tag, "--repo", repository, "--pattern", RECEIPT_NAME,
            "--dir", temp_dir)
        try:
            return json.loads((Path(temp_dir) / RECEIPT_NAME).read_text(encoding="utf-8"))
        except (OSError, json.JSONDecodeError) as error:
            raise ReleaseError(f"Existing Discord receipt cannot be read: {error}") from error


def _upload_receipt(repository: str, tag: str, receipt: dict[str, Any]) -> None:
    with tempfile.TemporaryDirectory(prefix="odyssey-release-receipt-") as temp_dir:
        path = Path(temp_dir) / RECEIPT_NAME
        path.write_text(json.dumps(receipt, indent=2, sort_keys=True) + "\n", encoding="utf-8")
        _gh("release", "upload", tag, "--repo", repository, str(path), "--clobber")
        download_dir = Path(temp_dir) / "readback"
        download_dir.mkdir()
        _gh("release", "download", tag, "--repo", repository, "--pattern", RECEIPT_NAME,
            "--dir", str(download_dir))
        if (download_dir / RECEIPT_NAME).read_bytes() != path.read_bytes():
            raise ReleaseError("GitHub release receipt readback differs from the uploaded receipt.")


def _verify_github_jar(repository: str, tag: str, expected_jar: bytes,
                       release: dict[str, Any]) -> None:
    if not any(asset.get("name") == JAR_NAME for asset in release.get("assets", [])):
        raise ReleaseError("GitHub release has no runnable odyssey-mod.jar asset.")
    with tempfile.TemporaryDirectory(prefix="odyssey-release-jar-") as temp_dir:
        _gh("release", "download", tag, "--repo", repository, "--pattern", JAR_NAME,
            "--dir", temp_dir)
        if (Path(temp_dir) / JAR_NAME).read_bytes() != expected_jar:
            raise ReleaseError("GitHub release JAR bytes differ from the build-job artifact.")


def _multipart(payload: dict[str, Any], jar: bytes) -> tuple[str, bytes]:
    boundary = "----OdysseyRelease" + hashlib.sha256(jar).hexdigest()[:24]
    parts: list[bytes] = []
    fields = {"payload_json": json.dumps(payload, ensure_ascii=False, separators=(",", ":"))}
    for name, value in fields.items():
        parts.extend(
            [
                f"--{boundary}\r\n".encode(),
                f'Content-Disposition: form-data; name="{name}"\r\n'.encode(),
                b"Content-Type: application/json\r\n\r\n",
                value.encode("utf-8"),
                b"\r\n",
            ]
        )
    for index, (filename, content, content_type) in enumerate(
        ((JAR_NAME, jar, "application/java-archive"),)
    ):
        parts.extend(
            [
                f"--{boundary}\r\n".encode(),
                f'Content-Disposition: form-data; name="files[{index}]"; filename="{filename}"\r\n'.encode(),
                f"Content-Type: {content_type}\r\n\r\n".encode(),
                content,
                b"\r\n",
            ]
        )
    parts.append(f"--{boundary}--\r\n".encode())
    return f"multipart/form-data; boundary={boundary}", b"".join(parts)


def announce(tag: str, repository: str, source_sha: str, jar_path: Path) -> str:
    version = parse_tag(tag)
    validate_jar(jar_path, version)
    jar_bytes = jar_path.read_bytes()
    jar_sha = hashlib.sha256(jar_bytes).hexdigest()
    webhook_url = _webhook_url()
    _webhook_channel(webhook_url)
    release = _load_release(repository, tag)
    release_url = release["url"]
    _verify_github_jar(repository, tag, jar_bytes, release)
    jar_url = next(asset["url"] for asset in release["assets"] if asset["name"] == JAR_NAME)
    notes_text = release.get("body", "")
    notes_bytes = (notes_text.rstrip() + "\n").encode("utf-8")
    notes_sha = hashlib.sha256(notes_bytes).hexdigest()
    payload = discord_payload(tag, notes_text, release_url, jar_url)
    verify_payload(payload)
    existing = _receipt_asset(repository, tag, release)
    if existing is not None:
        message_id = receipt_allows_reuse(existing, tag=tag, source_sha=source_sha,
                                          jar_sha=jar_sha, notes_sha=notes_sha)
        message = _discord_readback(webhook_url, message_id)
        verify_message(message, expected_jar=jar_bytes,
                       release_url=release_url, jar_url=jar_url, tag=tag,
                       expected_body=notes_text)
        return f"Verified existing Discord announcement {message_id} (no repost)."

    receipt: dict[str, Any] = {
        "schema": 1,
        "state": "pending",
        "tag": tag,
        "source_sha": source_sha,
        "jar_sha256": jar_sha,
        "notes_sha256": notes_sha,
        "channel_id": CHANNEL_ID,
        "created_at": datetime.now(timezone.utc).isoformat(),
    }
    _upload_receipt(repository, tag, receipt)
    content_type, body = _multipart(payload, jar_bytes)
    parsed = urllib.parse.urlsplit(webhook_url)
    query = urllib.parse.parse_qsl(parsed.query, keep_blank_values=True)
    query = [(key, value) for key, value in query if key not in ("wait", "with_components")]
    query += [("wait", "true"), ("with_components", "true")]
    execute_url = urllib.parse.urlunsplit(
        (parsed.scheme, parsed.netloc, parsed.path, urllib.parse.urlencode(query), "")
    )
    posted = _request_json(
        execute_url,
        method="POST",
        data=body,
        headers={"Content-Type": content_type},
    )
    message_id = posted.get("id")
    if not isinstance(message_id, str) or not message_id:
        raise ReleaseError("Discord execute webhook did not return the created message ID.")
    verify_message(posted, expected_jar=jar_bytes,
                   release_url=release_url, jar_url=jar_url, tag=tag,
                   expected_body=notes_text)
    readback = _discord_readback(webhook_url, message_id)
    verify_message(readback, expected_jar=jar_bytes,
                   release_url=release_url, jar_url=jar_url, tag=tag,
                   expected_body=notes_text)
    receipt.update({"state": "sent", "message_id": message_id,
                    "channel_id": posted["channel_id"],
                    "verified_at": datetime.now(timezone.utc).isoformat()})
    _upload_receipt(repository, tag, receipt)
    return f"Discord announcement {message_id} verified by readback and attachment byte comparison."


def command_artifact(args: argparse.Namespace) -> None:
    identity = validate_jar(Path(args.jar), args.version)
    identity.update({"source_sha": args.source_sha})
    path = Path(args.metadata)
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(identity, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    print(json.dumps(identity, sort_keys=True))


def command_verify_artifact(args: argparse.Namespace) -> None:
    identity = validate_jar(Path(args.jar), args.version)
    metadata = json.loads(Path(args.metadata).read_text(encoding="utf-8"))
    if metadata != {**identity, "source_sha": args.source_sha}:
        raise ReleaseError("Promoted JAR does not match its build-job identity/digest record.")
    print(json.dumps(metadata, sort_keys=True))


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    version_parser = commands.add_parser("version", help="validate a v-prefixed SemVer tag")
    version_parser.add_argument("--tag", required=True)
    artifact_parser = commands.add_parser("artifact", help="validate and record a build JAR")
    artifact_parser.add_argument("--jar", required=True)
    artifact_parser.add_argument("--version", required=True)
    artifact_parser.add_argument("--metadata", required=True)
    artifact_parser.add_argument("--source-sha", required=True)
    verify_parser = commands.add_parser("verify-artifact", help="verify the promoted artifact")
    verify_parser.add_argument("--jar", required=True)
    verify_parser.add_argument("--metadata", required=True)
    verify_parser.add_argument("--version", required=True)
    verify_parser.add_argument("--source-sha", required=True)
    announce_parser = commands.add_parser("announce", help="announce and verify a GitHub release")
    announce_parser.add_argument("--tag", required=True)
    announce_parser.add_argument("--repository", required=True)
    announce_parser.add_argument("--source-sha", required=True)
    announce_parser.add_argument("--jar", required=True)
    args = parser.parse_args()
    try:
        if args.command == "version":
            print(parse_tag(args.tag))
        elif args.command == "artifact":
            command_artifact(args)
        elif args.command == "verify-artifact":
            command_verify_artifact(args)
        else:
            print(announce(args.tag, args.repository, args.source_sha, Path(args.jar)))
    except (ReleaseError, OSError, json.JSONDecodeError, subprocess.SubprocessError) as error:
        print(f"release: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
