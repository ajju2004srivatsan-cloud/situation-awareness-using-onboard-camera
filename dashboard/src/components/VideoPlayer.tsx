import { useEffect, useRef, useState } from 'react';
import Hls from 'hls.js';

type Props = {
  mode: 'hls' | 'whep';
  url: string;
  title: string;
};

/**
 * Worldwide: HLS over HTTPS tunnel.
 * LAN: MediaMTX WHEP (WebRTC) for lower latency.
 */
export function VideoPlayer({ mode, url, title }: Props) {
  const videoRef = useRef<HTMLVideoElement>(null);
  const [error, setError] = useState<string | null>(null);
  const [state, setState] = useState<'connecting' | 'playing' | 'failed'>('connecting');

  useEffect(() => {
    let pc: RTCPeerConnection | null = null;
    let hls: Hls | null = null;
    let aborted = false;
    const el = videoRef.current;

    async function startWhep() {
      setError(null);
      setState('connecting');
      pc = new RTCPeerConnection({
        iceServers: [{ urls: 'stun:stun.l.google.com:19302' }],
      });
      pc.addTransceiver('video', { direction: 'recvonly' });
      pc.addTransceiver('audio', { direction: 'recvonly' });
      pc.ontrack = (ev) => {
        if (!el) return;
        if (el.srcObject !== ev.streams[0]) el.srcObject = ev.streams[0];
        void el.play().catch(() => undefined);
        setState('playing');
      };

      const offer = await pc.createOffer();
      await pc.setLocalDescription(offer);
      await new Promise<void>((resolve) => {
        if (!pc) return resolve();
        if (pc.iceGatheringState === 'complete') return resolve();
        const t = setTimeout(() => resolve(), 1500);
        pc.onicegatheringstatechange = () => {
          if (pc?.iceGatheringState === 'complete') {
            clearTimeout(t);
            resolve();
          }
        };
      });

      const local = pc.localDescription;
      if (!local || aborted) return;
      const res = await fetch(url, {
        method: 'POST',
        headers: { 'Content-Type': 'application/sdp' },
        body: local.sdp,
      });
      if (!res.ok) {
        const text = await res.text().catch(() => res.statusText);
        throw new Error(`WHEP ${res.status}: ${text.slice(0, 160)}`);
      }
      const answer = await res.text();
      await pc.setRemoteDescription({ type: 'answer', sdp: answer });
    }

    async function startHls() {
      setError(null);
      setState('connecting');
      if (!el) return;

      if (el.canPlayType('application/vnd.apple.mpegurl')) {
        el.src = url;
        await el.play().catch(() => undefined);
        setState('playing');
        return;
      }

      if (Hls.isSupported()) {
        hls = new Hls({
          enableWorker: true,
          lowLatencyMode: true,
          liveSyncDurationCount: 3,
        });
        hls.loadSource(url);
        hls.attachMedia(el);
        hls.on(Hls.Events.MANIFEST_PARSED, () => {
          void el.play().catch(() => undefined);
          setState('playing');
        });
        hls.on(Hls.Events.ERROR, (_e, data) => {
          if (data.fatal) {
            setState('failed');
            setError(data.details || 'HLS error — is the device publishing?');
          }
        });
        return;
      }

      throw new Error('HLS not supported in this browser');
    }

    const run = mode === 'hls' ? startHls : startWhep;
    run().catch((err: Error) => {
      if (aborted) return;
      setState('failed');
      setError(err.message || 'Stream unavailable');
    });

    return () => {
      aborted = true;
      pc?.close();
      hls?.destroy();
      if (el) {
        el.removeAttribute('src');
        el.srcObject = null;
      }
    };
  }, [mode, url]);

  return (
    <div className="video-panel">
      <div className="video-panel__header">
        <strong>{title}</strong>
        <span className={`pill pill--${state}`}>
          {state} · {mode.toUpperCase()}
        </span>
      </div>
      <video ref={videoRef} className="video-panel__el" autoPlay playsInline muted controls />
      {error && <p className="video-panel__error">{error}</p>}
      {!error && state === 'connecting' && (
        <p className="video-panel__hint">
          {mode === 'hls'
            ? 'Waiting for HLS (device must be GO LIVE)…'
            : 'Waiting for WebRTC publisher…'}
        </p>
      )}
    </div>
  );
}
