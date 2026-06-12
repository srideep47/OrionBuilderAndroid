'use strict';
/*
 * Orion Node host — runs inside the embedded nodejs-mobile runtime (:node process) and exposes a
 * small JSON-RPC + dev-server API on 127.0.0.1 that the Kotlin side drives.
 *
 * Key constraint: nodejs-mobile cannot spawn external child processes (there is no `node` binary on
 * disk and exec of arbitrary binaries is restricted). So everything runs IN-PROCESS:
 *   - npm runs in-process by requiring its bundled CLI (process.exit is trapped).
 *   - the dev server transforms TS/TSX/JSX on the fly with @babel/standalone (pure JS — no native
 *     binary, no child process, no worker threads, so it actually runs on nodejs-mobile). esbuild's
 *     wasm build is NOT usable here because its Node path spawns a child `node` to host the wasm.
 *   - bare imports (react, react-dom/client, …) resolve via an injected <script type="importmap">
 *     pointing at esm.sh, so the preview renders even before `npm install` finishes (needs network
 *     for vendored deps; the user's own code is transformed locally and offline).
 *
 * Args: orion-host.js <controlPort> <npmCliPath>
 */

const http = require('http');
const fs = require('fs');
const path = require('path');
const { URL } = require('url');

const CONTROL_PORT = parseInt(process.argv[2] || '8137', 10);
const NPM_CLI = process.argv[3] || '';
const HOST_DIR = __dirname;

// P2P bridge module + its last start info, loaded lazily and hosted in this same Node process.
let _p2pBridge = null;
let _p2pInfo = null;

// ---- helpers ---------------------------------------------------------------

function sendJson(res, code, obj) {
  const body = JSON.stringify(obj);
  res.writeHead(code, { 'Content-Type': 'application/json', 'Content-Length': Buffer.byteLength(body) });
  res.end(body);
}

function send(res, code, type, body) {
  res.writeHead(code, { 'Content-Type': type, 'Content-Length': Buffer.byteLength(body) });
  res.end(body);
}

function readBody(req) {
  // JSON.parse must not throw inside the 'end' handler — an uncaught exception there would kill
  // the whole Node process. Malformed bodies reject and surface as a 500 from the route handler.
  return new Promise((resolve, reject) => {
    let data = '';
    req.on('data', (c) => (data += c));
    req.on('end', () => {
      try { resolve(data ? JSON.parse(data) : {}); } catch (e) { reject(new Error('malformed JSON body')); }
    });
  });
}

// Run npm in-process. Captures console output and traps process.exit so a single install doesn't
// tear down the host. Returns { code, output }.
async function runNpm(argv, cwd) {
  const realExit = process.exit;
  const realArgv = process.argv;
  const realCwd = process.cwd();
  let captured = '';
  const origOut = process.stdout.write.bind(process.stdout);
  const origErr = process.stderr.write.bind(process.stderr);
  const cap = (chunk) => { captured += chunk; return true; };

  let exitCode = 0;
  try {
    if (cwd) process.chdir(cwd);
    process.stdout.write = cap;
    process.stderr.write = cap;
    process.argv = [process.execPath, NPM_CLI, ...argv];
    process.exit = (c) => { exitCode = c || 0; throw { __orionExit: true, code: exitCode }; };
    // npm's CLI bootstraps from its bin script; requiring lib/cli.js and invoking it runs the command.
    const npmEntry = path.join(path.dirname(NPM_CLI), '..', 'lib', 'cli.js');
    const cli = require(npmEntry);
    await cli(process);
  } catch (e) {
    if (e && e.__orionExit) exitCode = e.code;
    else { captured += '\n' + (e && e.stack ? e.stack : String(e)); exitCode = 1; }
  } finally {
    process.stdout.write = origOut;
    process.stderr.write = origErr;
    process.exit = realExit;
    process.argv = realArgv;
    try { process.chdir(realCwd); } catch (_) {}
  }
  return { code: exitCode, output: captured };
}

// ---- transform (Babel standalone, lazy) ------------------------------------

let _babel = null;
function babel() {
  if (!_babel) _babel = require(path.join(HOST_DIR, 'babel', 'babel.min.js'));
  return _babel;
}

function transformCode(code, filename) {
  const ext = path.extname(filename).toLowerCase();
  const presets = [];
  if (ext === '.ts' || ext === '.tsx' || ext === '.mts' || ext === '.cts') {
    presets.push(['typescript', {
      isTSX: ext === '.tsx',
      allExtensions: true,
      allowDeclareFields: true,
      onlyRemoveTypeImports: true,
    }]);
  }
  // Automatic JSX runtime → `import { jsx } from "react/jsx-runtime"` (resolved via the import map).
  presets.push(['react', { runtime: 'automatic', development: true }]);
  const out = babel().transform(code, {
    filename,
    presets,
    sourceType: 'module',
    sourceMaps: 'inline',
    compact: false,
  });
  return out.code;
}

// A CSS import (`import './x.css'`) is served as a JS module that injects a <style>, Vite-style.
function cssModule(cssText, key) {
  const id = 'orion:' + key;
  return [
    `const css = ${JSON.stringify(cssText)};`,
    `const id = ${JSON.stringify(id)};`,
    `let el = document.querySelector('style[data-orion="' + id + '"]');`,
    `if (!el) { el = document.createElement('style'); el.setAttribute('data-orion', id); document.head.appendChild(el); }`,
    `el.textContent = css;`,
    `export default css;`,
  ].join('\n');
}

// Build an import map from package.json deps → esm.sh, so bare specifiers resolve in the browser.
function buildImportMap(root) {
  const imports = {};
  const SKIP = new Set(['typescript', 'vite', '@vitejs/plugin-react', 'esbuild']);
  try {
    const pkg = JSON.parse(fs.readFileSync(path.join(root, 'package.json'), 'utf8'));
    const deps = Object.assign({}, pkg.dependencies, pkg.devDependencies);
    for (const [name, range] of Object.entries(deps)) {
      if (name.startsWith('@types/') || SKIP.has(name)) continue;
      const ver = String(range).replace(/^[^0-9]*/, '') || 'latest';
      imports[name] = `https://esm.sh/${name}@${ver}`;
      imports[name + '/'] = `https://esm.sh/${name}@${ver}/`;
    }
  } catch (_) { /* no package.json → no import map */ }
  return { imports };
}

// Injected into served HTML: import map + error overlay + live-reload client.
function injectedHead(root) {
  const importMap = JSON.stringify(buildImportMap(root));
  return [
    `<script type="importmap">${importMap}</script>`,
    `<script>(function(){`,
    `function overlay(msg){var d=document.getElementById('__orion_err')||document.createElement('div');`,
    `d.id='__orion_err';d.style.cssText='position:fixed;inset:0;z-index:99999;background:#1a0000;color:#ff8080;`,
    `font:13px/1.5 monospace;padding:16px;white-space:pre-wrap;overflow:auto';d.textContent=String(msg);`,
    `document.body&&document.body.appendChild(d);}`,
    `window.addEventListener('error',function(e){overlay((e.error&&e.error.stack)||e.message);});`,
    `window.addEventListener('unhandledrejection',function(e){overlay('Unhandled: '+((e.reason&&e.reason.stack)||e.reason));});`,
    `try{var es=new EventSource('/__orion/sse');es.onmessage=function(){location.reload();};}catch(_){}`,
    `})();</script>`,
  ].join('');
}

function injectHtml(html, root) {
  const head = injectedHead(root);
  if (html.includes('</head>')) return html.replace('</head>', head + '</head>');
  return head + html;
}

// ---- dev server ------------------------------------------------------------

const MIME = {
  '.html': 'text/html', '.js': 'text/javascript', '.mjs': 'text/javascript',
  '.json': 'application/json', '.svg': 'image/svg+xml', '.png': 'image/png',
  '.jpg': 'image/jpeg', '.jpeg': 'image/jpeg', '.gif': 'image/gif', '.ico': 'image/x-icon',
  '.webp': 'image/webp', '.woff': 'font/woff', '.woff2': 'font/woff2', '.ttf': 'font/ttf',
  '.map': 'application/json', '.wasm': 'application/wasm', '.txt': 'text/plain',
};
const SCRIPT_EXT = ['.tsx', '.ts', '.jsx', '.js', '.mjs', '.cjs', '.mts', '.cts'];
const RESOLVE_EXT = ['.tsx', '.ts', '.jsx', '.js', '.mjs'];

const devServers = new Map();      // port -> { server, root, watcher }
const sseClients = new Map();      // root -> Set<res>
const devLogs = new Map();         // port -> string[]

function pushDevLog(port, line) {
  if (!port) return;
  const rows = devLogs.get(port) || [];
  rows.push(`[${new Date().toLocaleTimeString()}] ${String(line).slice(0, 1200)}`);
  while (rows.length > 300) rows.shift();
  devLogs.set(port, rows);
}

// Resolve a request path to an on-disk file, trying extension/index variants (extensionless imports).
function resolveFile(file) {
  try { if (fs.statSync(file).isFile()) return file; } catch (_) {}
  for (const c of RESOLVE_EXT) { if (fs.existsSync(file + c)) return file + c; }
  try {
    if (fs.statSync(file).isDirectory()) {
      for (const c of RESOLVE_EXT) {
        const idx = path.join(file, 'index' + c);
        if (fs.existsSync(idx)) return idx;
      }
    }
  } catch (_) {}
  return null;
}

function handleSse(root, res) {
  res.writeHead(200, { 'Content-Type': 'text/event-stream', 'Cache-Control': 'no-cache', 'Connection': 'keep-alive' });
  res.write('retry: 1000\n\n');
  let set = sseClients.get(root);
  if (!set) { set = new Set(); sseClients.set(root, set); }
  set.add(res);
  req_on_close(res, () => set.delete(res));
}
function req_on_close(res, cb) { res.on('close', cb); res.on('finish', cb); }

// Cheap change detector: poll a signature (paths + mtimes) and notify SSE clients to reload.
function signature(root) {
  let s = '';
  const stack = [root];
  let n = 0;
  while (stack.length && n < 2000) {
    const d = stack.pop();
    let entries;
    try { entries = fs.readdirSync(d, { withFileTypes: true }); } catch (_) { continue; }
    for (const e of entries) {
      if (e.name === 'node_modules' || e.name === '.git' || e.name === '.orion') continue;
      const fp = path.join(d, e.name);
      if (e.isDirectory()) stack.push(fp);
      else { try { const st = fs.statSync(fp); s += fp + ':' + st.mtimeMs + ';'; n++; } catch (_) {} }
    }
  }
  return s;
}

function startWatcher(root) {
  let last = signature(root);
  return setInterval(() => {
    const sig = signature(root);
    if (sig !== last) {
      last = sig;
      const set = sseClients.get(root);
      if (set) for (const r of set) { try { r.write('data: reload\n\n'); } catch (_) {} }
    }
  }, 800);
}

function makeHandler(root) {
  const resolvedRoot = path.resolve(root);
  const hasPkg = fs.existsSync(path.join(root, 'package.json'));
  return (req, res) => {
    try {
      const port = req.socket && req.socket.localPort;
      const u = new URL(req.url, 'http://127.0.0.1');
      let rel = decodeURIComponent(u.pathname);
      if (rel === '/__orion/sse') return handleSse(root, res);
      if (rel === '/' || rel === '') rel = '/index.html';
      pushDevLog(port, `${req.method} ${rel}`);

      let file = path.join(root, rel);
      if (!path.resolve(file).startsWith(resolvedRoot)) { pushDevLog(port, `403 ${rel}`); res.writeHead(403); res.end('forbidden'); return; }

      let target = resolveFile(file);
      if (!target) {
        // SPA fallback for navigations; 404 for asset/module fetches.
        const accept = req.headers.accept || '';
        if (accept.includes('text/html')) target = path.join(root, 'index.html');
        if (!target || !fs.existsSync(target)) { pushDevLog(port, `404 ${rel}`); res.writeHead(404); res.end('not found'); return; }
      }

      const ext = path.extname(target).toLowerCase();

      if (ext === '.html') {
        let html = fs.readFileSync(target, 'utf8');
        if (hasPkg) html = injectHtml(html, root);
        send(res, 200, 'text/html', html);
        return;
      }
      if (SCRIPT_EXT.includes(ext)) {
        const src = fs.readFileSync(target, 'utf8');
        let js;
        try { js = transformCode(src, target); }
        catch (e) {
          const msg = 'Transform failed in ' + path.relative(root, target) + ': ' + (e && e.message ? e.message : e);
          pushDevLog(port, msg);
          js = `console.error(${JSON.stringify(msg)});\nthrow new Error(${JSON.stringify(msg)});`;
        }
        send(res, 200, 'text/javascript', js);
        return;
      }
      if (ext === '.css') {
        const css = fs.readFileSync(target, 'utf8');
        if (u.searchParams.has('raw')) send(res, 200, 'text/css', css);
        else send(res, 200, 'text/javascript', cssModule(css, rel));  // imported from JS → injector
        return;
      }
      res.writeHead(200, { 'Content-Type': MIME[ext] || 'application/octet-stream' });
      fs.createReadStream(target).pipe(res);
    } catch (e) {
      try { pushDevLog(req.socket && req.socket.localPort, `500 ${String(e && e.stack ? e.stack : e)}`); } catch (_) {}
      res.writeHead(500); res.end(String(e));
    }
  };
}

function startDevServer(root, port) {
  return new Promise((resolve, reject) => {
    const server = http.createServer(makeHandler(root));
    server.on('error', reject);
    server.listen(port, '127.0.0.1', () => {
      const actual = server.address().port;
      devServers.set(actual, { server, root, watcher: startWatcher(root) });
      pushDevLog(actual, `dev server started for ${root}`);
      resolve(actual);
    });
  });
}

function stopDevServer(port) {
  const entry = devServers.get(port);
  if (entry) {
    try { clearInterval(entry.watcher); } catch (_) {}
    try { entry.server.close(); } catch (_) {}
    devServers.delete(port);
    pushDevLog(port, 'dev server stopped');
    return true;
  }
  return false;
}

// ---- control server --------------------------------------------------------

const routes = {
  '/health': async () => ({ ok: true, node: process.version }),

  '/exec': async (body) => {
    const argv = body.argv || [];
    const cwd = body.cwd || process.cwd();
    if (argv[0] === 'npm') return await runNpm(argv.slice(1), cwd);
    // npx <pkg> … → npm exec (runs the locally-installed bin, e.g. `npx tsc --noEmit`).
    if (argv[0] === 'npx') return await runNpm(['exec', '--yes', '--', ...argv.slice(1)], cwd);
    if (argv[0] === 'node' && argv[1] === '-e') {
      let out = '';
      const o = process.stdout.write.bind(process.stdout);
      process.stdout.write = (c) => { out += c; return true; };
      let code = 0;
      try { eval(argv.slice(2).join(' ')); } catch (e) { out += String(e); code = 1; }
      process.stdout.write = o;
      return { code, output: out };
    }
    return { code: 127, output: `unsupported command: ${argv.join(' ')} (only npm/node -e run in-process)` };
  },

  '/devserver/start': async (body) => {
    const port = await startDevServer(body.dir, body.port || 0);
    return { port };
  },
  '/devserver/stop': async (body) => ({ stopped: stopDevServer(body.port) }),
  '/devserver/logs': async (body) => ({ logs: (devLogs.get(body.port) || []).join('\n') }),

  '/fs/read': async (body) => ({ content: fs.readFileSync(body.path, 'utf8') }),
  '/fs/write': async (body) => { fs.mkdirSync(path.dirname(body.path), { recursive: true }); fs.writeFileSync(body.path, body.content); return { ok: true }; },

  // P2P bridge runs IN-PROCESS (nodejs-mobile can't spawn a second node). The bridge opens its own
  // control server on body.controlPort; Kotlin talks to that port directly after start succeeds.
  '/p2p/start': async (body) => {
    if (!_p2pBridge) _p2pBridge = require(path.join(HOST_DIR, 'p2p-bridge.js'));
    _p2pInfo = await _p2pBridge.startBridge(body);
    return { ok: true, ..._p2pInfo };
  },
  '/p2p/stop': async () => {
    if (_p2pBridge) { try { await _p2pBridge.stopBridge(); } catch (_) {} }
    _p2pInfo = null;
    return { ok: true };
  },
};

const control = http.createServer(async (req, res) => {
  const u = new URL(req.url, 'http://127.0.0.1');
  const handler = routes[u.pathname];
  if (!handler) return sendJson(res, 404, { error: 'no such route' });
  try {
    const body = req.method === 'POST' ? await readBody(req) : {};
    const result = await handler(body);
    sendJson(res, 200, result);
  } catch (e) {
    sendJson(res, 500, { error: String(e && e.stack ? e.stack : e) });
  }
});

control.listen(CONTROL_PORT, '127.0.0.1', () => {
  console.log(`orion-host listening on 127.0.0.1:${CONTROL_PORT}`);
});
