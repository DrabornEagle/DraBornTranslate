# DraBornTranslate v5.0 · GameTranslator integration

**Development only — not a published, signed v5.0 Android update.**

Reference snapshot: GameTranslator v1.8.51, released 2026-11-23.
Source: https://gitlab.com/sefflex/GameTranslator
Licence: GNU AGPL-3.0-or-later. Preserve the original NOTICE, LICENSE,
Sinan Yorulmaz/CouchSubs and Sefflex copyright information.

## What is being synchronized

Official source in Flutter/Dart, Kotlin, C/C++, Swift, .NET and TV components is
mirrored under upstream/GameTranslator for integration work. Generated binaries,
game dialogue/copyrighted patch files and packaged APKs are deliberately omitted.
The importer also records the exact release tag and Git commit.

A daily GitHub Action polls GitLab Releases and mirrors the newest stable
upstream source snapshot to the isolated `upstream/GameTranslator` directory in
`main` (without automatically compiling or executing it). Original copyright
notices remain intact. Third-party changes are not auto-applied to the Android
host or published as a new DraBornTranslate production APK.
Original DraBornTranslate app and its Kotlin engine are kept intact.

The supplied v1.8.51 ZIP does not contain a GTA 6 translation pack. The GitLab
v1.8.51 release lists its Android APK as v1.8.50; source and Android versions
must therefore be tracked independently.

## Still required before declaring the v5.0 APK complete

- Build Flutter in the Kotlin host as a real feature, not a second installed app.
- Reconcile the PS5/Chiaki JNI native build, TV/Xbox modules, foreground services
  and Android permission/security requirements.
- Preserve the existing Lens and Portal capabilities and user data.
- Run unit tests, on-device OCR/translation tests, console/TV tests and signing checks.
- Sign only with the original DraBornTranslate production certificate.
- Preserve AGPL source offers and required copyright notices.

The imported source is a dependency for development, not yet an integrated binary.
