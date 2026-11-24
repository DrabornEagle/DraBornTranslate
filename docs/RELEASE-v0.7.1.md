# DraBornTranslate v0.7.1 · Cloud Gaming Pro

## Source preview — not a device-tested release
- CameraX replaces the manually managed Camera2 preview; ML Kit returns OCR boxes in PreviewView coordinates. Both landscape directions, portrait and automatic orientation. Dedicated camera viewport, separate reader sidebar in landscape and bottom panel in portrait, fit/fill scaling and safe insets.
- Two independent draggable/resizable OCR rectangles and precise percentage controls. Separate subtitle/Lens sizes, three translation modes, zoom, saved preferences and text-only view.
- Existing pack-first translation engine retained. The camera stays active in optical text-only view.
- Experimental camera-free Android mode: start a separately installed cloud client, select it in Android screen sharing, and use the existing overlay. On Android 13 and below manually return to the client. Protected or black frames cannot be translated. This does not mirror a running Portal cloud session.

## PS App captures (camera-free, non-live)
On Portal use Create to capture a screenshot, then download it via PS App → Game Library → Captures. Open DraBornTranslate → Kamerasız · PS App ekran görüntüsü and select the file. Local OCR and the existing pack-first engine translate up to 40 detected lines. Original and translated text can be selected. No PSN credentials or camera/mic permissions are required for this mode. Capture upload can be delayed; not a live subtitle feed.
Sony announcement: https://blog.playstation.com/?p=403496

## Research and limits
Sony explicitly excludes Share Screen, Share Play and broadcasting during Portal cloud streaming:
https://www.playstation.com/en-us/support/subscriptions/psportal-cloud-game-streaming/

Con (https://github.com/houqai/Con-app) describes an unofficial independent Android cloud client. Chiaki-ng Cloud (https://github.com/oversoul-love/chiaki-ng-cloud) describes experimental cloud protocol support. Neither has been verified to duplicate an existing Portal session. Do not provide PSN credentials to DraBornTranslate; no account login or network interception is added. No protected-content bypass or unofficial client code is bundled.

## Verification checklist
- Real device: both landscape directions and portrait, preview proportions, OCR alignment and separate region persistence.
- Confirm subtitles clear as dialogue changes, Lens text is clipped to the intended region and both modes work together.
- Check pack lookup, background/resume, denied camera permission, Android screen sharing and secure/black-frame behavior.
- CI runs unit tests, lint and debug assembly. Release signing uses existing repository secrets and checks the certificate against the prior signed APK. A successful build is not proof of Portal hardware compatibility.
