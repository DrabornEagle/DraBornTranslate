#!/bin/sh
# SPDX-License-Identifier: AGPL-3.0-only
# LG (webOS) TV uygulamasını dist/tv-app/ altında paketler (kaynak: tv/webos). Masaüstü ve mobil uygulamalar bu
# paketi içerir; geliştirici modu açık TV'ye kendileri kurar. webOS CLI'ı (ares-package) varsa o kullanılır, yoksa
# aynı biçimi üreten scripts/package-ipk.py. Mac, iPhone ve Android uygulamalarına giren kopya
# mobile/android/app/src/main/assets/tv-app altındadır: OUT=mobile/android/app/src/main/assets/tv-app ile oraya yazılır.
set -e
cd "$(dirname "$0")/.."
OUT="${OUT:-dist/tv-app}"
[ -f tv/webos/appinfo.json ] || { echo "tv/webos/appinfo.json yok."; exit 1; }
mkdir -p "$OUT"
rm -f "$OUT"/com.gametranslator.tv_*_all.ipk
if command -v ares-package > /dev/null; then
  ares-package tv/webos -o "$OUT" -n
else
  PY=$(command -v python3 || command -v python)
  "$PY" scripts/package-ipk.py --app-dir tv/webos --version auto --out-dir "$OUT"
fi
ls "$OUT"/com.gametranslator.tv_*_all.ipk
