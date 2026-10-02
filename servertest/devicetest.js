/**
 * End-to-end check on a real Android device or emulator.
 *
 * Why this exists: the jsdom test stubs the network *and* the WebSocket, and the
 * JVM test replaces App's socket handler with its own. Neither can see a genuine
 * HTTP response from the real MiniServer, and both happily pass while the phone
 * is completely unable to pair — which is exactly what happened. This drives the
 * dashboard in the device's own browser over the real port.
 *
 * Setup (Android SDK platform-tools on PATH, or pass ADB=<path to adb>):
 *
 *   adb install -r app/build/outputs/apk/release/BoxHub-1.0.0-release.apk
 *   adb shell am start -n com.boxhub/.MainActivity
 *   # read the pairing code off the TV screen (it is the first thing shown):
 *   adb shell uiautomator dump /sdcard/ui.xml && adb shell cat /sdcard/ui.xml
 *
 *   adb forward tcp:8790  tcp:8790
 *   adb forward tcp:9333  localabstract:chrome_devtools_remote
 *   adb shell am start -a android.intent.action.VIEW -d http://10.0.2.2:8790
 *
 *   node servertest/devicetest.js <pin> [cdpPort]
 *
 * Exit code is non-zero when pairing does not reach the dashboard, so it can be
 * wired into a script. The emulator's own IP is in the app's TV screen; use that
 * instead of 10.0.2.2 unless the device really is the box itself.
 */
'use strict';

const PIN = process.argv[2];
const PORT = Number(process.argv[3] || 9333);
const HOST_URL = process.env.BOXHUB_URL || 'http://10.0.2.15:8790/';

if (!PIN) {
  console.error('usage: node servertest/devicetest.js <4-digit pin> [cdpPort]');
  process.exit(2);
}

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

let failed = 0;
function check(name, ok, detail) {
  if (ok) { console.log('  PASS   ' + name); }
  else { failed++; console.log('  FAIL   ' + name + (detail ? '   -> ' + detail : '')); }
}

async function attach() {
  const list = await (await fetch(`http://127.0.0.1:${PORT}/json`)).json();
  const cands = list.filter((t) => t.type === 'page' && t.url.includes('8790'));
  const page = cands.find((t) => t.url === HOST_URL) || cands.find((t) => t.title === 'BoxHub') || cands[0];
  if (!page) throw new Error('no dashboard tab open; targets: ' + JSON.stringify(list.map((t) => t.url)));

  const ws = new WebSocket(page.webSocketDebuggerUrl);
  await new Promise((res, rej) => { ws.onopen = res; ws.onerror = rej; });

  let id = 0;
  const pending = new Map();
  const events = [];
  ws.onmessage = (m) => {
    const d = JSON.parse(m.data);
    if (d.id && pending.has(d.id)) {
      const p = pending.get(d.id);
      pending.delete(d.id);
      d.error ? p.rej(new Error(JSON.stringify(d.error))) : p.res(d.result);
    } else if (d.method === 'Runtime.exceptionThrown') {
      const x = d.params.exceptionDetails;
      events.push('EXCEPTION: ' + ((x.exception && x.exception.description) || x.text));
    } else if (d.method === 'Runtime.consoleAPICalled' && m.params.type === 'error') {
      events.push('console.error: ' + m.params.args.map((a) => a.value ?? a.description ?? '').join(' '));
    }
  };
  const send = (method, params) => new Promise((res, rej) => {
    const mid = ++id;
    const t = setTimeout(() => { pending.delete(mid); rej(new Error('CDP timeout: ' + method)); }, 15000);
    pending.set(mid, { res: (v) => { clearTimeout(t); res(v); }, rej: (e) => { clearTimeout(t); rej(e); } });
    ws.send(JSON.stringify({ id: mid, method, params: params || {} }));
  });
  const ev = async (expr) => {
    const r = await send('Runtime.evaluate', { expression: expr, returnByValue: true, awaitPromise: true });
    if (r.exceptionDetails) return { __err: ((r.exceptionDetails.exception || {}).description) || r.exceptionDetails.text };
    return r.result.value;
  };

  await send('Runtime.enable');
  await send('Page.enable').catch(() => {});
  // A hidden tab silently drops synthesised input.
  await send('Page.bringToFront').catch(() => {});
  await sleep(1200);
  return { ws, send, ev, events };
}

const STATE = `(function(){
  return {
    gateHidden: document.getElementById('gate').classList.contains('hidden'),
    boxes: Array.prototype.map.call(document.getElementById('pinbox').children,function(x){return x.textContent;}).join(''),
    err: document.getElementById('perr').textContent,
    stored: sessionStorage.getItem('boxhub_key'),
    model: document.getElementById('dmodel').textContent,
    android: document.getElementById('dandroid').textContent,
    via: document.getElementById('viaLabel').textContent,
    files: document.querySelectorAll('.row-item').length,
    dot: document.getElementById('dot').className
  };
})()`;

async function main() {
  console.log('BoxHub device test — real server, real browser\n');
  let s = null;
  for (let i = 1; i <= 4 && !s; i++) {
    try { s = await attach(); }
    catch (e) { console.log('  (attach attempt ' + i + ' failed: ' + e.message + ')'); await sleep(4000); }
  }
  if (!s) throw new Error('could not attach to the browser; is adb forward set up?');

  for (let i = 0; i < 30; i++) {
    if ((await s.ev(`!!document.getElementById('pinbox')`)) === true) break;
    await sleep(1000);
  }

  // Reset in place. location.reload() would tear down the CDP context, and
  // simply showing the gate is NOT enough: pinVal lives in the page's closure,
  // and pushDigit() ignores every tap while four digits are still in it. Use the
  // gate's own 清除 button so the reset goes through the same code a finger
  // would.
  await s.ev(`(function(){
    sessionStorage.clear();
    document.getElementById('gate').classList.remove('hidden');
    document.getElementById('perr').textContent = '';
    var clr = document.querySelector('#gate .keys button[data-d="clr"]');
    if (clr) clr.click();
    return true;
  })()`);
  const cleared = await s.ev(`Array.prototype.map.call(document.getElementById('pinbox').children,function(x){return x.textContent;}).join('')`);
  if (cleared !== '') throw new Error('could not clear the pin boxes (still "' + cleared + '") — probing a dirty page');

  const before = await s.ev(STATE);
  check('the PIN gate is up', before.gateHidden === false);
  check('no code is stored yet', before.stored === null, String(before.stored));

  // PROBE=1 records what the page actually does, for when a check fails.
  const PROBE = process.env.PROBE === '1';
  if (PROBE) {
    await s.ev(`(function(){
      window.__p = { fetches: [], timers: [], ws: [], errs: [] };
      var of = window.fetch;
      window.fetch = function(u, o){ window.__p.fetches.push(String(u)); return of.call(window, u, o); };
      var ot = window.setTimeout;
      window.setTimeout = function(fn, ms){
        window.__p.timers.push('sched:' + ms);
        return ot(function(){ window.__p.timers.push('FIRED:' + ms); return fn.apply(this, arguments); }, ms);
      };
      var OW = window.WebSocket;
      var P = function(u, pr){
        window.__p.ws.push('new ' + u);
        var k = new OW(u, pr);
        k.addEventListener('message', function(e){ window.__p.ws.push('msg:' + String(e.data).slice(0,60)); });
        k.addEventListener('close', function(e){ window.__p.ws.push('close ' + e.code); });
        return k;
      };
      P.prototype = OW.prototype;
      window.WebSocket = P;
      window.addEventListener('unhandledrejection', function(e){ window.__p.errs.push('rejection: ' + e.reason); });
      window.addEventListener('error', function(e){ window.__p.errs.push('error: ' + e.message); });
      return true;
    })()`);
  }

  console.log('\n  typing ' + PIN + ' on the keypad');
  for (const d of PIN.split('')) {
    const pos = await s.ev(`(function(){
      var b = document.querySelector('#gate .keys button[data-d="${d}"]');
      if (!b) return null;
      var r = b.getBoundingClientRect();
      return [Math.round(r.left + r.width/2), Math.round(r.top + r.height/2)];
    })()`);
    if (!pos) { check('keypad has a ' + d, false); continue; }
    const base = { x: pos[0], y: pos[1], button: 'left', clickCount: 1 };
    await s.send('Input.dispatchMouseEvent', Object.assign({ type: 'mousePressed', buttons: 1 }, base));
    await s.send('Input.dispatchMouseEvent', Object.assign({ type: 'mouseReleased', buttons: 0 }, base));
    await sleep(300);
  }

  await sleep(4000);
  const after = await s.ev(STATE);
  if (PROBE) {
    console.log('\n  probe: ' + JSON.stringify(await s.ev('window.__p'), null, 2));
  }

  check('the gate closed after the correct code', after.gateHidden === true, 'boxes=' + after.boxes + ' err=' + after.err);
  check('the code was stored for reuse', after.stored === PIN, String(after.stored));
  check('device info arrived over the socket', after.model && after.model !== '—', after.model);
  check('android version shown', after.android && after.android !== '连接中', after.android);
  check('injection channel reported', after.via && after.via !== '—', after.via);
  check('connection indicator is green', /on/.test(after.dot), after.dot);

  // On API 28+ the app cannot read /storage/emulated/0 at all, so /api/list
  // answers 404 on an emulator and the UI toasts an error instead of rendering
  // rows. That is an environment fact, not a pairing defect — but the panel must
  // still *react* rather than sit blank. Ask for a fresh listing and watch for
  // any of the three outcomes; the toast only lives ~2 s, so poll while it can
  // still be seen.
  await s.ev(`document.getElementById('reloadBtn').click()`);
  let listing = { rows: 0, empty: false, toast: '', store: '' };
  for (let i = 0; i < 12; i++) {
    await sleep(200);
    listing = await s.ev(`(function(){
      var toast = document.getElementById('toast');
      return {
        rows: document.querySelectorAll('.row-item').length,
        empty: !!document.querySelector('.list .empty'),
        toast: (toast && toast.classList.contains('on')) ? toast.textContent : '',
        store: document.getElementById('storeLabel').textContent
      };
    })()`);
    if (listing.rows > 0 || listing.empty || listing.toast) break;
  }
  check(
    'the file panel reacted to a fresh listing (rows, empty state, or an error toast)',
    listing.rows > 0 || listing.empty || listing.toast.length > 0,
    'rows=' + listing.rows + ' empty=' + listing.empty + ' toast="' + listing.toast + '"'
  );
  console.log('         (listing outcome: rows=' + listing.rows + ' empty=' + listing.empty +
    ' toast="' + listing.toast + '" — an error here only means this Android version blocks external storage)');

  if (s.events.length) {
    console.log('\n  page errors:');
    s.events.forEach((e) => console.log('    ' + e));
  }

  const shot = await s.send('Page.captureScreenshot', { format: 'png' }).catch(() => null);
  if (shot) {
    require('fs').writeFileSync(process.env.SHOT || 'devicetest.png', Buffer.from(shot.data, 'base64'));
    console.log('\n  screenshot -> ' + (process.env.SHOT || 'devicetest.png'));
  }
  s.ws.close();

  console.log('\n  failed: ' + failed);
  process.exit(failed ? 1 : 0);
}

main().catch((e) => { console.error('FAILED:', e.message); process.exit(1); });