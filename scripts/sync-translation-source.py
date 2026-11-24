"""Import the Android components used by DraBornTranslate from a public release."""

import argparse
import io
import json
import re
import subprocess
import urllib.request
import zipfile
from pathlib import Path, PurePosixPath

REPOSITORY = "https://gitlab.com/sefflex/GameTranslator"
LATEST = "https://gitlab.com/api/v4/projects/88821849/releases/permalink/latest"
REVISION = "scripts/upstream-revision.json"
ANDROID = "mobile/android/app/src/main"
PACKAGE = "com/gametranslator/gametranslator_mobile"
APP = "app/src/main"
FILES = {
    **{
        f"{ANDROID}/kotlin/{PACKAGE}/core/{name}.kt": f"{APP}/java/{PACKAGE}/core/{name}.kt"
        for name in ("GamePacks", "PackJson", "PackFuzzy", "PackTranslation")
    },
    f"{ANDROID}/kotlin/{PACKAGE}/Native.kt": f"{APP}/java/{PACKAGE}/Native.kt",
    **{
        f"{ANDROID}/cpp/{name}": f"{APP}/cpp/{name}"
        for name in ("gt_jni.c", "gt_native.c", "gt_native.h")
    },
    "LICENSE": f"{APP}/assets/LICENSE",
    "NOTICE": f"{APP}/assets/NOTICE",
}
ALLOWED = {*FILES.values(), REVISION}
STABLE = re.compile(r"v[0-9]+\.[0-9]+\.[0-9]+")
SHA = re.compile(r"[0-9a-f]{40}")


def request(url: str, maximum: int) -> bytes:
    headers = {"User-Agent": "DraBornTranslate-source-sync/5"}
    with urllib.request.urlopen(urllib.request.Request(url, headers=headers), timeout=90) as response:
        if not response.geturl().startswith("https://gitlab.com/"):
            raise ValueError("Unexpected release host")
        result = response.read(maximum + 1)
        if len(result) > maximum:
            raise ValueError("Release response exceeds the allowed size")
        return result


def number(tag: str) -> tuple[int, ...]:
    if not STABLE.fullmatch(tag):
        raise ValueError("Unexpected release tag")
    return tuple(map(int, tag[1:].split(".")))


def selected_files(zipped: zipfile.ZipFile) -> dict[str, bytes]:
    selected = {}
    for member in zipped.infolist():
        path = PurePosixPath(member.filename)
        if path.is_absolute() or ".." in path.parts or member.is_dir():
            continue
        name = "/".join(path.parts[1:])
        if name not in FILES:
            continue
        if name in selected or member.file_size > 1_000_000:
            raise ValueError(f"Invalid release archive member: {name}")
        selected[name] = zipped.read(member)
    if set(selected) != set(FILES):
        raise ValueError("The release did not contain all Android engine components")
    return selected


def sync() -> None:
    installed = json.loads(Path(REVISION).read_text())
    released = json.loads(request(LATEST, 2 * 1024 * 1024))
    tag = released["tag_name"]
    revision = released["commit"]["id"]
    if released.get("upcoming_release") or number(tag) <= number(installed["tag"]):
        print("Already at the latest stable upstream revision.")
        return
    if not SHA.fullmatch(revision):
        raise ValueError("Release commit could not be verified")
    archive = f"{REPOSITORY}/-/archive/{revision}/GameTranslator-{revision}.zip"
    with zipfile.ZipFile(io.BytesIO(request(archive, 140 * 1024 * 1024))) as zipped:
        data = selected_files(zipped)
    for source, target in FILES.items():
        Path(target).write_bytes(data[source])
    Path(REVISION).write_text(json.dumps({
        "tag": tag, "commit": revision, "released_at": released["released_at"],
    }, indent=2) + "\n")
    print(f"Prepared upstream revision {tag} ({revision[:8]}).")


def check_index() -> None:
    staged = subprocess.check_output(["git", "diff", "--cached", "--name-only", "-z"]).decode().split("\0")
    paths = {path for path in staged if path}
    if not paths <= ALLOWED or REVISION not in paths:
        raise ValueError("Unexpected files in upstream update")


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--check-index", action="store_true")
    args = parser.parse_args()
    if args.check_index:
        check_index()
    else:
        sync()
