"""Apply the reviewed mission timeline to the pinned app's embedded archive."""

from hashlib import sha256
from io import BytesIO
import json
from zipfile import ZIP_DEFLATED, ZipFile

VERSION = "2026.11.28.11"
OLD_VERSION = "2026.11.28.10"
REFERENCE = "gta-6-reference"
PERSONAL = "gta-6-personal-conversations"
PLAYABLE = {"Jason Duval", "Lucia Caminos"}


def pack_name(kind, version=VERSION):
    return f"{kind}-{version}.zip"


def encode(payload):
    return (json.dumps(payload, ensure_ascii=False, separators=(",", ":")) + "\n").encode()


def apply_flow(data, plan=None):
    if plan is not None and sha256(data).hexdigest() != plan["basePackSha256"]:
        raise ValueError("The timeline requires the published v0.7.10 dialogue pack")
    output = BytesIO()
    with ZipFile(BytesIO(data)) as base, ZipFile(output, "w", compression=ZIP_DEFLATED, compresslevel=9) as new:
        catalog = json.loads(base.read("catalog.json"))
        if catalog["version"] != OLD_VERSION:
            raise ValueError("Unexpected catalog version")
        catalog["version"] = VERSION
        updated = {}
        for mission in catalog["missions"]:
            compiled = plan["missions"].get(mission["id"]) if plan is not None else None
            if compiled is None:
                continue
            filename = f"texts/{mission['id']}.json"
            rows = json.loads(base.read(filename))
            updated[filename] = encode(apply_mission(mission, rows, compiled))
        for item in base.infolist():
            payload = encode(catalog) if item.filename == "catalog.json" else updated.get(item.filename)
            new.writestr(item, base.read(item) if payload is None else payload)
    return output.getvalue()


def apply_mission(mission, rows, compiled):
    if len(rows) != compiled["sourceRows"]:
        raise ValueError(f"Unexpected source row count for {mission['id']}")
    result = [line for line in rows if line["type"] != "dialogue"]
    used = set()
    scene_ids = set()
    for scene, records in compiled["scenes"]:
        identity = scene["id"]
        if identity in scene_ids or not records:
            raise ValueError(f"Duplicate or empty scene {identity}")
        scene_ids.add(identity)
        for entry in records:
            if isinstance(entry, dict) and "index" not in entry:
                line = dict(entry)
            else:
                index = entry["index"] if isinstance(entry, dict) else entry
                if index in used or rows[index]["type"] != "dialogue":
                    raise ValueError(f"Invalid source row {index} in {identity}")
                used.add(index)
                line = dict(rows[index])
                if isinstance(entry, dict) and entry.get("speaker"):
                    speaker = entry["speaker"]
                    line.update(speaker=speaker, npc=speaker not in PLAYABLE)
            line["sceneId"] = identity
            result.append(line)
    if len(used) != sum(row["type"] == "dialogue" for row in rows):
        raise ValueError(f"Timeline lost original dialogue for {mission['id']}")
    mission["dialogueScenes"] = [scene for scene, _ in compiled["scenes"]]
    mission["textCount"] = len(result)
    return result
