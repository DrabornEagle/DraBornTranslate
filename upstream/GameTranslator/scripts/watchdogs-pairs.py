# SPDX-License-Identifier: AGPL-3.0-only
"""Kullanım: watchdogs-pairs.py main_english.loc.txt (github.com/ahmet-celik/watch-dogs-loc-tool çıktısı) .0xANTI cikti.json
Watch Dogs: İngilizce .loc dökümü (id=metin, UTF-16) + Türkçe yama tablosu (<line id="..">) → {"EN": "TR"} JSON."""
import html, json, re, sys
sys.stdout.reconfigure(encoding='utf-8')

en_path, tr_path, out_path = sys.argv[1:4]
raw = open(en_path, 'rb').read()
text = raw.decode('utf-16')
en = {}
for line in text.splitlines():
    if '=' not in line:
        continue
    i, s = line.split('=', 1)
    if i.strip().isdigit():
        en[int(i)] = s.replace('[CR]', '').replace('[LF]', '\n')

tr = {}
for m in re.finditer(r'<line id="(\d+)">(.*?)</line>', open(tr_path, encoding='utf-8').read(), re.S):
    tr[int(m.group(1))] = html.unescape(m.group(2))

SILENCE = re.compile(r'\s*\[Silence[^\]]*\]\s*', re.I)
STYLE = re.compile(r'\[(?:CSS_[A-Z_]+|/n)\]', re.I)

def clean(x):
    x = STYLE.sub(' ', x.replace('[/n]', ' '))
    # yamadaki bozuk etiketler: '"Silence 2]', '[CSS_END ' (kapanmamış)
    x = re.sub(r'"?\[?Silence [\d.]+\]', ' ', x)
    x = re.sub(r'\[CSS_[A-Z_]*\]?', ' ', x)
    return re.sub(r'[ 	]+', ' ', x).strip()

def parts(x):
    return [p for p in (clean(q) for q in SILENCE.split(x)) if p]

def add(e, t):
    global same
    if not e or not t:
        return
    if e == t:
        same += 1
        return
    pairs.setdefault(e, t)

pairs, same, missing = {}, 0, 0
for i, t in tr.items():
    e = en.get(i)
    if e is None:
        missing += 1
        continue
    # [Silence N] oyunun konuşma arası etiketi: iki yanı ekranda ayrı altyazıdır. Parça sayıları tutarsa her parça
    # ayrı satır olur; tamamı da (etiketsiz) eklenir.
    ep, tp = parts(e), parts(t)
    if len(ep) == len(tp) and len(ep) > 1:
        for a_, b_ in zip(ep, tp):
            add(a_, b_)
    add(' '.join(ep), ' '.join(tp))
print('İngilizce', len(en), 'Türkçe', len(tr), 'eşleşen çift', len(pairs), 'aynı', same, 'İngilizcesi yok', missing)
json.dump(pairs, open(out_path, 'w', encoding='utf-8'), ensure_ascii=False, indent=0)
for k in list(pairs)[2000:2010]:
    print(repr(k), '=>', repr(pairs[k]))
