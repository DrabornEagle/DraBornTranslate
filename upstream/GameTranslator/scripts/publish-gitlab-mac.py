#!/usr/bin/env python3
# SPDX-License-Identifier: AGPL-3.0-only
"""Mac sürümünü (scripts/package-mac.sh çıktısı) GitLab sürüm sayfasına yükler; kurulu uygulamalar Sparkle ile görür.

Kullanım:
    python3 scripts/publish-gitlab-mac.py --dir dist/mac               # yalnız ne yapılacağını yazar
    python3 scripts/publish-gitlab-mac.py --dir dist/mac --yes         # gerçekten yükler
    python3 scripts/publish-gitlab-mac.py --dir dist/mac --attach-to v1.9.0 --yes
        # dosyaları yeniden yüklemeden, bu Mac sürümünün bağlantılarını başka (daha yeni) bir sürüm sayfasına ekler

Erişim anahtarı GITLAB_TOKEN ortam değişkeninden ya da --token-file ile verilen dosyadan okunur (api yetkili, depoda
en az Developer; komut satırına yazılmaz, hiçbir yere kaydedilmez).

Ne yüklenir, nereye:
  Paket deposu  gametranslator-mac / <sürüm> /
      GameTranslator-macos.dmg   (önce)
      appcast.xml                (en son: dosyası hazır olmayan bir sürümü hiç göstermesin)
  Sürüm sayfası v<sürüm> (etiket depoda olmalı): iki "package" bağlantısı
      GameTranslator-macos.dmg  → direct_asset_path /GameTranslator-macos.dmg
      appcast.xml               → direct_asset_path /appcast.xml

Neden kalıcı "latest" adresi (mac/project.yml SUFeedURL):
  https://gitlab.com/sefflex/GameTranslator/-/releases/permalink/latest/downloads/appcast.xml
  GitLab bu adresi her zaman en son yayımlanan sürümün (released_at) "appcast.xml" doğrudan bağlantısına yönlendirir.
  Böylece uygulamadaki adres hiç değişmez, yeni sürümde yalnız yeni sürüm sayfasına bağlantı eklenir; eski sürüm
  sayfaları kendi appcast'ini korur (geri dönmek için en yeni sürümü silmek yeter). Güncellemenin kendisi (DMG) sürüme
  bağlı paket adresinden iner (appcast'teki enclosure), "latest" yönlendirmesine bağlı değildir.
  Dikkat: "latest" platformdan bağımsızdır. Windows ya da Android için daha yeni bir sürüm sayfası açılırsa ve onda
  appcast.xml bağlantısı yoksa Mac'in güncelleme kanalı 404 verir; o sayfaya --attach-to ile bu Mac sürümünün
  bağlantıları eklenmelidir (betik sonunda kalıcı adresi anahtarsız okuyup denetler ve uyarır).
"""
import argparse
import json
import os
import pathlib
import re
import sys
import urllib.error
import urllib.parse
import urllib.request
import xml.etree.ElementTree as ET

PROJECT = 88821849
API = f"https://gitlab.com/api/v4/projects/{PROJECT}"
WEB = "https://gitlab.com/sefflex/GameTranslator"
PACKAGES = f"{API}/packages/generic/gametranslator-mac"
FEED = f"{WEB}/-/releases/permalink/latest/downloads/appcast.xml"
SPARKLE_NS = "{http://www.andymatuschak.org/xml-namespaces/sparkle}"


def read_release(directory: pathlib.Path) -> dict:
    env = directory / "release.env"
    if not env.is_file():
        raise SystemExit(f"release.env yok: {directory} (önce scripts/package-mac.sh)")
    info = dict(line.split("=", 1) for line in env.read_text(encoding="utf-8").split() if "=" in line)
    if info.get("MODE") != "release":
        raise SystemExit(f"{directory} bir deneme paketi ({info.get('MODE')}): yalnız Apple onaylı tam yayın yüklenir.")
    return info


def check_appcast(appcast: pathlib.Path, info: dict, dmg: pathlib.Path) -> str:
    """appcast.xml'in bu DMG'yi ve sürümü anlattığını denetler; enclosure adresini döndürür."""
    item = ET.parse(appcast).getroot().find("channel/item")
    if item is None:
        raise SystemExit("appcast.xml'de <item> yok")
    enclosure = item.find("enclosure")
    if item.findtext(f"{SPARKLE_NS}version") != info["BUILD_NUMBER"]:
        raise SystemExit("appcast.xml'deki yapı numarası release.env ile uyuşmuyor")
    if enclosure is None or enclosure.get("length") != str(dmg.stat().st_size):
        raise SystemExit("appcast.xml'deki DMG boyutu dosyayla uyuşmuyor (DMG appcast'ten sonra değişmiş)")
    if not enclosure.get(f"{SPARKLE_NS}edSignature"):
        raise SystemExit("appcast.xml'de EdDSA imzası yok")
    expected = f"{PACKAGES}/{info['VERSION']}/{dmg.name}"
    if enclosure.get("url") != expected:
        raise SystemExit(f"appcast.xml'deki indirme adresi beklenen değil: {enclosure.get('url')} (beklenen {expected})")
    return expected


def request(method: str, url: str, token: str | None = None, data: bytes | None = None, form: dict | None = None):
    headers = {"User-Agent": "GameTranslator-release"}
    if token:
        headers["PRIVATE-TOKEN"] = token
    if form is not None:
        data = urllib.parse.urlencode(form).encode()
        headers["Content-Type"] = "application/x-www-form-urlencoded"
    elif data is not None:
        headers["Content-Type"] = "application/octet-stream"
    req = urllib.request.Request(url, data=data, method=method, headers=headers)
    try:
        with urllib.request.urlopen(req, timeout=1800) as response:
            return response.status, response.read()
    except urllib.error.HTTPError as error:
        return error.code, error.read()


def must(status_body, what: str, ok=(200, 201)):
    status, body = status_body
    if status not in ok:
        raise SystemExit(f"{what} başarısız ({status}): {body.decode('utf-8', 'replace')[:300]}")
    return body


def links_for(info: dict, dmg_name: str) -> list[dict]:
    base = f"{PACKAGES}/{info['VERSION']}"
    return [
        # Ad, sayfadaki diğer bağlantılar gibi açıklamalı; kalıcı adresi direct_asset_path belirler.
        {"name": f"{dmg_name} (macOS v{info['VERSION']})", "url": f"{base}/{dmg_name}",
         "direct_asset_path": f"/{dmg_name}", "link_type": "package"},
        {"name": "appcast.xml (Mac güncelleme kanalı)", "url": f"{base}/appcast.xml",
         "direct_asset_path": "/appcast.xml", "link_type": "other"},
    ]


def same_link(existing: dict, link: dict) -> bool:
    """Aynı kalıcı yolu (API yanıtında direct_asset_url olarak döner) ya da aynı adı taşıyan bağlantı."""
    url = existing.get("direct_asset_url") or ""
    return existing.get("name") == link["name"] or url.endswith(f"/downloads{link['direct_asset_path']}")


def attach_links(tag: str, links: list[dict], token: str) -> None:
    quoted = urllib.parse.quote(tag, safe="")
    status, body = request("GET", f"{API}/releases/{quoted}", token)
    if status == 404:
        status, _ = request("GET", f"{API}/repository/tags/{quoted}", token)
        if status == 404:
            raise SystemExit(f"{tag} etiketi depoda yok: önce etiketi oluşturup gönderin (git tag {tag} && git push origin {tag}).")
        print(f"{tag} için sürüm sayfası yok, açılıyor ...")
        must(request("POST", f"{API}/releases", token, form={
            "tag_name": tag, "name": f"GameTranslator {tag.lstrip('v')}",
            "description": f"GameTranslator {tag.lstrip('v')}. Ayrıntılar: docs/surum-notlari.md"}), "sürüm sayfası açma")
    elif status != 200:
        raise SystemExit(f"sürüm sayfası okunamadı ({status}): {body.decode('utf-8', 'replace')[:300]}")

    existing = json.loads(must(request("GET", f"{API}/releases/{quoted}/assets/links?per_page=100", token), "bağlantıları okuma"))
    for link in links:
        same = [e for e in existing if same_link(e, link)]
        if same:
            must(request("PUT", f"{API}/releases/{quoted}/assets/links/{same[0]['id']}", token, form=link), f"bağlantı güncelleme {link['name']}")
            print(f"  bağlantı güncellendi: {tag} {link['name']}")
        else:
            must(request("POST", f"{API}/releases/{quoted}/assets/links", token, form=link), f"bağlantı ekleme {link['name']}")
            print(f"  bağlantı eklendi: {tag} {link['name']}")


def verify_feed(info: dict) -> None:
    """Kalıcı adresi uygulamanın yaptığı gibi anahtarsız okur."""
    status, body = request("GET", FEED)
    if status != 200:
        print(f"UYARI: {FEED} {status} döndü. En son sürüm sayfasında appcast.xml bağlantısı olmalı (--attach-to).")
        return
    item = ET.fromstring(body).find("channel/item")
    seen = item.findtext(f"{SPARKLE_NS}version") if item is not None else None
    if seen != info["BUILD_NUMBER"]:
        print(f"UYARI: kalıcı adres yapı {seen} gösteriyor, bu sürüm {info['BUILD_NUMBER']}. "
              "Daha yeni bir sürüm sayfası var ya da bu sürüm henüz en son değil.")
    else:
        print(f"Tamam: kurulu uygulamalar {FEED} üzerinden {info['VERSION']} ({seen}) sürümünü görecek.")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    parser.add_argument("--dir", required=True, type=pathlib.Path, help="package-mac.sh çıktısı (dist/mac)")
    parser.add_argument("--token-file", type=pathlib.Path, help="erişim anahtarının durduğu dosya (yoksa GITLAB_TOKEN)")
    parser.add_argument("--attach-to", metavar="ETIKET", help="yükleme yapmadan bağlantıları bu sürüm sayfasına ekle")
    parser.add_argument("--yes", action="store_true", help="gerçekten yükle (verilmezse yalnız plan yazılır)")
    args = parser.parse_args()
    sys.stdout.reconfigure(encoding="utf-8")

    info = read_release(args.dir)
    dmg = args.dir / info["DMG"]
    appcast = args.dir / "appcast.xml"
    for path in (dmg, appcast):
        if not path.is_file():
            raise SystemExit(f"dosya yok: {path}")
    check_appcast(appcast, info, dmg)
    tag = args.attach_to or info["TAG"]
    if not re.fullmatch(r"v\d+(\.\d+)*", info["TAG"]):
        raise SystemExit(f"beklenmeyen etiket: {info['TAG']}")
    links = links_for(info, dmg.name)

    print(f"Sürüm: {info['VERSION']} (yapı {info['BUILD_NUMBER']}), sürüm sayfası: {WEB}/-/releases/{tag}")
    if not args.attach_to:
        print(f"Paket deposu: {PACKAGES}/{info['VERSION']}/")
        for path in (dmg, appcast):
            print(f"  {path.name}  ({path.stat().st_size / 1e6:.1f} MB)")
    for link in links:
        print(f"  bağlantı {link['name']} → {link['url']} (kalıcı: {WEB}/-/releases/permalink/latest/downloads{link['direct_asset_path']})")
    if not args.yes:
        print("Hiçbir şey yüklenmedi. Yüklemek için --yes ekle.")
        return 0

    token = (args.token_file.read_text(encoding="utf-8").strip() if args.token_file else os.environ.get("GITLAB_TOKEN", "").strip())
    if not token:
        raise SystemExit("erişim anahtarı yok: GITLAB_TOKEN ortam değişkeni ya da --token-file")
    if not args.attach_to:
        for path in (dmg, appcast):
            print(f"yükleniyor: {path.name} ...", flush=True)
            must(request("PUT", f"{PACKAGES}/{info['VERSION']}/{path.name}", token, data=path.read_bytes()), f"yükleme {path.name}")
    attach_links(tag, links, token)
    verify_feed(info)
    return 0


if __name__ == "__main__":
    sys.exit(main())
