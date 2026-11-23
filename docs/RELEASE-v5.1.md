# DraBornTranslate v5.1 · Android Full GameTranslator Fork

**Development and local signed Android APK — not an officially tested GitHub Release.**

- Base APK provided by user: GameTranslator-mobil-arm64.apk, Android v1.8.50.
- Upstream reference: GitLab GameTranslator v1.8.51 source (2026-11-23).
- Android identity in the locally produced APK: com.draborneagle.draborntranslate.
- Launcher: DraBornTranslate.
- Version: 5.1.0 / 51001.
- New keystore: RSA-3072, SHA-256 certificate, separate from previous DraBornTranslate app.
- Local checks: APK Signature Scheme v2, APK content digest, ZIP CRC, AXML name/package/version, native library 16-KiB alignment.
- There has been no real-device install/PS5 functional test. Older Kotlin DraBornTranslate modules are not all compiled into this rebranded binary.
- Private keystore and password must not be added to this public repository.

## GTA 6 translation pack

The original GameTranslator v1.8.50 release notes claim approximately 475,000 GTA VI Turkish entries, including labels such as Kolt & Associates:. The uploaded v1.8.51 source ZIP and uploaded v1.8.50 APK do NOT embed that translation pack. The original Android code uses a remote manifest at:
https://gametranslator-packs.gametranslator-pool.workers.dev/v1/packs

The upstream Android engine downloads packs on demand, validates SHA-256 checksums and queries local indexed translations before running the fallback translation model. This preserves pack-first behavior for entries actually downloaded and matched. The server's live pack completeness and actual 475k entry count were not independently verified. The third-party game's script is not republished in this repository or in the locally built APK.

## Reproduce from full Android source

1. Get the official upstream GameTranslator v1.8.51 git checkout / source ZIP.
2. Run: python scripts/prepare_gametranslator_full_v5_1.py path/to/GameTranslator-v1.8.51
3. Install Flutter, Android SDK/NDK, CMake and the exact Chiaki native dependencies required by GameTranslator.
4. From mobile: flutter pub get ; flutter build apk --release --target-platform android-arm64 --build-name 5.1.0 --build-number 51001
5. Sign with the user's own safe private signing key, verify with Android apksigner, test on a real ARM64 phone, and retain AGPL source license notices.

This rebranding script is not a claim that the earlier, independent Kotlin Lens/Portal implementation has been fully merged into the Flutter/Chiaki source project.

## Signing and installation

The newly signed local APK cannot be installed as an in-place update over the old DraBornTranslate app, because Android checks the signature certificate. Back up data, uninstall the old app and then install the new APK. Future upgrades require the newly generated keystore, which should remain private.

Source: https://gitlab.com/sefflex/GameTranslator
Source mirror: https://github.com/DrabornEagle/DraBornTranslate/tree/main/upstream/GameTranslator
GNU AGPL-3.0 copyright notices for Sefflex/GameTranslator and Sinan Yorulmaz/CouchSubs are preserved.
