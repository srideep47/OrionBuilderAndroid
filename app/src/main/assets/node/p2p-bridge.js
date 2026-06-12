'use strict';
/*
 * Orion P2P bridge — runs inside the embedded nodejs-mobile (:node) process and speaks the EXACT
 * wire protocol of the desktop Orion Builder so a desktop peer cannot tell phone from PC:
 *   - Hyperswarm DHT topic = sha256("orionbuilder-v1"); Noise-encrypted connections (free via HS).
 *   - Framed JSON channel: [uint32 BE length][JSON utf8 payload]  (matches peer-channel.ts).
 *   - LAN UDP beacons on :38291, magic "ORION-LAN-V1", 15s cadence (matches lan-discovery.ts).
 *   - HELLO / METADATA / PING-PONG / LOAD_UPDATE / FRIEND_* / INFERENCE_* messages, byte-for-byte.
 *
 * Identity is injected from Kotlin (Keystore-wrapped 32-byte ed25519 seed) so it survives Node
 * restarts. Kotlin drives the bridge over a localhost control API and consumes events via SSE.
 *
 * Args: p2p-bridge.js <configJsonPath>
 *   config = { controlPort, seedHex, displayName, deviceName, deviceType, hardware, appVersion, lanPort? }
 */

const http = require('http');
const dgram = require('dgram');
const os = require('os');
const crypto = require('crypto');
const fs = require('fs');
const { EventEmitter } = require('events');

const Hyperswarm = require('hyperswarm');
const sodium = require('sodium-native');

// ── config / identity ───────────────────────────────────────────────────────

const CONFIG = JSON.parse(fs.readFileSync(process.argv[2], 'utf8'));
const LAN_PORT = CONFIG.lanPort || 38291;
const LAN_MAGIC = 'ORION-LAN-V1';
const KEEPALIVE_INTERVAL_MS = 10_000;
const KEEPALIVE_TIMEOUT_MS = 25_000;

function deriveKeypair(seedHex) {
  const seed = Buffer.from(seedHex, 'hex');
  if (seed.length !== sodium.crypto_sign_SEEDBYTES) throw new Error('seed must be 32 bytes');
  const publicKey = Buffer.alloc(sodium.crypto_sign_PUBLICKEYBYTES);
  const secretKey = Buffer.alloc(sodium.crypto_sign_SECRETKEYBYTES); // 64 = seed(32)+pub(32)
  sodium.crypto_sign_seed_keypair(publicKey, secretKey, seed);
  return { publicKey, secretKey };
}

const keyPair = deriveKeypair(CONFIG.seedHex);
const SELF_PUB_HEX = keyPair.publicKey.toString('hex');

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

// ── LAN discovery (matches lan-discovery.ts) ────────────────────────────────

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
  _broadcast() {
    if (!this.socket) return;
    const beacon = {
      magic: LAN_MAGIC,
      publicKey: SELF_PUB_HEX,
      displayName: CONFIG.displayName,
      deviceName: CONFIG.deviceName,
      appVersion: CONFIG.appVersion || '',
      timestamp: Date.now(),
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
    this.peers.set(b.publicKey, { publicKey: b.publicKey, displayName: b.displayName, deviceName: b.deviceName, address: rinfo.address, lastSeenAt: Date.now() });
    this.emit('peer-seen', this.peers.get(b.publicKey));
  }
}

// ── swarm ───────────────────────────────────────────────────────────────────

const connected = new Map();   // remotePubHex -> { channel, peer, lastSeenAt, pings, metadataReceived }
const trusted = new Set();     // trusted peer pubkey hex (injected from Kotlin)
const lanJoined = new Set();
let compute = { gpuUtilization: 0, loadedModels: [], computeAvailable: false, queueDepth: 0 };

const lan = new LanDiscovery();
let swarm = null;
let mainTopic = null;
let pingCounter = 1;

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

async function startSwarm() {
  swarm = new Hyperswarm({ keyPair });
  swarm.on('connection', (socket) => handleConnection(socket));
  mainTopic = crypto.createHash('sha256').update('orionbuilder-v1').digest();
  swarm.join(mainTopic, { server: true, client: true });
  await swarm.flush().catch(() => {});

  lan.on('peer-seen', (p) => joinLanRendezvous(p));
  lan.start();

  setInterval(keepalivePass, KEEPALIVE_INTERVAL_MS);
  emit('status', { isOnline: true, self: SELF_PUB_HEX });
}

function joinLanRendezvous(lanPeer) {
  if (!swarm || lanJoined.has(lanPeer.publicKey)) return;
  const [a, b] = [SELF_PUB_HEX, lanPeer.publicKey].sort();
  const topic = crypto.createHash('sha256').update('orion-lan-pair-').update(a).update(b).digest();
  lanJoined.add(lanPeer.publicKey);
  swarm.join(topic, { server: true, client: true });
  swarm.flush().catch(() => {});
}

function handleConnection(socket) {
  const remoteHex = Buffer.from(socket.remotePublicKey).toString('hex');
  const existing = connected.get(remoteHex);
  if (existing && !existing.channel.isClosed()) { try { socket.destroy(); } catch {} return; }

  const channel = new PeerChannel(socket);
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
    default: return; // MEDIA_* and others handled in later phases
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
  return new Promise((resolve) => { let d = ''; req.on('data', (c) => (d += c)); req.on('end', () => resolve(d ? JSON.parse(d) : {})); });
}
function json(res, code, obj) { const b = JSON.stringify(obj); res.writeHead(code, { 'Content-Type': 'application/json', 'Content-Length': Buffer.byteLength(b) }); res.end(b); }

const routes = {
  'GET /health': async () => ({ ok: true, self: SELF_PUB_HEX }),
  'GET /status': async () => ({ isOnline: !!swarm, self: SELF_PUB_HEX, peers: peerList() }),
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
};

const control = http.createServer(async (req, res) => {
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

control.listen(CONFIG.controlPort, '127.0.0.1', () => {
  console.log(`p2p-bridge control on 127.0.0.1:${CONFIG.controlPort}, self=${SELF_PUB_HEX.slice(0, 16)}…`);
  startSwarm().catch((e) => console.error('swarm start failed:', e));
});
