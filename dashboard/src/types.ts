export type DeviceTelemetry = {
  deviceId: string;
  type: string;
  lat: number;
  lng: number;
  altitude: number;
  heading: number;
  streamUrl: string;
  updatedAt: number;
};

export type ServerMessage =
  | {
      type: 'snapshot';
      devices: DeviceTelemetry[];
      serverHost?: string;
      publicBaseUrl?: string;
      rtmpPublic?: string;
    }
  | { type: 'telemetry'; device: DeviceTelemetry }
  | { type: 'device_offline'; deviceId: string }
  | { type: 'pong'; t: number; publicBaseUrl?: string; rtmpPublic?: string }
  | {
      type: 'public_urls';
      serverHost?: string;
      publicBaseUrl?: string;
      rtmpPublic?: string;
    };

export function isWorldHttps(): boolean {
  return window.location.protocol === 'https:';
}

export function resolveWsUrl(): string {
  const fromEnv = import.meta.env.VITE_WS_URL as string | undefined;
  if (fromEnv) return fromEnv;

  const params = new URLSearchParams(window.location.search);
  const override = params.get('ws');
  if (override) return override;

  const proto = window.location.protocol === 'https:' ? 'wss:' : 'ws:';
  const host = window.location.host || '192.168.0.137:8080';
  return `${proto}//${host}/ws`;
}

export function streamPathFromUrl(streamUrl: string, deviceId: string): string {
  try {
    if (streamUrl.startsWith('webrtc://')) {
      const u = new URL(streamUrl.replace('webrtc://', 'http://'));
      return u.pathname.replace(/^\//, '') || `live/${deviceId}`;
    }
    if (streamUrl.includes('/hls/')) {
      const u = new URL(streamUrl, window.location.origin);
      return u.pathname
        .replace(/^\/hls\//, '')
        .replace(/\/index\.m3u8$/, '')
        .replace(/^\//, '') || `live/${deviceId}`;
    }
    if (streamUrl.startsWith('http')) {
      const u = new URL(streamUrl);
      return u.pathname.replace(/^\//, '').replace(/\/whep$/, '').replace(/\/index\.m3u8$/, '')
        || `live/${deviceId}`;
    }
  } catch {
    /* fall through */
  }
  return `live/${deviceId}`;
}

/** Prefer HLS over the same origin when viewing via Cloudflare HTTPS (works worldwide). */
export function resolvePlayback(
  device: DeviceTelemetry,
  serverHostHint?: string,
  publicBaseUrl?: string,
): { mode: 'hls' | 'whep'; url: string } {
  const path = streamPathFromUrl(device.streamUrl, device.deviceId);

  if (device.streamUrl.includes('.m3u8')) {
    // Absolute HLS from device telemetry
    if (device.streamUrl.startsWith('http')) {
      return { mode: 'hls', url: device.streamUrl };
    }
  }

  if (isWorldHttps() || publicBaseUrl) {
    const base = (publicBaseUrl || window.location.origin).replace(/\/$/, '');
    return { mode: 'hls', url: `${base}/hls/${path}/index.m3u8` };
  }

  const host = serverHostHint || window.location.hostname || '192.168.0.137';
  const fromEnv = import.meta.env.VITE_WEBRTC_BASE as string | undefined;
  const webrtcBase = (fromEnv || `http://${host}:8889`).replace(/\/$/, '');
  return { mode: 'whep', url: `${webrtcBase}/${path}/whep` };
}
