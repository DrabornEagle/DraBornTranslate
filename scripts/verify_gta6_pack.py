#!/usr/bin/env python3
"""Check the public GTA 6 Turkish GamePack metadata and optional live binary.

This verifier never commits, uploads, or prints the third-party game's lines.
A manifest entry count alone does not prove the archive is complete: --live
downloads the actual pack, checks its SHA-256 and inspects parsed entry keys.
"""
from __future__ import annotations

import argparse
import gzip
import hashlib
import io
import json
import re
import sys
import unicodedata
import urllib.request

BASE = "https://gametranslator-packs.gametranslator-pool.workers.dev"
MANIFEST_URL = BASE + "/v1/packs"
MAX_MANIFEST = 512 * 1024
MAX_DOWNLOAD = 96 * 1024 * 1024
MAX_JSON = 245 * 1024 * 1024
SHA256 = re.compile(r"[0-9a-f]{64}")
GTA_ID = "gta-6"
KOLT_LABEL = "Kolt & Associates:"


def pack_key(text: str) -> str:
    """Match PackKey.canon: tags -> NFKC -> lower -> NFD strip marks -> letters/digits."""
    text = re.sub(r"<[^<>]{0,40}>", " ", text)
    text = unicodedata.normalize("NFKC", text).lower()
    text = unicodedata.normalize("NFD", text)
    result = []
    gap = False
    for char in text:
        category = unicodedata.category(char)
        if category[0] == "M":
            continue
        if category[0] in ("L", "N"):
            if gap and result:
                result.append(" ")
            result.append(char)
            gap = False
        else:
            gap = True
    canon = "".join(result)
    return hashlib.sha256(canon.encode("utf-8")).hexdigest()[:16]


def fetch(url: str, max_bytes: int) -> bytes:
    request = urllib.request.Request(url, headers={"User-Agent": "DraBornTranslate-PackVerifier/0.6"})
    with urllib.request.urlopen(request, timeout=90) as reply:
        if reply.status != 200:
            raise ValueError("Unexpected HTTP response " + str(reply.status))
        data = reply.read(max_bytes + 1)
        if len(data) > max_bytes:
            raise ValueError("Download exceeds the allowed size")
        return data


def parse_manifest(raw: bytes) -> dict:
    obj = json.loads(raw)
    candidates = [
        row for row in obj.get("packs", [])
        if row.get("id") == GTA_ID and row.get("lang") == "tr"
    ]
    if len(candidates) != 1:
        raise ValueError("Expected exactly one Turkish gta-6 pack")
    row = candidates[0]
    if (not SHA256.fullmatch(row.get("sha256", ""))
            or not isinstance(row.get("entries"), int) or row["entries"] <= 0
            or not isinstance(row.get("size"), int) or row["size"] <= 0
            or row["size"] > MAX_DOWNLOAD):
        raise ValueError("Invalid size, line count, or SHA-256 in manifest")
    return row


def verify_archive(pack: dict, archive: bytes) -> dict:
    actual = hashlib.sha256(archive).hexdigest()
    if len(archive) != pack["size"] or actual != pack["sha256"]:
        raise ValueError("Downloaded GTA 6 pack failed size/hash verification")
    if not archive.startswith(bytes([0x1f, 0x8b])):
        raise ValueError("Expected gzip GamePack")
    with gzip.GzipFile(fileobj=io.BytesIO(archive)) as stream:
        decompressed = stream.read(MAX_JSON + 1)
    if len(decompressed) > MAX_JSON:
        raise ValueError("GamePack expands beyond the size limit")
    doc = json.loads(decompressed)
    entries = doc.get("entries")
    if not isinstance(entries, dict):
        raise ValueError("GamePack entries object not found")
    # Count only non-empty translations with a valid 64-bit key,
    # matching the Android GamePack decoder's usable entry notion.
    usable = {key.lower(): val.strip() for key, val in entries.items()
              if isinstance(key, str) and re.fullmatch(r"[0-9a-fA-F]{16}", key)
              and isinstance(val, str) and val.strip()}
    count = len(usable)
    label_present = bool(usable.get(pack_key(KOLT_LABEL)))
    result = {
        "game": GTA_ID,
        "language": "tr",
        "format": doc.get("format"),
        "manifest_entries": pack["entries"],
        "decoded_entries": count,
        "sha256_match": True,
        "kolt_associates_label_present": label_present,
    }
    if count != pack["entries"]:
        raise ValueError(f"Manifest claimed {pack['entries']} entries but decoded {count}")
    if not label_present:
        raise ValueError("The labelled GTA 6 line is missing: Kolt & Associates:")
    return result


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--live", action="store_true", help="Download and verify public GTA 6 pack")
    args = parser.parse_args()
    assert pack_key(KOLT_LABEL) == "5a1c20dc114b3b6c"
    if not args.live:
        fake = {
            "id": GTA_ID, "lang": "tr", "size": 1, "entries": 1, "sha256": "a"*64
        }
        assert parse_manifest(json.dumps({"packs": [fake]}).encode()) == fake
        # Synthetic pack ensures the label hash and count logic work without game text.
        sample = json.dumps({
            "format": 2,
            "id": "gta-6",
            "entries": {pack_key(KOLT_LABEL): "Örnek etiket"},
        }).encode()
        compressed = gzip.compress(sample)
        fake["size"] = len(compressed)
        fake["sha256"] = hashlib.sha256(compressed).hexdigest()
        assert verify_archive(fake, compressed)["kolt_associates_label_present"]
        print("Synthetic pack tests passed.")
        return 0
    manifest = parse_manifest(fetch(MANIFEST_URL, MAX_MANIFEST))
    archive = fetch(BASE + "/v1/packs/" + GTA_ID, MAX_DOWNLOAD)
    verified = verify_archive(manifest, archive)
    print(json.dumps(verified, ensure_ascii=False, indent=2))
    print("Verified remote archive; no copyrighted text has been redistributed.")
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except Exception as exc:
        print("GTA 6 package verification FAILED:", exc, file=sys.stderr)
        sys.exit(1)
