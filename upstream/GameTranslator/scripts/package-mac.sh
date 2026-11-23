#!/bin/zsh
# SPDX-License-Identifier: AGPL-3.0-only
# GameTranslator Mac uygulamasının (mac/project.yml, XcodeGen) yayın paketini üretir:
#   dist/mac/GameTranslator-macos.dmg   Developer ID ile imzalı, Apple onaylı (notarized), bilet zımbalı (stapled) DMG
#   dist/mac/appcast.xml                Sparkle güncelleme kaydı (DMG'nin EdDSA imzasıyla)
#   dist/mac/release.env                sürüm bilgisi (scripts/publish-gitlab-mac.py okur)
# Ayrıntılı anlatım ve sertifika/profil/anahtar kurulumu: docs/mac-yayin.md
#
# Kullanım:
#   scripts/package-mac.sh            tam yayın: derle, imzala, onaylat, DMG, appcast
#   scripts/package-mac.sh --check    yalnız ön denetim (araçlar, sertifika, notarytool profili, Sparkle anahtarı)
#   scripts/package-mac.sh --no-notarize   Developer ID ile imzalar, DMG üretir; Apple'a göndermez, appcast yazmaz
#   scripts/package-mac.sh --adhoc    yerel deneme: geçici (ad-hoc) imza, DMG; sertifika/onay/appcast yok
#
# Ayarlar (ortam değişkenleri):
#   TEAM_ID          Apple geliştirici takımı (varsayılan Q7WKXJ8S3C, "Rahmi Çnar SAR" bireysel hesabı)
#   SIGN_IDENTITY    codesign kimliği; varsayılan: anahtar zincirindeki "Developer ID Application: ... (TEAM_ID)"
#   NOTARY_PROFILE   `xcrun notarytool store-credentials` ile kaydedilmiş profil (varsayılan GameTranslator-notary)
#   NOTARY_KEYCHAIN  profil varsayılan anahtar zincirinde değilse onun yolu (CI)
#   SPARKLE_KEY_FILE Sparkle özel anahtar dosyası (generate_keys -x çıktısı ya da Ed25519 PEM); verilmezse anahtar
#                    zincirindeki Sparkle anahtarı ("https://sparkle-project.org" / ed25519) kullanılır
#   APP_VERSION, BUILD_NUMBER  project.yml'deki MARKETING_VERSION / CURRENT_PROJECT_VERSION yerine
#   XCODE_JOBS       derlemeyi hafifletmek için paralel iş sayısı
#
# Her adım eksik bir şey bulduğunda ne yapılacağını yazarak durur; hiçbir adım sessizce atlanmaz.
set -e
setopt pipefail
cd "${0:A:h}/.."
ROOT="$PWD"
export DEVELOPER_DIR="${DEVELOPER_DIR:-$(xcode-select -p)}"

MODE=release
case "${1:-}" in
  "") ;;
  --check) MODE=check ;;
  --no-notarize) MODE=signed ;;
  --adhoc) MODE=adhoc ;;
  -h|--help) sed -n '2,24p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
  *) echo "Bilinmeyen seçenek: $1 (--check, --no-notarize, --adhoc)"; exit 2 ;;
esac

TEAM_ID="${TEAM_ID:-Q7WKXJ8S3C}"
NOTARY_PROFILE="${NOTARY_PROFILE:-GameTranslator-notary}"
GITLAB_PROJECT_URL="https://gitlab.com/sefflex/GameTranslator"
# Generic paket deposu (Windows güncellemeleri de aynı projede: publish-gitlab-release.py). Proje herkese açık olduğundan
# dosyalar anahtarsız indirilir. Sürüm sayfasındaki bağlantılar buraya yönlenir.
GITLAB_PACKAGES="https://gitlab.com/api/v4/projects/88821849/packages/generic/gametranslator-mac"
DOCS="docs/mac-yayin.md"

BUILD="$ROOT/build/mac"
OUT="$ROOT/dist/mac"
ARCHIVE="$BUILD/GameTranslator.xcarchive"
ENTITLEMENTS="$ROOT/mac/GameTranslator/GameTranslator.entitlements"
DMG_NAME=GameTranslator-macos.dmg

step() { print -P "%F{cyan}==>%f $*"; }
fail() { print -P "%F{red}HATA:%f $*" >&2; exit 1; }
warn() { print -P "%F{yellow}Uyarı:%f $*" >&2; }

# ---------------------------------------------------------------- ön denetim
mkdir -p "$ROOT/build"
PROBLEMS=()
problem() { PROBLEMS+=("$*"); }

for TOOL in xcodegen xcodebuild hdiutil codesign ditto; do
  command -v $TOOL > /dev/null || problem "$TOOL bulunamadı (xcodegen: brew install xcodegen; diğerleri Xcode ile gelir)."
done
[[ -f third-party/build/CChiaki.xcframework/Info.plist ]] \
  || problem "third-party/build/CChiaki.xcframework yok: önce scripts/build-chiaki-macos.sh (ve iOS dilimleri için build-chiaki-ios.sh) çalıştırın."

IDENTITY=""
if [[ $MODE != adhoc ]]; then
  # Aynı Mac'te başka takımların sertifikaları da olabilir (ör. eski Sefflex hesabı); kimlik takım koduyla seçilir ve
  # SHA-1 parmak iziyle kullanılır, codesign'ın ad benzerliğinden yanlış sertifikayı seçmesi önlenir.
  if [[ -n "$SIGN_IDENTITY" ]]; then
    IDENTITY_LINE=$(security find-identity -v -p codesigning | grep -F "$SIGN_IDENTITY" | head -1 || true)
  else
    IDENTITY_LINE=$(security find-identity -v -p codesigning | grep -F "\"Developer ID Application:" | grep -F "($TEAM_ID)\"" | head -1 || true)
  fi
  if [[ -z "$IDENTITY_LINE" ]]; then
    problem "Anahtar zincirinde takım $TEAM_ID için \"Developer ID Application\" sertifikası yok.
       Hesap sahibi (Rahmi Çnar SAR) developer.apple.com › Certificates › + › Developer ID Application ile bu Mac'in
       CSR'sinden sertifikayı oluşturmalı; .cer bu Mac'teki özel anahtarla .p12 yapılıp içe aktarılır. Ayrıntı: $DOCS §1."
  else
    IDENTITY=$(print -r -- "$IDENTITY_LINE" | awk '{print $2}')
    IDENTITY_NAME=$(print -r -- "$IDENTITY_LINE" | sed -E 's/^[^"]*"(.*)"$/\1/')
  fi
fi

if [[ $MODE == release || $MODE == check ]]; then
  KEYCHAIN_ARGS=()
  [[ -n "$NOTARY_KEYCHAIN" ]] && KEYCHAIN_ARGS=(--keychain "$NOTARY_KEYCHAIN")
  # notarytool history profili gerçekten Apple'a karşı dener: yanlış parola ya da süresi dolmuş anahtar da burada çıkar.
  if ! xcrun notarytool history --keychain-profile "$NOTARY_PROFILE" "${KEYCHAIN_ARGS[@]}" > "$ROOT/build/notary-check.log" 2>&1 \
       < /dev/null; then
    problem "notarytool profili \"$NOTARY_PROFILE\" kullanılamıyor ($(tail -1 "$ROOT/build/notary-check.log" 2>/dev/null)).
       Kurulum: xcrun notarytool store-credentials $NOTARY_PROFILE --apple-id <e-posta> --team-id $TEAM_ID
       (uygulamaya özel parola) ya da --key/--key-id/--issuer (App Store Connect API anahtarı). Ayrıntı: $DOCS §2."
  fi

  if [[ -n "$SPARKLE_KEY_FILE" ]]; then
    [[ -f "$SPARKLE_KEY_FILE" ]] || problem "SPARKLE_KEY_FILE bulunamadı: $SPARKLE_KEY_FILE"
  elif ! security find-generic-password -s "https://sparkle-project.org" -a ed25519 > /dev/null 2>&1; then
    problem "Sparkle EdDSA özel anahtarı anahtar zincirinde yok (ve SPARKLE_KEY_FILE verilmedi).
       Uygulamadaki SUPublicEDKey'in eşi olan anahtarı içe aktarın: generate_keys -f <dosya>. Ayrıntı: $DOCS §3."
  fi

  # Sparkle imzası yayından önce SUPublicEDKey ile doğrulanır (Ed25519): sistemdeki LibreSSL yetmez, OpenSSL 3+ gerekir.
  OPENSSL=""
  for CANDIDATE in "${OPENSSL_BIN:-}" /opt/homebrew/opt/openssl@3/bin/openssl /opt/homebrew/bin/openssl openssl; do
    [[ -n "$CANDIDATE" ]] && command -v "$CANDIDATE" > /dev/null || continue
    [[ "$("$CANDIDATE" version 2>/dev/null)" == OpenSSL\ [3-9]* ]] && { OPENSSL="$CANDIDATE"; break; }
  done
  [[ -n "$OPENSSL" ]] || problem "OpenSSL 3 ya da üstü bulunamadı (brew install openssl@3 ya da OPENSSL_BIN=...)."
fi

if (( ${#PROBLEMS} )); then
  print -P "%F{red}Ön denetim başarısız:%f" >&2
  for P in "${PROBLEMS[@]}"; do print -r -- "  - $P" >&2; done
  exit 1
fi
if [[ $MODE == check ]]; then
  step "Ön denetim tamam: kimlik $IDENTITY_NAME, notarytool profili $NOTARY_PROFILE, Sparkle anahtarı var."
  echo "  (Sparkle anahtarının uygulamadaki SUPublicEDKey ile eşleştiği derlemeden sonra denetlenir.)"
  exit 0
fi

# ---------------------------------------------------------------- derleme (xcodegen + xcodebuild archive)
# Türkçe dublajın ses motoru uygulamaya kopyalanır (project.yml: dist/tts/pocket-tts, isteğe bağlı kaynak).
[[ -x dist/tts/pocket-tts ]] || { step "pocket-tts indiriliyor"; ./scripts/fetch-pocket-tts.sh mac; }

step "Xcode projesi üretiliyor"
(cd mac && xcodegen generate --quiet)

if [[ $MODE == adhoc ]]; then
  SIGN_ARGS=(CODE_SIGN_IDENTITY="-" CODE_SIGN_STYLE=Manual DEVELOPMENT_TEAM="")
else
  # Xcode'un eklediği get-task-allow yetkisi olursa Apple onayı reddedilir; --timestamp onay için şarttır.
  SIGN_ARGS=(CODE_SIGN_IDENTITY="Developer ID Application" CODE_SIGN_STYLE=Manual DEVELOPMENT_TEAM="$TEAM_ID"
             PROVISIONING_PROFILE_SPECIFIER="" ENABLE_HARDENED_RUNTIME=YES
             OTHER_CODE_SIGN_FLAGS="--timestamp" CODE_SIGN_INJECT_BASE_ENTITLEMENTS=NO)
fi
VERSION_ARGS=()
[[ -n "$APP_VERSION" ]] && VERSION_ARGS+=(MARKETING_VERSION="$APP_VERSION")
[[ -n "$BUILD_NUMBER" ]] && VERSION_ARGS+=(CURRENT_PROJECT_VERSION="$BUILD_NUMBER")
JOB_ARGS=()
[[ -n "$XCODE_JOBS" ]] && JOB_ARGS=(-jobs "$XCODE_JOBS")

rm -rf "$ARCHIVE"
mkdir -p "$BUILD"
step "Arşivleniyor (Release, arm64) → $ARCHIVE"
xcodebuild -project mac/GameTranslator.xcodeproj -scheme GameTranslator -configuration Release \
  -destination 'generic/platform=macOS' -derivedDataPath "$BUILD/DerivedData" -archivePath "$ARCHIVE" \
  "${JOB_ARGS[@]}" "${SIGN_ARGS[@]}" "${VERSION_ARGS[@]}" ARCHS=arm64 ONLY_ACTIVE_ARCH=NO \
  archive > "$BUILD/archive.log" 2>&1 \
  || { grep -E "error:" "$BUILD/archive.log" | sort -u | head -20; fail "Arşivleme başarısız, ayrıntı: $BUILD/archive.log"; }

SRC_APP="$ARCHIVE/Products/Applications/GameTranslator.app"
[[ -d "$SRC_APP" ]] || fail "Arşivde GameTranslator.app yok ($SRC_APP)."
APP="$BUILD/GameTranslator.app"
rm -rf "$APP"
ditto "$SRC_APP" "$APP"
PLIST="$APP/Contents/Info.plist"
VERSION=$(/usr/libexec/PlistBuddy -c "Print CFBundleShortVersionString" "$PLIST")
BUILD_NO=$(/usr/libexec/PlistBuddy -c "Print CFBundleVersion" "$PLIST")
MIN_OS=$(/usr/libexec/PlistBuddy -c "Print LSMinimumSystemVersion" "$PLIST")
step "Sürüm $VERSION (yapı $BUILD_NO, macOS $MIN_OS+)"

# ---------------------------------------------------------------- paket içeriği denetimi
# Not: pipefail açıkken "komut | grep -q" erken kapanan boru yüzünden yanlış sonuç verebilir; çıktılar önce değişkene alınır.
[[ "$(lipo -archs "$APP/Contents/MacOS/GameTranslator")" == arm64 ]] || fail "Ana ikili yalnız arm64 olmalı."
# Uygulama macOS 26'yı hedefler; yeni SDK'yla gelen pipe2/dup3 zorunlu istenirse uygulama 26'da açılışta kapanır.
for BIN in "$APP"/Contents/MacOS/*; do
  IMPORTS=$(dyld_info -imports "$BIN" 2>/dev/null || true)
  if print -r -- "$IMPORTS" | grep -E "[[:space:]]_(pipe2|dup3)[[:space:]]" | grep -v weak-import > /dev/null; then
    fail "$BIN macOS 26'da olmayan pipe2/dup3'ü istiyor; uygulama macOS 26'da açılmaz."
  fi
done
[[ -x "$APP/Contents/MacOS/pocket-tts" ]] || fail "Dublaj ses motoru (pocket-tts) pakette yok."
for PATTERN in 'com.gametranslator.tv_*_all.ipk' 'gametranslator-androidtv_*.apk' 'gametranslator-samsung_*.wgt'; do
  FOUND=("$APP"/Contents/Resources/tv-app/${~PATTERN}(N))
  (( ${#FOUND} )) || fail "TV paketi eksik: Resources/tv-app/$PATTERN (kaynak: mobile/android/app/src/main/assets/tv-app)."
done
[[ -d "$APP/Contents/Resources/packs" ]] || fail "Oyun paketleri (Resources/packs) yok."
[[ -d "$APP/Contents/Frameworks/Sparkle.framework" ]] || fail "Sparkle.framework pakette yok."

# ---------------------------------------------------------------- içten dışa imza
# Xcode yalnız kendi derlediklerini imzalar; kopyalanan yardımcılar (pocket-tts, varsa TV yardımcısı) ve Sparkle'ın
# iç parçaları Apple onayı için kendi kimliğimizle, sıkılaştırılmış çalışmayla (hardened runtime) ve zaman damgasıyla
# yeniden imzalanır. Sıra içten dışadır: önce iç ikililer, sonra çerçeveler, en son uygulama.
if [[ $MODE == adhoc ]]; then
  SIGN=(codesign --force --options runtime -s -)
else
  SIGN=(codesign --force --options runtime --timestamp -s "$IDENTITY")
fi
sign() { "${SIGN[@]}" "$@" > /dev/null 2>> "$BUILD/codesign.log" || fail "İmzalanamadı: ${@[-1]} (ayrıntı: $BUILD/codesign.log)"; }
: > "$BUILD/codesign.log"
step "İç parçalar imzalanıyor (${IDENTITY_NAME:-ad-hoc})"

for HELPER in "$APP"/Contents/MacOS/*(N); do
  [[ "${HELPER:t}" == GameTranslator ]] && continue
  sign "$HELPER"
done

FRAMEWORKS="$APP/Contents/Frameworks"
SPARKLE="$FRAMEWORKS/Sparkle.framework/Versions/B"
# Sparkle belgelerindeki sıra (Downloader.xpc'nin ağ yetkisi korunur).
sign "$SPARKLE/XPCServices/Installer.xpc"
sign --preserve-metadata=entitlements "$SPARKLE/XPCServices/Downloader.xpc"
sign "$SPARKLE/Autoupdate"
sign "$SPARKLE/Updater.app"
sign "$FRAMEWORKS/Sparkle.framework"
# Diğer çerçeveler (WebRTC.framework ve SPM'in gömdüğü dinamik kitaplıklar).
for ITEM in "$FRAMEWORKS"/*.framework(N) "$FRAMEWORKS"/*.dylib(N); do
  [[ "${ITEM:t}" == Sparkle.framework ]] && continue
  sign "$ITEM"
done
# Uygulamanın içinde başka gömülü paket (PlugIns, XPCServices, Helpers) varsa onlar da.
for ITEM in "$APP"/Contents/{PlugIns,XPCServices,Helpers}/*(N); do sign "$ITEM"; done
sign --entitlements "$ENTITLEMENTS" "$APP"

step "İmza doğrulanıyor"
codesign --verify --deep --strict --verbose=2 "$APP" > "$BUILD/verify.log" 2>&1 \
  || { cat "$BUILD/verify.log"; fail "İmza doğrulanamadı."; }
SIGNATURE_INFO=$(codesign -d --verbose=2 "$APP" 2>&1)
ENTITLEMENTS_INFO=$(codesign -d --entitlements - --xml "$APP" 2>/dev/null || true)
[[ "$SIGNATURE_INFO" =~ "flags=[^ ]*runtime" ]] || fail "Uygulamada sıkılaştırılmış çalışma (hardened runtime) yok."
[[ "$ENTITLEMENTS_INFO" != *get-task-allow* ]] || fail "Uygulamada get-task-allow yetkisi var; Apple onayı reddeder."
if [[ $MODE != adhoc ]]; then
  [[ "$SIGNATURE_INFO" == *"TeamIdentifier=$TEAM_ID"* ]] || fail "Uygulama $TEAM_ID takımıyla imzalanmamış."
fi
# Her gömülü ikili de sıkılaştırılmış çalışmayla imzalı olmalı (Apple onayı tek tek bakar).
for BIN in "$APP"/Contents/MacOS/*(N) "$APP"/Contents/Frameworks/*.framework(N) \
           "$APP"/Contents/Frameworks/Sparkle.framework/Versions/B/{Autoupdate,Updater.app,XPCServices/*.xpc}(N); do
  INFO=$(codesign -d --verbose=2 "$BIN" 2>&1 || true)
  [[ "$INFO" =~ "flags=[^ ]*runtime" ]] || fail "Sıkılaştırılmış çalışma yok: ${BIN#$APP/}"
done

# ---------------------------------------------------------------- Apple onayı (uygulama)
KEYCHAIN_ARGS=()
[[ -n "$NOTARY_KEYCHAIN" ]] && KEYCHAIN_ARGS=(--keychain "$NOTARY_KEYCHAIN")
notarize() {  # $1: gönderilecek dosya, $2: günlük adı
  step "Apple'a onaya gönderiliyor: ${1:t} (profil $NOTARY_PROFILE)"
  xcrun notarytool submit "$1" --keychain-profile "$NOTARY_PROFILE" "${KEYCHAIN_ARGS[@]}" --wait --timeout 2h \
    2>&1 | tee "$BUILD/notary-$2.log" || true
  if ! grep -q "status: Accepted" "$BUILD/notary-$2.log"; then
    ID=$(sed -n 's/^ *id: \([0-9a-f-]*\).*/\1/p' "$BUILD/notary-$2.log" | head -1)
    [[ -n "$ID" ]] && xcrun notarytool log "$ID" --keychain-profile "$NOTARY_PROFILE" "${KEYCHAIN_ARGS[@]}" \
      "$BUILD/notary-$2-details.json" > /dev/null 2>&1 || true
    fail "Apple onayı reddedildi ya da tamamlanmadı: ${1:t}. Ayrıntı: $BUILD/notary-$2.log ${ID:+ve $BUILD/notary-$2-details.json}"
  fi
}

if [[ $MODE == release ]]; then
  # Uygulama ayrıca onaylatılıp zımbalanır: Uygulamalar'a kopyalanan .app internetsiz de Gatekeeper'dan geçsin.
  ZIP="$BUILD/GameTranslator-notarize.zip"
  rm -f "$ZIP"
  ditto -c -k --keepParent "$APP" "$ZIP"
  notarize "$ZIP" app
  xcrun stapler staple "$APP" || fail "Uygulamaya onay bileti zımbalanamadı."
  spctl --assess --type execute --verbose=2 "$APP" || fail "Gatekeeper uygulamayı kabul etmedi."
fi

# ---------------------------------------------------------------- DMG
step "DMG hazırlanıyor"
mkdir -p "$OUT"
DMG="$OUT/$DMG_NAME"
STAGE="$BUILD/dmg"
rm -rf "$STAGE" "$DMG"
mkdir -p "$STAGE"
ditto "$APP" "$STAGE/GameTranslator.app"
ln -s /Applications "$STAGE/Applications"
hdiutil create -volname GameTranslator -srcfolder "$STAGE" -fs HFS+ -format UDZO -ov -quiet "$DMG" \
  || fail "hdiutil DMG'yi oluşturamadı."
if [[ $MODE == adhoc ]]; then
  codesign --force -s - "$DMG"
else
  codesign --force --timestamp -s "$IDENTITY" "$DMG" || fail "DMG imzalanamadı."
fi
hdiutil verify -quiet "$DMG" || fail "DMG doğrulanamadı."

if [[ $MODE == release ]]; then
  notarize "$DMG" dmg
  xcrun stapler staple "$DMG" || fail "DMG'ye onay bileti zımbalanamadı."
  xcrun stapler validate "$DMG" > /dev/null || fail "DMG'deki onay bileti doğrulanamadı."
  spctl --assess --type open --context context:primary-signature --verbose=2 "$DMG" || fail "Gatekeeper DMG'yi kabul etmedi."
fi

LENGTH=$(stat -f %z "$DMG")
TAG="v$VERSION"
cat > "$OUT/release.env" <<EOF
VERSION=$VERSION
BUILD_NUMBER=$BUILD_NO
TAG=$TAG
DMG=$DMG_NAME
MODE=$MODE
EOF

if [[ $MODE != release ]]; then
  warn "$MODE paketi: Apple onayı yok, appcast üretilmedi; dağıtmayın."
  rm -f "$OUT/appcast.xml"
  step "Hazır: $DMG ($(du -h "$DMG" | cut -f1))"
  exit 0
fi

# ---------------------------------------------------------------- Sparkle appcast
# sign_update Sparkle'ın SPM ikili paketinde gelir (sürüm project.yml'deki Sparkle sürümüyle aynı).
SPARKLE_BIN=$(find "$BUILD/DerivedData/SourcePackages/artifacts" -path '*Sparkle/bin/sign_update' -type f 2>/dev/null | head -1)
[[ -x "$SPARKLE_BIN" ]] || fail "Sparkle'ın sign_update aracı bulunamadı ($BUILD/DerivedData/SourcePackages/artifacts)."
SPARKLE_BIN=${SPARKLE_BIN:h}
PUBLIC=$(/usr/libexec/PlistBuddy -c "Print SUPublicEDKey" "$PLIST")

SIGN_UPDATE_ARGS=()
if [[ -n "$SPARKLE_KEY_FILE" ]]; then
  KEY_FILE="$SPARKLE_KEY_FILE"
  if grep -q "BEGIN PRIVATE KEY" "$SPARKLE_KEY_FILE"; then
    # Eski düzen: Ed25519 PEM. sign_update ham 32 baytlık tohumu base64 olarak bekler.
    KEY_FILE="$BUILD/sparkle-seed"
    (umask 077; "$OPENSSL" pkey -in "$SPARKLE_KEY_FILE" -outform DER | tail -c 32 | base64 > "$KEY_FILE") \
      || fail "Sparkle PEM anahtarı okunamadı: $SPARKLE_KEY_FILE"
    trap 'rm -f "$BUILD/sparkle-seed"' EXIT
  fi
  SIGN_UPDATE_ARGS=(--ed-key-file "$KEY_FILE")
fi
# Uygulamadaki açık anahtarla eşleşmeyen imza bütün kullanıcıların güncellemesini kırar: önce doğrula.
SIG_ATTRS=$("$SPARKLE_BIN/sign_update" "${SIGN_UPDATE_ARGS[@]}" "$DMG") || fail "sign_update DMG'yi imzalayamadı."
SIGNATURE=$(print -r -- "$SIG_ATTRS" | sed -n 's/.*sparkle:edSignature="\([^"]*\)".*/\1/p')
[[ -n "$SIGNATURE" ]] || fail "sign_update imza üretmedi: $SIG_ATTRS"
# İmza, uygulamanın yapacağı gibi SUPublicEDKey ile doğrulanır.
VERIFY="$BUILD/sparkle-verify"
rm -rf "$VERIFY" && mkdir -p "$VERIFY"
# Ham 32 baytlık açık anahtar, Ed25519 SubjectPublicKeyInfo önekiyle DER'e çevrilir.
{ printf '\x30\x2a\x30\x05\x06\x03\x2b\x65\x70\x03\x21\x00'; print -rn -- "$PUBLIC" | base64 -D; } > "$VERIFY/public.der"
print -rn -- "$SIGNATURE" | base64 -D > "$VERIFY/signature.bin"
"$OPENSSL" pkeyutl -verify -pubin -keyform DER -inkey "$VERIFY/public.der" -rawin -in "$DMG" \
  -sigfile "$VERIFY/signature.bin" > /dev/null 2>&1 \
  || fail "Sparkle anahtarı uygulamadaki SUPublicEDKey ($PUBLIC) ile eşleşmiyor; bu imzayla güncelleme kurulmaz. Doğru anahtarı içe aktarın ($DOCS §3)."

PAGE="$GITLAB_PROJECT_URL/-/releases/$TAG"
# Güncelleme dosyası sürüme bağlı, değişmeyen adresten iner (kalıcı "latest" adresi yalnız appcast içindir).
ENCLOSURE="$GITLAB_PACKAGES/$VERSION/$DMG_NAME"
PUBDATE=$(LC_ALL=C date -u "+%a, %d %b %Y %H:%M:%S +0000")
cat > "$OUT/appcast.xml" <<EOF
<?xml version="1.0" encoding="utf-8"?>
<rss version="2.0" xmlns:sparkle="http://www.andymatuschak.org/xml-namespaces/sparkle">
  <channel>
    <title>GameTranslator</title>
    <link>$GITLAB_PROJECT_URL</link>
    <item>
      <title>GameTranslator $VERSION</title>
      <pubDate>$PUBDATE</pubDate>
      <sparkle:version>$BUILD_NO</sparkle:version>
      <sparkle:shortVersionString>$VERSION</sparkle:shortVersionString>
      <sparkle:minimumSystemVersion>$MIN_OS</sparkle:minimumSystemVersion>
      <link>$PAGE</link>
      <sparkle:fullReleaseNotesLink>$PAGE</sparkle:fullReleaseNotesLink>
      <enclosure url="$ENCLOSURE" length="$LENGTH" type="application/x-apple-diskimage" sparkle:edSignature="$SIGNATURE" />
    </item>
  </channel>
</rss>
EOF
xmllint --noout "$OUT/appcast.xml" 2>/dev/null || fail "appcast.xml geçerli XML değil."

step "Hazır:"
echo "  $DMG ($(du -h "$DMG" | cut -f1), imzalı, onaylı, zımbalı)"
echo "  $OUT/appcast.xml (sürüm $VERSION, yapı $BUILD_NO)"
echo "Yüklemek için: scripts/release-mac.sh --publish  (ya da python3 scripts/publish-gitlab-mac.py --dir dist/mac --yes)"
