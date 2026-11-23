#!/bin/zsh
# SPDX-License-Identifier: AGPL-3.0-only
# Mac sürümünü baştan sona çıkarır: imzalı + Apple onaylı DMG ve Sparkle appcast'i (scripts/package-mac.sh), ardından
# GitLab'a yükleme (scripts/publish-gitlab-mac.py). Adımlar ve ilk kurulum: docs/mac-yayin.md
#
# Kullanım:
#   scripts/release-mac.sh             paketi üretir, GitLab'a ne yükleneceğini yazar (yüklemez)
#   scripts/release-mac.sh --publish   paketi üretir ve yükler (GITLAB_TOKEN ya da GITLAB_TOKEN_FILE gerekir)
#   scripts/release-mac.sh --upload-only   dist/mac'teki hazır paketi yükler (yeniden derlemez)
#
# Ayarlar package-mac.sh ile aynıdır (TEAM_ID, SIGN_IDENTITY, NOTARY_PROFILE, NOTARY_KEYCHAIN, SPARKLE_KEY_FILE).
# Sürüm v<MARKETING_VERSION> etiketine bağlanır; etiket GitLab'da olmalı (git push origin v1.8.49).
set -e
setopt pipefail
cd "${0:A:h}/.."

PUBLISH=0
BUILD=1
case "${1:-}" in
  "") ;;
  --publish) PUBLISH=1 ;;
  --upload-only) PUBLISH=1; BUILD=0 ;;
  -h|--help) sed -n '2,13p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
  *) echo "Bilinmeyen seçenek: $1 (--publish, --upload-only)"; exit 2 ;;
esac

TOKEN_ARGS=()
if (( PUBLISH )); then
  # Uzun derleme ve onay beklemesinden sonra anahtar eksik çıkmasın: en başta denetlenir.
  if [[ -n "$GITLAB_TOKEN_FILE" ]]; then
    [[ -s "$GITLAB_TOKEN_FILE" ]] || { echo "HATA: GITLAB_TOKEN_FILE okunamadı: $GITLAB_TOKEN_FILE"; exit 1; }
    TOKEN_ARGS=(--token-file "$GITLAB_TOKEN_FILE")
  elif [[ -z "$GITLAB_TOKEN" ]]; then
    echo "HATA: yükleme için GITLAB_TOKEN ya da GITLAB_TOKEN_FILE gerekli (api yetkili erişim anahtarı; docs/mac-yayin.md §4)."
    exit 1
  fi
fi

if (( BUILD )); then
  ./scripts/package-mac.sh --check
  ./scripts/package-mac.sh
fi

PY=$(command -v python3) || { echo "HATA: python3 bulunamadı."; exit 1; }
if (( PUBLISH )); then
  "$PY" scripts/publish-gitlab-mac.py --dir dist/mac "${TOKEN_ARGS[@]}" --yes
else
  "$PY" scripts/publish-gitlab-mac.py --dir dist/mac
  echo "Yüklemek için: scripts/release-mac.sh --upload-only (GITLAB_TOKEN ile)."
fi
