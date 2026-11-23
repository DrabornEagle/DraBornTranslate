#!/usr/bin/env python3
# SPDX-License-Identifier: AGPL-3.0-only
"""Windows uygulamasının güncellemesini GitLab'a yükler; kurulu uygulamalar yeni sürümü buradan görür ve kendini günceller.

Kullanım:
    python scripts/publish-gitlab-release.py --dir <vpk çıktı klasörü>            # yalnız ne yapılacağını yazar
    python scripts/publish-gitlab-release.py --dir <vpk çıktı klasörü> --yes      # gerçekten yükler

<vpk çıktı klasörü>: `vpk pack ... --outputDir` ile üretilen klasör (releases.win.json ve GameTranslator-x.y.z-full.nupkg
içerir). Erişim anahtarı GITLAB_TOKEN ortam değişkeninden ya da --token-file ile verilen dosyadan okunur (depoya yazma
yetkili; komut satırına yazılmaz, hiçbir yere kaydedilmez).

Ne yüklenir, nereye:
  Paket deposu  gametranslator-update / win /   → uygulamanın baktığı sabit güncelleme kanalı
      GameTranslator-x.y.z-full.nupkg   (önce)
      releases.win.json                 (en son: liste, dosyası hazır olmayan bir sürümü hiç göstermesin)
Sürüm sayfası (etiket, açıklama, Setup.exe bağlantısı) bu betiğin işi değildir; eskisi gibi ayrıca açılır.

Uygulamadaki adres: windows/src/GameTranslator.App/GameTranslator.App.csproj içindeki GameTranslatorUpdateUrl.
"""
import argparse
import hashlib
import json
import os
import pathlib
import sys
import urllib.error
import urllib.request

PROJECT = 88821849
CHANNEL = f"https://gitlab.com/api/v4/projects/{PROJECT}/packages/generic/gametranslator-update/win"


def sha256(path: pathlib.Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1 << 20), b""):
            digest.update(chunk)
    return digest.hexdigest().upper()


def plan(directory: pathlib.Path):
    """Yüklenecek dosyalar (sırasıyla) ve sürüm; liste ile dosyalar tutarsızsa hata."""
    listing = directory / "releases.win.json"
    if not listing.is_file():
        raise SystemExit(f"releases.win.json yok: {directory}")
    assets = json.loads(listing.read_text(encoding="utf-8")).get("Assets") or []
    if not assets:
        raise SystemExit("releases.win.json boş")
    files = []
    for asset in assets:
        path = directory / asset["FileName"]
        if not path.is_file():
            raise SystemExit(f"listede var, klasörde yok: {asset['FileName']}")
        if path.stat().st_size != asset["Size"] or sha256(path) != asset["SHA256"].upper():
            raise SystemExit(f"dosya listeyle uyuşmuyor (boyut ya da SHA-256): {asset['FileName']}")
        files.append(path)
    versions = sorted({asset["Version"] for asset in assets})
    return files + [listing], versions


def upload(path: pathlib.Path, token: str) -> None:
    request = urllib.request.Request(f"{CHANNEL}/{path.name}", data=path.read_bytes(), method="PUT",
                                     headers={"PRIVATE-TOKEN": token, "Content-Type": "application/octet-stream"})
    with urllib.request.urlopen(request, timeout=1800) as response:
        if response.status not in (200, 201):
            raise SystemExit(f"yüklenemedi ({response.status}): {path.name}")


def published_versions() -> list[str]:
    """Kanaldaki listeyi anahtarsız (uygulamanın yaptığı gibi) okur."""
    request = urllib.request.Request(f"{CHANNEL}/releases.win.json", headers={"User-Agent": "GameTranslator"})
    with urllib.request.urlopen(request, timeout=60) as response:
        return sorted({asset["Version"] for asset in json.loads(response.read().decode("utf-8")).get("Assets", [])})


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    parser.add_argument("--dir", required=True, type=pathlib.Path, help="vpk çıktı klasörü")
    parser.add_argument("--token-file", type=pathlib.Path, help="erişim anahtarının durduğu dosya (yoksa GITLAB_TOKEN)")
    parser.add_argument("--yes", action="store_true", help="gerçekten yükle (verilmezse yalnız plan yazılır)")
    args = parser.parse_args()
    sys.stdout.reconfigure(encoding="utf-8")

    files, versions = plan(args.dir)
    print(f"Sürüm: {', '.join(versions)}")
    print(f"Hedef: {CHANNEL}/")
    for path in files:
        print(f"  {path.name}  ({path.stat().st_size / 1e6:.1f} MB)")
    if not args.yes:
        print("Hiçbir şey yüklenmedi. Yüklemek için --yes ekle.")
        return 0

    token = (args.token_file.read_text(encoding="utf-8").strip() if args.token_file else os.environ.get("GITLAB_TOKEN", "").strip())
    if not token:
        raise SystemExit("erişim anahtarı yok: GITLAB_TOKEN ortam değişkeni ya da --token-file")
    for path in files:
        print(f"yükleniyor: {path.name} ...", flush=True)
        try:
            upload(path, token)
        except urllib.error.HTTPError as error:
            raise SystemExit(f"yüklenemedi ({error.code}): {path.name}: {error.read().decode('utf-8', 'replace')[:300]}")
    seen = published_versions()
    print(f"Kanaldaki liste (anahtarsız okundu): {', '.join(seen)}")
    if not set(versions) <= set(seen):
        raise SystemExit("yüklenen sürüm kanalda görünmüyor")
    print("Tamam: kurulu uygulamalar açılışta (ve 6 saatte bir) bu sürümü görecek.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
