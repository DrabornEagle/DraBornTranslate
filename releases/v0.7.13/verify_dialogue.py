"""Run the mission flow check against classes and ZIP assets taken from the APK."""

import argparse
import json
from pathlib import Path
import tempfile
from zipfile import ZipFile

from build_apk import HERE, run
from flow_pack import PERSONAL, REFERENCE, pack_name


def verify(apk, android_jar, dex2jar, json_jar, base):
    plan = json.loads((HERE / "scene-plan.json").read_bytes())
    stats = plan["stats"]
    with tempfile.TemporaryDirectory(prefix="draborn-scene-verify-") as temporary, ZipFile(apk) as built:
        work = Path(temporary)
        if base is not None:
            with ZipFile(base) as previous:
                for entry in previous.infolist():
                    if entry.filename.startswith("lib/") and entry.filename.endswith(".so"):
                        assert built.getinfo(entry.filename).CRC == entry.CRC, entry.filename
        icons = json.loads(built.read("assets/mission-icons.json"))
        assert icons["down-to-earth"] == "blip_mission_brian.webp"
        assert icons["cti-pil-rd-bc-parent"] == "blip_mission_donnie.webp"
        assert all(f"assets/gta-6-mission-icons/{file}" in built.namelist() for file in set(icons.values()))
        details = built.read("classes3.dex")
        assert "Daha Fazla Oku".encode() in details and b"hasBundledArchive" in details
        assert "Görev Ara/Oku".encode() in details
        packs = []
        for kind in (REFERENCE, PERSONAL):
            path = work / pack_name(kind)
            path.write_bytes(built.read("assets/" + path.name))
            packs.append(path)
        classpath = [json_jar, android_jar]
        for index in range(1, 6):
            path = work / ("classes.dex" if index == 1 else f"classes{index}.dex")
            path.write_bytes(built.read(path.name))
            java_archive = path.with_suffix(".jar")
            run(dex2jar, "-f", "-o", java_archive, path)
            classpath.append(java_archive)
        java_classpath = ":".join(map(str, classpath))
        run("javac", "--release", "8", "-encoding", "UTF-8", "-classpath", java_classpath,
            "-d", work, HERE / "CheckMissionFlow.java")
        # dex2jar does not emit Java stack maps. This runs Kotlin and Java archive logic on Java 17.
        run("java", "-Xverify:none", "-classpath", f"{work}:{java_classpath}",
            "com.draborneagle.draborntranslate.CheckMissionFlow", *packs, 91 + stats["newTimelines"],
            stats["newRows"], 10663 + stats["visibleAdded"], 169 + stats["newDialogueMissions"])


if __name__ == "__main__":
    cli = argparse.ArgumentParser(description=__doc__)
    cli.add_argument("apk", type=Path)
    cli.add_argument("--android-jar", type=Path, required=True)
    cli.add_argument("--dex2jar", type=Path, required=True)
    cli.add_argument("--json-jar", type=Path, required=True)
    cli.add_argument("--base", type=Path, help="Original v0.7.12 APK for native library checks")
    verify(**vars(cli.parse_args()))
