'use strict';

const WebSocket = require('ws');

const WS_URL = process.env.WS_URL || 'ws://127.0.0.1:8080/ws';
const SERVER_HOST = process.env.SERVER_HOST || '192.168.0.137';
const INTERVAL_MS = Number(process.env.SIM_INTERVAL_MS || 1000);

const tracks = [
  {
    deviceId: 'drone_alpha_01',
    type: 'uav',
    lat: 13.0827,
    lng: 80.2707,
    altitude: 120,
    heading: 45,
    radius: 0.004,
    speed: 0.018,
  },
  {
    deviceId: 'gcs_alpha_01',
    type: 'ground',
    lat: 13.0795,
    lng: 80.2680,
    altitude: 8,
    heading: 200,
    radius: 0.0015,
    speed: 0.01,
  },
];

let t0 = Date.now();

function sample(track) {
  const t = (Date.now() - t0) / 1000;
  const lat = track.lat + Math.sin(t * track.speed) * track.radius;
  const lng = track.lng + Math.cos(t * track.speed * 0.85) * track.radius;
  const heading = (track.heading + t * 12) % 360;
  const altitude = track.altitude + Math.sin(t * 0.4) * 8;
  return {
    deviceId: track.deviceId,
    type: track.type,
    lat,
    lng,
    altitude,
    heading,
    streamUrl: `webrtc://${SERVER_HOST}:8889/live/${track.deviceId}`,
  };
}

function connect() {
  console.log(`[sim] connecting ${WS_URL}`);
  const ws = new WebSocket(`${WS_URL}?role=edge`);

  ws.on('open', () => {
    console.log('[sim] live — publishing demo tracks');
    const tick = () => {
      if (ws.readyState !== WebSocket.OPEN) return;
      for (const track of tracks) {
        ws.send(JSON.stringify(sample(track)));
      }
    };
    tick();
    setInterval(tick, INTERVAL_MS);
  });

  ws.on('close', () => {
    console.log('[sim] disconnected — retry in 2s');
    setTimeout(connect, 2000);
  });

  ws.on('error', (err) => {
    console.error('[sim]', err.message);
  });
}

connect();
