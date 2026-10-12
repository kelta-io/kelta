import type { ReactNode, CSSProperties } from 'react';

/**
 * Responsive breakpoint configuration
 *
 * @deprecated Unused by Kelta; scheduled for removal in the next major
 * release of @kelta/components. Copy the type into your app if you depend on it.
 */
export interface ResponsiveBreakpoints {
  /**
   * Mobile breakpoint (default: 768px)
   */
  mobile?: number;
  /**
   * Tablet breakpoint (default: 1024px)
   */
  tablet?: number;
  /**
   * Desktop breakpoint (default: 1280px)
   */
  desktop?: number;
}

/**
 * Props for PageLayout component
 *
 * @deprecated Unused by Kelta; scheduled for removal in the next major
 * release of @kelta/components. Copy the type into your app if you depend on it.
 */
export interface PageLayoutProps {
  /**
   * Main content
   */
  children: ReactNode;

  /**
   * Header content (optional)
   */
  header?: ReactNode;

  /**
   * Footer content (optional)
   */
  footer?: ReactNode;

  /**
   * Custom class name
   */
  className?: string;

  /**
   * Custom styles
   */
  style?: CSSProperties;

  /**
   * Test ID for testing
   */
  testId?: string;
}

/**
 * Props for TwoColumnLayout component
 *
 * @deprecated Unused by Kelta; scheduled for removal in the next major
 * release of @kelta/components. Copy the type into your app if you depend on it.
 */
export interface TwoColumnLayoutProps {
  /**
   * Sidebar content
   */
  sidebar: ReactNode;

  /**
   * Main content
   */
  main: ReactNode;

  /**
   * Sidebar width (CSS value)
   */
  sidebarWidth?: string;

  /**
   * Sidebar position
   */
  sidebarPosition?: 'left' | 'right';

  /**
   * Custom class name
   */
  className?: string;

  /**
   * Custom styles
   */
  style?: CSSProperties;

  /**
   * Responsive breakpoints
   */
  breakpoints?: ResponsiveBreakpoints;

  /**
   * Whether sidebar collapses on mobile
   */
  collapsibleOnMobile?: boolean;

  /**
   * Test ID for testing
   */
  testId?: string;
}

/**
 * Props for ThreeColumnLayout component
 *
 * @deprecated Unused by Kelta; scheduled for removal in the next major
 * release of @kelta/components. Copy the type into your app if you depend on it.
 */
export interface ThreeColumnLayoutProps {
  /**
   * Left sidebar content
   */
  left: ReactNode;

  /**
   * Main content
   */
  main: ReactNode;

  /**
   * Right sidebar content
   */
  right: ReactNode;

  /**
   * Left sidebar width (CSS value)
   */
  leftWidth?: string;

  /**
   * Right sidebar width (CSS value)
   */
  rightWidth?: string;

  /**
   * Custom class name
   */
  className?: string;

  /**
   * Custom styles
   */
  style?: CSSProperties;

  /**
   * Responsive breakpoints
   */
  breakpoints?: ResponsiveBreakpoints;

  /**
   * Whether sidebars collapse on mobile
   */
  collapsibleOnMobile?: boolean;

  /**
   * Test ID for testing
   */
  testId?: string;
}
