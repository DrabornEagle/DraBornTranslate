"""Build v0.7.11 from the exact published Android release and the reviewed timeline."""

import argparse
from hashlib import file_digest
import json
from pathlib import Path
import re
import shutil
import subprocess
import tempfile
from zipfile import ZIP_STORED, ZipFile, ZipInfo

from flow_pack import OLD_VERSION, PERSONAL, REFERENCE, apply_flow, pack_name
from manifest import manifest_version

HERE = Path(__file__).resolve().parent
NAMESPACE = "com/draborneagle/draborntranslate"
MODEL = f"L{NAMESPACE}/"
HELPER = MODEL + "MissionV078;"
ACTIVITY = MODEL + "GtaMissionDetailsActivity;"
DIALOGUE = MODEL + "GtaMissionDialogue;"
LANGUAGE = MODEL + "GtaMissionSearchLanguage;"
CONTAINER = "Landroid/widget/LinearLayout;"
BASE_HASH = "3a0a9cb52f49716b22642186da66566ed2f3754709713bad9812f3f77fdfcee3"
CERTIFICATE = "2a1caa144d305716f2b60238ddf9cd0114843adf765cf71c829daa7cad3618d5"
PACKAGE = "com.draborneagle.draborntranslate.v5"
NAME = "0.7.11"
CODE = 29


def run(*args, capture=False):
    return subprocess.run([str(arg) for arg in args], capture_output=capture, check=True, text=True).stdout


def replace_once(source, search, replacement):
    if source.count(search) != 1:
        raise ValueError(f"Could not identify the intended method change: {search[:85]}")
    return source.replace(search, replacement, 1)


def patch_helper(path):
    value = path.read_text(encoding="utf-8")
    signature = f".method static pickDialogue({ACTIVITY}{CONTAINER}{DIALOGUE}{LANGUAGE}){DIALOGUE}"
    start = value.index(signature)
    end = value.index(".end method", start) + len(".end method")
    recording = MODEL + "MissionV078$Recording;"
    bridge = f"""{signature}
    .locals 2

    sget-object v0, {HELPER}->recordings:Ljava/util/Map;
    invoke-interface {{v0, p0}}, Ljava/util/Map;->get(Ljava/lang/Object;)Ljava/lang/Object;
    move-result-object v0
    check-cast v0, {recording}
    const/4 v1, 0x0
    if-eqz v0, :choose
    iget-object v1, v0, {recording}->dialogue:{DIALOGUE}

    :choose
    invoke-static {{p0, p1, p2, p3, v1}}, {MODEL}MissionFlow;->select({ACTIVITY}{CONTAINER}{DIALOGUE}{LANGUAGE}{DIALOGUE}){DIALOGUE}
    move-result-object v0
    return-object v0
.end method"""
    value = value[:start] + bridge + value[end:]
    for method in ("add", "chip"):
        value = replace_once(value, f".method private static {method}(", f".method static {method}(")
    path.write_text(value, encoding="utf-8")


def patch_details(path):
    value = path.read_text(encoding="utf-8")
    comparator = MODEL + "GtaMissionDetailsActivity$showPackText$$inlined$sortedBy$1;"
    # inSceneOrder already supplies the scene timeline. Category selection filters it in place.
    pattern = (r"(?m)^    \.line 639\n    check-cast v1, Ljava/lang/Iterable;\n\n"
               r"    \.line 996\n    new-instance v3, " + re.escape(comparator) +
               r"[\s\S]*?    move-result-object v1\n\n(?=    :cond_263\n)")
    value, changed = re.subn(pattern, "", value)
    if changed != 1:
        raise ValueError("Could not remove the category sort from mission details")
    start = value.index(".method final v078RefreshDialogue()V")
    end = value.index(".end method", start)
    method = value[start:end]
    keep_mode = re.sub(
        rf"    sget-object v0, {MODEL}GtaMissionDetailsActivity\$ConversationView;->ALL:[^\n]+\n\n"
        rf"    iput-object v0, p0, {ACTIVITY}->conversationView:[^\n]+\n\n", "", method)
    if method == keep_mode:
        raise ValueError("Could not keep the current reading mode when switching archives")
    path.write_text(value[:start] + keep_mode + value[end:], encoding="utf-8")


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
        flow_entry = ZipInfo("classes5.dex")
        flow_entry.compress_type = ZIP_STORED
        result.writestr(flow_entry, replacements["classes5.dex"].read_bytes())


def build(base_apk, output, keystore_zip, android_tools, android_jar, smali_tools, dex2jar):
    with base_apk.open("rb") as stream:
        if file_digest(stream, "sha256").hexdigest() != BASE_HASH:
            raise ValueError("Use the original signed v0.7.10 APK from GitHub Releases")
    verify(base_apk, android_tools)
    plan = json.loads((HERE / "flow-plan.json").read_bytes())
    with tempfile.TemporaryDirectory(prefix="draborn-0711-") as temporary:
        work = Path(temporary)
        with ZipFile(base_apk) as archive:
            for name in ("classes.dex", "classes2.dex", "classes3.dex", "classes4.dex"):
                (work / name).write_bytes(archive.read(name))
        details = disassemble(work, "classes3.dex", smali_tools)
        patch_details(details / NAMESPACE / "GtaMissionDetailsActivity.smali")
        helper = disassemble(work, "classes4.dex", smali_tools)
        patch_helper(helper / NAMESPACE / "MissionV078.smali")
        updated3 = work / "updated3.dex"
        updated4 = work / "updated4.dex"
        assemble(details, updated3, smali_tools)
        assemble(helper, updated4, smali_tools)
        flow = compile_flow(work, android_tools, android_jar, dex2jar,
                            [work / "classes.dex", work / "classes2.dex", updated3, updated4])

        unaligned, aligned, signed = (work / name for name in ("unaligned.apk", "aligned.apk", "signed.apk"))
        package(base_apk, unaligned, {"classes3.dex": updated3, "classes4.dex": updated4, "classes5.dex": flow}, plan)
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
