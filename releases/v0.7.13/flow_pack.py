"""Install the ordered group of scenes while preserving the original subtitle payloads."""

from hashlib import sha256
from io import BytesIO
import json
from zipfile import ZIP_DEFLATED, ZipFile

VERSION = "2026.11.28.13"
OLD_VERSION = "2026.11.28.12"
REFERENCE = "gta-6-reference"
PERSONAL = "gta-6-personal-conversations"
PLAYABLE = {"Jason Duval", "Lucia Caminos"}
ARCHIVE = "archive-only"


def pack_name(kind, version=VERSION):
    return f"{kind}-{version}.zip"


def encode(payload):
    return (json.dumps(payload, ensure_ascii=False, separators=(",", ":")) + "\n").encode()


def apply_flow(data, plan=None):
    if plan is not None and sha256(data).hexdigest() != plan["basePackSha256"]:
        raise ValueError("The timeline requires the published v0.7.12 dialogue pack")
    output = BytesIO()
    with ZipFile(BytesIO(data)) as base, ZipFile(output, "w", compression=ZIP_DEFLATED, compresslevel=9) as new:
        catalog = json.loads(base.read("catalog.json"))
        if catalog["version"] != OLD_VERSION:
            raise ValueError("Unexpected catalog version")
        catalog["version"] = VERSION
        updated = {}
        base_names = set(base.namelist())
        for mission in catalog["missions"]:
            if mission["id"] == "cti-pil-rd-bc-parent":
                mission["giver"] = "Donnie"
                mission["mapLabels"] = ["Find Donnie’s Package"]
            compiled = plan["missions"].get(mission["id"]) if plan is not None else None
            if compiled is None:
                continue
            filename = f"texts/{mission['id']}.json"
            rows = json.loads(base.read(filename)) if filename in base_names else []
            updated[filename] = encode(apply_mission(mission, rows, compiled))
        for item in base.infolist():
            payload = encode(catalog) if item.filename == "catalog.json" else updated.get(item.filename)
            new.writestr(item, base.read(item) if payload is None else payload)
        for filename in sorted(updated.keys() - base_names):
            new.writestr(filename, updated[filename])
    return output.getvalue()


def apply_mission(mission, rows, compiled):
    if len(rows) != compiled["sourceRows"]:
        raise ValueError(f"Unexpected source row count for {mission['id']}")
    guides = [line for line in rows if line["type"] != "dialogue"]
    timeline = []
    used = set()
    scenes = []
    scene_ids = set()
    for scene, records in compiled["scenes"]:
        identity = scene["id"]
        if identity in scene_ids or not identity.startswith("timeline-") or not records:
            raise ValueError(f"Invalid timeline scene {identity}")
        scene_ids.add(identity)
        scenes.append(scene)
        for entry in records:
            if isinstance(entry, dict) and "index" not in entry:
                line = dict(entry)
            else:
                index = entry["index"] if isinstance(entry, dict) else entry
                if not 0 <= index < len(rows) or index in used or rows[index]["type"] != "dialogue":
                    raise ValueError(f"Invalid source row {index} in {identity}")
                used.add(index)
                line = dict(rows[index])
                if isinstance(entry, dict) and "speaker" in entry:
                    speaker = entry["speaker"]
                    if speaker:
                        line.update(speaker=speaker, npc=speaker not in PLAYABLE)
                    else:
                        line.pop("speaker", None)
                        line.pop("npc", None)
            line["sceneId"] = identity
            timeline.append(line)
    unplaced = [dict(row, sceneId=ARCHIVE) for i, row in enumerate(rows)
                if i not in used and row["type"] == "dialogue"]
    if unplaced:
        scenes.append({"id": ARCHIVE, "titleTr": "Kaynak replikleri", "category": "other"})
    result = guides + timeline + unplaced
    mission["dialogueScenes"] = scenes
    mission["textCount"] = len(result)
    return result
