import { useEffect, useRef } from 'react';
import {
  Cartesian2,
  Cartesian3,
  Color,
  ColorMaterialProperty,
  ConstantProperty,
  DistanceDisplayCondition,
  Entity,
  HeadingPitchRange,
  HeadingPitchRoll,
  HeightReference,
  HorizontalOrigin,
  Ion,
  LabelStyle,
  Math as CesiumMath,
  NearFarScalar,
  ScreenSpaceEventHandler,
  ScreenSpaceEventType,
  Transforms,
  UrlTemplateImageryProvider,
  VerticalOrigin,
  Viewer,
  defined,
} from 'cesium';
import type { DeviceTelemetry } from '../types';
import 'cesium/Build/Cesium/Widgets/widgets.css';

type Props = {
  devices: DeviceTelemetry[];
  selectedId: string | null;
  onSelect: (id: string) => void;
};

function isGroundStation(type: string) {
  const t = type.toLowerCase();
  return t === 'phone' || t === 'ground' || t === 'gcs' || t === 'ground_station';
}

function ageLabel(updatedAt: number) {
  const s = Math.max(0, Math.round((Date.now() - updatedAt) / 1000));
  return s < 2 ? 'live' : `${s}s ago`;
}

function infoLabel(d: DeviceTelemetry) {
  const kind = isGroundStation(d.type) ? 'GROUND STATION' : 'UAV';
  return [
    d.deviceId,
    kind,
    `Alt ${d.altitude.toFixed(1)} m`,
    `Hdg ${d.heading.toFixed(0)}°`,
    `${d.lat.toFixed(5)}, ${d.lng.toFixed(5)}`,
    ageLabel(d.updatedAt),
  ].join('\n');
}

const trailBuffers = new Map<string, Cartesian3[]>();

function upsertTrail(viewer: Viewer, deviceId: string, position: Cartesian3, ground: boolean) {
  const max = 90;
  let buf = trailBuffers.get(deviceId);
  if (!buf) {
    buf = [];
    trailBuffers.set(deviceId, buf);
  }
  const last = buf[buf.length - 1];
  if (!last || Cartesian3.distance(last, position) > 2) {
    buf.push(Cartesian3.clone(position));
    if (buf.length > max) buf.shift();
  }

  const id = `${deviceId}_trail`;
  let trail = viewer.entities.getById(id);
  const positions = buf.slice();
  const material = ground
    ? Color.fromCssColorString('#f0b429').withAlpha(0.5)
    : Color.fromCssColorString('#3dd6c6').withAlpha(0.5);

  if (!trail) {
    viewer.entities.add({
      id,
      polyline: {
        positions,
        width: 2,
        material,
      },
    });
  } else if (trail.polyline) {
    trail.polyline.positions = new ConstantProperty(positions) as never;
  }
}

/**
 * Cesium 3D globe:
 * - UAV → heading cone
 * - Ground station → box
 * Labels + infoBox carry id, type, alt, heading, coords, freshness, stream.
 */
export function CesiumMap({ devices, selectedId, onSelect }: Props) {
  const containerRef = useRef<HTMLDivElement>(null);
  const viewerRef = useRef<Viewer | null>(null);
  const entitiesRef = useRef<Map<string, Entity>>(new Map());
  const fittedRef = useRef(false);
  const onSelectRef = useRef(onSelect);
  onSelectRef.current = onSelect;

  useEffect(() => {
    if (!containerRef.current || viewerRef.current) return;

    const ionToken = import.meta.env.VITE_CESIUM_ION_TOKEN as string | undefined;
    if (ionToken) Ion.defaultAccessToken = ionToken;

    const viewer = new Viewer(containerRef.current, {
      animation: false,
      timeline: false,
      baseLayerPicker: false,
      geocoder: false,
      homeButton: true,
      sceneModePicker: true,
      navigationHelpButton: false,
      fullscreenButton: true,
      infoBox: true,
      selectionIndicator: true,
      shouldAnimate: true,
    });

    viewer.imageryLayers.removeAll();
    viewer.imageryLayers.addImageryProvider(
      new UrlTemplateImageryProvider({
        url: 'https://tile.openstreetmap.org/{z}/{x}/{y}.png',
        credit: '© OpenStreetMap contributors',
        maximumLevel: 19,
      }),
    );

    viewer.scene.globe.depthTestAgainstTerrain = false;
    viewer.scene.globe.enableLighting = false;
    viewer.scene.fog.enabled = false;

    const handler = new ScreenSpaceEventHandler(viewer.scene.canvas);
    handler.setInputAction((movement: { position: Cartesian2 }) => {
      const picked = viewer.scene.pick(movement.position);
      if (defined(picked) && picked.id instanceof Entity && typeof picked.id.id === 'string') {
        const id = String(picked.id.id).replace(/_trail$/, '');
        onSelectRef.current(id);
      }
    }, ScreenSpaceEventType.LEFT_CLICK);

    viewerRef.current = viewer;

    return () => {
      handler.destroy();
      entitiesRef.current.clear();
      trailBuffers.clear();
      if (!viewer.isDestroyed()) viewer.destroy();
      viewerRef.current = null;
      fittedRef.current = false;
    };
  }, []);

  useEffect(() => {
    const viewer = viewerRef.current;
    if (!viewer || viewer.isDestroyed()) return;

    const live = new Set(devices.map((d) => d.deviceId));
    for (const [id, entity] of [...entitiesRef.current.entries()]) {
      if (!live.has(id)) {
        viewer.entities.remove(entity);
        entitiesRef.current.delete(id);
        const trail = viewer.entities.getById(`${id}_trail`);
        if (trail) viewer.entities.remove(trail);
        trailBuffers.delete(id);
      }
    }

    for (const d of devices) {
      const stale = Date.now() - d.updatedAt > 8000;
      const selected = d.deviceId === selectedId;
      const ground = isGroundStation(d.type);
      const alt = Math.max(d.altitude, ground ? 3 : 8);
      const position = Cartesian3.fromDegrees(d.lng, d.lat, alt);
      const headingRad = CesiumMath.toRadians(d.heading);

      let entity = entitiesRef.current.get(d.deviceId);
      if (!entity) {
        entity = viewer.entities.add({ id: d.deviceId });
        entitiesRef.current.set(d.deviceId, entity);
      }

      entity.position = new ConstantProperty(position) as never;
      entity.name = d.deviceId;
      entity.description = new ConstantProperty(`
        <table class="cesium-infoBox-defaultTable">
          <tr><th>ID</th><td>${d.deviceId}</td></tr>
          <tr><th>Type</th><td>${ground ? 'Ground station' : 'UAV'}</td></tr>
          <tr><th>Latitude</th><td>${d.lat.toFixed(6)}</td></tr>
          <tr><th>Longitude</th><td>${d.lng.toFixed(6)}</td></tr>
          <tr><th>Altitude</th><td>${d.altitude.toFixed(1)} m MSL</td></tr>
          <tr><th>Heading</th><td>${d.heading.toFixed(1)}°</td></tr>
          <tr><th>Updated</th><td>${ageLabel(d.updatedAt)}</td></tr>
          <tr><th>Stream</th><td>${d.streamUrl}</td></tr>
        </table>
      `) as never;

      entity.label = {
        text: infoLabel(d),
        font: 'bold 12px IBM Plex Sans, Segoe UI, sans-serif',
        fillColor: Color.WHITE,
        outlineColor: Color.BLACK,
        outlineWidth: 3,
        style: LabelStyle.FILL_AND_OUTLINE,
        verticalOrigin: VerticalOrigin.BOTTOM,
        horizontalOrigin: HorizontalOrigin.LEFT,
        pixelOffset: new Cartesian2(20, ground ? -16 : -30),
        disableDepthTestDistance: Number.POSITIVE_INFINITY,
        showBackground: true,
        backgroundColor: Color.fromCssColorString('#0d1524').withAlpha(0.85),
        backgroundPadding: new Cartesian2(8, 6),
        scaleByDistance: new NearFarScalar(400, 1.1, 30000, 0.5),
        distanceDisplayCondition: new DistanceDisplayCondition(0, 8e6),
      };

      const alpha = stale ? 0.38 : selected ? 1 : 0.92;
      const uavColor = Color.fromCssColorString(selected ? '#5ef0de' : '#3dd6c6').withAlpha(alpha);
      const gndColor = Color.fromCssColorString(selected ? '#ffd666' : '#f0b429').withAlpha(alpha);

      if (ground) {
        entity.cylinder = undefined;
        entity.box = {
          dimensions: new Cartesian3(selected ? 32 : 24, selected ? 32 : 24, selected ? 16 : 12),
          material: new ColorMaterialProperty(gndColor),
          outline: true,
          outlineColor: Color.WHITE.withAlpha(0.9),
          heightReference: HeightReference.NONE,
        };
        entity.orientation = Transforms.headingPitchRollQuaternion(
          position,
          new HeadingPitchRoll(headingRad, 0, 0),
        ) as never;
      } else {
        entity.box = undefined;
        entity.cylinder = {
          length: selected ? 58 : 44,
          topRadius: 0,
          bottomRadius: selected ? 24 : 17,
          material: new ColorMaterialProperty(uavColor),
          outline: true,
          outlineColor: Color.WHITE.withAlpha(0.75),
          numberOfVerticalLines: 0,
          slices: 28,
          heightReference: HeightReference.NONE,
        };
        // Tip of cone faces travel/heading direction
        entity.orientation = Transforms.headingPitchRollQuaternion(
          position,
          new HeadingPitchRoll(headingRad, CesiumMath.toRadians(-90), 0),
        ) as never;
      }

      upsertTrail(viewer, d.deviceId, position, ground);
    }

    if (!fittedRef.current && devices.length > 0) {
      fittedRef.current = true;
      void viewer
        .zoomTo(viewer.entities, new HeadingPitchRange(0, CesiumMath.toRadians(-42), 0))
        .catch(() => {
          viewer.camera.flyTo({
            destination: Cartesian3.fromDegrees(devices[0].lng, devices[0].lat, 1800),
            duration: 1.1,
          });
        });
    }

    if (selectedId) {
      const ent = entitiesRef.current.get(selectedId);
      if (ent) viewer.selectedEntity = ent;
    }
  }, [devices, selectedId]);

  return <div ref={containerRef} className="map cesium-map" />;
}
