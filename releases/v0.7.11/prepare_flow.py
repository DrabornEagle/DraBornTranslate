"""Match recorded mission clips to the existing Turkish subtitle archive.

The output retains hashes, Turkish additions from the supplied language pack,
and scene ordering. English text and downloaded walkthrough pages are inputs.
"""

import argparse
from collections import Counter, defaultdict
import csv
from dataclasses import dataclass
import gzip
from hashlib import sha256
import json
from pathlib import Path
import re
import unicodedata
from urllib.parse import parse_qs, urlsplit
from zipfile import ZipFile

from lxml import html

ROOT = Path(__file__).resolve().parent
UNPLACED = {"unplaced-dialogue", "source-dialogue", "other", None}
PLAYABLE = {"Jason Duval", "Lucia Caminos"}
ACTORS = {
    "J": "Jason Duval", "Jason": "Jason Duval", "L": "Lucia Caminos", "Lucia": "Lucia Caminos",
    "BOOBIE": "Boobie Ike", "Boobie": "Boobie Ike", "CAL": "Cal Hampton", "Cal": "Cal Hampton",
    "Raul": "Raul Bautista", "RAUL": "Raul Bautista", "Méndez": "Aldo Méndez",
    "Monterossa": "Monty", "CLB": "Boobie Ike", "ANDREA": "Andrea", "KARIM": "Karim",
    "DRE": "Dre’Quan Priest", "Dre’Quan": "Dre’Quan Priest", "DONNIE": "Donnie",
}
NON_NAMES = {"Scene", "San4San", "La Polilla Negra", "Dispatch", "Cop", "NPC", "Guard",
             "Radio", "Stranger", "Man", "Woman", "Unknown"}
ROCKSTAR_TAG = re.compile(r"~[^~\s]{1,40}~")
ANNOTATION = re.compile(r"\[[^\[\]\r\n]{1,65}\]")
PART = re.compile(r"(.+?)_(\d+)([A-Z]?)$")
CAPTION = re.compile(r"^(\d+)\s*(.*?)\s*“([^”]+)”")
FRAME = re.compile(r"https://media\.leonidex\.com/scenes/[a-f0-9]{8}\.webp(?:\?v=[a-f0-9]{8})?")
GAMEPLAY = re.compile(r"/media/gameplay/missions/[a-z0-9-]+/[0-9]{2}\.webp")


def plain(value):
    return unicodedata.normalize("NFKC", re.sub(r"<[^<>\r\n]{1,40}>", " ", value))


def normalized(value):
    return " ".join("".join(
        c if unicodedata.category(c)[0] in "NL" else " "
        for c in unicodedata.normalize("NFD", plain(value).lower())
        if unicodedata.category(c)[0] != "M"
    ).split())


def digest(value):
    clean = normalized(value)
    return sha256(clean.encode()).hexdigest()[:16] if clean else None


def valid_person(value):
    name = value.strip().rstrip(":").strip()
    if name in NON_NAMES or not re.fullmatch(r"[\w .’'&/-]{2,65}", name) or any(c.isdigit() for c in name):
        return None
    return ACTORS.get(name, name)


@dataclass(frozen=True)
class Clip:
    number: int
    route: str
    key: str
    words: str
    speaker: str | None


@dataclass(frozen=True)
class Cue:
    number: int
    alternate: str
    key: str
    words: str
    speaker: str | None


def read_clips(page):
    clips = []
    for link in page.xpath('//main//a[starts-with(@href,"/scenes/")]'):
        spoken = CAPTION.match(" ".join(link.text_content().split()))
        if not spoken:
            continue
        words = normalized(ANNOTATION.sub(" ", spoken[3]))
        key = digest(words)
        if key and len(words) >= 10:
            clips.append(Clip(int(spoken[1]), link.get("href"), key, words, valid_person(spoken[2])))
    return sorted(clips, key=lambda clip: clip.number)


def read_scripts(research, roots):
    scripts = defaultdict(lambda: defaultdict(list))
    for shard in sorted(research.glob("d_*.json")):
        for actor, source, label, english, *_ in json.loads(shard.read_bytes()):
            root, _, _ = source.partition("AU_")
            if not english or root not in roots or not label.startswith(root + "_"):
                continue
            selector = PART.fullmatch(label[len(root) + 1:])
            key = digest(english)
            if selector is None or not key:
                continue
            words = normalized(ANNOTATION.sub(" ", english))
            if not words:
                continue
            scripts[root][selector[1]].append(Cue(int(selector[2]), selector[3], key, words, ACTORS.get(actor)))
    for groups in scripts.values():
        for rows in groups.values():
            rows.sort(key=lambda cue: (cue.number, cue.alternate))
    return scripts


def read_translation_keys(research, requested):
    matches = defaultdict(set)
    with (research / "english-text.tsv").open(encoding="utf-8", newline="") as file:
        for row in csv.reader(file, delimiter="\t", quoting=csv.QUOTE_NONE):
            if len(row) < 10 or row[2][:1] != "S" or row[5] not in {"dialogue", "subtitle"}:
                continue
            value = "\t".join(row[9:]).strip()
            clean = ROCKSTAR_TAG.sub(" ", value)
            whole = digest(clean)
            if whole in requested:
                matches[whole].add(whole)
            if "~" in value:
                for part in ROCKSTAR_TAG.split(value):
                    key = digest(part)
                    if key in requested:
                        matches[key].add(whole)
    return matches


def scenes_and_rows(archive, mission):
    existing = {scene["id"]: scene for scene in mission.get("dialogueScenes", [])}
    filename = "texts/" + mission["id"] + ".json"
    rows = json.loads(archive.read(filename)) if filename in archive.namelist() else []
    return existing, rows


def ordered_units(groups, tokens):
    for item in tokens:
        label, _, bounds = item.partition(":")
        if label not in groups:
            raise ValueError(f"Unknown numbered block {label}")
        first, last = map(int, bounds.split("-")) if bounds else (0, 99999)
        # A letter suffix is an alternate take at the same numbered position.
        used = set()
        for cue in groups[label]:
            if first <= cue.number <= last and cue.number not in used:
                used.add(cue.number)
                yield cue


def stage_frames(research, page, slug, clips, steps=()):
    found = []
    # First use captures anchored to the recorded line in this very stage.
    for clip in clips:
        path = clip.route.rsplit("/", 1)[-1]
        for filename in [f"scene-{path}.html", f"{path}.html"]:
            cached = research / filename
            if not cached.exists():
                continue
            doc = html.parse(str(cached))
            for url in doc.xpath(f'//main[.//a[@href="/missions/{slug}"]]//img/@src'):
                if FRAME.fullmatch(url):
                    found.append(url)
    for image in page.xpath('//main//img[contains(@alt, ": ")]'):
        raw = image.get("src", "")
        path = parse_qs(urlsplit(raw).query).get("url", [raw])[0]
        if not GAMEPLAY.fullmatch(path) or not path.startswith(f"/media/gameplay/missions/{slug}/"):
            continue
        if int(path[-7:-5]) in steps:
            found.append("https://leonidex.com" + path)
    return list(dict.fromkeys(found))[:3]


def stage_scene(existing, identity, title, frames):
    scene = dict(existing.get(identity, {"id": identity, "category": "gameplay"}))
    if title:
        scene["titleTr"] = title
    scene["environmentTr"] = "Ara sahne" if scene["category"] == "cinematic" else "Görev içi konuşma"
    previous = {image["url"]: image for image in scene.get("gallery", [])}
    for url in frames:
        previous.setdefault(url, {"url": url})
    if previous:
        scene["gallery"] = list(previous.values())[:3]
    return scene


def supplemental(existing, staged, rows, assigned):
    scenes = {}
    extra = defaultdict(list)
    for index, row in enumerate(rows):
        if index in assigned or row["type"] != "dialogue":
            continue
        old = row.get("sceneId")
        if old in UNPLACED or old not in existing:
            scene = {"id": "unplaced-dialogue", "titleTr": "Sahnesi doğrulanmamış replikler", "category": "other"}
        else:
            scene = dict(existing[old])
            scene["id"] = "flow-extra-" + old
            if old in staged:
                # Reuse the original scene title for takes left out of the observed cut.
                scene["environmentTr"] = "Diğer replikler"
        identity = scene["id"]
        scenes.setdefault(identity, scene)
        extra[identity].append(index)
    return [[scene, extra[identity]] for identity, scene in scenes.items()]


def add_edition_row(key, speaker, language):
    text = language.get(key)
    if not text or not text.strip():
        return None
    row = {"type": "dialogue", "subtitleKey": key, "tr": text.strip()}
    if speaker:
        row.update(speaker=speaker, npc=speaker not in PLAYABLE)
    return row


def find_cue(clip, cues):
    exact = [i for i, cue in enumerate(cues) if cue.key == clip.key or cue.words == clip.words]
    if len(exact) == 1:
        return exact[0]
    if len(clip.words) >= 19:
        partial = [i for i, cue in enumerate(cues) if f" {clip.words} " in f" {cue.words} "]
        if len(partial) == 1:
            return partial[0]
    return None


def manual_flow(research, mission, clips, page, groups, existing, rows, translation_keys, language):
    positions = defaultdict(list)
    for index, row in enumerate(rows):
        if row["type"] == "dialogue":
            positions[row.get("subtitleKey")].append(index)
    used = set()
    sections = []
    chosen = set()
    anchors = 0
    for identity, title, (first, last), blocks in mission["stages"]:
        nearby = [clip for clip in clips if first <= clip.number <= last]
        cues = list(ordered_units(groups, blocks))
        ordered = []
        extras = defaultdict(list)
        confirmed = defaultdict(list)
        for clip in nearby:
            position = find_cue(clip, cues)
            if position is not None:
                confirmed[position].append(clip)
                anchors += 1
        for clip in nearby:
            if any(clip in matches for matches in confirmed.values()):
                continue
            keys = translation_keys.get(clip.key, set())
            if not keys:
                continue
            key = clip.key if clip.key in language else next(iter(keys)) if len(keys) == 1 else None
            if not key or key in chosen:
                continue
            # Do not duplicate a longer source line already moved with the raw block.
            if any(cue.key == key for cue in cues):
                continue
            next_position = next((p for p, matches in sorted(confirmed.items())
                                  if any(c.number > clip.number for c in matches)), len(cues))
            extras[next_position].append((key, clip.speaker if key == clip.key else None, clip))
        for index in range(len(cues) + 1):
            items = extras.get(index, [])
            if index < len(cues):
                cue = cues[index]
                observed = confirmed[index]
                speaker = next((clip.speaker for clip in observed if clip.speaker), cue.speaker)
                items = items + [(cue.key, speaker, None)]
            for key, speaker, clip in items:
                origins = UNPLACED | {identity}
                if identity == "flow-boobie-assignment":
                    origins.add("cinematic-clb-tower-int")
                available = [i for i in positions[key] if i not in used and rows[i].get("sceneId") in origins]
                available.sort(key=lambda i: (rows[i].get("sceneId") != identity,
                                              speaker is not None and rows[i].get("speaker") != speaker, i))
                if available:
                    old = available[0]
                    used.add(old)
                    if speaker and rows[old].get("speaker") in (None, "NPC"):
                        ordered.append({"index": old, "speaker": speaker})
                    else:
                        ordered.append(old)
                    chosen.add(key)
                    anchors += clip is not None
                elif key not in chosen and (clip is not None or index in confirmed) and key in language:
                    if positions[key]:
                        continue
                    row = add_edition_row(key, speaker, language)
                    if row:
                        ordered.append(row)
                        chosen.add(key)
                        anchors += clip is not None
        if ordered:
            frames = stage_frames(research, page, mission["slug"], nearby, mission.get("frames", {}).get(identity, []))
            sections.append([stage_scene(existing, identity, title, frames), ordered])
    return sections, used, anchors


def script_roots(mission, titles):
    identity = mission["id"].replace("-", "_").upper()
    title = normalized(mission.get("titleEn") or "")
    named = {code for code, english, *_ in titles if title and normalized(english) == title}
    named.update(alias for alias in mission.get("aliases", []) if re.fullmatch(r"[A-Z][A-Z0-9_]+", alias))
    named.add(identity)
    for scene in mission.get("dialogueScenes", []):
        name = scene["id"]
        if name.startswith("cinematic-"):
            prefix = re.split(r"_(?:INT\d*|EXT\d*|MCS\d+)(?:_|$)",
                              name.removeprefix("cinematic-").replace("-", "_").upper())[0]
            named.add(prefix)
    return named


def nearby_script_lines(clip, positions, groups):
    candidates = []
    for name, cues in groups:
        index = find_cue(clip, cues)
        if index is None or cues[index].key not in positions:
            continue
        candidates.append((name, cues, index))
    if len(candidates) != 1:
        return clip.key, []
    name, cues, index = candidates[0]
    key = cues[index].key
    # A short, single-take script block provides context for an observed line.
    is_conditional = re.search(r"(?:^|_)(?:ALT|FAIL|RETRY|BARK|IDLE|HINT|LOST|RPLY|TAUNT|WAIT)\w*", name)
    if is_conditional or len(cues) > 12 or any(cue.alternate for cue in cues):
        return key, []
    return key, [cue.key for cue in cues]


def automatic_flow(research, page, slug, clips, existing, rows, groups):
    positions = defaultdict(list)
    by_scene = defaultdict(list)
    for i, row in enumerate(rows):
        if row["type"] == "dialogue":
            positions[row.get("subtitleKey")].append(i)
            if row.get("sceneId") in existing and row.get("sceneId") not in UNPLACED:
                by_scene[row["sceneId"]].append(i)
    anchors = []
    context = {}
    for clip in clips:
        if len(clip.words) < 18:
            continue
        key, sequence = nearby_script_lines(clip, positions, groups)
        found = positions.get(key, [])
        if len(found) != 1:
            continue
        anchors.append((clip, found[0], rows[found[0]].get("sceneId")))
        if sequence:
            context[clip.number] = sequence
    identified = {scene for _, _, scene in anchors if scene in by_scene}
    if len(anchors) < 4 or not identified:
        return [], set(), 0
    if len(anchors) - sum(scene in identified for _, _, scene in anchors) < 2:
        return [], set(), 0
    sections = []
    assigned = set()
    shown = set()
    pending = []
    unanchored = list(by_scene)

    def flush_playback():
        if not pending:
            return
        batch, indices = zip(*pending)
        sequence = sum(scene["id"].startswith("flow-stage-") for scene, _ in sections) + 1
        frames = stage_frames(research, page, slug, batch)
        scene = stage_scene({}, f"flow-stage-{sequence}", f"Görev aşaması {sequence}", frames)
        sections.append([scene, list(indices)])
        assigned.update(indices)
        pending.clear()

    def show_scene(identity, frames=()):
        flush_playback()
        sections.append([stage_scene(existing, identity, None, frames), by_scene[identity]])
        assigned.update(by_scene[identity])
        shown.add(identity)

    reserved = {index: clip.number for clip, index, _ in anchors}
    for clip, index, identity in anchors:
        if identity in identified:
            if identity in shown:
                continue
            # Keep unanchored cinematics in their existing relative position.
            for earlier in unanchored[:unanchored.index(identity)]:
                if earlier not in identified and earlier not in shown:
                    show_scene(earlier)
            frames = stage_frames(research, page, slug, [c for c, _, s in anchors if s == identity])
            show_scene(identity, frames)
        elif index not in assigned and all(i != index for _, i in pending):
            possible = [positions[key][0] for key in context.get(clip.number, []) if len(positions.get(key, [])) == 1]
            if index not in possible:
                possible = [index]
            for line in possible:
                if (line not in assigned and all(i != line for _, i in pending)
                        and rows[line].get("sceneId") in UNPLACED
                        and reserved.get(line, clip.number) == clip.number):
                    pending.append((clip, line))
    flush_playback()
    for identity in unanchored:
        if identity not in shown:
            show_scene(identity)
    return sections, assigned, len(anchors)


def load_walkthrough(research, slug, mission):
    path = research / f"leonidex-{slug}.html"
    if not path.exists():
        return None
    page = html.parse(str(path))
    expected = normalized(mission.get("titleEn") or mission.get("titleTr") or "")
    actual = normalized(page.xpath("string(//main//h1[1])"))
    return page if expected and expected == actual else None


def prepare(base_pack, research, language_pack, output):
    rules = json.loads((ROOT / "flow-rules.json").read_bytes())
    with ZipFile(base_pack) as archive:
        catalog = json.loads(archive.read("catalog.json"))
        candidates = []
        for mission in catalog["missions"]:
            if mission.get("searchTarget"):
                continue
            identity = mission["id"]
            guess = normalized((mission.get("titleEn") or "").replace("'", "").replace("’", "")).replace(" ", "-")
            slugs = [rules[identity]["slug"]] if identity in rules else list(dict.fromkeys([guess, identity]))
            for slug in slugs:
                page = load_walkthrough(research, slug, mission)
                if page is not None:
                    candidates.append((mission, slug, page, read_clips(page)))
                    break
        manual_keys = {clip.key for mission, _, _, clips in candidates if mission["id"] in rules for clip in clips}
        translation_keys = read_translation_keys(research, manual_keys)
        with gzip.open(language_pack, "rt", encoding="utf-8") as source:
            payload = json.load(source)
        language = payload["segments"] | payload["entries"]
        titles = json.loads((research / "dialogue-index.json").read_bytes())["titled"]
        roots = {mission["id"]: script_roots(mission, titles) for mission, _, _, _ in candidates}
        selected_roots = set().union(*roots.values(), {rule["root"] for rule in rules.values()})
        scripts = read_scripts(research, selected_roots)
        plans = {}
        stats = Counter()
        for mission, slug, page, clips in candidates:
            identity = mission["id"]
            existing, rows = scenes_and_rows(archive, mission)
            if identity in rules:
                rule = rules[identity]
                sections, used, anchors = manual_flow(research, rule, clips, page, scripts[rule["root"]],
                                                      existing, rows, translation_keys, language)
            else:
                groups = [(name, cues) for root in sorted(roots[identity]) for name, cues in scripts[root].items()]
                sections, used, anchors = automatic_flow(research, page, slug, clips, existing, rows, groups)
            if not any(scene["id"].startswith("flow-") for scene, _ in sections):
                continue
            story_rows = sum(len(group) for _, group in sections)
            ext = supplemental(existing, {scene["id"] for scene, _ in sections}, rows, used)
            plans[identity] = {
                "source": f"https://leonidex.com/missions/{slug}",
                "captureMatches": anchors,
                "storyScenes": len(sections),
                "sourceRows": len(rows),
                "scenes": sections + ext,
            }
            stats.update(missions=1, storyRows=story_rows, repositioned=len(used),
                         added=story_rows-len(used), matches=anchors, additional=sum(len(g) for _, g in ext))
        if not rules.keys() <= plans.keys():
            raise ValueError("Missing playback evidence for a manually reviewed mission")
    payload = {"basePackSha256": sha256(base_pack.read_bytes()).hexdigest(), "stats": dict(stats), "missions": plans}
    output.write_text(json.dumps(payload, ensure_ascii=False, separators=(",", ":")) + "\n", encoding="utf-8")
    print(json.dumps(stats, ensure_ascii=False))
    for identity in rules:
        plan = plans[identity]
        print(identity, plan["captureMatches"], [(scene["id"], len(indices))
              for scene, indices in plan["scenes"][:plan["storyScenes"]]])


if __name__ == "__main__":
    cli = argparse.ArgumentParser(description=__doc__)
    cli.add_argument("base_pack", type=Path)
    cli.add_argument("research", type=Path)
    cli.add_argument("language_pack", type=Path)
    cli.add_argument("output", type=Path)
    prepare(**vars(cli.parse_args()))
