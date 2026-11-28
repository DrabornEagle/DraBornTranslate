"""Prepare scene-by-scene mission timelines from the published archive and observed gameplay."""

import argparse
from collections import Counter, defaultdict
import gzip
from hashlib import sha256
import json
from pathlib import Path
import re
from zipfile import ZipFile

from scene_evidence import ANNOTATION, Event, Quote, key, load, normalized

HERE = Path(__file__).resolve().parent
PLAYABLE = {"Jason Duval", "Lucia Caminos"}
CUES = {"J": "Jason Duval", "L": "Lucia Caminos", "CAL": "Cal Hampton",
        "DONNIE": "Donnie", "BOOBIE": "Boobie Ike", "RAUL": "Raul Bautista",
        "ANDREA": "Andrea", "KARIM": "Karim", "BRIAN": "Brian Heder"}
CONTEXT = json.loads((HERE / "scene_context.json").read_bytes())
FOCUS = json.loads((HERE / "focus-routes.json").read_bytes())


def meaningful_scene(scene):
    identity = scene["id"]
    return identity not in {"unplaced-dialogue", "source-dialogue", "other"} and not identity.startswith("flow-extra-")


def frames(scene, additional):
    pictures = {pic["url"]: pic for pic in scene.get("gallery", [])}
    for url in additional:
        pictures.setdefault(url, {"url": url})
    return list(pictures.values())[:3]


def context_for(title, category):
    if category == "cinematic":
        return "Sinematik ara sahne"
    if category == "call":
        return "Telefon konuşması"
    value = normalized(title)
    if re.search(r"\b(escape|lose the cops|flee|kacıs|kacma|takipten)\b", value):
        return "Kaçış"
    if re.search(r"\b(drive|while driving|park the car|arabayla|arac ici|arabada)\b", value):
        return "Araç içi konuşma"
    return "Görev içi konuşma"


def script_lookup(research, catalog):
    file = research / "dialogue-index.json"
    titles = defaultdict(set)
    if file.exists():
        for identity, english, *_ in json.loads(file.read_bytes())["titled"]:
            titles[normalized(english)].add(identity)
    roots = {}
    for mission in catalog:
        identity = mission["id"]
        choices = set(titles[normalized(mission.get("titleEn") or "")])
        choices |= {name for name in mission.get("aliases", []) if re.fullmatch(r"[A-Z][A-Z0-9_]+", name)}
        if identity in FOCUS:
            choices.add(FOCUS[identity]["root"])
        roots[identity] = choices
    wanted = set().union(*roots.values())
    spoken = defaultdict(dict)
    blocks = defaultdict(lambda: defaultdict(list))
    for path in sorted(research.glob("d_*.json")):
        for actor, conversation, label, english, *_ in json.loads(path.read_bytes()):
            root, _, _ = conversation.partition("AU_")
            if root in wanted and english:
                words = normalized(ANNOTATION.sub(" ", english))
                if len(words) >= 18:
                    spoken[root][words] = key(normalized(english))
                if words and label.startswith(root + "_"):
                    match = re.fullmatch(r"(.+?)_(\d+)([A-Z]?)", label[len(root) + 1:])
                    if match:
                        raw = Quote(normalized(english), CUES.get(actor))
                        blocks[root][match[1]].append((int(match[2]), match[3], raw))
    return {identity: (
                tuple({pair for root in choices for pair in spoken[root].items()}),
                [rows for root in sorted(choices) for rows in blocks[root].values()])
            for identity, choices in roots.items()}


class Scene:
    def __init__(self, metadata, items):
        self.metadata = metadata
        self.items = list(items)
        self.observed = set()

    def add_frames(self, images):
        pictures = frames(self.metadata, images)
        if pictures:
            self.metadata["gallery"] = pictures


class MissionTimeline:
    def __init__(self, identity, original_scenes, rows, language, script):
        self.identity = identity
        self.rows = rows
        self.language = language
        self.script, self.blocks = script
        self.sections = {}
        self.placed = set()
        self.translated = set()
        self.by_key = defaultdict(list)
        self.original_scenes = {item["id"]: item for item in original_scenes}
        self.scoped = CONTEXT.get(identity, {})
        for i, row in enumerate(rows):
            if row["type"] == "dialogue":
                self.by_key[row.get("subtitleKey")].append(i)

    def scene(self, identity, title=None, category="gameplay", original=()):
        if identity in self.sections:
            return self.sections[identity]
        entry = dict(self.original_scenes.get(identity, {"titleTr": title, "category": category}))
        if identity in self.scoped.get("cinematics", []):
            entry["category"] = "cinematic"
        if entry["category"] in {"other", "ambient"}:
            entry["category"] = "gameplay"
        entry["id"] = "timeline-" + identity
        entry["environmentTr"] = self.scoped.get("stages", {}).get(
            identity, context_for(entry.get("titleTr") or title or "", entry["category"]))
        scene = Scene(entry, original)
        scene.add_frames(self.scoped.get("frames", {}).get(identity, []))
        self.sections[identity] = scene
        self.placed.update(original)
        return scene

    def original_dialogue(self, row):
        if isinstance(row, int):
            return self.rows[row]
        if "index" in row:
            return self.rows[row["index"]]
        return row

    def quote_key(self, line):
        direct = line.key
        if direct in self.by_key or direct in self.language:
            return direct
        if len(line.words) < 22:
            return None
        needle = f" {line.words} "
        aligned = {digest for words, digest in self.script if needle in f" {words} "
                   and (digest in self.by_key or digest in self.language)}
        return aligned.pop() if len(aligned) == 1 else None

    def targets(self, event):
        anchors = {self.quote_key(line) for line in event.lines if len(line.words) >= 15}
        votes = defaultdict(set)
        for identity, scene in self.sections.items():
            for item in scene.items:
                digest = self.original_dialogue(item).get("subtitleKey")
                if digest in anchors:
                    votes[digest].add(identity)
        return Counter(next(iter(matches)) for matches in votes.values() if len(matches) == 1)

    def candidate(self, line, seen):
        match = self.quote_key(line)
        if match is None or match in seen:
            return None
        available = [i for i in self.by_key[match] if i not in self.placed]
        if available:
            selected = next((i for i in available if line.speaker and line.speaker == self.rows[i].get("speaker")), available[0])
            row = self.rows[selected]
            # The clip label only applies when it identifies the same subtitle, in full.
            if line.speaker and match == line.key and row.get("speaker") in (None, "NPC"):
                return {"index": selected, "speaker": line.speaker}
            return selected
        if self.by_key[match] or match not in self.language or match in self.translated:
            return None
        text = self.language[match].strip()
        if not text:
            return None
        row = {"subtitleKey": match, "type": "dialogue", "tr": text}
        if line.speaker and match == line.key:
            row.update(speaker=line.speaker, npc=line.speaker not in PLAYABLE)
        return row

    def attach(self, scene, event, captured=False):
        scene.add_frames(event.frames)
        placed = {self.original_dialogue(item).get("subtitleKey"): item for item in scene.items}
        known = [placed.get(self.quote_key(caption)) for caption in event.lines]
        if captured:
            scene.observed.update(match for line in event.lines if (match := self.quote_key(line)) is not None)
            definitive = []
            for exact, anchor in zip(event.lines, known):
                if anchor is not None and exact.key in placed and anchor not in definitive:
                    definitive.append(anchor)
            slots = sorted(scene.items.index(anchor) for anchor in definitive)
            for slot, anchor in zip(slots, definitive):
                scene.items[slot] = anchor
        seen = set(placed)
        last = None
        for pos, cue in enumerate(event.lines):
            anchor = known[pos]
            if anchor is not None:
                item = self.original_dialogue(anchor)
                old_speaker = item.get("speaker")
                confirmed_person = captured and len(cue.words) >= 22
                if (cue.speaker and cue.key == item.get("subtitleKey") and cue.speaker != old_speaker
                        and (old_speaker in (None, "NPC") or confirmed_person)):
                    idx = anchor if isinstance(anchor, int) else anchor.get("index")
                    if idx is not None:
                        enriched = {"index": idx, "speaker": cue.speaker}
                        scene.items[scene.items.index(anchor)] = enriched
                        # Update all references to this anchor in the same observed sequence.
                        known = [enriched if x == anchor else x for x in known]
                        anchor = enriched
                last = anchor
                continue
            candidate = self.candidate(cue, seen)
            if candidate is None:
                continue
            following = next((item for item in known[pos + 1:] if item is not None), None)
            left = scene.items.index(last) if last is not None else -1
            right = scene.items.index(following) if following is not None else len(scene.items)
            if right <= left:
                continue
            # Insert before the next observed cue or directly after the last one.
            slot = right if following is not None or last is None else left + 1
            scene.items.insert(slot, candidate)
            source_index = candidate if isinstance(candidate, int) else candidate.get("index")
            if source_index is not None:
                self.placed.add(source_index)
            actual_key = self.original_dialogue(candidate)["subtitleKey"]
            if source_index is None:
                self.translated.add(actual_key)
            seen.add(actual_key)
            last = candidate

    def extend_reviewed(self, clips, steps):
        manual = FOCUS.get(self.identity)
        if manual:
            clip_scenes = {n: identity for identity, _, (first, last), _ in manual["stages"]
                           if identity in self.sections and first > 0 for n in range(first, last + 1)}
        else:
            clip_scenes = {}
        for event in clips:
            identity = clip_scenes.get(event.number)
            if identity is None:
                choices = self.targets(event)
                if len(choices) == 1:
                    identity = next(iter(choices))
            if identity in self.sections:
                self.attach(self.sections[identity], event, captured=True)
        for event in steps:
            preferred = self.scoped.get("steps", {}).get(str(event.number))
            choices = self.targets(event)
            if len(choices) == 1:
                exact = next(iter(choices))
                if preferred is None or preferred == exact:
                    scene = self.sections[exact]
                    if scene.metadata.get("titleTr", "").startswith("Görev aşaması"):
                        scene.metadata.update(titleTr=event.title,
                                              environmentTr=context_for(event.title, scene.metadata["category"]))
                    self.attach(scene, event)
                    continue
            if preferred in self.sections:
                # Objective frames are scoped to their verified stage. Mixed-speech summaries stay with clip evidence.
                if not choices:
                    self.attach(self.sections[preferred], event)
                else:
                    self.sections[preferred].add_frames(event.frames)

    def build_from_capture(self, clips, steps):
        def existing_scene(event):
            votes = Counter()
            for phrase in event.lines:
                match = self.quote_key(phrase)
                ids = {self.rows[i].get("sceneId") for i in self.by_key[match]}
                ids = {sid for sid in ids if sid in self.original_scenes
                       and self.original_scenes[sid]["category"] == "cinematic" and meaningful_scene(self.original_scenes[sid])}
                if len(ids) == 1:
                    votes.update(ids)
            return next(iter(votes)) if len(votes) == 1 else None

        # Join consecutive captures only when they share an observed objective or an exact cutscene.
        stream = list(clips if len(clips) >= 3 else steps)
        by_cue = defaultdict(list)
        for step in steps:
            for cue in step.lines:
                if len(cue.words) >= 18 and step not in by_cue[cue.key]:
                    by_cue[cue.key].append(step)

        def matched_objective(event):
            matched = {step for cue in event.lines for step in by_cue[cue.key]}
            return matched.pop() if len(matched) == 1 else None

        associated = [matched_objective(event) for event in stream]
        cutscenes = [existing_scene(event) for event in stream]
        for index, event in enumerate(stream):
            if not event.lines:
                continue
            identity = cutscenes[index]
            if identity:
                selected = self.scene(identity, original=(
                    i for i, row in enumerate(self.rows) if row["type"] == "dialogue"
                    and row.get("sceneId") == identity and i not in self.placed))
            else:
                step = associated[index]
                if step is None and 0 < index < len(stream)-1 and not cutscenes[index-1] and not cutscenes[index+1]:
                    if associated[index-1] == associated[index+1]:
                        step = associated[index-1]
                if step is not None:
                    candidate = f"objective-{step.number}"
                    if candidate in self.sections and next(reversed(self.sections)) != candidate:
                        candidate += f"-{event.number}"
                    selected = self.scene(candidate, step.title)
                    selected.add_frames(step.frames)
                else:
                    selected = self.scene(f"capture-{event.number}", event.title or f"Görev aşaması {event.number}")
            self.attach(selected, event, captured=len(clips) >= 3)
        if len(self.placed) + len(self.translated) >= 3:
            self.extend_reviewed((), steps)

    def complete_between_anchors(self):
        # Fill only intervals with two already-observed endpoints inside one numbered block.
        for scene in self.sections.values():
            if len(scene.observed) < 2:
                continue
            sequence = {self.original_dialogue(item).get("subtitleKey"): i for i, item in enumerate(scene.items)}
            for raw in self.blocks:
                if len(raw) < 3:
                    continue
                numbered = sorted(raw, key=lambda item: (item[0], item[1]))
                verified = [i for i, (_, _, cue) in enumerate(numbered)
                            if cue.key in scene.observed and cue.key in sequence]
                for first, last in zip(verified, verified[1:]):
                    begin, _, left = numbered[first]
                    end, _, right = numbered[last]
                    if not 2 <= end - begin <= 18 or sequence[left.key] >= sequence[right.key]:
                        continue
                    interval = numbered[first:last+1]
                    if len(interval) != end - begin + 1 or any(alt for _, alt, _ in interval):
                        continue
                    self.attach(scene, Event(0, "", tuple(cue for _, _, cue in interval), ()))

    def plan(self, url):
        stages = [[stage.metadata, stage.items] for stage in self.sections.values() if stage.items]
        count = sum(len(lines) for _, lines in stages)
        if count < 3:
            return None
        previous_ids = set(self.sections)
        restored = sum(self.rows[i].get("sceneId") not in previous_ids for i in self.placed)
        return {"source": url, "sourceRows": len(self.rows), "scenes": stages,
                "statistics": {"scenes": len(stages), "shown": count, "moved": restored,
                               "added": len(self.translated),
                               "scenesWithFrames": sum(bool(scene.get("gallery")) for scene, _ in stages),
                               "scenesWithMultipleFrames": sum(len(scene.get("gallery", [])) > 1 for scene, _ in stages)}}


def match_slug(research, mission, old):
    previous = old.get("source", "").rsplit("/", 1)[-1]
    title = mission.get("titleEn") or mission.get("titleTr") or ""
    guess = normalized(title.replace("'", "").replace("’", "")).replace(" ", "-")
    for slug in dict.fromkeys((previous, guess, mission["id"])):
        if slug:
            clips, steps = load(research, slug, title)
            if clips or steps:
                return slug, clips, steps
    return previous, (), ()


def main(base_pack, previous_plan, research, language_pack, output):
    data = base_pack.read_bytes()
    previous = json.loads(previous_plan.read_bytes())["missions"]
    with gzip.open(language_pack, "rt", encoding="utf-8") as f:
        tr = json.load(f)
    language = tr["segments"] | tr["entries"]
    stats = Counter()
    plans = {}
    with ZipFile(base_pack) as archive:
        catalog = json.loads(archive.read("catalog.json"))["missions"]
        scripts = script_lookup(research, catalog)
        filenames = set(archive.namelist())
        for mission in catalog:
            identity = mission["id"]
            filename = f"texts/{identity}.json"
            if mission.get("searchTarget") or filename not in filenames:
                continue
            previous_mission = previous.get(identity, {})
            slug, clips, steps = match_slug(research, mission, previous_mission)
            rows = json.loads(archive.read(filename))
            scenes = mission.get("dialogueScenes", [])
            flow = MissionTimeline(identity, scenes, rows, language, scripts[identity])
            by_scene = defaultdict(list)
            for i, row in enumerate(rows):
                if row["type"] == "dialogue":
                    by_scene[row.get("sceneId")].append(i)
            if previous_mission:
                for scene in scenes:
                    if meaningful_scene(scene) and by_scene[scene["id"]]:
                        flow.scene(scene["id"], original=by_scene[scene["id"]])
                flow.extend_reviewed(clips, steps)
            elif clips or steps:
                flow.build_from_capture(clips, steps)
            else:
                continue
            flow.complete_between_anchors()
            compiled = flow.plan(f"https://leonidex.com/missions/{slug}" if slug else mission.get("textSourceUrl"))
            if compiled:
                plans[identity] = compiled
                stats.update(compiled["statistics"])
                if not any(row["type"] == "dialogue" for row in rows):
                    stats["newMissionDialogues"] += 1
    stats["missions"] = len(plans)
    if not FOCUS.keys() <= plans.keys():
        raise ValueError("One of the manually checked mission timelines is missing")
    artifact = {"basePackSha256": sha256(data).hexdigest(), "stats": dict(stats), "missions": plans}
    output.write_text(json.dumps(artifact, ensure_ascii=False, separators=(",", ":")) + "\n", encoding="utf-8")
    print(json.dumps(stats, ensure_ascii=False))
    for identity in FOCUS:
        print(identity, plans[identity]["statistics"])


if __name__ == "__main__":
    cli = argparse.ArgumentParser(description=__doc__)
    for name in ["base_pack", "previous_plan", "research", "language_pack", "output"]:
        cli.add_argument(name, type=Path)
    main(**vars(cli.parse_args()))
