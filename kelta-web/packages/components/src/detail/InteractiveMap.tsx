/**
 * InteractiveMap
 *
 * Lazy-loaded wrapper around `InteractiveMap.impl` (maplibre-gl). Loading
 * the engine is gated on render — pages that never instantiate this
 * component pay nothing toward the bundle. Falls back to a slim loading
 * placeholder while the chunk arrives, and to its own error state if
 * maplibre-gl isn't installed (the package's peer dep is optional).
 *
 * AddressMap auto-uses this when `interactive` is true and lat/lng are
 * available; otherwise it stays with the cheaper static-image rendering.
 */

import React, { Suspense } from 'react';
import { cn } from './_utils';

const InteractiveMapImpl = React.lazy(() => import('./InteractiveMap.impl'));

/**
 * @deprecated Public export unused by Kelta; it will stop being exported from @kelta/components
 * in the next major release (it stays as an internal part of `AddressMap`).
 * Use `AddressMap` instead.
 */
export interface InteractiveMapProps {
  lat: number;
  lng: number;
  label?: string;
  zoom?: number;
  className?: string;
}

/**
 * @deprecated Public export unused by Kelta; it will stop being exported from @kelta/components
 * in the next major release (it stays as an internal part of `AddressMap`).
 * Use `AddressMap` instead.
 */
export function InteractiveMap(props: InteractiveMapProps): React.ReactElement {
  return (
    <ErrorBoundary>
      <Suspense
        fallback={
          <div
            className={cn(
              'flex h-full w-full items-center justify-center bg-muted text-[11px] text-muted-foreground',
              props.className
            )}
            aria-busy="true"
          >
            Loading map…
          </div>
        }
      >
        <InteractiveMapImpl {...props} />
      </Suspense>
    </ErrorBoundary>
  );
}

interface ErrorBoundaryState {
  hasError: boolean;
}

class ErrorBoundary extends React.Component<{ children: React.ReactNode }, ErrorBoundaryState> {
  state: ErrorBoundaryState = { hasError: false };

  static getDerivedStateFromError(): ErrorBoundaryState {
    return { hasError: true };
  }

  render(): React.ReactNode {
    if (this.state.hasError) {
      return (
        <div className="flex h-full w-full items-center justify-center bg-muted text-[11px] text-muted-foreground">
          Map unavailable — install `maplibre-gl` to enable interactive maps.
        </div>
      );
    }
    return this.props.children;
  }
}
