#!/usr/bin/env python3
"""Mirror open-source GameTranslator code for review, never prebuilt APKs or game scripts."""
import argparse
import json
import re
import shutil
import subprocess
import tempfile
import urllib.request
from pathlib import Path

API = "https://gitlab.com/api/v4/projects/88821849/releases?per_page=100"
REMOTE = "https://gitlab.com/sefflex/GameTranslator.git"
TAG = re.compile(r"^v(\d+)\.(\d+)\.(\d+)$")
ROOTS = ("mobile", "core", "tv", "windows", "mac", "scripts")
LEGAL = ("LICENSE", "NOTICE", "THIRD-PARTY-NOTICES.md", "PRIVACY.md")
SUFFIXES = {".kt", ".kts", ".dart", ".swift", ".cs", ".csproj", ".sln", ".c", ".h",
            ".cpp", ".hpp", ".m", ".mm", ".md", ".txt", ".sh", ".bat", ".cmd",
            ".xml", ".json", ".yaml", ".yml", ".gradle", ".properties", ".lock",
            ".plist", ".html", ".css", ".js", ".cmake", ".pro", ".pbxproj",
            ".xcconfig", ".storyboard", ".entitlements", ".py", ".toml"}
NAMES = {"CMakeLists.txt", "gradlew", "gradlew.bat", ".gitignore", ".gitattributes"}
SKIP = {".git", ".gradle", ".dart_tool", ".idea", ".build", "build", "bin",
        "obj", "Pods", "yamalar", "patches", "packs", "screenshots"}


def latest():
    req = urllib.request.Request(API, headers={"User-Agent": "DraBornTranslate-v5-sync"})
    with urllib.request.urlopen(req, timeout=30) as response:
        releases = json.load(response)
    tags = [(tuple(map(int, match.groups())), row["tag_name"])
            for row in releases
            if (match := TAG.fullmatch(row.get("tag_name", "")))]
    if not tags:
        raise RuntimeError("Resmi GitLab sürümü bulunamadı")
    return max(tags)[1]


def allowed(path):
    if any(part in SKIP or part.startswith(".git") for part in path.parts):
        return False
    if path.suffix.lower() in {".apk", ".aab", ".exe", ".dll", ".so", ".dmg",
                               ".wgt", ".ipk", ".zip", ".gz", ".pdf"}:
        return False
    if path.suffix.lower() in SUFFIXES or path.name in NAMES:
        return True
    if path.parts[:2] == ("mobile", "assets") and path.suffix.lower() in {
        ".png", ".jpg", ".jpeg", ".ttf", ".otf"
    }:
        return True
    if path.parts[:2] == ("mobile", "android") and path.suffix.lower() in {
        ".png", ".webp"
    }:
        return True
    return False


def sync(destination, tag):
    if not TAG.fullmatch(tag):
        raise ValueError("Geçersiz sürüm etiketi")
    with tempfile.TemporaryDirectory(prefix="draborn-upstream-") as temporary:
        repo = Path(temporary) / "source"
        subprocess.run(["git", "clone", "--depth", "1", "--single-branch",
                        "--branch", tag, REMOTE, str(repo)], check=True)
        commit = subprocess.check_output(["git", "-C", str(repo), "rev-parse",
                                          "HEAD"], text=True).strip()
        marker = destination / "source-version.json"
        if marker.is_file():
            old = json.loads(marker.read_text(encoding="utf-8"))
            if old.get("tag") == tag and old.get("commit") == commit:
                print("Already synchronized", tag)
                return
        destination.mkdir(parents=True, exist_ok=True)
        for name in ROOTS:
            shutil.rmtree(destination / name, ignore_errors=True)
        for name in LEGAL:
            (destination / name).unlink(missing_ok=True)
        count = 0
        for folder in ROOTS:
            root = repo / folder
            if not root.is_dir():
                continue
            for source in root.rglob("*"):
                if not source.is_file() or source.is_symlink() or source.stat().st_size > 5_000_000:
                    continue
                path = source.relative_to(repo)
                if not allowed(path):
                    continue
                target = destination / path
                target.parent.mkdir(parents=True, exist_ok=True)
                shutil.copyfile(source, target)
                count += 1
        for name in LEGAL:
            source = repo / name
            if source.is_file():
                shutil.copyfile(source, destination / name)
        if count < 20 or not (destination / "LICENSE").is_file() or not (destination / "NOTICE").is_file():
            raise RuntimeError("Kaynak dosyaları veya telif bildirimleri eksik")
        marker.write_text(json.dumps({
            "tag": tag, "commit": commit, "source_files": count,
            "upstream": REMOTE,
            "release": "https://gitlab.com/sefflex/GameTranslator/-/releases/" + tag,
            "integration_state": "imported-for-review-not-compiled",
            "excluded": "prebuilt APKs, generated files and game dialogue packs"
        }, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
        print("Imported source", tag, commit, "files", count)


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--tag", default=None)
    parser.add_argument("--output", default="upstream/GameTranslator")
    args = parser.parse_args()
    sync(Path(args.output), args.tag or latest())
