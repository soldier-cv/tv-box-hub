/**
 * Headless behaviour test for the BoxHub phone dashboard.
 *
 * Loads the real assets/index.html into jsdom, stubs only the network, then
 * drives the UI the way a finger would and asserts on what the UI actually
 * asked the server for.
 *
 * This exists because two of the bugs it has already caught were invisible to
 * visual review: the PIN keypad rendered perfectly but had no click handler
 * bound, and submitPin() verified the stored (empty) key instead of the code
 * the user had just typed. Either one made first-run impossible.
 *
 *   node servertest/uitest.js      (requires jsdom on NODE_PATH)
 */
'use strict';

const fs = require('fs');
const path = require('path');
const { JSDOM } = require('jsdom');

const INDEX = path.join(__dirname, '..', 'app', 'src', 'main', 'assets', 'index.html');
const PIN = '1357';

let passed = 0;
const failures = [];

function check(name, ok, detail) {
  if (ok) { passed++; console.log('  PASS   ' + name); }
  else { failures.push(name); console.log('  FAIL   ' + name + (detail ? '   -> ' + detail : '')); }
}

/** Let the page's promise chains settle before asserting on their effects. */
function flush() {
  return new Promise(function (r) { setImmediate(r); }).then(function () {
    return new Promise(function (r) { setImmediate(r); });
  });
}

const DEFAULT_ITEMS = [
  { name: 'Movies', dir: true, size: 0, mtime: 1, ext: '', apk: false },
  { name: 'notes.txt', dir: false, size: 2048, mtime: 1, ext: 'txt', apk: false },
  { name: 'BoxHub.apk', dir: false, size: 700000, mtime: 1, ext: 'apk', apk: true },
  { name: 'clip.mkv', dir: false, size: 900, mtime: 1, ext: 'mkv', apk: false }
];

function boot(opts) {
  opts = opts || {};
  const items = opts.items || DEFAULT_ITEMS;
  const state = { accept: opts.accept || function (k) { return k === PIN; } };

  const html = fs.readFileSync(INDEX, 'utf8');
  const requests = [];
  // Timers and sockets are captured rather than run, so a test can decide when a
  // reconnect or a poll tick happens. Without that, anything scheduled by the
  // page fires inside whatever call the test made and hides ordering bugs.
  const intervals = [];
  const timeouts = [];
  const sockets = [];
  const holds = [];

  const dom = new JSDOM(html, {
    url: 'http://127.0.0.1:8790/',
    runScripts: 'dangerously',
    pretendToBeVisual: false,
    beforeParse(window) {
      window.sessionStorage.clear();

      window.fetch = function (input, init) {
        let url;
        try { url = new URL(String(input), 'http://127.0.0.1:8790'); }
        catch (e) { url = { pathname: String(input), searchParams: new URLSearchParams() }; }
        const sp = url.searchParams;
        requests.push({
          method: (init && init.method) || 'GET',
          path: url.pathname,
          key: sp.get('k'),
          code: sp.get('code'),
          p: sp.get('path'),
          name: sp.get('name')
        });

        if (opts.offline) return Promise.reject(new TypeError('Failed to fetch'));

        if (opts.hold) {
          return new Promise(function (res, rej) {
            holds.push({ res: res, rej: rej, path: url.pathname, key: sp.get('k') });
          });
        }

        const ok = state.accept(sp.get('k'));
        let payload;
        if (!ok) {
          payload = { ok: false, error: 'unauthorized', need: 'pin' };
        } else if (url.pathname === '/api/list') {
          payload = { ok: true, path: sp.get('path') || '', abs: '/storage/emulated/0', storage: '5 GB', items: items };
        } else if (url.pathname === '/api/key') {
          payload = { ok: true, via: 'shell' };
        } else if (url.pathname === '/api/delete') {
          payload = { ok: true, dir: true, entries: 3, name: sp.get('path') };
        } else if (url.pathname === '/api/info') {
          payload = { ok: true, running: true, model: 'N1', android: '7.1.2', root: '/storage/emulated/0',
                      storage: '5 GB', remote: 'shell', remoteOk: true, capture: true, addrs: ['192.168.1.50'] };
        } else {
          payload = { ok: true };
        }
        return Promise.resolve({
          ok: ok, status: ok ? 200 : 401,
          json: function () { return Promise.resolve(payload); }
        });
      };

      window.WebSocket = function (url) {
        this.url = url;
        this.readyState = 1;
        this.sent = [];
        this.closed = false;
        this.close = function () { this.closed = true; };
        this.send = function (d) { this.sent.push(d); };
        sockets.push(this);
      };
      window.setInterval = function (fn, ms) { intervals.push({ fn: fn, ms: ms }); return intervals.length; };
      window.clearInterval = function () {};
      window.setTimeout = function (fn, ms) {
        if (opts.deferTimeouts) { timeouts.push({ fn: fn, ms: ms }); return timeouts.length; }
        fn(); return 0;
      };
      window.clearTimeout = function () {};
      window.scrollTo = function () {};
    }
  });

  return { dom, window: dom.window, doc: dom.window.document, requests, state, intervals, timeouts, sockets, holds };
}

/** Runs every timer the page scheduled but did not execute. */
function runTimeouts(t) {
  const due = t.timeouts.splice(0, t.timeouts.length);
  due.forEach(function (x) { x.fn(); });
  return due.length;
}
/** The 3 s fallback poll, once the socket has dropped. */
function pollTick(t) {
  const p = t.intervals.filter(function (i) { return i.ms === 3000; });
  if (!p.length) return false;
  p[p.length - 1].fn();
  return true;
}
function socketOpen(s) { if (s.onopen) s.onopen(); }
function socketFrame(s, obj) { if (s.onmessage) s.onmessage({ data: JSON.stringify(obj) }); }
function socketClose(s) { if (s.onclose) s.onclose(); }

/** Answers every request the hold-mode stub is still holding, with success. */
function release(t, payload) {
  const due = t.holds.splice(0, t.holds.length);
  due.forEach(function (h) {
    h.res({
      ok: true, status: 200,
      json: function () { return Promise.resolve(Object.assign({ ok: true }, payload)); }
    });
  });
  return due.length;
}
/** Detaches a held request without answering it, so it can answer late. */
function take(t, hold) {
  const i = t.holds.indexOf(hold);
  if (i >= 0) t.holds.splice(i, 1);
  return hold;
}
/** Resolves one held request and removes it from the pending list. */
function settle(t, hold, response) {
  const i = t.holds.indexOf(hold);
  if (i >= 0) t.holds.splice(i, 1);
  hold.res(response);
}
function unauthorized() {
  return {
    ok: false, status: 401,
    json: function () { return Promise.resolve({ ok: false, error: 'unauthorized', need: 'pin' }); }
  };
}

function countOf(list, p) { return list.filter(function (r) { return r.path === p; }).length; }
function lastOf(list, p) {
  for (let i = list.length - 1; i >= 0; i--) if (list[i].path === p) return list[i];
  return null;
}
function click(win, el) { el.dispatchEvent(new win.MouseEvent('click', { bubbles: true, cancelable: true })); }
function typePin(win, doc, digits) {
  digits.split('').forEach(function (d) { click(win, doc.querySelector('#gate .keys button[data-d="' + d + '"]')); });
}
function labelsOf(doc) {
  return Array.prototype.map.call(doc.getElementById('shActs').children, function (b) { return b.textContent; });
}
function findBtn(doc, text) {
  return Array.prototype.find.call(doc.getElementById('shActs').children, function (b) { return b.textContent === text; });
}

async function main() {
  console.log('BoxHub dashboard behaviour test (jsdom, real index.html)\n');

  // ---------------------------------------------------------------- A
  console.log('[A] first run, pairing with the correct code');
  {
    const t = boot();
    // No stored code means there is nothing to probe with, so the gate is up
    // immediately instead of after a pointless 401 round-trip.
    check('gate shown immediately when no code is stored',
      !t.doc.getElementById('gate').classList.contains('hidden'));
    check('no unauthenticated request is sent before pairing',
      t.requests.length === 0, JSON.stringify(t.requests));

    typePin(t.window, t.doc, PIN);
    await flush();

    check('gate hidden after correct code', t.doc.getElementById('gate').classList.contains('hidden'));
    check('code persisted to sessionStorage', t.window.sessionStorage.getItem('boxhub_key') === PIN,
      String(t.window.sessionStorage.getItem('boxhub_key')));
    check('first request carried the typed code',
      t.requests.length > 0 && t.requests[0].path === '/api/info' && t.requests[0].key === PIN,
      JSON.stringify(t.requests[0]));
    check('file list requested with the code', lastOf(t.requests, '/api/list') && lastOf(t.requests, '/api/list').key === PIN);
    check('every request carried the code',
      t.requests.every(function (r) { return r.key === PIN; }),
      JSON.stringify(t.requests.filter(function (r) { return r.key !== PIN; })));
    check('file rows rendered', t.doc.querySelectorAll('.row-item').length === DEFAULT_ITEMS.length,
      String(t.doc.querySelectorAll('.row-item').length));
    check('apk row shows an install chip', !!t.doc.querySelector('.row-item .chip'));
  }

  // ---------------------------------------------------------------- B
  console.log('\n[B] wrong pairing code');
  {
    const t = boot();
    typePin(t.window, t.doc, '9999');
    await flush();

    check('error message shown', /不正确/.test(t.doc.getElementById('perr').textContent), t.doc.getElementById('perr').textContent);
    check('gate stays visible', !t.doc.getElementById('gate').classList.contains('hidden'));
    check('wrong code not persisted', t.window.sessionStorage.getItem('boxhub_key') === null,
      String(t.window.sessionStorage.getItem('boxhub_key')));
    let filled = 0;
    const dots = t.doc.getElementById('pinbox').children;
    for (let i = 0; i < dots.length; i++) if (dots[i].textContent) filled++;
    check('input cleared after a wrong code', filled === 0, 'filled=' + filled);

    typePin(t.window, t.doc, PIN);
    await flush();
    check('recovers with the right code', t.doc.getElementById('gate').classList.contains('hidden'));
    check('no file list fetched while unauthenticated',
      countOf(t.requests, '/api/list') === 1, String(countOf(t.requests, '/api/list')));
  }

  // ---------------------------------------------------------------- C
  console.log('\n[C] every remote-control button');
  {
    const t = boot();
    typePin(t.window, t.doc, PIN);
    await flush();

    const expected = [
      ['4', '返回'], ['3', '主页'], ['82', '菜单'], ['24', '音量 +'], ['25', '音量 -'],
      ['23', 'OK'], ['19', '上'], ['20', '下'], ['21', '左'], ['22', '右'],
      ['85', '播放/暂停'], ['87', '下一首'], ['88', '上一首'], ['86', '停止']
    ];

    for (const pair of expected) {
      const code = pair[0], label = pair[1];
      const btn = t.doc.querySelector('.k[data-key="' + code + '"]');
      check('button exists: ' + label, !!btn);
      if (!btn) continue;
      const before = countOf(t.requests, '/api/key');
      click(t.window, btn);
      await flush();
      const sent = t.requests.filter(function (r) { return r.path === '/api/key'; });
      const got = sent[sent.length - 1];
      check(label + ' sends keycode ' + code,
        sent.length === before + 1 && got && got.code === code && got.method === 'POST',
        got ? 'code=' + got.code + ' method=' + got.method : 'no request');
      check(label + ' carries the pairing code', got && got.key === PIN, got && got.key);
    }
  }

  // ---------------------------------------------------------------- D
  console.log('\n[D] file rows open an action sheet, and delete asks first');
  {
    const t = boot();
    typePin(t.window, t.doc, PIN);
    await flush();

    const notes = Array.prototype.find.call(t.doc.querySelectorAll('.row-item'), function (r) {
      return r.textContent.indexOf('notes.txt') >= 0;
    });
    check('notes.txt row present', !!notes);
    if (notes) {
      click(t.window, notes.querySelector('.more'));
      check('sheet opened', t.doc.getElementById('sheet').classList.contains('on'));
      const labels = labelsOf(t.doc);
      check('offers 重命名', labels.indexOf('重命名') >= 0, labels.join('|'));
      check('offers 下载链接', labels.indexOf('下载链接') >= 0, labels.join('|'));
      check('offers 删除', labels.indexOf('删除') >= 0, labels.join('|'));
      check('plain file delete is one step', labels.indexOf('确认删除') < 0, labels.join('|'));
      check('nothing deleted yet', countOf(t.requests, '/api/delete') === 0);

      click(t.window, findBtn(t.doc, '删除'));
      await flush();
      const d = lastOf(t.requests, '/api/delete');
      check('delete reaches the server', !!d && d.p === 'notes.txt', JSON.stringify(d));
      check('delete carries the pairing code', d && d.key === PIN);
    }
  }

  // ---------------------------------------------------------------- E
  console.log('\n[E] directory delete is reachable and confirms');
  {
    const t = boot();
    typePin(t.window, t.doc, PIN);
    await flush();

    const dirRow = Array.prototype.find.call(t.doc.querySelectorAll('.row-item'), function (r) {
      return r.textContent.indexOf('Movies') >= 0;
    });
    check('directory row present', !!dirRow);
    if (dirRow) {
      click(t.window, dirRow.querySelector('.more'));
      const labels = labelsOf(t.doc);
      check('directory offers 删除目录', labels.indexOf('删除目录') >= 0, labels.join('|'));

      click(t.window, findBtn(t.doc, '删除目录'));
      const labels2 = labelsOf(t.doc);
      check('a confirmation step appears', labels2.indexOf('确认删除') >= 0, labels2.join('|'));
      check('confirmation states the consequence', /全部内容/.test(t.doc.getElementById('shActs').textContent),
        t.doc.getElementById('shActs').textContent);
      check('still nothing deleted before confirming', countOf(t.requests, '/api/delete') === 0);

      click(t.window, findBtn(t.doc, '确认删除'));
      await flush();
      const d = lastOf(t.requests, '/api/delete');
      check('confirmed delete reaches the server', !!d && d.p === 'Movies', JSON.stringify(d));
    }
  }

  // ---------------------------------------------------------------- F
  console.log('\n[E2] cancelling a destructive action');
  {
    const t = boot();
    typePin(t.window, t.doc, PIN);
    await flush();
    const dirRow = Array.prototype.find.call(t.doc.querySelectorAll('.row-item'), function (r) {
      return r.textContent.indexOf('Movies') >= 0;
    });
    click(t.window, dirRow.querySelector('.more'));
    click(t.window, findBtn(t.doc, '删除目录'));
    click(t.window, findBtn(t.doc, '取消'));
    await flush();
    check('cancel deletes nothing', countOf(t.requests, '/api/delete') === 0);
  }

  // ---------------------------------------------------------------- G
  console.log('\n[F] text field');
  {
    const t = boot();
    typePin(t.window, t.doc, PIN);
    await flush();
    t.doc.getElementById('textIn').value = 'hello 盒子';
    click(t.window, t.doc.getElementById('textGo'));
    await flush();
    check('text send endpoint hit', countOf(t.requests, '/api/text') === 1, String(countOf(t.requests, '/api/text')));
    check('text field cleared after send', t.doc.getElementById('textIn').value === '',
      t.doc.getElementById('textIn').value);
  }

  // ---------------------------------------------------------------- H
  console.log('\n[G] navigation');
  {
    const t = boot();
    typePin(t.window, t.doc, PIN);
    await flush();

    const movies = Array.prototype.find.call(t.doc.querySelectorAll('.row-item'), function (r) {
      return r.textContent.indexOf('Movies') >= 0;
    });
    click(t.window, movies);
    await flush();
    const l = lastOf(t.requests, '/api/list');
    check('clicking a directory navigates into it', l && l.p === 'Movies', l && l.p);
    check('breadcrumb rendered', t.doc.querySelectorAll('#crumbs button').length >= 1,
      String(t.doc.querySelectorAll('#crumbs button').length));
  }

  // ---------------------------------------------------------------- I
  console.log('\n[H] install an APK');
  {
    const t = boot();
    typePin(t.window, t.doc, PIN);
    await flush();
    const chip = t.doc.querySelector('.row-item .chip');
    check('install chip present on an apk row', !!chip);
    click(t.window, chip);
    await flush();
    const i = lastOf(t.requests, '/api/install');
    check('install endpoint hit with the file path', !!i && i.p === 'BoxHub.apk', JSON.stringify(i));
  }

  // ---------------------------------------------------------------- J
  console.log('\n[H] a pairing code that stops working re-opens the gate');
  {
    // The TV regenerates its code whenever BoxHub restarts. A live session
    // must therefore still be able to fall back to the gate — the opposite of
    // the stale-probe race that was removed.
    const t = boot();
    typePin(t.window, t.doc, PIN);
    await flush();
    check('paired and gate hidden', t.doc.getElementById('gate').classList.contains('hidden'));

    t.state.accept = function () { return false; };   // server rotated its code
    click(t.window, t.doc.querySelector('.k[data-key="4"]'));
    await flush();

    const back = lastOf(t.requests, '/api/key');
    check('a request was attempted with the old code', !!back && back.code === '4');

    // The listing path is the one that actually re-opens the gate.
    const t2 = boot();
    typePin(t2.window, t2.doc, PIN);
    await flush();
    t2.state.accept = function () { return false; };
    click(t2.window, t2.doc.getElementById('reloadBtn'));
    await flush();
    check('a revoked code re-opens the gate',
      !t2.doc.getElementById('gate').classList.contains('hidden'));
    check('the revoked code is dropped', t2.window.sessionStorage.getItem('boxhub_key') === null,
      String(t2.window.sessionStorage.getItem('boxhub_key')));
  }

  console.log('\n[K] a stale socket cannot slam the gate onto a paired session');
  {
    // The reported symptom: after entering the code the page "refreshes", i.e.
    // the gate comes back over a session that is already authenticated. The old
    // socket was never closed, so its late `unauthorized` frame landed on the
    // new session.
    const NEW = '4821';
    const t = boot();
    typePin(t.window, t.doc, PIN);
    await flush();
    check('paired with the first code', t.doc.getElementById('gate').classList.contains('hidden'));
    const stale = t.sockets[0];
    check('a socket was opened for the session', !!stale);

    // The TV restarts and hands out a different code.
    t.state.accept = function (k) { return k === NEW; };
    click(t.window, t.doc.getElementById('reloadBtn'));
    await flush();
    check('gate re-opened for the new code', !t.doc.getElementById('gate').classList.contains('hidden'));

    typePin(t.window, t.doc, NEW);
    await flush();
    check('re-paired with the new code', t.doc.getElementById('gate').classList.contains('hidden'));
    check('a second socket replaced the first', t.sockets.length === 2, String(t.sockets.length));
    check('the stale socket was closed', !!stale.closed);

    socketFrame(stale, { event: 'unauthorized' });
    await flush();
    check('a late unauthorized frame from the stale socket is ignored',
      t.doc.getElementById('gate').classList.contains('hidden'));

    socketClose(stale);
    await flush();
    check('a close from the stale socket does not knock the live one offline',
      t.doc.getElementById('dot').className.indexOf('off') < 0, t.doc.getElementById('dot').className);

    socketFrame(t.sockets[1], { event: 'log', line: '12:00:00  still connected' });
    await flush();
    check('the live socket still drives the page',
      /still connected/.test(t.doc.getElementById('log').textContent), t.doc.getElementById('log').textContent);
  }

  console.log('\n[L] the polling fallback is not dead code');
  {
    // wsPoll() used to read a timer off the socket that the close handler had
    // already nulled, so the fallback threw and the dashboard stayed dead for
    // the rest of the page's life.
    const t = boot({ deferTimeouts: true });
    typePin(t.window, t.doc, PIN);
    runTimeouts(t);
    await flush();
    check('paired', t.doc.getElementById('gate').classList.contains('hidden'));
    const live = t.sockets[0];
    socketOpen(live);
    check('dot green once the socket opens', /on/.test(t.doc.getElementById('dot').className),
      t.doc.getElementById('dot').className);

    t.state.accept = function () { return true; };
    socketClose(live);
    check('a poll is scheduled after the socket drops', pollTick(t));
    await flush();
    check('polling keeps the dashboard alive without a socket',
      /on/.test(t.doc.getElementById('dot').className), t.doc.getElementById('dot').className);

    // A rotated code must be noticed on the polling path too, otherwise the page
    // sits there with a dead dot and no way back to the gate.
    t.state.accept = function () { return false; };
    pollTick(t);
    await flush();
    check('a rotated code re-opens the gate while polling',
      !t.doc.getElementById('gate').classList.contains('hidden'));
    check('the dead code is forgotten', t.window.sessionStorage.getItem('boxhub_key') === null,
      String(t.window.sessionStorage.getItem('boxhub_key')));
  }

  console.log('\n[M] a poll that lands after a successful re-pair is discarded');
  {
    // The opposite direction of the stale-probe race: a response carrying the
    // old code must not re-open the gate on the session that replaced it.
    const NEW = '4821';
    const INFO = { model: 'N1', capture: true, remote: 'shell', remoteOk: true };
    const LIST = { storage: '5 GB', path: '', items: [], model: 'N1', capture: true, remote: 'shell', remoteOk: true };
    const t = boot({ hold: true, deferTimeouts: true });

    typePin(t.window, t.doc, PIN);
    runTimeouts(t);            // submitPin -> /api/info, held
    release(t, INFO);          // pairing succeeds -> boot() runs
    await flush();
    release(t, LIST);          // boot()'s own requests
    await flush();
    check('paired', t.doc.getElementById('gate').classList.contains('hidden'));
    check('a socket was opened', !!t.sockets[0]);

    // The socket drops and a poll goes out with the OLD code, stalling in flight.
    socketClose(t.sockets[0]);
    check('a poll went out', pollTick(t));
    check('the poll is still in flight', t.holds.length === 1, String(t.holds.length));
    const stalePoll = take(t, t.holds[0]);   // answers only when we say so
    check('the poll carried the old code', stalePoll.key === PIN, String(stalePoll.key));

    // Meanwhile the box rotated its code and the listing request is refused
    // too, so the gate comes up and the user re-pairs.
    t.state.accept = function (k) { return k === NEW; };
    click(t.window, t.doc.getElementById('reloadBtn'));
    settle(t, t.holds[t.holds.length - 1], unauthorized());
    await flush();
    check('the gate opened', !t.doc.getElementById('gate').classList.contains('hidden'));

    typePin(t.window, t.doc, NEW);
    runTimeouts(t);
    release(t, INFO);
    await flush();
    release(t, LIST);
    await flush();
    check('re-paired with the new code', t.doc.getElementById('gate').classList.contains('hidden'));

    // Only now does the old poll answer 401.
    stalePoll.res(unauthorized());
    await flush();
    check('the stale 401 does not re-open the gate',
      t.doc.getElementById('gate').classList.contains('hidden'));
    check('the new code survives',
      t.window.sessionStorage.getItem('boxhub_key') === NEW,
      String(t.window.sessionStorage.getItem('boxhub_key')));
  }

  console.log('\n[N] the gate does not blame the code for an unreachable box');
  {
    const t = boot({ offline: true });
    typePin(t.window, t.doc, PIN);
    await flush();
    const msg = t.doc.getElementById('perr').textContent;
    check('a transport failure does not say the code is wrong', !/不正确/.test(msg), msg);
    check('it points at the app instead', /BoxHub/.test(msg), msg);
  }

  console.log('\n[I] session reuse: a stored code boots without the gate');
  {
    const t = boot();
    typePin(t.window, t.doc, PIN);
    await flush();
    const stored = t.window.sessionStorage.getItem('boxhub_key');

    // Second load, same origin, code already stored.
    const html = fs.readFileSync(INDEX, 'utf8');
    const dom2 = new JSDOM(html, {
      url: 'http://127.0.0.1:8790/', runScripts: 'dangerously', pretendToBeVisual: false,
      beforeParse(window) {
        window.sessionStorage.setItem('boxhub_key', stored);
        window.fetch = function () { return Promise.reject(new Error('should not be needed')); };
        window.WebSocket = function () { this.close = function () {}; };
        window.setInterval = function () { return 0; };
        window.setTimeout = function (fn) { fn(); return 0; };
        window.scrollTo = function () {};
      }
    });
    // The real probe does run, so give it a working stub instead.
    dom2.window.fetch = function (input) {
      return Promise.resolve({ ok: true, status: 200, json: function () {
        return Promise.resolve({ ok: true, path: '', storage: '5 GB', items: DEFAULT_ITEMS, remote: 'shell', remoteOk: true, capture: true });
      } });
    };
    const gate = dom2.window.document.getElementById('gate');
    check('gate starts hidden when a code is stored', gate.classList.contains('hidden'));
  }

  console.log('\n--------------------------------------------------');
  console.log('passed: ' + passed + '   failed: ' + failures.length);
  if (failures.length) {
    failures.forEach(function (f) { console.log('  - ' + f); });
    process.exit(1);
  }
  console.log('ALL GREEN');
  process.exit(0);
}

main();
