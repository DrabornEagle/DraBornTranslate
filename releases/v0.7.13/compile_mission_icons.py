"""Associate original game marker textures already present in the APK with their mission contacts."""

import argparse
import json
from pathlib import Path
import re
import unicodedata
from zipfile import ZipFile

ALIASES = {
    "abe": "abe", "andrea": "andrea", "billy": "billy", "boobie": "boobie", "brian": "brian",
    "cal": "cal", "cellerica": "cellerica", "donnie": "donnie", "dr merle": "dr_merle",
    "dre quan": "drequan", "drequan": "drequan", "jason": "jason", "kenton": "kenton",
    "kirk": "kirk", "kordell": "kordell", "kyle": "kyle", "linda": "linda", "lucia": "lucia",
    "mattia": "mattia", "mia": "mia", "moses": "moses", "nate": "nate", "papa audacity": "papa_audacity",
    "pilot pete": "pilot_pete", "randy": "randy", "raul": "raul", "scarlet sun": "scarlet_sun",
    "shayna": "shayna", "shirley": "shirley", "tyrone": "tyrone", "vinny": "vinny", "yago": "yago"
}
OVERRIDES = {"cti-pil-rd-bc-parent": "donnie", "clean-up-after-donnie": "donnie",
             "get-the-chop": "lucia", "find-the-lost-drugs": "donnie"}


def normalize(name):
    text = unicodedata.normalize("NFD", name.lower())
    return " ".join(re.sub(r"[^a-z0-9]+", " ", "".join(c for c in text if not unicodedata.combining(c))).split())


def contact_texture(mission):
    label = (mission.get("giver") or next(iter(mission.get("characters") or []), "")).split("/", 1)[0]
    name = normalize(label)
    return next((code for prefix, code in ALIASES.items() if name == prefix or name.startswith(prefix+" ")), None)


def main(apk, output):
    with ZipFile(apk) as app:
        avatars = {Path(path).name for path in app.namelist() if path.startswith("assets/gta-6-mission-icons/")}
        existing = json.loads(app.read("assets/mission-icons.json"))
        reference = "assets/gta-6-reference-2026.11.28.12.zip"
        with app.open(reference) as entry, ZipFile(entry) as bundle:
            missions = json.loads(bundle.read("catalog.json"))["missions"]
    fixed = {}
    for mission in missions:
        identity = mission["id"]
        code = OVERRIDES.get(identity) or contact_texture(mission)
        if code is None:
            continue
        filename = f"blip_mission_{code}.webp"
        if filename in avatars:
            fixed[identity] = filename
    result = fixed | existing
    for identity in OVERRIDES.keys() & fixed.keys():
        result[identity] = fixed[identity]
    output.write_text(json.dumps(result, ensure_ascii=False, separators=(",", ":"))+"\n", encoding="utf-8")
    print(f"{len(result)} missions use original glyph textures ({len(set(result.values()))} glyphs)")


if __name__ == "__main__":
    cli = argparse.ArgumentParser(description=__doc__)
    cli.add_argument("apk", type=Path)
    cli.add_argument("output", type=Path)
    main(**vars(cli.parse_args()))
