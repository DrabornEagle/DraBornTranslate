// SPDX-License-Identifier: AGPL-3.0-only
// TV katman modunda (Android TV, body.overlay) örtü TV'nin büyüttüğü resimdeki İngilizce satırı da kaplar; uygulama
// içinde yerleşim değişmez (CouchSubs #473). node --test scripts/tv-cover.test.js
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

function run(overlay, code) {
  const context = {
    document: { body: { classList: { contains: name => overlay && name === 'overlay' } } },
    result: null,
  };
  vm.runInNewContext(`${slice('  var OVERLAY_ZOOM', '  // Altyazıyı seçilen yerleşime')}
result = (function () { ${code} })();`, context);
  return context.result;
}

const line = { left: 0.3, top: 0.85, right: 0.7, bottom: 0.9 };

test('uygulama içinde kutu olduğu gibi kalır', () => {
  const box = run(false, `return screenBox(${JSON.stringify(line)}, isOverlay());`);
  assert.equal(JSON.stringify(box), JSON.stringify(line));
});

test('katmanda kutu ekran ortasına göre %5 büyür ve TV resmi büyütünce İngilizce satırı kaplar', () => {
  const box = run(true, `return screenBox(${JSON.stringify(line)}, isOverlay());`);
  // TV'nin %5 büyüttüğü resimde İngilizce satır 0.29..0.71 arasındadır.
  assert.ok(Math.abs(box.left - 0.29) < 1e-9 && Math.abs(box.right - 0.71) < 1e-9, JSON.stringify(box));
  assert.ok(box.bottom <= 1 && box.top > line.top, JSON.stringify(box));
});

test('kutu ekranın dışına taşmaz', () => {
  const box = run(true, `return screenBox({ left: 0, top: 0.95, right: 1, bottom: 0.99 }, true);`);
  assert.equal(box.left, 0);
  assert.equal(box.right, 1);
  assert.ok(box.bottom <= 1);
});
