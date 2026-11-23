# Cloud Gaming Translate — 0.7.0-alpha01

Experimental companion subtitles on the Android phone, not on the physical Portal screen. No PSN sign-in, camera or PS5 console required. Android 12+ with an installed on-device English (US) speech recognizer is required. The existing game pack and local translation engine are reused. Audio recognition runs only while the activity is visible and after microphone permission and explicit Start. No audio files are saved by this module.

## Device test
1. Install a compatible test build (debug signature differs from release; back up the existing app/pack first or use a separate device).
2. Install the GTA 6 Turkish pack via the main screen if permitted; verify its status.
3. On Portal launch the cloud game, enable English subtitles, reduce music relative to dialogue if supported.
4. Place phone near Portal speaker; open Cloud Gaming Translate and grant microphone permission, then start.
5. Check English transcription and Turkish text; measure dialogue-to-caption delay, dropped lines, overlapping speech, repeated lines, incorrect pack matches, and fallback quality. Test 30 minutes and stop/resume, denied permission, missing English model, and backgrounding.
6. Stop to release microphone. No silent game text or Lens translation is provided by audio mode.

## Next milestones
- Dedicated streaming ASR with voice activity detection and time-stamped PCM frames, test USB-C audio input with a suitable line-level stereo capture adapter and headphone splitter. Android SpeechRecognizer is not designed for unlimited continuous use; the alpha restarts short recognition sessions and can miss lines.
- Confidence-based pack matching and context-aware fallback, benchmark on real gameplay with user-supplied examples.
- Optional local subtitle display on a second screen; Portal overlay is not a verified capability.
- Run unit tests/lint/debug build, then verify device behavior and existing release signing identity before a release. No signed APK is claimed by this branch.
