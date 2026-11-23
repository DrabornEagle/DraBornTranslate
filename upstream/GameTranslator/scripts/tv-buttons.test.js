// SPDX-License-Identifier: AGPL-3.0-only
// Tuş simgeleri: TV uygulaması, OBS katmanı ve ikinci ekran metindeki PlayStation ve Xbox tuşlarını aynı biçimde çizer.
// PlayStation çizimi Xbox eklenmeden önceki çıktıyla birebir aynı kalır. node --test scripts/tv-buttons.test.js
'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const root = path.join(__dirname, '..');
const read = file => fs.readFileSync(path.join(root, file), 'utf8');
const OVERLAY = 'windows/src/GameTranslator.Core/Resources/obs-overlay.html';
const SOURCES = {
  tv: 'tv/webos/app.js',
  obs: OVERLAY,
  screen: 'windows/src/GameTranslator.Core/Resources/second-screen.html',
};

// Kaynaktan BUTTON ... appendWithButtons bölümü alınır, sahte DOM'da çalıştırılır; çıktı "[sınıf|yazı]" diye yazılır.
function renderer(file) {
  const src = read(file);
  const start = src.indexOf('  var BUTTON =');
  const end = src.indexOf('\n  }\n', src.indexOf('  function appendWithButtons(', start));
  assert.ok(start > 0 && end > start, `tuş işlevleri bulunamadı: ${file}`);
  const element = () => ({ className: '', text: '', children: [],
    set textContent(value) { this.text = value; }, appendChild(child) { this.children.push(child); return child; } });
  const context = { document: { createElement: element, createTextNode: text => ({ node: text }) } };
  vm.runInNewContext(src.slice(start, end + 4) + '\nthis.appendWithButtons = appendWithButtons;', context);
  return text => {
    const el = element();
    context.appendWithButtons(el, text);
    return el.children.map(c => (c.node !== undefined ? c.node : `[${c.className}|${c.text}]`)).join('');
  };
}

// Xbox eklenmeden önceki çıktı (tv/webos/app.js, TV 1.0.13).
const PS_BEFORE = {
  'Tırmanmak için ✕ basılı tut, ○ ile bırak':
    'Tırmanmak için [btn btn-face btn-cross|✕] basılı tut, [btn btn-face btn-circle|] ile bırak',
  '△ □ × ✖ ◯ ✜ ▦ ↑↓←→':
    '[btn btn-face btn-triangle|△] [btn btn-face btn-square|] [btn btn-face btn-cross|✕] [btn btn-face btn-cross|✕] ' +
    '[btn btn-face btn-circle|] [btn btn-face btn-plain|✜] [btn btn-face btn-plain|▦] [btn btn-face btn-plain|↑]' +
    '[btn btn-face btn-plain|↓][btn btn-face btn-plain|←][btn btn-face btn-plain|→]',
  'L1 R2 L3 R3 OPTIONS TOUCHPAD':
    '[btn btn-shoulder|L1] [btn btn-shoulder|R2] [btn btn-shoulder|L3] [btn btn-shoulder|R3] [btn btn-wide|OPTIONS] [btn btn-wide|▦]',
  'Ⓒ ve Ⓩ': '[btn btn-face btn-plain|Ⓒ] ve [btn btn-face btn-plain|Ⓩ]',
};

for (const [name, file] of Object.entries(SOURCES)) {
  const render = renderer(file);

  test(`${name}: PlayStation tuşları eskisi gibi çizilir`, () => {
    for (const [text, expected] of Object.entries(PS_BEFORE)) assert.equal(render(text), expected);
  });

  test(`${name}: Xbox yüz tuşları kendi renginde harfle çizilir`, () => {
    assert.equal(render('Zıplamak için Ⓐ, geri Ⓑ'),
      'Zıplamak için [btn btn-face btn-xbox btn-xbox-a|A], geri [btn btn-face btn-xbox btn-xbox-b|B]');
    assert.equal(render('ⓍⓎ'), '[btn btn-face btn-xbox btn-xbox-x|X][btn btn-face btn-xbox btn-xbox-y|Y]');
  });

  test(`${name}: LB RB LT RT LS RS omuz tuşu, ⧉ ☰ koyu dairede`, () => {
    assert.equal(render('LB RB LT RT LS RS'),
      ['LB', 'RB', 'LT', 'RT', 'LS', 'RS'].map(t => `[btn btn-shoulder|${t}]`).join(' '));
    assert.equal(render('Harita ⧉, durdur ☰'),
      'Harita [btn btn-face btn-xbox btn-view|], durdur [btn btn-face btn-xbox btn-menu|]');
    assert.equal(render('Nişan LT+RT.'), 'Nişan [btn btn-shoulder|LT]+[btn btn-shoulder|RT].');
  });

  test(`${name}: sözcük içindeki ya da küçük harfli LB / LT tuş sayılmaz`, () => {
    for (const text of ['BLT', 'LBS', 'lb', 'Alt', 'ALT', 'RSVP', 'L1X', 'lt rt', 'Ls']) assert.equal(render(text), text);
  });
}

test('OBS katmanının kopyaları Windows dosyasıyla birebir aynı', () => {
  const windows = read(OVERLAY);
  assert.equal(read('core/Sources/GameTranslatorCore/Resources/obs-overlay.html'), windows);
  assert.equal(read('core/obs-overlay.html'), windows);
});

test('TV, OBS ve ikinci ekranın tuş tanıma kuralı aynı', () => {
  const rule = file => read(file).match(/var BUTTON = .*;/)[0];
  assert.equal(rule(SOURCES.obs), rule(SOURCES.tv));
  assert.equal(rule(SOURCES.screen), rule(SOURCES.tv));
});

test('TV stil dosyası ve OBS katmanı Xbox sınıflarını tanımlar', () => {
  for (const file of ['tv/webos/style.css', OVERLAY, SOURCES.screen]) {
    const css = read(file);
    for (const cls of ['.btn-xbox ', '.btn-xbox-a', '.btn-xbox-b', '.btn-xbox-x', '.btn-xbox-y', '.btn-view::before', '.btn-menu::after'])
      assert.ok(css.includes(cls), `${file}: ${cls} yok`);
  }
});
