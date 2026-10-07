'use strict';
/**
 * 로컬 미리보기 서버 (배포에는 필요 없음)
 *
 *   node dev/server.js            → http://localhost:8787
 *   PORT=3000 node dev/server.js
 *   OWNER=1 node dev/server.js    → 스크립트 주인(자동 관리자)으로 접속한 것처럼
 *
 * src/ 의 Apps Script 코드를 dev/gas-mock.js 위에서 그대로 실행합니다.
 * 데이터는 dev/.data/state.json 에 저장되어 서버를 껐다 켜도 남습니다.
 */
const http = require('http');
const fs = require('fs');
const path = require('path');
const { createGasEnv } = require('./gas-mock');

const PORT = Number(process.env.PORT || 8787);
const DATA_DIR = path.join(__dirname, '.data');
const STATE_FILE = process.env.STATE_FILE || path.join(DATA_DIR, 'state.json');
const AS_OWNER = process.env.OWNER === '1';

const env = createGasEnv({
  serviceUrl: 'http://localhost:' + PORT + '/exec',
  printErrors: true,
  sharingFails: process.env.SHARING_FAILS === '1',
});

if (process.env.RESET !== '1' && fs.existsSync(STATE_FILE)) {
  try {
    env.restore(JSON.parse(fs.readFileSync(STATE_FILE, 'utf8')));
    console.log('저장된 데이터를 불러왔습니다: ' + STATE_FILE);
  } catch (e) {
    console.warn('저장된 데이터를 읽지 못해 새로 시작합니다: ' + e.message);
  }
}

env.runAsEditor('setup');
console.log(env.logs.map((l) => l.line).filter((l) => /관리자 키|스프레드시트/.test(l)).join('\n'));

let saveTimer = null;
function save() {
  clearTimeout(saveTimer);
  saveTimer = setTimeout(() => {
    fs.mkdirSync(path.dirname(STATE_FILE), { recursive: true });
    fs.writeFileSync(STATE_FILE, JSON.stringify(env.serialize()));
  }, 200);
}
save();

/** 실제 웹 앱의 google.script.run / history / url 을 흉내 내는 코드 */
const SHIM = `<script>
(function () {
  function runner(ok, fail, user) {
    return new Proxy({}, { get: function (_, name) {
      if (name === 'withSuccessHandler') return function (f) { return runner(f, fail, user); };
      if (name === 'withFailureHandler') return function (f) { return runner(ok, f, user); };
      if (name === 'withUserObject') return function (u) { return runner(ok, fail, u); };
      return function () {
        var args = Array.prototype.slice.call(arguments);
        fetch('/rpc', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ fn: name, args: args }) })
          .then(function (r) { return r.json(); })
          .then(function (res) {
            if (res.ok) { if (ok) ok(res.result, user); }
            else if (fail) fail(new Error(res.error), user);
          })
          .catch(function (e) { if (fail) fail(new Error('NetworkError: ' + e.message), user); });
      };
    } });
  }
  function loc() {
    var p = {}, ps = {};
    new URLSearchParams(location.search).forEach(function (v, k) { if (!(k in p)) p[k] = v; (ps[k] = ps[k] || []).push(v); });
    return { parameter: p, parameters: ps, hash: location.hash.replace(/^#/, '') };
  }
  function url(params, hash) {
    var s = new URLSearchParams();
    Object.keys(params || {}).forEach(function (k) { [].concat(params[k]).forEach(function (v) { s.append(k, v); }); });
    var q = s.toString();
    return location.pathname + (q ? '?' + q : '') + (hash ? '#' + hash : '');
  }
  window.google = { script: {
    run: runner(null, null),
    history: {
      push: function (state, params, hash) { history.pushState(state, '', url(params, hash)); },
      replace: function (state, params, hash) { history.replaceState(state, '', url(params, hash)); },
      setChangeHandler: function (fn) { window.addEventListener('popstate', function (e) { fn({ state: e.state, location: loc() }); }); }
    },
    url: { getLocation: function (cb) { setTimeout(function () { cb(loc()); }, 0); } },
    host: { close: function () {}, setHeight: function () {}, setWidth: function () {} }
  } };
})();
</script>`;

function renderPage(query) {
  env.activeUser = AS_OWNER ? env.ownerEmail : '';
  const out = env.doGet(query);
  const metas = out.getMetaTags().map((m) => '<meta name="' + m.name + '" content="' + m.content + '">').join('');
  const title = '<title>' + out.getTitle().replace(/</g, '&lt;') + '</title>';
  return out.getContent().replace('<head>', '<head>' + title + metas + SHIM);
}

function readBody(req) {
  return new Promise((resolve, reject) => {
    const chunks = [];
    let size = 0;
    req.on('data', (c) => {
      size += c.length;
      if (size > 60 * 1024 * 1024) {
        reject(new Error('payload too large'));
        req.destroy();
        return;
      }
      chunks.push(c);
    });
    req.on('end', () => resolve(Buffer.concat(chunks).toString('utf8')));
    req.on('error', reject);
  });
}

const server = http.createServer(async (req, res) => {
  const u = new URL(req.url, 'http://localhost');
  try {
    if (req.method === 'GET' && (u.pathname === '/' || u.pathname === '/exec')) {
      const query = {};
      u.searchParams.forEach((v, k) => { if (!(k in query)) query[k] = v; });
      const html = renderPage(query);
      res.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8', 'Cache-Control': 'no-store' });
      res.end(html);
      return;
    }
    if (req.method === 'POST' && u.pathname === '/rpc') {
      const body = JSON.parse(await readBody(req));
      env.activeUser = AS_OWNER ? env.ownerEmail : '';
      let payload;
      try {
        payload = { ok: true, result: env.run.apply(null, [body.fn].concat(body.args || [])) };
      } catch (e) {
        payload = { ok: false, error: e.message };
      }
      save();
      res.writeHead(200, { 'Content-Type': 'application/json; charset=utf-8' });
      res.end(JSON.stringify(payload));
      return;
    }
    // 개발용: 드라이브 파일 내용을 그대로 내려줌 (브라우저 테스트에서 썸네일 주소 대신 사용)
    const m = /^\/drive\/([A-Za-z0-9_-]+)$/.exec(u.pathname);
    if (req.method === 'GET' && m) {
      const file = env.drive.files.get(m[1]);
      if (!file || file._trashed || !file._blob || file._access !== 'ANYONE_WITH_LINK') {
        res.writeHead(404);
        res.end('not found');
        return;
      }
      res.writeHead(200, { 'Content-Type': file._blob._type });
      res.end(file._blob._bytes);
      return;
    }
    res.writeHead(404, { 'Content-Type': 'text/plain; charset=utf-8' });
    res.end('not found');
  } catch (e) {
    console.error(e);
    res.writeHead(500, { 'Content-Type': 'text/plain; charset=utf-8' });
    res.end(String(e && e.stack ? e.stack : e));
  }
});

server.listen(PORT, () => {
  console.log('\n로컬 미리보기: http://localhost:' + PORT + (AS_OWNER ? '  (주인 계정으로 접속 = 자동 관리자)' : ''));
});
