import type { ReactElement } from 'react';
import type { TwoColumnLayoutProps } from './types';

/**
 * Default breakpoints for responsive behavior
 */
const DEFAULT_BREAKPOINTS = {
  mobile: 768,
  tablet: 1024,
  desktop: 1280,
};

/**
 * TwoColumnLayout component for sidebar layouts.
 *
 * Features:
 * - Configurable sidebar width and position
 * - Responsive behavior with breakpoints
 * - Collapsible sidebar on mobile
 * - Flexbox-based layout system
 * - Supports custom styling props
 *
 * @example
 * ```tsx
 * <TwoColumnLayout
 *   sidebar={<Sidebar />}
 *   main={<MainContent />}
 *   sidebarWidth="300px"
 *   sidebarPosition="left"
 *   collapsibleOnMobile
 * />
 * ```
 *
 * @deprecated Unused by Kelta; scheduled for removal in the next major
 * release of @kelta/components. Copy the component into your app if you depend on it.
 */
export function TwoColumnLayout({
  sidebar,
  main,
  sidebarWidth = '250px',
  sidebarPosition = 'left',
  className = '',
  style,
  breakpoints = DEFAULT_BREAKPOINTS,
  collapsibleOnMobile = true,
  testId = 'kelta-two-column-layout',
}: TwoColumnLayoutProps): ReactElement {
  const layoutStyle = {
    ...style,
    '--sidebar-width': sidebarWidth,
    '--mobile-breakpoint': `${breakpoints.mobile ?? DEFAULT_BREAKPOINTS.mobile}px`,
    '--tablet-breakpoint': `${breakpoints.tablet ?? DEFAULT_BREAKPOINTS.tablet}px`,
    '--desktop-breakpoint': `${breakpoints.desktop ?? DEFAULT_BREAKPOINTS.desktop}px`,
  } as React.CSSProperties;

  return (
    <div
      className={`kelta-two-column-layout kelta-two-column-layout--sidebar-${sidebarPosition} ${collapsibleOnMobile ? 'kelta-two-column-layout--collapsible' : ''} ${className}`}
      style={layoutStyle}
      data-testid={testId}
      data-sidebar-position={sidebarPosition}
    >
      {sidebarPosition === 'left' && (
        <aside
          className="kelta-two-column-layout__sidebar"
          role="complementary"
          aria-label="Sidebar"
        >
          {sidebar}
        </aside>
      )}
      <main className="kelta-two-column-layout__main" role="main">
        {main}
      </main>
      {sidebarPosition === 'right' && (
        <aside
          className="kelta-two-column-layout__sidebar"
          role="complementary"
          aria-label="Sidebar"
        >
          {sidebar}
        </aside>
      )}
    </div>
  );
}
