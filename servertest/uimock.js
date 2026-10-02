/**
 * Mock backend for previewing the BoxHub phone dashboard on the desktop.
 *
 * Serves the real assets/index.html and answers every endpoint the UI calls,
 * with a synthesised PNG standing in for `screencap` and a realistic file
 * listing. WebSocket is deliberately left unimplemented so the UI's polling
 * fallback path gets exercised too.
 *
 *   node servertest/uimock.js [port]
 */
'use strict';

const http = require('http');
const zlib = require('zlib');
const fs = require('fs');
const path = require('path');

const PORT = Number(process.argv[2] || 8791);
const INDEX = path.join(__dirname, '..', 'app', 'src', 'main', 'assets', 'index.html');
const indexHtml = fs.readFileSync(INDEX, 'utf8');

// ---------------------------------------------------------------- mock PNG

function crc32(buf) {
  let c, table = [];
  for (let n = 0; n < 256; n++) {
    c = n;
    for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1;
    table[n] = c >>> 0;
  }
  let crc = 0xffffffff;
  for (let i = 0; i < buf.length; i++) crc = table[(crc ^ buf[i]) & 0xff] ^ (crc >>> 8);
  return (crc ^ 0xffffffff) >>> 0;
}

function chunk(type, data) {
  const len = Buffer.alloc(4);
  len.writeUInt32BE(data.length, 0);
  const body = Buffer.concat([Buffer.from(type, 'ascii'), data]);
  const crc = Buffer.alloc(4);
  crc.writeUInt32BE(crc32(body), 0);
  return Buffer.concat([len, body, crc]);
}

/** Draws a plausible "TV launcher" so the preview panel can be judged visually. */
function mockScreen(w, h) {
  const px = Buffer.alloc(w * h * 3);
  const set = (x, y, r, g, b) => {
    if (x < 0 || y < 0 || x >= w || y >= h) return;
    const o = (y * w + x) * 3;
    px[o] = r; px[o + 1] = g; px[o + 2] = b;
  };
  const rect = (x0, y0, x1, y1, r, g, b) => {
    for (let y = y0; y < y1; y++) for (let x = x0; x < x1; x++) set(x, y, r, g, b);
  };
  // dark gradient background
  for (let y = 0; y < h; y++) {
    const t = y / h;
    for (let x = 0; x < w; x++) {
      const v = 16 + Math.round(10 * (1 - t));
      set(x, y, v, v, v + 2);
    }
  }
  // left rail
  rect(0, 0, Math.round(w * 0.10), h, 30, 30, 33);
  for (let i = 0; i < 5; i++) {
    rect(14, 30 + i * 34, 14 + 24, 30 + i * 34 + 18, 70, 70, 76);
  }
  // header
  rect(Math.round(w * 0.13), 22, Math.round(w * 0.97), 30, 120, 120, 128);
  // grid of app tiles
  const gx = Math.round(w * 0.13), gy = 56, tw = Math.round(w * 0.15), th = Math.round(h * 0.16);
  const accents = [[196, 148, 106], [120, 132, 168], [150, 168, 140], [178, 122, 118], [130, 150, 168], [168, 156, 120]];
  for (let r = 0; r < 3; r++) {
    for (let c = 0; c < 5; c++) {
      const x = gx + c * (tw + 12), y = gy + r * (th + 12);
      const a = accents[(r * 5 + c) % accents.length];
      rect(x, y, x + tw, y + th, a[0], a[1], a[2]);
      rect(x, y + th - 4, x + tw, y + th, a[0] * 0.6, a[1] * 0.6, a[2] * 0.6);
    }
  }
  // selection ring on the middle tile
  rect(gx + 2 * (tw + 12) - 3, gy + th + 3 - 3, gx + 2 * (tw + 12) + tw + 3, gy + 2 * th + 6, 235, 235, 235);

  // raw scanlines with filter byte 0
  const raw = Buffer.alloc(h * (w * 3 + 1));
  for (let y = 0; y < h; y++) {
    raw[y * (w * 3 + 1)] = 0;
    px.copy(raw, y * (w * 3 + 1) + 1, y * w * 3, (y + 1) * w * 3);
  }
  const ihdr = Buffer.alloc(13);
  ihdr.writeUInt32BE(w, 0); ihdr.writeUInt32BE(h, 4);
  ihdr[8] = 8; ihdr[9] = 2; ihdr[10] = 0; ihdr[11] = 0; ihdr[12] = 0;
  return Buffer.concat([
    Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]),
    chunk('IHDR', ihdr),
    chunk('IDAT', zlib.deflateSync(raw)),
    chunk('IEND', Buffer.alloc(0))
  ]);
}
const SCREEN = mockScreen(640, 360);

// ---------------------------------------------------------------- mock data

const INFO = {
  ok: true, port: 8790,
  model: 'Phorix 斐讯 N1',
  android: 'Android 7.1.2 (API 25)',
  root: '/storage/emulated/0',
  storage: '5.6 GB 可用 3.1 GB',
  addrs: ['192.168.1.50'],
  uptimeMs: 734000, clients: 1, connections: 1,
  remote: 'shell', remoteOk: true, shellInput: true,
  capture: true, captureError: '', lastError: ''
};

const now = Date.now();
const HOUR = 3600 * 1000;
// Every /api/* request the dashboard makes is recorded, so the UI can be
// verified end-to-end in a real browser: click a button, then read this back.
const reqLog = [];

// Pairing code the dashboard must present. Matches the PIN the TV screen shows.
const PIN = '1357';const LIST = {
  ok: true, path: '', abs: '/storage/emulated/0',
  storage: INFO.storage,
  items: [
    { name: 'Android', dir: true, size: 0, mtime: now - 200 * HOUR, ext: '', apk: false },
    { name: 'DCIM', dir: true, size: 0, mtime: now - 30 * HOUR, ext: '', apk: false },
    { name: 'Download', dir: true, size: 0, mtime: now - 2 * HOUR, ext: '', apk: false },
    { name: 'Movies', dir: true, size: 0, mtime: now - 8 * HOUR, ext: '', apk: false },
    { name: 'Music', dir: true, size: 0, mtime: now - 90 * HOUR, ext: '', apk: false },
    { name: 'BoxHub-1.0-release.apk', dir: false, size: 705740, mtime: now - 600000, ext: 'apk', apk: true },
    { name: '大佛普拉斯与春菊与小西瓜.mkv', dir: false, size: 4123456789, mtime: now - 5 * HOUR, ext: 'mkv', apk: false },
    { name: 'blade.runner.2049.2019.2160p.hevc.mkv', dir: false, size: 24817239014, mtime: now - 51 * HOUR, ext: 'mkv', apk: false },
    { name: 'concert.flac', dir: false, size: 482130091, mtime: now - 300 * HOUR, ext: 'flac', apk: false },
    { name: 'notes.txt', dir: false, size: 2048, mtime: now - 70 * HOUR, ext: 'txt', apk: false },
    { name: 'poster.jpg', dir: false, size: 2311884, mtime: now - 400 * HOUR, ext: 'jpg', apk: false }
  ]
};

// ---------------------------------------------------------------- server

const MIME = { '.html': 'text/html; charset=utf-8', '.png': 'image/png' };
const VIDEO = ['mp4', 'm4v', 'mkv', 'webm', 'avi', 'mov', 'ts', 'flv'];
const AUDIO = ['mp3', 'm4a', 'flac', 'wav', 'aac', 'ogg'];
const IMAGE = ['jpg', 'jpeg', 'png', 'gif', 'webp', 'bmp', 'svg'];

const server = http.createServer((req, res) => {
  const u = new URL(req.url, 'http://x');
  const p = u.pathname;
  const q = k => u.searchParams.get(k);
  const send = (code, type, body, extra) => {
    res.writeHead(code, Object.assign({ 'Content-Type': type, 'Cache-Control': 'no-store' }, extra || {}));
    res.end(body);
  };

  if (p === '/' || p === '/index.html') return send(200, MIME['.html'], indexHtml);
  if (p === '/favicon.ico') return send(204, 'image/x-icon', '');

  // Test-only endpoint: what the dashboard has actually asked for so far.
  if (p === '/mock/log') return send(200, 'application/json; charset=utf-8', JSON.stringify(reqLog));
  if (p === '/mock/reset') { reqLog.length = 0; return send(200, 'application/json', '{"ok":true}'); }

  if (p.startsWith('/api/')) {
    reqLog.push({ t: Date.now(), method: req.method, path: p, query: Object.fromEntries(u.searchParams) });
    if (reqLog.length > 500) reqLog.shift();
    // Enforce the pairing code exactly like the real server does, so the
    // dashboard's PIN gate and key propagation can be tested end to end.
    if (p !== '/api/ping' && u.searchParams.get('k') !== PIN) {
      return send(401, 'application/json; charset=utf-8', '{"ok":false,"error":"unauthorized","need":"pin"}');
    }
  }

  if (p === '/api/info') return send(200, 'application/json; charset=utf-8', JSON.stringify(INFO));
  if (p === '/api/list') return send(200, 'application/json; charset=utf-8', JSON.stringify(LIST));
  if (p === '/api/logs') return send(200, 'application/json; charset=utf-8', JSON.stringify({
    ok: true,
    data: [
      '12:04:31  服务已启动 端口 8790  配对码 4821',
      '12:04:32  遥控通道: SHELL / 截屏: 可用',
      '12:07:14  上传 BoxHub-1.0-release.apk 673.4 KB'
    ]
  }));
  if (p === '/api/shot') return send(200, MIME['.png'], SCREEN);
  if (p.startsWith('/api/key')) return send(200, 'application/json; charset=utf-8', '{"ok":true,"via":"shell"}');
  if (p.startsWith('/api/')) return send(200, 'application/json; charset=utf-8', '{"ok":true}');

  // Minimal stand-in for /dl so the media viewer path can be exercised.
  if (p.startsWith('/dl/')) {
    const name = decodeURIComponent(p.slice(4));
    const ext = (name.split('.').pop() || '').toLowerCase();
    if (IMAGE.indexOf(ext) >= 0) return send(200, MIME['.png'], SCREEN);
    if (VIDEO.indexOf(ext) >= 0 || AUDIO.indexOf(ext) >= 0) {
      return send(200, 'application/octet-stream', Buffer.alloc(4096, 0));
    }
    return send(200, 'text/plain; charset=utf-8', 'mock content of ' + name);
  }

  send(404, 'text/plain', 'not found');
});

server.listen(PORT, '127.0.0.1', () => {
  console.log('BoxHub UI mock on http://127.0.0.1:' + PORT + '  (any PIN accepted)');
});