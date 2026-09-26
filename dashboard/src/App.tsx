import { useState } from 'react';
import { useTelemetry } from './hooks/useTelemetry';
import { CesiumMap } from './components/CesiumMap';
import { VideoPlayer } from './components/VideoPlayer';
import { isWorldHttps, resolvePlayback } from './types';

function isGroundStation(type: string) {
  const t = type.toLowerCase();
  return t === 'phone' || t === 'ground' || t === 'gcs' || t === 'ground_station';
}

export default function App() {
  const { devices, status, serverHost, publicBaseUrl, rtmpPublic } = useTelemetry();
  const list = Object.values(devices);
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const selected = selectedId ? devices[selectedId] : undefined;

  const playback = selected
    ? resolvePlayback(selected, serverHost, publicBaseUrl)
    : null;

  const uavs = list.filter((d) => !isGroundStation(d.type));
  const grounds = list.filter((d) => isGroundStation(d.type));
  const world = isWorldHttps() || Boolean(publicBaseUrl);

  return (
    <div className="app">
      <header className="topbar">
        <div className="brand">
          <span className="brand__mark" />
          <div>
            <h1>Situation Awareness Using Onboard Camera</h1>
            <p>
              Cesium · {world ? 'worldwide HLS + WSS' : 'LAN WebRTC'}
              {publicBaseUrl ? ` · ${publicBaseUrl.replace(/^https?:\/\//, '')}` : ''}
            </p>
          </div>
        </div>
        <div className="topbar__meta">
          <span className={`status status--${status}`}>{status}</span>
          <span className="meta">{uavs.length} UAV · {grounds.length} ground</span>
        </div>
      </header>

      <main className="layout">
        <section className="map-pane">
          <CesiumMap devices={list} selectedId={selectedId} onSelect={setSelectedId} />
          <div className="map-legend">
            <span><i className="legend legend--uav" /> UAV cone</span>
            <span><i className="legend legend--gnd" /> Ground station box</span>
            <span>{world ? 'World mode (HLS)' : 'LAN mode (WebRTC)'}</span>
          </div>
        </section>

        <aside className="side">
          {(publicBaseUrl || rtmpPublic) && (
            <div className="side__section">
              <h2>World endpoints</h2>
              {publicBaseUrl && (
                <p className="endpoint">{publicBaseUrl}</p>
              )}
              {rtmpPublic && (
                <p className="endpoint">{rtmpPublic}/live/&lt;deviceId&gt;</p>
              )}
              <p className="empty">Paste these into the G20 app Settings.</p>
            </div>
          )}

          <div className="side__section">
            <h2>Devices</h2>
            {list.length === 0 && (
              <p className="empty">No live devices. Start the G20 app with GO LIVE.</p>
            )}
            <ul className="device-list">
              {list.map((d) => {
                const ground = isGroundStation(d.type);
                const ageSec = Math.max(0, Math.round((Date.now() - d.updatedAt) / 1000));
                return (
                  <li key={d.deviceId}>
                    <button
                      type="button"
                      className={d.deviceId === selectedId ? 'device active' : 'device'}
                      onClick={() => setSelectedId(d.deviceId)}
                    >
                      <span className="device__id">
                        <span className={ground ? 'shape shape--box' : 'shape shape--cone'} />
                        {d.deviceId}
                      </span>
                      <span className="device__meta">
                        {ground ? 'Ground station' : 'UAV'} · alt {d.altitude.toFixed(1)} m · hdg{' '}
                        {d.heading.toFixed(0)}°
                      </span>
                      <span className="device__meta">
                        {d.lat.toFixed(5)}, {d.lng.toFixed(5)} · {ageSec < 2 ? 'live' : `${ageSec}s ago`}
                      </span>
                    </button>
                  </li>
                );
              })}
            </ul>
          </div>

          <div className="side__section side__section--video">
            <h2>Video</h2>
            {selected && playback ? (
              <VideoPlayer
                key={`${playback.mode}:${playback.url}`}
                mode={playback.mode}
                url={playback.url}
                title={selected.deviceId}
              />
            ) : (
              <p className="empty">Select a cone or ground box to open its feed.</p>
            )}
          </div>
        </aside>
      </main>
    </div>
  );
}
