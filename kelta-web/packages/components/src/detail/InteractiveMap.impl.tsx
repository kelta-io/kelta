/**
 * InteractiveMap.impl
 *
 * Imports `maplibre-gl` at module level. Loaded only via React.lazy from
 * [InteractiveMap](./InteractiveMap.tsx), so the ~600 KB engine stays out
 * of the consumer's main bundle until a record actually renders an
 * interactive map.
 *
 * Consumers must import `maplibre-gl/dist/maplibre-gl.css` (e.g. in their
 * app's main entry) for the popup/controls styling to render.
 */

import React, { useEffect, useRef } from 'react';
import { Map as MapLibreMap, Marker, Popup, type StyleSpecification } from 'maplibre-gl';

export interface InteractiveMapImplProps {
  lat: number;
  lng: number;
  /** Pre-formatted label shown on the marker popup */
  label?: string;
  /** Initial zoom (default 14) */
  zoom?: number;
  /**
   * Maplibre style URL or inline style object. When omitted, defaults to a
   * minimal OpenStreetMap raster style (free, no API key). Pass a Mapbox
   * style URL + token via the `style` prop for higher quality.
   */
  style?: StyleSpecification | string;
  className?: string;
}

const OSM_STYLE: StyleSpecification = {
  version: 8,
  sources: {
    osm: {
      type: 'raster',
      tiles: ['https://tile.openstreetmap.org/{z}/{x}/{y}.png'],
      tileSize: 256,
      attribution: '© OpenStreetMap contributors',
    },
  },
  layers: [
    {
      id: 'osm',
      type: 'raster',
      source: 'osm',
    },
  ],
};

export default function InteractiveMapImpl({
  lat,
  lng,
  label,
  zoom = 14,
  style,
  className,
}: InteractiveMapImplProps): React.ReactElement {
  const container = useRef<HTMLDivElement | null>(null);
  const mapRef = useRef<MapLibreMap | null>(null);

  // Initialize map once on mount; recreate when style changes.
  useEffect(() => {
    if (!container.current) return;
    const map = new MapLibreMap({
      container: container.current,
      style: style ?? OSM_STYLE,
      center: [lng, lat],
      zoom,
      attributionControl: { compact: true },
    });
    mapRef.current = map;
    const marker = new Marker({ color: '#3b82f6' }).setLngLat([lng, lat]).addTo(map);
    if (label) {
      marker.setPopup(new Popup({ offset: 24 }).setText(label));
    }
    return () => {
      map.remove();
      mapRef.current = null;
    };
    // We intentionally only watch the style here; lat/lng/zoom/label updates
    // are forwarded via the separate effect below to avoid blowing away the
    // map instance + user-pan state on every prop change.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [style]);

  // Re-center when coords change.
  useEffect(() => {
    if (!mapRef.current) return;
    mapRef.current.setCenter([lng, lat]);
    mapRef.current.setZoom(zoom);
  }, [lat, lng, zoom]);

  return <div ref={container} className={className} />;
}
