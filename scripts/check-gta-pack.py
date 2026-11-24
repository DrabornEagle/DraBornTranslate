"""Check the public catalog and package without publishing game dialogue."""

import gzip
import hashlib
import io
import json
import urllib.request

ORIGIN = "https://gametranslator-packs.gametranslator-pool.workers.dev"
MAX_PACK = 48 * 1024 * 1024
MAX_JSON = 96 * 1024 * 1024


def get(path: str, limit: int) -> bytes:
    headers = {"Accept-Encoding": "identity", "User-Agent": "DraBornTranslate-pack-check/5"}
    with urllib.request.urlopen(urllib.request.Request(ORIGIN + path, headers=headers), timeout=60) as response:
        data = response.read(limit + 1)
        if len(data) > limit:
            raise ValueError("Package exceeded its specified size")
        return data


catalog = json.loads(get("/v1/packs", 512 * 1024))
pack = next(entry for entry in catalog["packs"] if entry["id"] == "gta-6" and entry.get("lang", "tr") == "tr")
if not 0 < pack["size"] <= MAX_PACK:
    raise ValueError("The catalog returned an unsupported package size")
contents = get("/v1/packs/gta-6", pack["size"])
if len(contents) != pack["size"] or hashlib.sha256(contents).hexdigest() != pack["sha256"].lower():
    raise ValueError("The package checksum does not match the catalog")
with gzip.GzipFile(fileobj=io.BytesIO(contents)) as compressed:
    expanded = compressed.read(MAX_JSON + 1)
if len(expanded) > MAX_JSON:
    raise ValueError("The expanded package is too large")
decoded = json.loads(expanded)
items = decoded["entries"]
label = hashlib.sha256(b"kolt associates").hexdigest()[:16]
if not isinstance(items, dict) or not items.get(label):
    raise ValueError("The known labelled entry is missing")
print(f"GTA 6 Türkçe. Paket {pack['version']}. Katalog {pack['entries']} satır. İndirilen tabloda {len(items)} satır, JSON boyutu {len(expanded)} bayt.")
