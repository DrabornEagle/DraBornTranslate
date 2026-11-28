"""Build v0.7.12 from the exact published Android release and the reviewed scene timeline."""

import argparse
from hashlib import file_digest
import json
from pathlib import Path
import re
import shutil
import subprocess
import tempfile
from zipfile import ZipFile, ZipInfo

from flow_pack import OLD_VERSION, PERSONAL, REFERENCE, apply_flow, pack_name
from manifest import manifest_version

HERE = Path(__file__).resolve().parent
NAMESPACE = "com/draborneagle/draborntranslate"
MODEL = f"L{NAMESPACE}/"
ACTIVITY = MODEL + "GtaMissionDetailsActivity;"
CONTAINER = "Landroid/widget/LinearLayout;"
BASE_HASH = "ffe1edf8c0ac96a9b9251b4dd9e105a7c3e42cd7e1afb1790fcc02d81923d0a2"
CERTIFICATE = "2a1caa144d305716f2b60238ddf9cd0114843adf765cf71c829daa7cad3618d5"
PACKAGE = "com.draborneagle.draborntranslate.v5"
NAME = "0.7.12"
CODE = 30


def run(*args, capture=False):
    return subprocess.run([str(arg) for arg in args], capture_output=capture, check=True, text=True).stdout


def replace_once(source, search, replacement):
    if source.count(search) != 1:
        raise ValueError(f"Could not identify the intended method change: {search[:85]}")
    return source.replace(search, replacement, 1)


def patch_details(path):
    value = path.read_text(encoding="utf-8")
    start = value.index(".method private final render()V")
    end = value.index(".end method", start)
    method = value[start:end]
    footer = (
        f"    iget-object v1, v0, {ACTIVITY}->body:{CONTAINER}\n"
        f"    invoke-static {{v1}}, {MODEL}MissionFlow;->addBackToTop({CONTAINER})V\n\n"
        "    return-void"
    )
    if method.count("    return-void") != 2:
        raise ValueError("Unexpected mission detail render paths")
    method = method.replace("    return-void", footer)
    value = value[:start] + method + value[end:]
    path.write_text(value, encoding="utf-8")


def patch_search(path, lower=False):
    value = path.read_text(encoding="utf-8")
    label = r'"G\u00f6rev ara"' if lower else r'"G\u00f6rev Ara"'
    path.write_text(replace_once(value, label, r'"G\u00f6rev Ara/Oku"'), encoding="utf-8")


def disassemble(work, name, smali):
    target = work / (name + "-smali")
    run("java", "-cp", smali, "org.jf.baksmali.Main", "disassemble", "-a", 29, "-o", target, work / name)
    return target


def assemble(target, output, smali):
    run("java", "-cp", smali, "org.jf.smali.Main", "assemble", "-a", 29, "-o", output, target)


def compile_flow(work, sdk, android_jar, dex2jar, dex_paths):
    classpath = [android_jar]
    for dex in dex_paths:
        jar = dex.with_suffix(".jar")
        run(dex2jar, "-f", "-o", jar, dex)
        classpath.append(jar)
    classes = work / "flow-classes"
    classes.mkdir()
    run("javac", "--release", "8", "-encoding", "UTF-8", "-classpath", ":".join(map(str, classpath)),
        "-d", classes, HERE / "MissionFlow.java")
    new_dex = work / "mission-flow"
    new_dex.mkdir()
    run(sdk / "d8", "--release", "--min-api", 29, "--lib", android_jar, "--output", new_dex,
        *sorted(classes.rglob("*.class")))
    return new_dex / "classes.dex"


def verify(apk, sdk):
    details = run(sdk / "apksigner", "verify", "--print-certs", apk, capture=True)
    if f"certificate SHA-256 digest: {CERTIFICATE}" not in details:
        raise ValueError("Unexpected DraBornTranslate signing certificate")


def package(base, destination, replacements, plan):
    old_packs = {f"assets/{pack_name(kind, OLD_VERSION)}": kind for kind in (REFERENCE, PERSONAL)}
    with ZipFile(base) as archive, ZipFile(destination, "w", compresslevel=4, allowZip64=False) as result:
        for entry in archive.infolist():
            name = entry.filename
            if re.fullmatch(r"META-INF/(?:MANIFEST\.MF|[^/]+\.(?:SF|RSA|DSA|EC))", name, re.I):
                continue
            if name in old_packs:
                kind = old_packs[name]
                new_name = f"assets/{pack_name(kind)}"
                new_entry = ZipInfo(new_name, date_time=entry.date_time)
                new_entry.compress_type = entry.compress_type
                result.writestr(new_entry, apply_flow(archive.read(entry), plan if kind == PERSONAL else None))
            elif name == "AndroidManifest.xml":
                result.writestr(entry, manifest_version(archive.read(entry)))
            elif name in replacements:
                result.writestr(entry, replacements[name].read_bytes())
            else:
                result.writestr(entry, archive.read(entry))



def build(base_apk, output, keystore_zip, android_tools, android_jar, smali_tools, dex2jar):
    with base_apk.open("rb") as stream:
        if file_digest(stream, "sha256").hexdigest() != BASE_HASH:
            raise ValueError("Use the original signed v0.7.11 APK from GitHub Releases")
    verify(base_apk, android_tools)
    plan = json.loads((HERE / "scene-plan.json").read_bytes())
    with tempfile.TemporaryDirectory(prefix="draborn-0712-") as temporary:
        work = Path(temporary)
        with ZipFile(base_apk) as archive:
            for name in ("classes.dex", "classes2.dex", "classes3.dex", "classes4.dex"):
                (work / name).write_bytes(archive.read(name))
        details = disassemble(work, "classes3.dex", smali_tools)
        patch_details(details / NAMESPACE / "GtaMissionDetailsActivity.smali")
        patch_search(details / NAMESPACE / "GtaMissionSearchActivity.smali")
        home = disassemble(work, "classes.dex", smali_tools)
        for activity in ("MainActivity", "HomeActivity", "GamePacksActivity"):
            patch_search(home / NAMESPACE / f"{activity}.smali", lower=True)
        updated1 = work / "updated1.dex"
        assemble(home, updated1, smali_tools)
        updated3 = work / "updated3.dex"
        assemble(details, updated3, smali_tools)
        flow = compile_flow(work, android_tools, android_jar, dex2jar,
                            [updated1, work / "classes2.dex", updated3, work / "classes4.dex"])

        unaligned, aligned, signed = (work / name for name in ("unaligned.apk", "aligned.apk", "signed.apk"))
        package(base_apk, unaligned, {"classes.dex": updated1, "classes3.dex": updated3, "classes5.dex": flow}, plan)
        run(android_tools / "zipalign", "-f", "-P", 16, 4, unaligned, aligned)
        if keystore_zip is not None:
            with ZipFile(keystore_zip) as archive:
                key = work / "release.jks"
                password = work / "password"
                for path in (key, password):
                    path.touch(mode=0o600, exist_ok=False)
                key.write_bytes(archive.read("DraBornTranslate-v5-release.jks"))
                password.write_bytes(archive.read("imza-parolasi.txt").strip() + b"\n")
            run(android_tools / "apksigner", "sign", "--ks", key, "--ks-key-alias", "draborntranslate-v5",
                "--ks-pass", f"file:{password}", "--v4-signing-enabled", "false", "--out", signed, aligned)
            verify(signed, android_tools)
            final_apk = signed
        else:
            final_apk = aligned
        run(android_tools / "zipalign", "-c", "-P", 16, 4, final_apk)
        badge = run(android_tools / "aapt", "dump", "badging", final_apk, capture=True)
        if f"name='{PACKAGE}' versionCode='{CODE}' versionName='{NAME}'" not in badge:
            raise ValueError("Unexpected Android package or version")
        output.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(final_apk, output)


if __name__ == "__main__":
    cli = argparse.ArgumentParser(description=__doc__)
    cli.add_argument("base_apk", type=Path)
    cli.add_argument("output", type=Path)
    cli.add_argument("--keystore-zip", type=Path)
    cli.add_argument("--android-tools", type=Path, required=True)
    cli.add_argument("--android-jar", type=Path, required=True)
    cli.add_argument("--smali-tools", required=True, help="Java classpath for smali and baksmali 2.5.2")
    cli.add_argument("--dex2jar", type=Path, required=True, help="Path to d2j-dex2jar.sh from dex2jar 2.4")
    build(**vars(cli.parse_args()))
