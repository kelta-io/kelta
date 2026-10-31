import { useState, useCallback, useRef, KeyboardEvent } from 'react';
import type { ReactElement } from 'react';
import { useNavigate, useLocation } from 'react-router-dom';
import type { NavigationProps, MenuItem } from './types';

/**
 * Filter menu items to those the user can access (recursive). A parent whose
 * children were all filtered out is dropped unless it is itself actionable.
 */
function filterMenuItems(
  menuItems: MenuItem[],
  hasAccess: (item: MenuItem) => boolean
): MenuItem[] {
  return menuItems
    .filter(hasAccess)
    .map((item) => ({
      ...item,
      children: item.children ? filterMenuItems(item.children, hasAccess) : undefined,
    }))
    .filter((item) => {
      // Remove items that have children but all children were filtered out
      if (item.children && item.children.length === 0) {
        return item.path !== undefined || item.onClick !== undefined;
      }
      return true;
    });
}

/** Whether any descendant of the item is active (recursive). */
function menuItemHasActiveChild(item: MenuItem, isActive: (path?: string) => boolean): boolean {
  if (!item.children) return false;
  return item.children.some(
    (child) => isActive(child.path) || menuItemHasActiveChild(child, isActive)
  );
}

/** Flat list of the items currently visible given the expanded set (recursive). */
function flattenVisibleItems(
  menuItems: MenuItem[],
  expandedItems: Set<string>,
  parentExpanded = true
): MenuItem[] {
  if (!parentExpanded) return [];

  return menuItems.flatMap((item) => {
    const result = [item];
    if (item.children && expandedItems.has(item.id)) {
      result.push(...flattenVisibleItems(item.children, expandedItems, true));
    }
    return result;
  });
}

/**
 * Navigation component for menu rendering with React Router integration.
 *
 * Features:
 * - Renders menu items from configuration
 * - Filters items based on user roles
 * - Handles navigation on click using React Router
 * - Highlights active menu item based on current route
 * - Supports nested menus with expand/collapse
 * - Keyboard navigation support for accessibility
 *
 * @example
 * ```tsx
 * <Navigation
 *   items={[
 *     { id: 'home', label: 'Home', path: '/' },
 *     { id: 'users', label: 'Users', path: '/users', roles: ['admin'] },
 *     {
 *       id: 'settings',
 *       label: 'Settings',
 *       children: [
 *         { id: 'profile', label: 'Profile', path: '/settings/profile' },
 *         { id: 'security', label: 'Security', path: '/settings/security' },
 *       ]
 *     },
 *   ]}
 *   currentUser={user}
 *   orientation="vertical"
 * />
 * ```
 */
export function Navigation({
  items,
  currentUser = null,
  orientation = 'horizontal',
  collapsible = false,
  className = '',
  testId = 'kelta-navigation',
}: NavigationProps): ReactElement {
  const navigate = useNavigate();
  const location = useLocation();
  const [expandedItems, setExpandedItems] = useState<Set<string>>(new Set());
  const [focusedItemId, setFocusedItemId] = useState<string | null>(null);
  const navRef = useRef<HTMLElement>(null);

  // Check if user has access to a menu item
  const hasAccess = useCallback(
    (item: MenuItem): boolean => {
      if (!item.roles || item.roles.length === 0) return true;
      if (!currentUser) return false;
      return item.roles.some((role) => currentUser.roles.includes(role));
    },
    [currentUser]
  );

  // Filter items based on user roles (recursive)
  const filterItems = useCallback(
    (menuItems: MenuItem[]): MenuItem[] => filterMenuItems(menuItems, hasAccess),
    [hasAccess]
  );

  // Check if a path is active (exact match or starts with for nested routes)
  const isActive = useCallback(
    (path?: string): boolean => {
      if (!path) return false;
      // Exact match for root path
      if (path === '/') return location.pathname === '/';
      // For other paths, check if current path starts with the menu path
      return location.pathname === path || location.pathname.startsWith(`${path}/`);
    },
    [location.pathname]
  );

  // Check if any child is active (for parent highlighting)
  const hasActiveChild = useCallback(
    (item: MenuItem): boolean => menuItemHasActiveChild(item, isActive),
    [isActive]
  );

  // Toggle expanded state for an item
  const toggleExpanded = useCallback((itemId: string) => {
    setExpandedItems((prev) => {
      const next = new Set(prev);
      if (next.has(itemId)) {
        next.delete(itemId);
      } else {
        next.add(itemId);
      }
      return next;
    });
  }, []);

  // Handle item click
  const handleItemClick = useCallback(
    (item: MenuItem, event?: React.MouseEvent) => {
      event?.preventDefault();

      if (item.onClick) {
        item.onClick();
        return;
      }

      if (item.children && item.children.length > 0) {
        toggleExpanded(item.id);
        return;
      }

      if (item.path) {
        void navigate(item.path);
      }
    },
    [navigate, toggleExpanded]
  );

  // Get flat list of visible items for keyboard navigation
  const getVisibleItems = useCallback(
    (menuItems: MenuItem[], parentExpanded = true): MenuItem[] =>
      flattenVisibleItems(menuItems, expandedItems, parentExpanded),
    [expandedItems]
  );

  // Handle keyboard navigation
  const handleKeyDown = useCallback(
    (event: KeyboardEvent<HTMLElement>, item: MenuItem, visibleItems: MenuItem[]) => {
      const currentIndex = visibleItems.findIndex((i) => i.id === item.id);

      switch (event.key) {
        case 'ArrowDown':
        case 'ArrowRight': {
          event.preventDefault();
          const nextIndex = Math.min(currentIndex + 1, visibleItems.length - 1);
          setFocusedItemId(visibleItems[nextIndex].id);
          break;
        }
        case 'ArrowUp':
        case 'ArrowLeft': {
          event.preventDefault();
          const prevIndex = Math.max(currentIndex - 1, 0);
          setFocusedItemId(visibleItems[prevIndex].id);
          break;
        }
        case 'Enter':
        case ' ': {
          event.preventDefault();
          handleItemClick(item);
          break;
        }
        case 'Home': {
          event.preventDefault();
          setFocusedItemId(visibleItems[0]?.id ?? null);
          break;
        }
        case 'End': {
          event.preventDefault();
          setFocusedItemId(visibleItems[visibleItems.length - 1]?.id ?? null);
          break;
        }
        case 'Escape': {
          if (item.children && expandedItems.has(item.id)) {
            event.preventDefault();
            toggleExpanded(item.id);
          }
          break;
        }
      }
    },
    [expandedItems, handleItemClick, toggleExpanded]
  );

  // Render a menu item
  const renderItem = (
    item: MenuItem,
    depth: number = 0,
    visibleItems: MenuItem[]
  ): ReactElement => {
    const hasChildren = item.children && item.children.length > 0;
    const isExpanded = expandedItems.has(item.id);
    const active = isActive(item.path) || hasActiveChild(item);
    const isFocused = focusedItemId === item.id;

    return (
      <li
        key={item.id}
        className={`kelta-navigation__item ${active ? 'kelta-navigation__item--active' : ''} ${hasChildren ? 'kelta-navigation__item--has-children' : ''} ${isExpanded ? 'kelta-navigation__item--expanded' : ''}`}
        data-depth={depth}
        data-item-id={item.id}
        role="none"
      >
        <button
          className={`kelta-navigation__link ${isFocused ? 'kelta-navigation__link--focused' : ''}`}
          onClick={(e) => handleItemClick(item, e)}
          onKeyDown={(e) => handleKeyDown(e, item, visibleItems)}
          onFocus={() => setFocusedItemId(item.id)}
          aria-expanded={hasChildren ? isExpanded : undefined}
          aria-current={isActive(item.path) ? 'page' : undefined}
          aria-haspopup={hasChildren ? 'true' : undefined}
          role="menuitem"
          tabIndex={
            isFocused || (!focusedItemId && depth === 0 && visibleItems[0]?.id === item.id) ? 0 : -1
          }
        >
          {item.icon && (
            <span className="kelta-navigation__icon" aria-hidden="true">
              {item.icon}
            </span>
          )}
          <span className="kelta-navigation__label">{item.label}</span>
          {hasChildren && (
            <span className="kelta-navigation__arrow" aria-hidden="true">
              {isExpanded ? '▼' : '▶'}
            </span>
          )}
        </button>

        {hasChildren && isExpanded && (
          <ul
            className="kelta-navigation__submenu"
            role="menu"
            aria-label={`${item.label} submenu`}
          >
            {item.children!.map((child) => renderItem(child, depth + 1, visibleItems))}
          </ul>
        )}
      </li>
    );
  };

  const filteredItems = filterItems(items);
  const visibleItems = getVisibleItems(filteredItems);

  return (
    <nav
      ref={navRef}
      className={`kelta-navigation kelta-navigation--${orientation} ${collapsible ? 'kelta-navigation--collapsible' : ''} ${className}`}
      data-testid={testId}
      aria-label="Main navigation"
    >
      <ul className="kelta-navigation__list" role="menubar" aria-orientation={orientation}>
        {filteredItems.map((item) => renderItem(item, 0, visibleItems))}
      </ul>
    </nav>
  );
}
