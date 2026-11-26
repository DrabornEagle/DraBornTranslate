"""Build or verify the GTA 6 mission archive distributed by DraBornTranslate."""

import argparse
import hashlib
import io
import json
from pathlib import Path
import re
from zipfile import ZIP_DEFLATED, ZipFile, ZipInfo

ROOT = Path(__file__).resolve().parents[1]
MAX_ARCHIVE_BYTES = 48 * 1024 * 1024
MAX_CATALOG_BYTES = 2 * 1024 * 1024
MAX_ENTRY_BYTES = 6 * 1024 * 1024
MAX_EXPANDED_BYTES = 192 * 1024 * 1024
MISSION_ID = re.compile(r"[a-z0-9]+(?:-[a-z0-9]+)*")


def json_bytes(value):
    return (json.dumps(value, ensure_ascii=False, indent=2) + "\n").encode("utf-8")


def archive_inputs(directory):
    catalog = json.loads((directory / "catalog.json").read_text(encoding="utf-8"))
    missions = catalog["missions"]
    if catalog["format"] != 1 or not catalog["version"] or not 1 <= len(missions) <= 1500:
        raise ValueError("Invalid mission catalog")
    ids = [mission["id"] for mission in missions]
    if len(set(ids)) != len(ids) or any(len(id_) > 80 or not MISSION_ID.fullmatch(id_) for id_ in ids):
        raise ValueError("Mission IDs must be unique lowercase slugs")
    text_files = {path.stem: path for path in (directory / "texts").glob("*.json")}
    unknown = text_files.keys() - set(ids)
    if unknown:
        raise ValueError(f"Text files without a mission: {', '.join(sorted(unknown))}")

    entries = {}
    line_count = 0
    for mission in missions:
        mission_id = mission["id"]
        path = text_files.get(mission_id)
        rows = json.loads(path.read_text(encoding="utf-8")) if path else []
        if not isinstance(rows, list) or len(rows) > 30_000:
            raise ValueError(f"Invalid text file for {mission_id}")
        mission["textCount"] = len(rows)
        if rows:
            # Keep records compact. Android reads and paginates one mission at a time.
            entry = json.dumps(rows, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
            if len(entry) > MAX_ENTRY_BYTES:
                raise ValueError(f"Text file too large for {mission_id}")
            entries[f"texts/{mission_id}.json"] = entry
            line_count += len(rows)

    entries["catalog.json"] = json_bytes(catalog)
    if len(entries["catalog.json"]) > MAX_CATALOG_BYTES or sum(map(len, entries.values())) > MAX_EXPANDED_BYTES:
        raise ValueError("Expanded archive exceeds the Android pack size limit")
    return catalog["version"], entries, len(missions), line_count


def manifest_for(archive, version):
    if not 1 <= len(archive) <= MAX_ARCHIVE_BYTES:
        raise ValueError("Archive exceeds the Android download size limit")
    return {"version": version, "bytes": len(archive), "sha256": hashlib.sha256(archive).hexdigest()}


def archive_name(manifest):
    return f"gta-6-tr-{manifest['sha256'][:12]}.zip"


def build(directory, entries, version):
    destination = io.BytesIO()
    with ZipFile(destination, "w") as archive:
        for name in sorted(entries):
            archive.writestr(ZipInfo(name), entries[name], compress_type=ZIP_DEFLATED, compresslevel=9)
    data = destination.getvalue()
    manifest = manifest_for(data, version)
    (directory / archive_name(manifest)).write_bytes(data)
    (directory / "manifest.json").write_bytes(json_bytes(manifest))


def check(directory, entries, version):
    manifest = json.loads((directory / "manifest.json").read_text(encoding="utf-8"))
    if not isinstance(manifest["sha256"], str) or not re.fullmatch(r"[a-f0-9]{64}", manifest["sha256"]):
        raise ValueError("Invalid SHA-256 in package manifest")
    path = directory / archive_name(manifest)
    if path.stat().st_size > MAX_ARCHIVE_BYTES:
        raise ValueError("Archive is too large")
    data = path.read_bytes()
    if manifest != manifest_for(data, version):
        raise ValueError("Published archive and manifest do not match")
    with ZipFile(io.BytesIO(data)) as archive:
        if sorted(archive.namelist()) != sorted(entries):
            raise ValueError("Published archive has different entries from its sources")
        for item in archive.infolist():
            expected = entries[item.filename]
            if item.file_size != len(expected) or archive.read(item) != expected:
                raise ValueError(f"Published archive does not match {item.filename}")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--build", action="store_true", help="Regenerate the archive and download manifest")
    parser.add_argument("--directory", type=Path, default=ROOT / "packs" / "gta-6")
    args = parser.parse_args()
    version, entries, missions, lines = archive_inputs(args.directory)
    if args.build:
        build(args.directory, entries, version)
    check(args.directory, entries, version)
    print(f"GTA 6 / {version}: {missions} missions, {lines} text records")


if __name__ == "__main__":
    main()
