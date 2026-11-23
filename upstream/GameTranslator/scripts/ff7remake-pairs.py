"""FF7 Remake: İngilizce satırlar (JSON: EN → makine çevirisi TR; kimlik yok) ile topluluk yamasının Türkçesi
(kimlik + konuşmacı + TR) eşlenir. Köprü: aynı İngilizce satırın iki Türkçesi (makine ve insan) birbirine benzer;
komşu satırlar yamada da komşudur (kimlik sırası). Önce harf üçlüleriyle en benzer adaylar bulunur, sonra sahne
içi sıra tutarlılığıyla doğrulanır.
ff7align.py <en.json> <yama Text/US klasörü> <çıktı.json> [rapor.txt]"""
import json, sys, os, re, glob, math, collections

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import ff7txt

sys.stdout.reconfigure(encoding='utf-8')
en_path, patch_dir, out_path = sys.argv[1:4]
report_path = sys.argv[4] if len(sys.argv) > 4 else None

# --- İngilizce taraf: "Ad: söz" ve hemen ardından "söz" ikizleri tek satıra iner
raw = json.loads(open(en_path, 'rb').read().decode('utf-8-sig'))
keys = list(raw)
lines = []  # (en, mt, konuşmacı)
seen = {}
for i, k in enumerate(keys):
    m = re.match(r"^([A-Z][\w .'’\-()]{0,30}?): (.+)$", k, re.S)
    if m and m.group(2) in raw:
        continue  # konuşmacılı ikiz; konuşmacısızı aşağıda işlenir
    speaker = ''
    if i > 0:
        p = re.match(r"^([A-Z][\w .'’\-()]{0,30}?): (.+)$", keys[i - 1], re.S)
        if p and p.group(2) == k:
            speaker = p.group(1)
    if k not in seen:
        seen[k] = len(lines)
        en = re.sub(r'\s+', ' ', re.sub(r'\([^)]*\)', ' ', re.sub(r'^\?+: ', '', k))).strip()
        mt = re.sub(r'\s+', ' ', re.sub(r'\([^)]*\)', ' ', re.sub(r'^\?+: ', '', raw[k]))).strip()
        if en and mt:
            lines.append((en, mt, speaker))
print('İngilizce satır', len(keys), '→ tekil', len(lines), '| konuşmacılı', sum(1 for l in lines if l[2]))

# --- Yama tarafı
patch = []  # (kimlik, tr, konuşmacı, dosya, sıra)
for path in sorted(glob.glob(os.path.join(patch_dir, '*_TxtRes.uexp'))):
    entries, _, _ = ff7txt.read(path)
    name = os.path.basename(path)
    for key, text, speaker in entries:
        text = re.sub(r'\s*\r?\n\s*', ' ', text).strip()
        if text:
            patch.append((key, text, speaker, name, len(patch)))
print('yama satırı', len(patch))


def norm(s):
    s = s.lower().replace('’', "'").replace('…', '...')
    s = s.translate(str.maketrans('çğıöşüâîû', 'cgiosuaiu'))
    return re.sub(r'[^a-z0-9]+', ' ', s).strip()


def grams(s):
    s = ' ' + norm(s) + ' '
    return collections.Counter(s[i:i + 3] for i in range(len(s) - 2))


# harf üçlüsü dizini (yama)
pg = [grams(p[1]) for p in patch]
df = collections.Counter()
for g in pg:
    df.update(g.keys())
N = len(patch)
idf = {t: math.log(1 + N / c) for t, c in df.items()}
pn = [math.sqrt(sum((v * idf[t]) ** 2 for t, v in g.items())) or 1 for g in pg]
index = collections.defaultdict(list)
for i, g in enumerate(pg):
    for t, v in g.items():
        index[t].append((i, v * idf[t]))


def candidates(text, top=5):
    g = grams(text)
    qn = math.sqrt(sum((v * idf.get(t, 0)) ** 2 for t, v in g.items())) or 1
    score = collections.defaultdict(float)
    for t, v in g.items():
        w = v * idf.get(t, 0)
        if w and len(index[t]) < 4000:
            for i, pw in index[t]:
                score[i] += w * pw
    best = sorted(score.items(), key=lambda x: -x[1])[:60]
    return sorted(((s / (qn * pn[i]), i) for i, s in best), reverse=True)[:top]


def numbers(s):
    return sorted(re.findall(r'\d+', s))


cand = [candidates(mt) for _, mt, _ in lines]


def cos(a, b):
    na = math.sqrt(sum((v * idf.get(t, 0)) ** 2 for t, v in a.items())) or 1
    nb = math.sqrt(sum((v * idf.get(t, 0)) ** 2 for t, v in b.items())) or 1
    return sum(v * b[t] * idf.get(t, 0) ** 2 for t, v in a.items() if t in b) / (na * nb)


def ratio_ok(a, b, lo=0.5, hi=2.0):
    la, lb = len(norm(a)), len(norm(b))
    return la > 0 and lb > 0 and lo <= lb / la <= hi


def best_run(mt, i):
    """i'yi içeren, aynı dosyada ardışık 1–5 yama satırından makine çevirisine en çok benzeyeni: (puan, a, b)."""
    g = grams(mt)
    best = (0, i, i)
    for a in range(max(0, i - 4), i + 1):
        for b in range(i, min(len(patch), a + 5)):
            if patch[a][3] != patch[b][3]:
                continue
            joined = ' '.join(patch[k][1] for k in range(a, b + 1))
            if not ratio_ok(mt, joined):
                continue
            score = cos(g, grams(joined))
            if score > best[0] + 1e-9:
                best = (score, a, b)
    return best


runs = {}  # satır → (puan, a, b)
for li, cs in enumerate(cand):
    en, mt, _ = lines[li]
    top = (0, 0, 0)
    for s, i in cs[:3]:
        r = best_run(mt, i)
        if r[0] > top[0]:
            top = r
    runs[li] = top

match = {}
for li, (score, a, b) in runs.items():
    en, mt, _ = lines[li]
    joined = ' '.join(patch[k][1] for k in range(a, b + 1))
    if score >= 0.6 and len(norm(mt)) >= 12 and numbers(mt) == numbers(joined):
        match[li] = (score, a, b)
print('çapa', len(match))


def near(li):
    spots = []
    for step in (-1, 1):
        j, hops = li + step, 0
        while 0 <= j < len(lines) and hops < 6:
            if j in match:
                spots.append(match[j][1])
                break
            j += step
            hops += 1
    return spots


for _ in range(3):
    added = 0
    for li, cs in enumerate(cand):
        if li in match or not cs:
            continue
        spots = near(li)
        if not spots:
            continue
        en, mt, _ = lines[li]
        best = None
        for s, i in cs:
            if any(patch[i][3] == patch[x][3] and abs(i - x) <= 40 for x in spots):
                r = best_run(mt, i)
                if best is None or r[0] > best[0]:
                    best = r
        if best and best[0] >= 0.36 and numbers(mt) == numbers(' '.join(patch[k][1] for k in range(best[1], best[2] + 1))):
            match[li] = best
            added += 1
    print('komşulukla eklenen', added)
    if not added:
        break

SENT = re.compile(r'(?<=[.!?…])["”’\']?\s+(?=[A-ZÇĞİÖŞÜ"“¿¡\-])')


def split_groups(en, mt, a, b):
    """Birden çok yama satırına denk gelen söz: İngilizce cümleler yama satırlarına sırayla dağıtılır (makine çevirisinin
    cümleleri köprü). Cümle sayıları tutmuyorsa ya da bir parça benzemiyorsa boş döner."""
    es, ms = SENT.split(en), SENT.split(mt)
    k = b - a + 1
    if len(es) != len(ms) or len(es) < k:
        return []
    n = len(es)
    tg = [grams(patch[a + j][1]) for j in range(k)]
    memo = {}

    def go(start, j):
        if j == k:
            return (0, []) if start == n else (-9, [])
        if (start, j) in memo:
            return memo[(start, j)]
        best = (-9, [])
        for end in range(start + 1, n - (k - j - 1) + 1):
            c = cos(grams(' '.join(ms[start:end])), tg[j])
            rest = go(end, j + 1)
            if c + rest[0] > best[0]:
                best = (c + rest[0], [(start, end, c)] + rest[1])
        memo[(start, j)] = best
        return best

    total, parts = go(0, 0)
    if total < 0 or any(c < 0.3 for _, _, c in parts):
        return []
    out = []
    for j, (s0, e0, c) in enumerate(parts):
        e, t = ' '.join(es[s0:e0]), patch[a + j][1]
        if ratio_ok(' '.join(ms[s0:e0]), t, 0.45, 2.2):
            out.append((e, t))
    return out


def sentences(x):
    return len(re.findall(r'[.!?…]+(?=["”’]?(?:\s|$))', x)) or 1


def good(en, mt, tr, score):
    """Son süzgeç: uzunluk makine çevirisine yakın olmalı; cümle sayısı tutmuyorsa (yama satırının yanına komşusu
    eklenmiş ya da eksik kalmış olabilir) yalnız çok benzeyen kabul edilir."""
    if not ratio_ok(mt, tr, 0.6, 1.6):
        return False
    return sentences(en) == sentences(tr) or score >= 0.75


pairs = {}
rows = []
parts_added = 0
dropped = 0
for li in sorted(match):
    en, mt, speaker = lines[li]
    score, a, b = match[li]
    tr = ' '.join(patch[k][1] for k in range(a, b + 1))
    if not good(en, mt, tr, score):
        dropped += 1
        continue
    rows.append((score, en, mt, tr, patch[a][0] + ('+%d' % (b - a) if b > a else '')))
    if b > a:
        for e, t in split_groups(en, mt, a, b):
            if e != t and e not in pairs:
                pairs[e] = t
                parts_added += 1
    if en != tr:
        pairs.setdefault(en, tr)
json.dump(pairs, open(out_path, 'w', encoding='utf-8'), ensure_ascii=False, indent=0)
print('süzgeçte düşen', dropped)
print('eşleşen satır', len(rows), '/', len(lines), '| çok satırlı', sum(1 for m in match.values() if m[2] > m[1]), '| parça çifti', parts_added, '| toplam çift', len(pairs))
if report_path:
    with open(report_path, 'w', encoding='utf-8') as f:
        for score, en, mt, tr, key in sorted(rows):
            f.write(f'{score:.2f}\t{key}\n   EN {en}\n   MT {mt}\n   TR {tr}\n')
        f.write('\n=== EŞLEŞMEYENLER ===\n')
        for li, (en, mt, sp) in enumerate(lines):
            if li not in match:
                top = cand[li][0] if cand[li] else (0, None)
                f.write(f'{top[0]:.2f}\tEN {en}\n   MT {mt}\n   ?? {patch[top[1]][1] if top[1] is not None else ""}\n')
