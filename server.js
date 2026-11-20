// Lift Log self-hosted server. No dependencies: Node 18+.
// Usage:
//   node server.js --set-code     set or change the login passcode
//   node server.js                start (PORT env, default 3090)
'use strict';
const http = require('http');
const fs = require('fs');
const path = require('path');
const crypto = require('crypto');

const ROOT = __dirname;
const DATA_DIR = process.env.DATA_DIR || path.join(ROOT, 'data');
const PUB = path.join(ROOT, 'public');
const CONF = path.join(DATA_DIR, 'config.json');
const STORE = path.join(DATA_DIR, 'store.json');
const PORT = Number(process.env.PORT || 3090);
const HOST = process.env.HOST || '127.0.0.1';
const COOKIE = 'll_s';
const SESSION_DAYS = 180;

fs.mkdirSync(DATA_DIR, { recursive: true, mode: 0o700 });
fs.mkdirSync(path.join(DATA_DIR, 'backups'), { recursive: true, mode: 0o700 });

function loadConf() { try { return JSON.parse(fs.readFileSync(CONF, 'utf8')); } catch { return null; } }
function saveConf(c) { fs.writeFileSync(CONF, JSON.stringify(c, null, 2), { mode: 0o600 }); }

// ---------- set passcode ----------
if (process.argv.includes('--set-code')) {
  const rl = require('readline').createInterface({ input: process.stdin, output: process.stdout });
  rl.question('New passcode (at least 6 characters): ', (code) => {
    rl.close();
    code = String(code || '').trim();
    if (code.length < 6) { console.error('Too short. Nothing changed.'); process.exit(1); }
    const salt = crypto.randomBytes(16).toString('hex');
    const hash = crypto.scryptSync(code, salt, 64).toString('hex');
    const old = loadConf() || {};
    // a new secret signs out every device that used the old passcode
    saveConf({ salt, hash, secret: crypto.randomBytes(32).toString('hex'), created: old.created || new Date().toISOString() });
    console.log('Passcode saved. All devices will need to sign in again.');
    process.exit(0);
  });
  return;
}

let conf = loadConf();
if (!conf) { console.error('No passcode yet. Run: node server.js --set-code'); process.exit(1); }

// ---------- store: { collection: { id: doc } } ----------
let store = {};
try { store = JSON.parse(fs.readFileSync(STORE, 'utf8')); } catch { store = {}; }
let rev = Date.now();
let writeTimer = null;
function persist() {
  clearTimeout(writeTimer);
  writeTimer = setTimeout(() => {
    const tmp = STORE + '.tmp';
    fs.writeFileSync(tmp, JSON.stringify(store), { mode: 0o600 });
    fs.renameSync(tmp, STORE);
    const day = new Date().toISOString().slice(0, 10);
    const bk = path.join(DATA_DIR, 'backups', `store-${day}.json`);
    if (!fs.existsSync(bk)) {
      fs.copyFileSync(STORE, bk);
      // keep the last 30 daily backups
      const all = fs.readdirSync(path.join(DATA_DIR, 'backups')).filter(f => f.startsWith('store-')).sort();
      all.slice(0, Math.max(0, all.length - 30)).forEach(f => fs.unlinkSync(path.join(DATA_DIR, 'backups', f)));
    }
  }, 300);
}
const COLL = /^(sessions|bodyweight|food|daily|settings)$/;
const ID = /^[A-Za-z0-9_\-.~:@+]{1,200}$/;

// ---------- auth ----------
function sign(v) { return crypto.createHmac('sha256', conf.secret).update(v).digest('hex'); }
function makeToken() { const exp = Date.now() + SESSION_DAYS * 864e5; const v = `${exp}.${crypto.randomBytes(8).toString('hex')}`; return `${v}.${sign(v)}`; }
function validToken(t) {
  if (!t) return false; const i = t.lastIndexOf('.'); if (i < 0) return false;
  const v = t.slice(0, i), s = t.slice(i + 1);
  const good = sign(v);
  if (s.length !== good.length || !crypto.timingSafeEqual(Buffer.from(s), Buffer.from(good))) return false;
  return Number(v.split('.')[0]) > Date.now();
}
function cookies(req) { const o = {}; (req.headers.cookie || '').split(';').forEach(p => { const [k, ...r] = p.trim().split('='); if (k) o[k] = decodeURIComponent(r.join('=')); }); return o; }
const authed = req => validToken(cookies(req)[COOKIE]);
const attempts = new Map(); // ip -> [timestamps]
function limited(ip) { const now = Date.now(); const a = (attempts.get(ip) || []).filter(t => now - t < 15 * 60e3); attempts.set(ip, a); return a.length >= 5; }
function clientIp(req) { return (req.headers['cf-connecting-ip'] || req.headers['x-real-ip'] || req.socket.remoteAddress || '').toString(); }

// ---------- helpers ----------
const SEC = {
  'X-Content-Type-Options': 'nosniff', 'X-Frame-Options': 'DENY', 'Referrer-Policy': 'no-referrer',
  'X-Robots-Tag': 'noindex, nofollow', 'Cache-Control': 'no-store',
};
function send(res, code, body, type = 'application/json', extra = {}) {
  res.writeHead(code, { 'Content-Type': type, ...SEC, ...extra });
  res.end(typeof body === 'string' || Buffer.isBuffer(body) ? body : JSON.stringify(body));
}
function readBody(req, limit = 2e6) {
  return new Promise((ok, no) => { let n = 0; const ch = []; req.on('data', c => { n += c.length; if (n > limit) { no(new Error('too big')); req.destroy(); } else ch.push(c); }); req.on('end', () => ok(Buffer.concat(ch).toString('utf8'))); req.on('error', no); });
}
const MIME = { '.html': 'text/html; charset=utf-8', '.jpg': 'image/jpeg', '.png': 'image/png', '.svg': 'image/svg+xml', '.json': 'application/json', '.webmanifest': 'application/manifest+json' };

const LOGIN = `<!doctype html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><meta name="robots" content="noindex,nofollow"><title>Sign in</title>
<style>:root{color-scheme:light dark}body{margin:0;min-height:100vh;display:grid;place-items:center;font:16px system-ui,-apple-system,sans-serif;background:#f3f5f9;color:#0e1626}
@media (prefers-color-scheme:dark){body{background:#0a0f1a;color:#eef1f7}input{background:#131b2b!important;border-color:#25314a!important;color:#eef1f7}}
form{width:min(320px,calc(100% - 32px));display:grid;gap:10px}input{padding:14px;border-radius:12px;border:1px solid #dfe4ed;font-size:16px;background:#fff}
button{padding:14px;border:0;border-radius:12px;background:#14213d;color:#fff;font-weight:600;font-size:16px}p{margin:0;font-size:13px;opacity:.7;min-height:18px}</style></head>
<body><form method="post" action="/login"><input name="code" type="password" autocomplete="current-password" aria-label="Passcode" placeholder="Passcode" autofocus required><button>Continue</button><p>__MSG__</p></form></body></html>`;

// ---------- server ----------
http.createServer(async (req, res) => {
  try {
    const url = new URL(req.url, 'http://x');
    const p = url.pathname;

    if (p === '/robots.txt') return send(res, 200, 'User-agent: *\nDisallow: /\n', 'text/plain');

    if (p === '/login' && req.method === 'POST') {
      const ip = clientIp(req);
      if (limited(ip)) return send(res, 429, LOGIN.replace('__MSG__', 'Too many tries. Wait 15 minutes.'), 'text/html; charset=utf-8');
      const body = new URLSearchParams(await readBody(req, 4096));
      const code = String(body.get('code') || '');
      const h = crypto.scryptSync(code, conf.salt, 64);
      if (h.length === 64 && crypto.timingSafeEqual(h, Buffer.from(conf.hash, 'hex'))) {
        attempts.delete(ip);
        const secure = (req.headers['x-forwarded-proto'] || '').includes('https') || process.env.COOKIE_SECURE === '1';
        return send(res, 303, '', 'text/plain', { Location: '/', 'Set-Cookie': `${COOKIE}=${makeToken()}; Path=/; HttpOnly; SameSite=Strict; Max-Age=${SESSION_DAYS * 86400}${secure ? '; Secure' : ''}` });
      }
      attempts.get(ip).push(Date.now());
      return send(res, 401, LOGIN.replace('__MSG__', 'That passcode didn\'t work.'), 'text/html; charset=utf-8');
    }
    if (p === '/logout') return send(res, 303, '', 'text/plain', { Location: '/', 'Set-Cookie': `${COOKIE}=; Path=/; Max-Age=0` });

    if (!authed(req)) {
      if (p.startsWith('/api/')) return send(res, 401, { error: 'signed_out' });
      return send(res, 200, LOGIN.replace('__MSG__', ''), 'text/html; charset=utf-8');
    }

    // ---- API ----
    if (p === '/api/all' && req.method === 'GET') return send(res, 200, { rev, store });
    let m = p.match(/^\/api\/doc\/([^/]+)\/([^/]+)$/);
    if (m) {
      const [, coll, id] = m.map(decodeURIComponent);
      if (!COLL.test(coll) || !ID.test(id)) return send(res, 400, { error: 'bad_path' });
      if (req.method === 'PUT') {
        const doc = JSON.parse(await readBody(req));
        if (!doc || typeof doc !== 'object' || Array.isArray(doc)) return send(res, 400, { error: 'bad_body' });
        (store[coll] = store[coll] || {})[id] = doc; rev = Date.now(); persist();
        return send(res, 200, { ok: true, rev });
      }
      if (req.method === 'DELETE') { if (store[coll]) delete store[coll][id]; rev = Date.now(); persist(); return send(res, 200, { ok: true, rev }); }
    }
    if (p === '/api/import' && req.method === 'POST') {
      const inc = JSON.parse(await readBody(req, 20e6));
      for (const [coll, docs] of Object.entries(inc || {})) {
        if (!COLL.test(coll) || typeof docs !== 'object') continue;
        for (const [id, doc] of Object.entries(docs)) if (ID.test(id) && doc && typeof doc === 'object') (store[coll] = store[coll] || {})[id] = doc;
      }
      rev = Date.now(); persist(); return send(res, 200, { ok: true, rev });
    }

    // ---- static ----
    let file = p === '/' ? '/index.html' : p;
    const full = path.normalize(path.join(PUB, file));
    if (!full.startsWith(PUB + path.sep)) return send(res, 404, 'Not found', 'text/plain');
    fs.readFile(full, (err, buf) => {
      if (err) return send(res, 404, 'Not found', 'text/plain');
      const ext = path.extname(full);
      send(res, 200, buf, MIME[ext] || 'application/octet-stream', ext === '.jpg' ? { 'Cache-Control': 'private, max-age=604800' } : {});
    });
  } catch (e) {
    console.error(e); send(res, 500, { error: 'server_error' });
  }
}).listen(PORT, HOST, () => console.log(`Lift Log on http://${HOST}:${PORT}`));
