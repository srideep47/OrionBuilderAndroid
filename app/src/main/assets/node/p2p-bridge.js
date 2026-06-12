'use strict';
/*
 * Orion P2P bridge — runs inside the embedded nodejs-mobile (:node) process and speaks the EXACT
 * wire protocol of the desktop Orion Builder so a desktop peer cannot tell phone from PC:
 *   - Hyperswarm DHT topic = sha256("orionbuilder-v1"); Noise-encrypted connections (free via HS).
 *   - Framed JSON channel: [uint32 BE length][JSON utf8 payload]  (matches peer-channel.ts).
 *   - LAN UDP beacons on :38291, magic "ORION-LAN-V1", 15s cadence (matches lan-discovery.ts).
 *   - HELLO / METADATA / PING-PONG / LOAD_UPDATE / FRIEND_* / INFERENCE_* / MEDIA_* / BUILD_*
 *     messages, byte-for-byte.
 *
 * Transport modes:
 *   - 'full': hyperswarm DHT + Noise (needs the sodium-native/udx-native arm64 addons).
 *   - 'lan' : pure-JS fallback — UDP beacons (same format, plus an additive `tcpPort` field that
 *     old peers ignore) + direct TCP framed-JSON channels with an ed25519 challenge-response
 *     handshake (Node built-in crypto). Used on nodejs-mobile, where process.dlopen of ANY .node
 *     addon exit(1)s the process (verified against the official v18.20.4 binary), so the natives
 *     must never even be require()d there. LAN channels are authenticated but not encrypted —
 *     LAN-scoped interim until the addon loading is fixed (then mode upgrades to 'full' as-is).
 *
 * Identity is injected from Kotlin (Keystore-wrapped 32-byte ed25519 seed) so it survives Node
 * restarts. Kotlin drives the bridge over a localhost control API and consumes events via SSE.
 *
 * Args: p2p-bridge.js <configJsonPath>
 *   config = { controlPort, seedHex, displayName, deviceName, deviceType, hardware, appVersion,
 *              lanPort?, useNatives? }
 */

const http = require('http');
const net = require('net');
const dgram = require('dgram');
const os = require('os');
const crypto = require('crypto');
const fs = require('fs');
const { EventEmitter } = require('events');

// ── config / identity ───────────────────────────────────────────────────────
// nodejs-mobile can't spawn a second `node`, so the bridge is loaded IN-PROCESS by orion-host.js
// via startBridge(config). A standalone shim at the bottom keeps `node p2p-bridge.js <cfg>` working
// for desktop tests. CONFIG/keyPair/SELF_PUB_HEX are assigned by startBridge before any peer I/O.

const LAN_MAGIC = 'ORION-LAN-V1';
const LAN_AUTH_CONTEXT = 'orion-lan-auth-v1';
const KEEPALIVE_INTERVAL_MS = 10_000;
const KEEPALIVE_TIMEOUT_MS = 25_000;
const LAN_AUTH_TIMEOUT_MS = 10_000;

// RFC 8410 DER prefixes for wrapping raw ed25519 keys into PKCS#8 / SPKI for node:crypto.
const ED25519_PKCS8_PREFIX = Buffer.from('302e020100300506032b657004220420', 'hex');
const ED25519_SPKI_PREFIX = Buffer.from('302a300506032b6570032100', 'hex');

let CONFIG = null;
let LAN_PORT = 38291;
let USE_NATIVES = false;
let keyPair = null;       // { publicKey: Buffer(32), secretKey: Buffer(64), privObj: KeyObject }
let SELF_PUB_HEX = '';

// Pure node:crypto ed25519 — works on nodejs-mobile where native addons cannot load. The
// sodium-style 64-byte secretKey is just seed‖publicKey, so hyperswarm (full mode) gets exactly
// the keypair sodium would have produced for the same seed.
function deriveKeypair(seedHex) {
  const seed = Buffer.from(seedHex, 'hex');
  if (seed.length !== 32) throw new Error('seed must be 32 bytes');
  const privObj = crypto.createPrivateKey({
    key: Buffer.concat([ED25519_PKCS8_PREFIX, seed]),
    format: 'der',
    type: 'pkcs8',
  });
  const spki = crypto.createPublicKey(privObj).export({ format: 'der', type: 'spki' });
  const publicKey = Buffer.from(spki.subarray(spki.length - 32));
  return { publicKey, secretKey: Buffer.concat([seed, publicKey]), privObj };
}

function rawPubToKeyObject(rawPubHex) {
  return crypto.createPublicKey({
    key: Buffer.concat([ED25519_SPKI_PREFIX, Buffer.from(rawPubHex, 'hex')]),
    format: 'der',
    type: 'spki',
  });
}

function signAuth(remoteNonceHex) {
  const data = Buffer.from(LAN_AUTH_CONTEXT + remoteNonceHex + SELF_PUB_HEX, 'utf-8');
  return crypto.sign(null, data, keyPair.privObj).toString('hex');
}

function verifyAuth(selfNonceHex, remotePubHex, signatureHex) {
  try {
    const data = Buffer.from(LAN_AUTH_CONTEXT + selfNonceHex + remotePubHex, 'utf-8');
    return crypto.verify(null, data, rawPubToKeyObject(remotePubHex), Buffer.from(signatureHex, 'hex'));
  } catch {
    return false;
  }
}

// ── event bus → SSE clients (bridge → Kotlin) ───────────────────────────────

const bus = new EventEmitter();
function emit(event, data) { bus.emit('event', { event, data }); }

// ── framed JSON peer channel (matches peer-channel.ts) ──────────────────────

class PeerChannel extends EventEmitter {
  constructor(socket) {
    super();
    this.socket = socket;
    this.buf = Buffer.alloc(0);
    this.closed = false;
    socket.on('data', (c) => this._onData(c));
    socket.on('error', (e) => this.emit('error', e));
    socket.on('close', () => this._onClose());
    socket.on('end', () => this._onClose());
  }
  send(msg) {
    if (this.closed) return false;
    try {
      const json = Buffer.from(JSON.stringify(msg), 'utf-8');
      const header = Buffer.allocUnsafe(4);
      header.writeUInt32BE(json.length, 0);
      this.socket.write(Buffer.concat([header, json]));
      return true;
    } catch { this._onClose(); return false; }
  }
  isClosed() { return this.closed; }
  close() {
    if (this.closed) return;
    try { this.socket.end?.(); this.socket.destroy?.(); } catch {}
    this._onClose();
  }
  _onClose() { if (this.closed) return; this.closed = true; this.emit('close'); }
  _onData(chunk) {
    this.buf = Buffer.concat([this.buf, chunk]);
    while (this.buf.length >= 4) {
      const len = this.buf.readUInt32BE(0);
      if (this.buf.length < 4 + len) break;
      const raw = this.buf.slice(4, 4 + len).toString('utf-8');
      this.buf = this.buf.slice(4 + len);
      try { this.emit('message', JSON.parse(raw)); } catch { /* malformed — ignore */ }
    }
  }
}

// ── LAN discovery (matches lan-discovery.ts; beacon gains additive tcpPort) ─

class LanDiscovery extends EventEmitter {
  constructor() { super(); this.socket = null; this.peers = new Map(); this.timer = null; }
  start() {
    const socket = dgram.createSocket({ type: 'udp4', reuseAddr: true });
    socket.on('error', () => {});
    socket.on('message', (buf, rinfo) => this._onMessage(buf, rinfo));
    socket.bind(LAN_PORT, () => {
      try { socket.setBroadcast(true); } catch {}
      this.socket = socket;
      this.timer = setInterval(() => this._broadcast(), 15_000);
      this._broadcast();
    });
  }
  stop() { if (this.timer) clearInterval(this.timer); try { this.socket?.close(); } catch {} this.socket = null; this.peers.clear(); }
  getAddress(pub) { return this.peers.get(pub)?.address ?? null; }
  getPeer(pub) { return this.peers.get(pub) ?? null; }
  _broadcast() {
    if (!this.socket) return;
    const beacon = {
      magic: LAN_MAGIC,
      publicKey: SELF_PUB_HEX,
      displayName: CONFIG.displayName,
      deviceName: CONFIG.deviceName,
      appVersion: CONFIG.appVersion || '',
      timestamp: Date.now(),
      tcpPort: TCP_PORT || undefined, // additive: lets LAN-mode peers direct-connect; others ignore
    };
    const payload = Buffer.from(JSON.stringify(beacon), 'utf-8');
    const targets = new Set(['255.255.255.255']);
    for (const t of this._subnetBroadcasts()) targets.add(t);
    for (const t of targets) this.socket.send(payload, 0, payload.length, LAN_PORT, t, () => {});
  }
  *_subnetBroadcasts() {
    for (const list of Object.values(os.networkInterfaces())) {
      for (const iface of list || []) {
        if (iface.family !== 'IPv4' || iface.internal) continue;
        const a = iface.address.split('.').map(Number);
        const m = iface.netmask.split('.').map(Number);
        if (a.length === 4 && m.length === 4) yield a.map((x, i) => (x | (~m[i] & 0xff)) & 0xff).join('.');
      }
    }
  }
  _onMessage(buf, rinfo) {
    let b;
    try { b = JSON.parse(buf.toString('utf-8')); } catch { return; }
    if (b.magic !== LAN_MAGIC || b.publicKey === SELF_PUB_HEX) return;
    if (typeof b.publicKey !== 'string' || b.publicKey.length < 16 || b.publicKey.length > 128) return;
    this.peers.set(b.publicKey, {
      publicKey: b.publicKey, displayName: b.displayName, deviceName: b.deviceName,
      address: rinfo.address, tcpPort: typeof b.tcpPort === 'number' ? b.tcpPort : null,
      lastSeenAt: Date.now(),
    });
    this.emit('peer-seen', this.peers.get(b.publicKey));
  }
}

// ── swarm / connections ─────────────────────────────────────────────────────

const connected = new Map();   // remotePubHex -> { channel, peer, lastSeenAt, pings, metadataReceived }
const trusted = new Set();     // trusted peer pubkey hex (injected from Kotlin)
const lanJoined = new Set();
const dialing = new Set();     // remotePubHex with an in-flight TCP dial
let compute = { gpuUtilization: 0, loadedModels: [], computeAvailable: false, queueDepth: 0 };

const lan = new LanDiscovery();
let swarm = null;
let mainTopic = null;
let pingCounter = 1;
let control = null;
let keepaliveTimer = null;
let tcpServer = null;
let TCP_PORT = 0;
let MODE = 'lan';              // 'full' | 'lan'
let NATIVE_ERROR = null;

function selfMetadata() {
  return {
    publicKey: SELF_PUB_HEX,
    displayName: CONFIG.displayName,
    deviceName: CONFIG.deviceName,
    deviceType: CONFIG.deviceType || 'laptop',
    hardware: CONFIG.hardware || null,
    loadedModels: compute.loadedModels,
    gpuUtilization: compute.gpuUtilization,
    computeAvailable: compute.computeAvailable,
    appVersion: CONFIG.appVersion || '',
  };
}

function peerList() {
  return Array.from(connected.values()).map((e) => e.peer);
}

async function startNetworking() {
  startTcpListener();

  if (USE_NATIVES) {
    try {
      // Lazily required: on nodejs-mobile these MUST never be require()d (silent exit(1)).
      const Hyperswarm = require('hyperswarm');
      swarm = new Hyperswarm({ keyPair: { publicKey: keyPair.publicKey, secretKey: keyPair.secretKey } });
      swarm.on('connection', (socket) => handleSwarmConnection(socket));
      mainTopic = crypto.createHash('sha256').update('orionbuilder-v1').digest();
      swarm.join(mainTopic, { server: true, client: true });
      await swarm.flush().catch(() => {});
      MODE = 'full';
    } catch (e) {
      NATIVE_ERROR = String(e && e.message ? e.message : e);
      console.error('hyperswarm unavailable, falling back to LAN-only:', NATIVE_ERROR);
      swarm = null;
      MODE = 'lan';
    }
  } else {
    MODE = 'lan';
  }

  lan.on('peer-seen', (p) => {
    if (swarm) joinLanRendezvous(p);
    maybeDialTcp(p);
  });
  lan.start();

  keepaliveTimer = setInterval(keepalivePass, KEEPALIVE_INTERVAL_MS);
  emit('status', { isOnline: true, self: SELF_PUB_HEX, mode: MODE, nativeError: NATIVE_ERROR });
}

function joinLanRendezvous(lanPeer) {
  if (!swarm || lanJoined.has(lanPeer.publicKey)) return;
  const [a, b] = [SELF_PUB_HEX, lanPeer.publicKey].sort();
  const topic = crypto.createHash('sha256').update('orion-lan-pair-').update(a).update(b).digest();
  lanJoined.add(lanPeer.publicKey);
  swarm.join(topic, { server: true, client: true });
  swarm.flush().catch(() => {});
}

function handleSwarmConnection(socket) {
  const remoteHex = Buffer.from(socket.remotePublicKey).toString('hex');
  attachChannel(new PeerChannel(socket), remoteHex);
}

// ── LAN TCP transport (pure JS): listener + authenticated dial ──────────────
// Both sides exchange LAN_AUTH_HELLO {publicKey, nonce}, then prove key ownership with
// LAN_AUTH_PROOF {signature over context‖remoteNonce‖selfPub}. Only after mutual verification does
// the channel join the normal HELLO/METADATA flow, so a LAN peer cannot spoof another's identity.

function startTcpListener() {
  tcpServer = net.createServer((sock) => {
    sock.setNoDelay(true);
    authenticate(new PeerChannel(sock));
  });
  tcpServer.on('error', (e) => console.error('tcp listener error:', e.message));
  tcpServer.listen(0, '0.0.0.0', () => { TCP_PORT = tcpServer.address().port; });
}

function maybeDialTcp(lanPeer) {
  if (!lanPeer.tcpPort) return;                       // peer has no TCP fallback (e.g. desktop DHT-only)
  if (SELF_PUB_HEX >= lanPeer.publicKey) return;      // deterministic direction: smaller key dials
  const existing = connected.get(lanPeer.publicKey);
  if (existing && !existing.channel.isClosed()) return;
  if (dialing.has(lanPeer.publicKey)) return;

  dialing.add(lanPeer.publicKey);
  const sock = net.connect({ host: lanPeer.address, port: lanPeer.tcpPort }, () => sock.setNoDelay(true));
  const channel = new PeerChannel(sock);
  const done = () => dialing.delete(lanPeer.publicKey);
  channel.on('close', done);
  authenticate(channel, lanPeer.publicKey).then(done, done);
}

function authenticate(channel, expectedHex = null) {
  return new Promise((resolve) => {
    const nonce = crypto.randomBytes(16).toString('hex');
    let remoteHello = null;
    let verified = false;
    const timeout = setTimeout(() => { channel.close(); resolve(false); }, LAN_AUTH_TIMEOUT_MS);

    const finish = (ok, remoteHex) => {
      clearTimeout(timeout);
      channel.removeListener('message', onMessage);
      if (ok) {
        attachChannel(channel, remoteHex, { reuse: true });
        resolve(true);
      } else {
        channel.close();
        resolve(false);
      }
    };

    const onMessage = (msg) => {
      if (msg.type === 'LAN_AUTH_HELLO') {
        if (typeof msg.publicKey !== 'string' || typeof msg.nonce !== 'string') return finish(false);
        if (expectedHex && msg.publicKey !== expectedHex) return finish(false);
        if (msg.publicKey === SELF_PUB_HEX) return finish(false);
        remoteHello = msg;
        channel.send({ type: 'LAN_AUTH_PROOF', signature: signAuth(msg.nonce) });
        return;
      }
      if (msg.type === 'LAN_AUTH_PROOF') {
        if (!remoteHello) return finish(false);
        verified = verifyAuth(nonce, remoteHello.publicKey, msg.signature);
        return finish(verified, remoteHello.publicKey);
      }
      // Any non-auth message before the handshake completes is a protocol violation.
      if (!verified) return finish(false);
    };

    channel.on('message', onMessage);
    channel.once('close', () => { clearTimeout(timeout); resolve(false); });
    channel.send({ type: 'LAN_AUTH_HELLO', publicKey: SELF_PUB_HEX, nonce });
  });
}

// ── shared channel lifecycle (both transports converge here) ────────────────

function attachChannel(channel, remoteHex, opts = {}) {
  const existing = connected.get(remoteHex);
  if (existing && !existing.channel.isClosed()) { try { channel.close(); } catch {} return; }

  connected.set(remoteHex, { channel, peer: placeholderPeer(remoteHex), lastSeenAt: Date.now(), pings: new Map(), metadataReceived: false });

  channel.send({ type: 'HELLO', publicKey: SELF_PUB_HEX, displayName: CONFIG.displayName });
  channel.send({ type: 'METADATA', payload: selfMetadata() });
  channel.send({ type: 'REQUEST_LOAD' });

  channel.on('error', () => {});
  channel.on('message', (msg) => {
    const e = connected.get(remoteHex);
    if (e) e.lastSeenAt = Date.now();
    handleMessage(remoteHex, channel, msg);
  });
  channel.on('close', () => {
    const e = connected.get(remoteHex);
    if (e?.channel !== channel) return;
    connected.delete(remoteHex);
    emit('peers-changed', peerList());
  });
}

function placeholderPeer(hex) {
  return {
    publicKey: hex, fingerprint: hex.slice(0, 16).toUpperCase(),
    displayName: trusted.has(hex) ? hex.slice(0, 8) : `Unknown ${hex.slice(0, 6)}`,
    deviceName: '—', deviceType: 'desktop', hardware: null, status: 'connecting',
    isTrusted: trusted.has(hex), isLan: lan.getAddress(hex) !== null, latencyMs: null,
    loadedModels: [], gpuUtilization: 0, computeAvailable: false, appVersion: '',
  };
}

function handleMessage(remoteHex, channel, msg) {
  switch (msg.type) {
    case 'LAN_AUTH_HELLO': case 'LAN_AUTH_PROOF': return; // late auth retransmits — ignore
    case 'PING': channel.send({ type: 'PONG', nonce: msg.nonce }); return;
    case 'PONG': {
      const e = connected.get(remoteHex); if (!e) return;
      const sentAt = e.pings.get(msg.nonce);
      if (sentAt !== undefined) { e.peer.latencyMs = Date.now() - sentAt; e.pings.delete(msg.nonce); e.peer.status = 'online'; emit('peers-changed', peerList()); }
      return;
    }
    case 'HELLO': return;
    case 'METADATA': {
      const p = msg.payload;
      if (!p || p.publicKey !== remoteHex) return; // metadata must match the authenticated identity
      const e = connected.get(remoteHex);
      const wasNew = !e || !e.metadataReceived;
      const peer = {
        publicKey: p.publicKey, fingerprint: p.publicKey.slice(0, 16).toUpperCase(),
        displayName: p.displayName, deviceName: p.deviceName, deviceType: p.deviceType,
        hardware: p.hardware, status: 'online', isTrusted: trusted.has(p.publicKey),
        isLan: lan.getAddress(p.publicKey) !== null, latencyMs: e?.peer.latencyMs ?? null,
        loadedModels: p.loadedModels, gpuUtilization: p.gpuUtilization,
        computeAvailable: p.computeAvailable, appVersion: p.appVersion,
      };
      connected.set(remoteHex, { channel, peer, lastSeenAt: Date.now(), pings: e?.pings ?? new Map(), metadataReceived: true });
      emit('peers-changed', peerList());
      if (wasNew) emit('peer-online', peer);
      return;
    }
    case 'LOAD_UPDATE': {
      const e = connected.get(remoteHex); if (!e) return;
      e.peer.gpuUtilization = msg.gpuUtilization; e.peer.loadedModels = msg.loadedModels; e.peer.computeAvailable = msg.computeAvailable;
      emit('peers-changed', peerList());
      return;
    }
    case 'REQUEST_LOAD': channel.send({ type: 'LOAD_UPDATE', ...compute }); return;
    case 'FRIEND_REQUEST':
      emit('friend-request', { fromPublicKey: msg.fromPublicKey, fromDisplayName: msg.fromDisplayName, fromDeviceName: msg.fromDeviceName });
      return;
    case 'FRIEND_ACCEPT':
      trusted.add(msg.fromPublicKey);
      { const e = connected.get(msg.fromPublicKey); if (e) { e.peer.isTrusted = true; emit('peers-changed', peerList()); } }
      emit('friend-accepted', { publicKey: msg.fromPublicKey });
      return;
    // Inbound inference: a peer wants US to run on our model. Gate on trust, hand to Kotlin.
    case 'INFERENCE_REQUEST':
      if (!trusted.has(remoteHex)) { channel.send({ type: 'INFERENCE_ERROR', requestId: msg.requestId, error: 'Peer is not in your trusted list' }); return; }
      emit('inference-request', { peerId: remoteHex, requestId: msg.requestId, body: msg.body });
      return;
    case 'INFERENCE_CANCEL': emit('inference-cancel', { peerId: remoteHex, requestId: msg.requestId }); return;
    // Outbound inference replies (we asked a peer): relay chunks to Kotlin via the pending handler.
    case 'INFERENCE_CHUNK': emit('inference-chunk', { requestId: msg.requestId, data: msg.data }); return;
    case 'INFERENCE_DONE': emit('inference-done', { requestId: msg.requestId }); return;
    case 'INFERENCE_ERROR': emit('inference-error', { requestId: msg.requestId, error: msg.error }); return;
    // Media job queue + sharing — matches desktop peer-channel.ts exactly. Jobs: flat
    // MEDIA_JOB_SUBMIT fields, MEDIA_JOB_STATUS lifecycle. Result files are pulled separately
    // via MEDIA_DOWNLOAD_REQUEST/CHUNK using the fileNames carried by a "done" status.
    case 'MEDIA_JOB_SUBMIT':
      if (!trusted.has(remoteHex)) { channel.send({ type: 'MEDIA_JOB_STATUS', jobId: msg.jobId, status: 'failed', error: 'Peer is not in your trusted list' }); return; }
      emit('media-submit', { peerId: remoteHex, jobId: msg.jobId, kind: msg.kind, prompt: msg.prompt, audioPrompt: msg.audioPrompt, audioKind: msg.audioKind, aspectRatio: msg.aspectRatio, durationSec: msg.durationSec });
      return;
    case 'MEDIA_JOB_STATUS':
      emit('media-status', { peerId: remoteHex, jobId: msg.jobId, status: msg.status, stage: msg.stage || '', error: msg.error || null, fileNames: msg.fileNames || [] });
      return;
    case 'MEDIA_ANNOUNCE': emit('media-announce', { peerId: remoteHex, items: msg.items || [] }); return;
    case 'MEDIA_LIST_REQUEST': emit('media-list-request', { peerId: remoteHex }); return;
    case 'MEDIA_DOWNLOAD_REQUEST':
      if (!trusted.has(remoteHex)) { channel.send({ type: 'MEDIA_DOWNLOAD_ERROR', requestId: msg.requestId, error: 'Peer is not in your trusted list' }); return; }
      emit('media-download-request', { peerId: remoteHex, requestId: msg.requestId, fileName: msg.fileName });
      return;
    case 'MEDIA_DOWNLOAD_CHUNK': emit('media-download-chunk', { peerId: remoteHex, requestId: msg.requestId, data: msg.data, eof: !!msg.eof }); return;
    case 'MEDIA_DOWNLOAD_ERROR': emit('media-download-error', { peerId: remoteHex, requestId: msg.requestId, error: msg.error }); return;
    // Build offload (desktop compute schema): job submitted, log lines stream back, then artifact.
    case 'BUILD_JOB_SUBMIT':
      if (!trusted.has(remoteHex)) { channel.send({ type: 'BUILD_JOB_RESULT', jobId: msg.jobId, ok: false, error: 'Peer is not in your trusted list' }); return; }
      emit('build-submit', { peerId: remoteHex, jobId: msg.jobId, job: msg.job, bundleBase64: msg.bundleBase64 });
      return;
    case 'BUILD_JOB_CANCEL': emit('build-cancel', { peerId: remoteHex, jobId: msg.jobId }); return;
    case 'BUILD_JOB_ACCEPTED': emit('build-accepted', { peerId: remoteHex, jobId: msg.jobId }); return;
    case 'BUILD_JOB_LOG': emit('build-log', { peerId: remoteHex, jobId: msg.jobId, line: msg.line }); return;
    case 'BUILD_JOB_RESULT': emit('build-result', { peerId: remoteHex, jobId: msg.jobId, ok: msg.ok, artifactName: msg.artifactName, artifactBase64: msg.artifactBase64, error: msg.error }); return;
    default: return;
  }
}

function keepalivePass() {
  const now = Date.now();
  for (const [key, e] of connected) {
    if (e.channel.isClosed()) continue;
    if (now - e.lastSeenAt > KEEPALIVE_TIMEOUT_MS) { e.channel.close(); continue; }
    const nonce = pingCounter++;
    e.pings.set(nonce, now);
    for (const [n, t] of e.pings) if (now - t > 30_000) e.pings.delete(n);
    e.channel.send({ type: 'PING', nonce });
  }
}

function broadcastLoad() {
  for (const { channel } of connected.values()) { try { channel.send({ type: 'LOAD_UPDATE', ...compute }); } catch {} }
}

// ── control API (Kotlin → bridge) ───────────────────────────────────────────

function readBody(req) {
  // JSON.parse must not throw inside the 'end' handler — an uncaught exception there kills the
  // whole Node process (the host included). Malformed bodies resolve to {} and 500 via the route.
  return new Promise((resolve, reject) => {
    let d = '';
    req.on('data', (c) => (d += c));
    req.on('end', () => {
      try { resolve(d ? JSON.parse(d) : {}); } catch (e) { reject(new Error('malformed JSON body')); }
    });
  });
}
function json(res, code, obj) { const b = JSON.stringify(obj); res.writeHead(code, { 'Content-Type': 'application/json', 'Content-Length': Buffer.byteLength(b) }); res.end(b); }

const routes = {
  'GET /health': async () => ({ ok: true, self: SELF_PUB_HEX, mode: MODE }),
  'GET /status': async () => ({ isOnline: !!control, self: SELF_PUB_HEX, mode: MODE, nativeError: NATIVE_ERROR, peers: peerList() }),
  'POST /trust/set': async (b) => { trusted.clear(); (b.publicKeys || []).forEach((k) => trusted.add(k)); for (const e of connected.values()) e.peer.isTrusted = trusted.has(e.peer.publicKey); emit('peers-changed', peerList()); return { ok: true, count: trusted.size }; },
  'POST /compute/set': async (b) => { compute = { gpuUtilization: b.gpuUtilization || 0, loadedModels: b.loadedModels || [], computeAvailable: !!b.computeAvailable, queueDepth: b.queueDepth || 0 }; broadcastLoad(); return { ok: true }; },
  'POST /friend/request': async (b) => { const e = connected.get(b.publicKey); if (!e) return { ok: false, error: 'peer not connected' }; e.channel.send({ type: 'FRIEND_REQUEST', fromPublicKey: SELF_PUB_HEX, fromDisplayName: CONFIG.displayName, fromDeviceName: CONFIG.deviceName }); return { ok: true }; },
  'POST /friend/accept': async (b) => { trusted.add(b.publicKey); const e = connected.get(b.publicKey); if (e) { e.channel.send({ type: 'FRIEND_ACCEPT', fromPublicKey: SELF_PUB_HEX }); e.peer.isTrusted = true; emit('peers-changed', peerList()); } return { ok: true }; },
  // Outbound inference: ask a peer to run on its model. Replies relayed via SSE inference-* events.
  'POST /inference/request': async (b) => { const e = connected.get(b.peerId); if (!e || e.channel.isClosed()) return { ok: false, error: 'peer not connected' }; const ok = e.channel.send({ type: 'INFERENCE_REQUEST', requestId: b.requestId, body: b.body }); return { ok }; },
  'POST /inference/cancel': async (b) => { connected.get(b.peerId)?.channel.send({ type: 'INFERENCE_CANCEL', requestId: b.requestId }); return { ok: true }; },
  // Inbound inference reply: Kotlin streams chunks for a request a peer made to us.
  'POST /inference/respond': async (b) => {
    const e = connected.get(b.peerId); if (!e) return { ok: false };
    if (b.error) e.channel.send({ type: 'INFERENCE_ERROR', requestId: b.requestId, error: b.error });
    else if (b.done) e.channel.send({ type: 'INFERENCE_DONE', requestId: b.requestId });
    else e.channel.send({ type: 'INFERENCE_CHUNK', requestId: b.requestId, data: b.data });
    return { ok: true };
  },
  // Media job submission to a host peer's queue (desktop MEDIA_JOB_SUBMIT shape, flat fields).
  'POST /media/submit': async (b) => {
    const e = connected.get(b.peerId);
    if (!e || e.channel.isClosed()) return { ok: false, error: 'peer not connected' };
    const ok = e.channel.send({
      type: 'MEDIA_JOB_SUBMIT', jobId: b.jobId, kind: b.kind, prompt: b.prompt,
      audioPrompt: b.audioPrompt, audioKind: b.audioKind, aspectRatio: b.aspectRatio, durationSec: b.durationSec,
    });
    return { ok };
  },
  // Pull a finished file from the host peer (desktop media-share protocol).
  'POST /media/download': async (b) => {
    const e = connected.get(b.peerId);
    if (!e || e.channel.isClosed()) return { ok: false, error: 'peer not connected' };
    const ok = e.channel.send({ type: 'MEDIA_DOWNLOAD_REQUEST', requestId: b.requestId, fileName: b.fileName });
    return { ok };
  },
  'POST /media/list': async (b) => { connected.get(b.peerId)?.channel.send({ type: 'MEDIA_LIST_REQUEST' }); return { ok: true }; },
  // Build offload submission (git bundle as base64; logs/artifact stream back via SSE).
  'POST /build/submit': async (b) => {
    const e = connected.get(b.peerId);
    if (!e || e.channel.isClosed()) return { ok: false, error: 'peer not connected' };
    const ok = e.channel.send({ type: 'BUILD_JOB_SUBMIT', jobId: b.jobId, job: b.job, bundleBase64: b.bundleBase64 });
    return { ok };
  },
  'POST /build/cancel': async (b) => { connected.get(b.peerId)?.channel.send({ type: 'BUILD_JOB_CANCEL', jobId: b.jobId }); return { ok: true }; },
  // Host-side replies for media jobs WE are serving (desktop MEDIA_JOB_STATUS shape).
  'POST /media/respond': async (b) => {
    const e = connected.get(b.peerId); if (!e) return { ok: false };
    e.channel.send({ type: 'MEDIA_JOB_STATUS', jobId: b.jobId, status: b.status, stage: b.stage, error: b.error, fileNames: b.fileNames });
    return { ok: true };
  },
  // Host-side replies for media downloads WE are serving (chunked base64 + eof).
  'POST /media/download/respond': async (b) => {
    const e = connected.get(b.peerId); if (!e) return { ok: false };
    if (b.error) e.channel.send({ type: 'MEDIA_DOWNLOAD_ERROR', requestId: b.requestId, error: b.error });
    else e.channel.send({ type: 'MEDIA_DOWNLOAD_CHUNK', requestId: b.requestId, data: b.data, eof: !!b.eof });
    return { ok: true };
  },
  'POST /build/respond': async (b) => {
    const e = connected.get(b.peerId); if (!e) return { ok: false };
    if (b.line !== undefined) e.channel.send({ type: 'BUILD_JOB_LOG', jobId: b.jobId, line: b.line });
    else if (b.done) e.channel.send({ type: 'BUILD_JOB_RESULT', jobId: b.jobId, ok: !!b.ok, artifactName: b.artifactName, artifactBase64: b.artifactBase64, error: b.error });
    else e.channel.send({ type: 'BUILD_JOB_ACCEPTED', jobId: b.jobId });
    return { ok: true };
  },
};

function buildControlServer() {
  return http.createServer(async (req, res) => {
    const url = new URL(req.url, 'http://127.0.0.1');
    if (url.pathname === '/events') {
      res.writeHead(200, { 'Content-Type': 'text/event-stream', 'Cache-Control': 'no-cache', 'Connection': 'keep-alive' });
      res.write('retry: 1000\n\n');
      const listener = (ev) => { try { res.write(`data: ${JSON.stringify(ev)}\n\n`); } catch {} };
      bus.on('event', listener);
      req.on('close', () => bus.off('event', listener));
      return;
    }
    const handler = routes[`${req.method} ${url.pathname}`];
    if (!handler) return json(res, 404, { error: 'no such route' });
    try { json(res, 200, await handler(req.method === 'POST' ? await readBody(req) : {})); }
    catch (e) { json(res, 500, { error: String(e && e.stack ? e.stack : e) }); }
  });
}

// ── lifecycle (in-process API for orion-host.js; also the standalone entry) ──

function startBridge(config) {
  if (control) return Promise.resolve({ self: SELF_PUB_HEX, controlPort: CONFIG.controlPort, mode: MODE, alreadyRunning: true });
  CONFIG = config;
  LAN_PORT = CONFIG.lanPort || 38291;
  // Natives are opt-in: nodejs-mobile silently exit(1)s on ANY process.dlopen, so the require must
  // never happen there. Desktop (standalone/test) passes useNatives:true for full DHT mode.
  USE_NATIVES = CONFIG.useNatives ?? (process.platform !== 'android');
  keyPair = deriveKeypair(CONFIG.seedHex);
  SELF_PUB_HEX = keyPair.publicKey.toString('hex');
  control = buildControlServer();
  return new Promise((resolve, reject) => {
    control.once('error', reject);
    control.listen(CONFIG.controlPort, '127.0.0.1', () => {
      const info = { self: SELF_PUB_HEX, controlPort: CONFIG.controlPort };
      startNetworking()
        .then(() => {
          console.log(`p2p-bridge control on 127.0.0.1:${CONFIG.controlPort}, self=${SELF_PUB_HEX.slice(0, 16)}…, mode=${MODE}`);
          resolve({ ...info, mode: MODE, nativeError: NATIVE_ERROR });
        })
        .catch((e) => { console.error('networking start failed:', e); resolve({ ...info, mode: MODE, swarmError: String(e) }); });
    });
  });
}

async function stopBridge() {
  if (keepaliveTimer) { clearInterval(keepaliveTimer); keepaliveTimer = null; }
  try { lan.stop(); } catch {}
  lan.removeAllListeners('peer-seen');
  for (const e of connected.values()) { try { e.channel.close(); } catch {} }
  connected.clear();
  lanJoined.clear();
  dialing.clear();
  try { if (swarm) await swarm.destroy(); } catch {}
  swarm = null; mainTopic = null;
  try { if (tcpServer) tcpServer.close(); } catch {}
  tcpServer = null; TCP_PORT = 0;
  try { if (control) control.close(); } catch {}
  control = null;
  emit('status', { isOnline: false, self: SELF_PUB_HEX, mode: MODE });
  return { ok: true };
}

module.exports = { startBridge, stopBridge };

// Standalone entry for desktop testing: `node p2p-bridge.js <configJsonPath>`
if (require.main === module) {
  const cfg = JSON.parse(fs.readFileSync(process.argv[2], 'utf8'));
  startBridge(cfg).catch((e) => { console.error('bridge start failed:', e); process.exit(1); });
}
