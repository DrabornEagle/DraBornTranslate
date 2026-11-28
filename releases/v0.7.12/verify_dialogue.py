"""Run the mission flow check against classes and ZIP assets taken from the APK."""

import argparse
import json
from pathlib import Path
import tempfile
from zipfile import ZipFile

from build_apk import HERE, run
from flow_pack import PERSONAL, REFERENCE, pack_name


def verify(apk, android_jar, dex2jar, json_jar):
    plan = json.loads((HERE / "scene-plan.json").read_bytes())
    stats = plan["stats"]
    with tempfile.TemporaryDirectory(prefix="draborn-scene-verify-") as temporary, ZipFile(apk) as base:
        work = Path(temporary)
        packs = []
        for kind in (REFERENCE, PERSONAL):
            path = work / pack_name(kind)
            path.write_bytes(base.read("assets/" + path.name))
            packs.append(path)
        classpath = [json_jar, android_jar]
        for index in range(1, 6):
            path = work / ("classes.dex" if index == 1 else f"classes{index}.dex")
            path.write_bytes(base.read(path.name))
            java_archive = path.with_suffix(".jar")
            run(dex2jar, "-f", "-o", java_archive, path)
            classpath.append(java_archive)
        java_classpath = ":".join(map(str, classpath))
        run("javac", "--release", "8", "-encoding", "UTF-8", "-classpath", java_classpath,
            "-d", work, HERE / "CheckMissionFlow.java")
        # dex2jar does not emit Java stack maps. This runs Kotlin and Java archive logic on Java 17.
        run("java", "-Xverify:none", "-classpath", f"{work}:{java_classpath}",
            "com.draborneagle.draborntranslate.CheckMissionFlow", *packs, stats["missions"], stats["added"], stats["shown"], stats.get("newMissionDialogues", 0))


if __name__ == "__main__":
    cli = argparse.ArgumentParser(description=__doc__)
    cli.add_argument("apk", type=Path)
    cli.add_argument("--android-jar", type=Path, required=True)
    cli.add_argument("--dex2jar", type=Path, required=True)
    cli.add_argument("--json-jar", type=Path, required=True)
    verify(**vars(cli.parse_args()))
