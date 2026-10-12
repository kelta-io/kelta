import type { ReactElement } from 'react';
import type { ThreeColumnLayoutProps } from './types';

/**
 * Default breakpoints for responsive behavior
 */
const DEFAULT_BREAKPOINTS = {
  mobile: 768,
  tablet: 1024,
  desktop: 1280,
};

/**
 * ThreeColumnLayout component for complex layouts with left and right sidebars.
 *
 * Features:
 * - Configurable left and right sidebar widths
 * - Responsive behavior with breakpoints
 * - Collapsible sidebars on mobile
 * - Flexbox-based layout system
 * - Supports custom styling props
 *
 * @example
 * ```tsx
 * <ThreeColumnLayout
 *   left={<LeftSidebar />}
 *   main={<MainContent />}
 *   right={<RightSidebar />}
 *   leftWidth="200px"
 *   rightWidth="300px"
 *   collapsibleOnMobile
 * />
 * ```
 *
 * @deprecated Unused by Kelta; scheduled for removal in the next major
 * release of @kelta/components. Copy the component into your app if you depend on it.
 */
export function ThreeColumnLayout({
  left,
  main,
  right,
  leftWidth = '200px',
  rightWidth = '200px',
  className = '',
  style,
  breakpoints = DEFAULT_BREAKPOINTS,
  collapsibleOnMobile = true,
  testId = 'kelta-three-column-layout',
}: ThreeColumnLayoutProps): ReactElement {
  const layoutStyle = {
    ...style,
    '--left-width': leftWidth,
    '--right-width': rightWidth,
    '--mobile-breakpoint': `${breakpoints.mobile ?? DEFAULT_BREAKPOINTS.mobile}px`,
    '--tablet-breakpoint': `${breakpoints.tablet ?? DEFAULT_BREAKPOINTS.tablet}px`,
    '--desktop-breakpoint': `${breakpoints.desktop ?? DEFAULT_BREAKPOINTS.desktop}px`,
  } as React.CSSProperties;

  return (
    <div
      className={`kelta-three-column-layout ${collapsibleOnMobile ? 'kelta-three-column-layout--collapsible' : ''} ${className}`}
      style={layoutStyle}
      data-testid={testId}
    >
      <aside
        className="kelta-three-column-layout__left"
        role="complementary"
        aria-label="Left sidebar"
      >
        {left}
      </aside>
      <main className="kelta-three-column-layout__main" role="main">
        {main}
      </main>
      <aside
        className="kelta-three-column-layout__right"
        role="complementary"
        aria-label="Right sidebar"
      >
        {right}
      </aside>
    </div>
  );
}
