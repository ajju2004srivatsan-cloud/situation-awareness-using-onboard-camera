import { useCallback, useEffect, useRef, useState } from 'react';
import type { DeviceTelemetry, ServerMessage } from '../types';
import { resolveWsUrl } from '../types';

export function useTelemetry() {
  const [devices, setDevices] = useState<Record<string, DeviceTelemetry>>({});
  const [status, setStatus] = useState<'connecting' | 'live' | 'offline'>('connecting');
  const [serverHost, setServerHost] = useState<string | undefined>();
  const [publicBaseUrl, setPublicBaseUrl] = useState<string | undefined>();
  const [rtmpPublic, setRtmpPublic] = useState<string | undefined>();
  const wsRef = useRef<WebSocket | null>(null);
  const retryRef = useRef(0);

  const applyPublic = useCallback((msg: ServerMessage) => {
    if ('serverHost' in msg && msg.serverHost) setServerHost(msg.serverHost);
    if ('publicBaseUrl' in msg && msg.publicBaseUrl) setPublicBaseUrl(msg.publicBaseUrl);
    if ('rtmpPublic' in msg && msg.rtmpPublic) setRtmpPublic(msg.rtmpPublic);
  }, []);

  const applyMessage = useCallback((msg: ServerMessage) => {
    if (msg.type === 'snapshot' || msg.type === 'public_urls' || msg.type === 'pong') {
      applyPublic(msg);
    }
    if (msg.type === 'snapshot') {
      const next: Record<string, DeviceTelemetry> = {};
      for (const d of msg.devices || []) next[d.deviceId] = d;
      setDevices(next);
      return;
    }
    if (msg.type === 'telemetry') {
      setDevices((prev) => ({ ...prev, [msg.device.deviceId]: msg.device }));
      return;
    }
    if (msg.type === 'device_offline') {
      setDevices((prev) => {
        const copy = { ...prev };
        delete copy[msg.deviceId];
        return copy;
      });
    }
  }, [applyPublic]);

  useEffect(() => {
    let cancelled = false;
    let timer: ReturnType<typeof setTimeout> | undefined;

    const connect = () => {
      if (cancelled) return;
      setStatus('connecting');
      const url = resolveWsUrl();
      const ws = new WebSocket(url);
      wsRef.current = ws;

      ws.onopen = () => {
        retryRef.current = 0;
        setStatus('live');
      };

      ws.onmessage = (ev) => {
        try {
          applyMessage(JSON.parse(ev.data) as ServerMessage);
        } catch {
          /* ignore */
        }
      };

      ws.onclose = () => {
        setStatus('offline');
        wsRef.current = null;
        const delay = Math.min(8000, 500 * 2 ** retryRef.current++);
        timer = setTimeout(connect, delay);
      };

      ws.onerror = () => ws.close();
    };

    connect();
    const ping = setInterval(() => {
      if (wsRef.current?.readyState === WebSocket.OPEN) {
        wsRef.current.send(JSON.stringify({ type: 'ping' }));
      }
    }, 10000);

    return () => {
      cancelled = true;
      clearInterval(ping);
      if (timer) clearTimeout(timer);
      wsRef.current?.close();
    };
  }, [applyMessage]);

  return { devices, status, serverHost, publicBaseUrl, rtmpPublic };
}
