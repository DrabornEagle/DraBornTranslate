#!/usr/bin/env python3
"""Prepare GameTranslator Android v1.8.51 source as DraBornTranslate 5.1.

This script renames the app's published identity, version and launcher, while
preserving upstream AGPL source notices, native class packages and TV protocols.
"""
import argparse
from pathlib import Path

def replace(path, before, after):
    data=path.read_text(encoding="utf-8")
    if before not in data:
        raise RuntimeError("Expected source value not found: "+str(path))
    path.write_text(data.replace(before,after),encoding="utf-8")
    print("Updated",path)

def main():
    parser=argparse.ArgumentParser()
    parser.add_argument("source",type=Path)
    args=parser.parse_args()
    src=args.source.resolve()
    if not (src/"LICENSE").exists() or not (src/"NOTICE").exists():
        raise SystemExit("Refusing source without AGPL license and original notices")
    root=src/"mobile"
    replace(root/"pubspec.yaml", "version: 1.8.50+18051", "version: 5.1.0+51001")
    replace(root/"pubspec.yaml", "GameTranslator: PS5 oyun altyazılarını çevirip TV'de gösterir.",
            "DraBornTranslate: PS5 oyun altyazılarını çevirip TV'de gösterir.")
    replace(root/"android/app/build.gradle.kts",
            'applicationId = "com.gametranslator.gametranslator_mobile"',
            'applicationId = "com.draborneagle.draborntranslate"')
    replace(root/"android/app/src/main/AndroidManifest.xml",
            'android:label="GameTranslator"', 'android:label="DraBornTranslate"')
    replace(root/"lib/main.dart","title: 'GameTranslator'","title: 'DraBornTranslate'")
    (src/"DRABORNTRANSLATE-SOURCE-NOTICE.txt").write_text(
        "DraBornTranslate v5.1 based on GameTranslator v1.8.51.\n"
        "Preserve original AGPL-3.0 copyright and source notices.\n"
        "The Android source must be built with Flutter, NDK and Chiaki-ng.\n"
        "GTA 6 translation dialogue pack is not bundled by this script.\n",
        encoding="utf-8")
    print("Source identity prepared: DraBornTranslate 5.1.0, code 51001.")
    print("Android runtime tests and signing remain required.")

if __name__=="__main__":
    main()
