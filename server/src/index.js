'use strict';

const path = require('path');
const http = require('http');
const express = require('express');
const cors = require('cors');
const { WebSocketServer } = require('ws');
const fs = require('fs');

const PORT = Number(process.env.TELEMETRY_PORT || 8080);
const SERVER_HOST = process.env.SERVER_HOST || '127.0.0.1';
const STALE_MS = Number(process.env.STALE_DEVICE_MS || 15000);
const MTX_HLS = process.env.MTX_HLS_URL || 'http://127.0.0.1:8888';
const MTX_WEBRTC = process.env.MTX_WEBRTC_URL || 'http://127.0.0.1:8889';
const DASHBOARD_DIST = process.env.DASHBOARD_DIST
  || path.join(__dirname, '../../dashboard/dist');
const ROOT = path.join(__dirname, '../..');

function readPublicUrls() {
  const out = {
    publicBaseUrl: (process.env.PUBLIC_BASE_URL || '').replace(/\/$/, ''),
    rtmpPublic: (process.env.RTMP_PUBLIC || '').replace(/\/$/, ''),
  };
  try {
    const p = path.join(ROOT, '.public-urls.json');
    if (fs.existsSync(p)) {
      const j = JSON.parse(fs.readFileSync(p, 'utf8'));
      if (j.publicBaseUrl) out.publicBaseUrl = String(j.publicBaseUrl).replace(/\/$/, '');
      if (j.rtmpPublic) out.rtmpPublic = String(j.rtmpPublic).replace(/\/$/, '');
    }
  } catch {
    /* ignore */
  }
  try {
    const t = path.join(ROOT, '.tunnel-url');
    if (!out.publicBaseUrl && fs.existsSync(t)) {
      out.publicBaseUrl = fs.readFileSync(t, 'utf8').trim().replace(/\/$/, '');
    }
  } catch {
    /* ignore */
  }
  return out;
}

/** @type {Map<string, object>} */
const devices = new Map();

const app = express();
app.use(cors());
app.use(express.json({ limit: '64kb' }));

function proxyTo(targetBase) {
  return (req, res) => {
    const target = new URL(req.originalUrl.replace(/^\/(hls|webrtc)/, '') || '/', targetBase);
    const headers = { ...req.headers, host: target.host };
    delete headers['content-length'];
    const preq = http.request(
      {
        protocol: target.protocol,
        hostname: target.hostname,
        port: target.port,
        path: target.pathname + target.search,
        method: req.method,
        headers,
      },
      (pres) => {
        res.writeHead(pres.statusCode || 502, pres.headers);
        pres.pipe(res);
      },
    );
    preq.on('error', (err) => {
      res.status(502).send(`Upstream error: ${err.message}`);
    });
    req.pipe(preq);
  };
}

// Worldwide-safe video: HLS proxied through the same Cloudflare HTTPS origin
app.use('/hls', proxyTo(MTX_HLS));
// LAN / advanced: WHEP/WHIP signaling proxied (media still needs ICE/TURN off-LAN)
app.use('/webrtc', proxyTo(MTX_WEBRTC));

app.get('/health', (_req, res) => {
  const pub = readPublicUrls();
  res.json({
    ok: true,
    serverHost: SERVER_HOST,
    ...pub,
    devices: devices.size,
    uptimeSec: Math.floor(process.uptime()),
    world: Boolean(pub.publicBaseUrl),
  });
});

app.get('/api/devices', (_req, res) => {
  res.json([...devices.values()]);
});

app.get('/api/public', (_req, res) => {
  res.json(readPublicUrls());
});

app.use(express.static(DASHBOARD_DIST, {
  setHeaders(res, filePath) {
    if (filePath.endsWith('.js') || filePath.endsWith('.css')) {
      res.setHeader('Cache-Control', 'no-cache');
    }
  },
}));

app.get('*', (req, res, next) => {
  if (
    req.path.startsWith('/api')
    || req.path.startsWith('/health')
    || req.path.startsWith('/hls')
    || req.path.startsWith('/webrtc')
    || req.path.startsWith('/ws')
  ) {
    return next();
  }
  res.sendFile(path.join(DASHBOARD_DIST, 'index.html'), (err) => {
    if (err) res.status(404).send('Dashboard not built yet. Run: cd dashboard && npm run build');
  });
});

const server = http.createServer(app);
const wss = new WebSocketServer({ server, path: '/ws' });

function broadcast(payload) {
  const data = JSON.stringify(payload);
  for (const client of wss.clients) {
    if (client.readyState === 1) client.send(data);
  }
}

function normalizeTelemetry(raw) {
  if (!raw || typeof raw !== 'object') return null;
  const deviceId = String(raw.deviceId || '').trim();
  if (!deviceId) return null;
  const lat = Number(raw.lat);
  const lng = Number(raw.lng);
  if (!Number.isFinite(lat) || !Number.isFinite(lng)) return null;

  const pub = readPublicUrls();
  const streamUrl = raw.streamUrl
    || (pub.publicBaseUrl
      ? `${pub.publicBaseUrl}/hls/live/${deviceId}/index.m3u8`
      : `webrtc://${SERVER_HOST}:8889/live/${deviceId}`);

  return {
    deviceId,
    type: raw.type || 'uav',
    lat,
    lng,
    altitude: Number.isFinite(Number(raw.altitude)) ? Number(raw.altitude) : 0,
    heading: Number.isFinite(Number(raw.heading)) ? Number(raw.heading) : 0,
    streamUrl,
    updatedAt: Date.now(),
  };
}

function publicSnapshotFields() {
  const pub = readPublicUrls();
  return {
    serverHost: SERVER_HOST,
    publicBaseUrl: pub.publicBaseUrl || undefined,
    rtmpPublic: pub.rtmpPublic || undefined,
  };
}

wss.on('connection', (socket) => {
  socket.send(JSON.stringify({
    type: 'snapshot',
    devices: [...devices.values()],
    ...publicSnapshotFields(),
  }));

  socket.on('message', (buf) => {
    let msg;
    try {
      msg = JSON.parse(buf.toString());
    } catch {
      return;
    }

    if (msg && msg.type === 'ping') {
      socket.send(JSON.stringify({ type: 'pong', t: Date.now(), ...publicSnapshotFields() }));
      return;
    }

    const telemetry = normalizeTelemetry(msg);
    if (!telemetry) return;

    devices.set(telemetry.deviceId, telemetry);
    broadcast({ type: 'telemetry', device: telemetry });
  });
});

setInterval(() => {
  const now = Date.now();
  for (const [id, device] of devices) {
    if (now - device.updatedAt > STALE_MS) {
      devices.delete(id);
      broadcast({ type: 'device_offline', deviceId: id });
    }
  }
}, 2000);

// Refresh public URL file watchers for clients that connect later
setInterval(() => {
  broadcast({ type: 'public_urls', ...publicSnapshotFields() });
}, 15000);

server.listen(PORT, '0.0.0.0', () => {
  const pub = readPublicUrls();
  console.log(`[sa-server] http://0.0.0.0:${PORT}  (host hint: ${SERVER_HOST})`);
  console.log(`[sa-server] websocket ws://0.0.0.0:${PORT}/ws`);
  console.log(`[sa-server] HLS proxy /hls → ${MTX_HLS}`);
  if (pub.publicBaseUrl) console.log(`[sa-server] PUBLIC ${pub.publicBaseUrl}`);
  if (pub.rtmpPublic) console.log(`[sa-server] RTMP PUBLIC ${pub.rtmpPublic}`);
  console.log(`[sa-server] dashboard dir: ${DASHBOARD_DIST}`);
});
