"""Recover mission-specific subtitle groups from the shipped archive and labelled game data."""

import argparse
from collections import Counter, defaultdict
import gzip
from hashlib import sha256
import json
from pathlib import Path
import re
from zipfile import ZipFile

from scene_evidence import key, normalized

HERE = Path(__file__).resolve().parent
REVIEWED = json.loads((HERE / "reviewed-scenes.json").read_bytes())
PLAYABLE = {"Jason Duval", "Lucia Caminos"}
NAMES = {"J": "Jason Duval", "L": "Lucia Caminos", "CAL": "Cal Hampton", "BRIAN": "Brian Heder",
         "DONNIE": "Donnie", "BOOBIE": "Boobie Ike", "ANDREA": "Andrea", "RAUL": "Raul Bautista",
         "KARIM": "Karim", "MATTIA": "Mattia Tauro", "SHIRLEY": "Shirley", "PEGGY": "Peggy",
         "RANDY": "Randy", "ANDY": "Andy", "LINDA": "Linda"}
REVIEWED_SPEAKERS = {
    "COM_SFH_FPF": {
        "IG1_ENTER": ["L", "J", "L", "J", "L", "L", "J", "L", "J", "L"],
        "IG1_REBUILD1": ["J", "L", "J", "L", "J", "L", "J", "L"],
        "IG1_GLASS4": ["L", "J", "L", "J"],
        "EXT": ["J", "L", "L", "J", "L"],
    },
    "CI_UP_BBNSHP": {
        "IG5_TALKSTART": ["SHIRLEY"], "IG5_TALK1": ["SHIRLEY","SHIRLEY"],
        "IG5_TALK2": ["SHIRLEY","SHIRLEY"], "IG5_TALK3": ["SHIRLEY"],
        "IG5_TALK4": ["SHIRLEY"], "IG5_TALK5": ["SHIRLEY"],
        "IG5_TALK6": ["SHIRLEY","SHIRLEY","SHIRLEY","SHIRLEY"],
        "SCTREQ_SER": ["J"], "SCTREQ_RECOG": ["J"], "SCTREQ_SEE": ["J"],
        "IG5_PEGGY2": ["PEGGY","J"], "IG5_GOTSCOOT": ["SHIRLEY","J","SHIRLEY","J","SHIRLEY"],
    },
    "CTI_PIL_RD_SH": {
        "INTRO_CAL1": ["CAL","J","CAL","CAL","J","CAL"],
        "TRACK_INTRO2": ["J",None,"J",None,"J"],
        "OUTRO_DON": ["J","DONNIE","DONNIE","J"],
    },
    "CTI_PIL_RD_BG": {"INSPECT": ["J"]},
    "FT_AMUCO": {"LOCKER": [None, "J"]},
    "CTI_SHOW_HATH": {
        "CALL_J": ["J", "RANDY", "J", "RANDY", "J", "RANDY", "RANDY", "RANDY", "J", "RANDY", "RANDY"],
        "ARR_BJ": [None, None],
        "CLONER_BJ": [None, None, None, None],
        "CLONE_BJ": [None, None],
        "TRACKER_BJ": [None, None],
    },
    "UP_MLSPM": {"MCS1": ["L", "LINDA", "LINDA", "L", "LINDA", "LINDA", "LINDA",
                          "L", "LINDA", "LINDA", "LINDA", "L", "L"]},
    "FT_PGIFT": {"ORDER_FLOWERS": ["J", None], "PICKED_FLOWERS4": ["J", None, "J", None]},
    "CSC_MLM_LEDR2": {"PHONE_ANDY1": ["ANDY", "L"] * 5, "DROPOFF": ["L", None]},
}
ASSOCIATED = {"cti-pil-rd-bc-parent": "clean-up-after-donnie"}
DISALLOWED = re.compile(r"(?:^|_)(?:ALT|FAIL|IDLE|RETRY|DEAD|KILLED|LOSE|WRONG|WARN|WANTED|RE)[0-9]*(?:_|$)")
SAFE_CUE = re.compile(r"(?:^|_)(?:TALK|CHAT|MEET|ARRIVE|INTRO|BRIEF|LEADIN|CALL|GREET|BANTER|CONVO)[0-9]*(?:_|$)")


def game_data(source):
    groups = defaultdict(lambda: defaultdict(list))
    roots_for_key = defaultdict(set)
    for f in sorted(source.glob("d_*.json")):
        for _route, table, label, english, *_ in json.loads(f.read_bytes()):
            root = table.partition("AU_")[0]
            if not english or not label.startswith(root + "_"):
                continue
            match = re.fullmatch(r"(.+?)_(\d+)([A-Z]?)", label[len(root) + 1:])
            norm = normalized(english)
            if not norm:
                continue
            digest = key(norm)
            if len(norm) >= 18:
                roots_for_key[digest].add(root)
            if match:
                # The source's first field identifies the playable route for the whole block.
                groups[root][match[1]].append((int(match[2]), match[3], digest))
    return groups, roots_for_key


def auto_root(mission, hashes, roots_for_key, titles):
    direct = [code for code, english, *_ in titles
              if normalized(english) == normalized(mission.get("titleEn") or "")]
    phrases = Counter(root for h in hashes for root in roots_for_key.get(h, ()))
    if len(direct) == 1 and phrases[direct[0]] >= 3:
        return direct[0]
    top = phrases.most_common(2)
    if top and top[0][1] >= 5 and (len(top) == 1 or top[0][1] >= 2*top[1][1]):
        return top[0][0]
    return None


def common_blocks(grouped, existing):
    # Prefer explicit named intro/outro blocks. Gameplay blocks must carry an ordered stage number and a dialogue cue.
    choices = defaultdict(list)
    for label, records in grouped.items():
        if DISALLOWED.search(label) or any(alt for _, alt, *_ in records):
            continue
        hits = sum(h in existing for _, _, h in records)
        if hits < 2:
            continue
        if label in {"INT", "INTRO", "LEADIN", "BRIEF"}:
            choices[0].append((hits, label))
        elif label in {"EXT", "OUTRO", "FINISH", "CONCLUSION"}:
            choices[10000].append((hits, label))
        else:
            phase = re.match(r"^(?:IG|MCS)(\d+)(?:_|$)", label)
            if phase and SAFE_CUE.search(label[phase.end():]) and hits >= 3:
                choices[int(phase[1])].append((hits, label))
    result = []
    for phase, candidates in sorted(choices.items()):
        candidates.sort(reverse=True)
        slots = [name for _, name in candidates]
        if len(slots) > 1 and candidates[0][0] == candidates[1][0]:
            # Two equally supported takes at the same trigger are ambiguous.
            continue
        name = "Görev başlangıcı" if phase == 0 else "Görev kapanışı" if phase == 10000 else f"Görev aşaması {phase}"
        result.append([f"script-{phase}", name, "gameplay", "Numaralı görev konuşması", slots[:1]])
    return result


def rows_for_sections(rule, groups, old, language):
    known = defaultdict(list)
    used = set()
    used_hash = set()
    for index, row in enumerate(old):
        if row["type"] == "dialogue" and row.get("subtitleKey"):
            known[row["subtitleKey"]].append(index)
    sections = []
    root = rule["root"]
    speakers = REVIEWED_SPEAKERS.get(root, {})
    for identity, title, kind, context, picked in rule["stages"]:
        entries = []
        for block in picked:
            taken = set()
            cast = speakers.get(block, [])
            for n, alt, digest in sorted(groups[root].get(block, [])):
                if n in taken or alt or digest in used_hash:
                    continue
                taken.add(n)
                reviewed_speaker = 1 <= n <= len(cast)
                name = NAMES.get(cast[n-1]) if reviewed_speaker else None
                ids = [i for i in known[digest] if i not in used]
                if ids:
                    idx = ids[0]
                    used.add(idx)
                    prior = old[idx]
                    if reviewed_speaker and prior.get("speaker") != name:
                        entries.append({"index": idx, "speaker": name})
                    else:
                        entries.append(idx)
                elif digest in language and language[digest].strip():
                    row = {"type": "dialogue", "subtitleKey": digest, "tr": language[digest].strip()}
                    if name:
                        row.update(speaker=name, npc=name not in PLAYABLE)
                    entries.append(row)
                else:
                    continue
                used_hash.add(digest)
        if entries:
            scene = {"id": "timeline-"+identity, "titleTr": title, "category": kind, "environmentTr": context}
            photo = rule.get("frames", {}).get(identity)
            if photo:
                scene["gallery"] = [{"url": value} for value in photo]
            sections.append([scene, entries])
    return sections


def cinematics(mission, rows):
    numbered = []
    for scene in mission.get("dialogueScenes", []):
        if scene.get("category") != "cinematic":
            continue
        before = scene["id"]
        if re.search(r"(?:alt|fail|retry|alternate)(?:-|$)", before):
            continue
        rank = re.search(r"-(int|mcs(\d+)|ext)(?:-|$)", before)
        if not rank:
            continue
        stage = 0 if rank[1] == "int" else 10000 if rank[1] == "ext" else int(rank[2])
        indices = [i for i, r in enumerate(rows) if r["type"] == "dialogue" and r.get("sceneId") == before]
        if indices:
            data = dict(scene, id="timeline-"+before, environmentTr="Sinematik ara sahne")
            numbered.append((stage, len(numbered), data, indices))
    return [[scene, rr] for _, _, scene, rr in sorted(numbered)]


def read_pack(path):
    with ZipFile(path) as pack:
        catalogue = json.loads(pack.read("catalog.json"))
        paths = set(pack.namelist())
        rows = {m["id"]: json.loads(pack.read(f'texts/{m["id"]}.json'))
                if f'texts/{m["id"]}.json' in paths else [] for m in catalogue["missions"]}
        return catalogue["missions"], rows


def additions(section):
    return sum(isinstance(row, dict) and "index" not in row for _, rows in section for row in rows)


def build(base_pack, evidence, language_archive, output):
    missions, existing = read_pack(base_pack)
    with gzip.open(language_archive, "rt", encoding="utf-8") as f:
        tr = json.load(f)
    language = tr["segments"] | tr["entries"]
    groups, roots_for_key = game_data(evidence)
    titles = json.loads((evidence / "dialogue-index.json").read_bytes())["titled"]
    plan = {}
    for mission in missions:
        mid = mission["id"]
        if mid in ASSOCIATED or mission.get("searchTarget"):
            continue
        if any(scene["id"].startswith("timeline-") for scene in mission.get("dialogueScenes", [])):
            continue
        rows = existing[mid]
        hashes = {r.get("subtitleKey") for r in rows if r["type"] == "dialogue"}
        if mid in REVIEWED:
            item = REVIEWED[mid]
            sections = rows_for_sections(item, groups, rows, language)
            source = item["source"]
        else:
            sections = cinematics(mission, rows)
            source = mission.get("textSourceUrl") or mission.get("sourceUrl")
            if not sections and hashes:
                root = auto_root(mission, hashes, roots_for_key, titles)
                if root:
                    rule = {"root": root, "stages": common_blocks(groups[root], hashes)}
                    sections = rows_for_sections(rule, groups, rows, language)
                    source = f"https://six.mics.dev/dialogue?mission=t:{root}"
        if sum(len(x) for _, x in sections) >= 3:
            plan[mid] = {"scenes": sections, "source": source, "sourceRows": len(rows)}
    for alias, target in ASSOCIATED.items():
        if target not in plan:
            continue
        entry = plan[target]
        target_rows = existing[target]
        copied = []
        for scene, rows in entry["scenes"]:
            linked = []
            for row in rows:
                if isinstance(row, int):
                    linked.append(dict(target_rows[row]))
                elif "index" in row:
                    name = row["speaker"]
                    record = dict(target_rows[row["index"]])
                    if name:
                        record.update(speaker=name, npc=name not in PLAYABLE)
                    else:
                        record.pop("speaker", None)
                        record.pop("npc", None)
                    linked.append(record)
                else:
                    linked.append(dict(row))
            copied.append([scene, linked])
        plan[alias] = {"scenes": copied, "source": "https://six.mics.dev/missions/cti-pil-rd-bc-parent",
                       "sourceRows": len(existing[alias])}
    restored = sum(len(rows) for m in plan.values() for _, rows in m["scenes"])
    added = sum(additions(m["scenes"]) for m in plan.values())
    stats = {"newTimelines": len(plan), "newRows": added, "visibleAdded": restored,
             "searchable": sum(not bool(m.get("searchTarget")) for m in missions),
             "newDialogueMissions": sum(not any(r["type"] == "dialogue" for r in existing[mid]) for mid in plan)}
    digest = sha256(base_pack.read_bytes()).hexdigest()
    output.write_text(json.dumps({"basePackSha256": digest, "stats": stats, "missions": plan},
                                ensure_ascii=False, separators=(",", ":"))+"\n", encoding="utf-8")
    print(stats)
    for mid in [*REVIEWED, *ASSOCIATED]:
        if mid in plan:
            print(mid, [(s["titleTr"], len(rows)) for s, rows in plan[mid]["scenes"]])
    print("timeline ids:", ", ".join(sorted(plan)))


if __name__ == "__main__":
    cli = argparse.ArgumentParser(description=__doc__)
    for field in ("base_pack", "evidence", "language_archive", "output"):
        cli.add_argument(field, type=Path)
    build(**vars(cli.parse_args()))
