// SPDX-License-Identifier: AGPL-3.0-only
// TV'de ek alan kutusu ve tuş simgeleri: çeviri İngilizce yazının tam üstünü örter, altyazının yazı tipi ve rengiyle
// yazılır; metindeki ✕ / R2 gibi tuşlar PlayStation simgesi olarak çizilir. node --test scripts/tv-region.test.js
'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const app = fs.readFileSync(path.join(__dirname, '..', 'tv', 'webos', 'app.js'), 'utf8');
const slice = (from, to) => {
  const start = app.indexOf(from);
  const end = app.indexOf(to, start);
  assert.ok(start > 0 && end > start, `TV işlevi bulunamadı: ${from}`);
  return app.slice(start, end);
};

function element(tag) {
  return {
    tag, style: {}, className: '', dir: '', children: [], scrollHeight: 0,
    set textContent(value) { this.children = value ? [{ text: value }] : []; },
    get textContent() { return this.children.map(c => c.text ?? c.textContent).join(''); },
    appendChild(child) { this.children.push(child); return child; },
    removeChild() {},
  };
}

function run(code) {
  const regions = element('div');
  const subtitle = element('div');
  subtitle.style.fontFamily = "'GTFontQuicksand', sans-serif";
  subtitle.style.color = 'rgb(255, 230, 0)';
  subtitle.style.fontWeight = '700';
  const made = [];
  const context = {
    window: { innerWidth: 1920, innerHeight: 1080 },
    document: {
      createElement(tag) { const el = element(tag); made.push(el); return el; },
      createTextNode(text) { return { text }; },
    },
    $: id => (id === 'regions' ? regions : subtitle),
    result: null,
  };
  vm.runInNewContext(`${slice('  function validBox(box)', '\n  //')}
${slice('  function isRtl(text)', '\n  //')}
${slice('  var BUTTON =', '  function setSubtitleText(')}
${slice('  var regionBoxes = {};', '  function hideRegion(id)')}
function hideRegion() {}
result = (function () { ${code} })();`, context);
  return { made, result: context.result };
}

test('ek alan İngilizce yazının tam üstünü örter ve altyazının görünüşüyle yazılır', () => {
  const { made } = run(`showRegion({ id: 'area1', text: 'Deniz uçağına bin', box: { left: 0.44, top: 0.945, right: 0.58, bottom: 0.97 }, line: 0.022 });`);
  const el = made[0];
  assert.equal(el.className, 'region');
  assert.ok(Math.abs(parseFloat(el.style.top) - 94.1) < 0.01, el.style.top);
  // Tek satırlık yazı tek satır kalır: kutu iki yana %35 genişler, alt satıra geçmez.
  assert.equal(el.style.whiteSpace, 'nowrap');
  const boxWidth = (0.584 - 0.436) * 1920;
  assert.ok(Math.abs(parseFloat(el.style.width) - Math.round(boxWidth * 1.35)) <= 1, el.style.width);
  const middle = parseFloat(el.style.left) / 100 * 1920 + parseFloat(el.style.width) / 2;
  assert.ok(Math.abs(middle - 0.51 * 1920) < 2, String(middle));
  assert.equal(el.style.fontFamily, "'GTFontQuicksand', sans-serif");
  assert.equal(el.style.color, 'rgb(255, 230, 0)');
  assert.equal(el.style.fontWeight, '700');
  // Ekranın ortasındaki görev satırı ortalı, köşedeki ipucu kutusu sola yaslı.
  assert.equal(el.style.textAlign, 'center');
  const hint = run(`showRegion({ id: 'area2', text: 'İpucu', box: { left: 0.02, top: 0.27, right: 0.22, bottom: 0.36 }, line: 0.02 });`).made[0];
  assert.equal(hint.style.textAlign, 'left');
  // Çok satırlı ipucu kutusu sarar (tek satıra zorlanmaz).
  assert.equal(hint.style.whiteSpace, '');
});

test('metindeki tuşlar PlayStation simgesi olarak çizilir', () => {
  const { made } = run(`showRegion({ id: 'area1', text: 'Ağır saldırı için R2, tırmanmak için ✕ basılı tut', box: { left: 0.3, top: 0.9, right: 0.7, bottom: 0.95 }, line: 0.03 });`);
  const el = made[0];
  const icons = el.children.filter(child => child.className && child.className.startsWith('btn'));
  assert.deepEqual(icons.map(icon => [icon.className, icon.textContent]), [['btn btn-shoulder', 'R2'], ['btn btn-face btn-cross', '✕']]);
  // Sözcük içindeki harfler tuş sayılmaz ("FLR2X" gibi bir ad yok; "R2'ye" ise tuş + ek).
  const { made: other } = run(`setTextWithButtons(document.createElement('div'), "R2'ye bas, ALR1X değil");`);
  assert.deepEqual(other[0].children.filter(c => c.className).map(c => c.textContent), ['R2']);
});

test('oyunun renkli yazdığı sözcük ek alanda aynı renkte çizilir', () => {
  const { made } = run(`showRegion({ id: 'area1', text: '⁅#ff8dab|Deniz uçağına⁆ bin', box: { left: 0.44, top: 0.945, right: 0.58, bottom: 0.97 }, line: 0.022 });`);
  const el = made[0];
  const colored = el.children.find(child => child.style && child.style.color === '#ff8dab');
  assert.ok(colored, 'renkli parça yok');
  assert.equal(colored.textContent, 'Deniz uçağına');
  assert.equal(el.textContent, 'Deniz uçağına bin');
});
