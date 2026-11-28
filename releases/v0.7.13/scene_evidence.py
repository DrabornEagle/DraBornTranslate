"""Read stage order, dialogue fingerprints and media from recorded walkthrough pages."""

from dataclasses import dataclass
from hashlib import sha256
from pathlib import Path
import re
import unicodedata
from urllib.parse import parse_qs, urlsplit

from lxml import html

CAPTION = re.compile(r"^(\d+)\s*(.*?)\s*“([^”]+)”")
QUOTES = re.compile(r'“([^”]+)”|"([^"]+)"')
TAG = re.compile(r"<[^<>\r\n]{1,40}>|~[^~\s]{1,40}~")
ANNOTATION = re.compile(r"\[[^\[\]\r\n]{1,65}\]")
FRAME = re.compile(r"https://media\.leonidex\.com/scenes/[a-f0-9]{8}\.webp(?:\?v=[a-f0-9]{8})?")
GAMEPLAY = re.compile(r"/media/gameplay/missions/[a-z0-9-]+/[0-9]{2}\.webp")
CHARACTERS = {
    "Jason": "Jason Duval", "Lucia": "Lucia Caminos", "Boobie": "Boobie Ike", "Raul": "Raul Bautista",
    "Cal": "Cal Hampton", "Brian": "Brian Heder", "Méndez": "Aldo Méndez", "Mendez": "Aldo Méndez",
    "Monterossa": "Monty", "Dre'Quan": "Dre’Quan Priest", "Tyrone": "Tyrone Russo", "Stefanie": "Stefanie Gates",
}
GENERIC = {"cop", "dispatch", "stranger", "who", "scene", "man", "woman", "guard", "npc",
           "unknown", "soldier", "police", "bystander", "la polilla negra", "san4san", "narrator"}


def normalized(text):
    normalized_tags = unicodedata.normalize("NFKC", TAG.sub(" ", text)).lower()
    letters = unicodedata.normalize("NFD", normalized_tags)
    return " ".join("".join(c if unicodedata.category(c)[0] in "LN" else " "
                            for c in letters if unicodedata.category(c)[0] != "M").split())


def key(clean):
    return sha256(clean.encode()).hexdigest()[:16]


def person(text):
    label = text.strip().rstrip(":").strip()
    lower = label.lower()
    if lower in GENERIC or lower.startswith(("unknown ", "unidentified ", "unnamed ")) or not re.fullmatch(r"[\w .’'&-]{2,55}", label) or any(c.isdigit() for c in label):
        return None
    return CHARACTERS.get(label, label)


@dataclass(frozen=True)
class Quote:
    words: str
    speaker: str | None

    @property
    def key(self):
        return key(self.words)


def quote(text, name=None):
    words = normalized(ANNOTATION.sub(" ", text))
    return Quote(words, name) if len(words) >= 10 else None


@dataclass(frozen=True)
class Event:
    number: int
    title: str
    lines: tuple
    frames: tuple


def quoted_paragraph(text, allowed):
    lines = []
    speaker = None
    previous = 0
    for match in QUOTES.finditer(text):
        before = text[previous:match.start()]
        if ":" in before and before.strip().endswith(":"):
            last = re.split(r"[.!?…;/]", before)[-1].strip().rstrip(":")
            last = last.split(",", 1)[0].strip()
            # Walkthrough paragraphs also contain descriptions ending in a colon.
            speaker = person(last) if last in allowed else None
        elif not re.fullmatch(r"[\s,;/]*", before):
            speaker = None
        line = quote(match[1] or match[2], speaker)
        if line:
            lines.append(line)
        previous = match.end()
    return tuple(lines)


def shot_page(research, route, slug, anchor):
    path = research / f"scene-{Path(route).name}.html"
    if not path.is_file():
        return (anchor,), ()
    doc = html.parse(str(path))
    if not doc.xpath(f'//main//a[@href="/missions/{slug}"]'):
        return (anchor,), ()
    spoken = []
    for el in doc.xpath('//main//details//li[blockquote]'):
        line = quote(el.xpath("string(./blockquote)"), person(el.xpath("string(./p)")))
        if line:
            spoken.append(line)
    for el in doc.xpath('//main//details//ol/li[not(blockquote)]'):
        fields = el.xpath("./div|./p")
        if len(fields) >= 2:
            line = quote(fields[1].text_content(), person(fields[0].text_content()))
            if line:
                spoken.append(line)
    # Accept the clip transcript only when the main walkthrough's cue belongs to it.
    if not any(anchor.words in s.words or s.words in anchor.words for s in spoken):
        spoken = [anchor]
    frames = [src for src in doc.xpath("//main//img/@src") if FRAME.fullmatch(src)]
    return tuple(spoken), tuple(dict.fromkeys(frames))


def load(research, slug, expected):
    path = research / f"leonidex-{slug}.html"
    if not path.is_file():
        return (), ()
    page = html.parse(str(path))
    if normalized(page.xpath("string(//main//h1[1])")) != normalized(expected):
        return (), ()
    clips, allowed = [], set(CHARACTERS)
    for link in page.xpath('//main//a[starts-with(@href,"/scenes/")]'):
        match = CAPTION.match(" ".join(link.text_content().split()))
        if not match:
            continue
        prefix = match[2].strip()
        speaker = prefix.rstrip(":").strip() if prefix.endswith(":") else ""
        anchor = quote(match[3], person(speaker))
        if anchor:
            lines, frames = shot_page(research, link.get('href'), slug, anchor)
            title = speaker if not person(speaker) else ""
            clips.append(Event(int(match[1]), title, lines, frames))
            if person(speaker):
                allowed.add(speaker)
    steps = []
    for index, step in enumerate(page.xpath('//main//li[h3][p]'), 1):
        name = step.xpath("string(./h3)")
        lines = quoted_paragraph(step.xpath("string(./p)"), allowed)
        frames = []
        for url in step.xpath(".//img/@src"):
            path = parse_qs(urlsplit(url).query).get("url", [url])[0]
            if GAMEPLAY.fullmatch(path) and path.startswith(f"/media/gameplay/missions/{slug}/"):
                frames.append("https://leonidex.com" + path)
        steps.append(Event(index, name, lines, tuple(dict.fromkeys(frames))))
    return tuple(clips), tuple(steps)
