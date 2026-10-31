/**
 * RecordHeader
 *
 * 72×72 gradient avatar + title + meta row + actions cluster for a record
 * detail page. Matches the design handoff at
 * `design_handoff_kelta_detail_layout/`.
 *
 * Migrated from kelta-ui/app so admin + runtime shells share the same
 * implementation. Uses the local UI primitives in `../ui/` so no consumer
 * primitive dependency.
 */

import React, { useCallback, useMemo, useState } from 'react';
import { Check, Copy, MoreHorizontal } from 'lucide-react';
import { Badge } from '../ui/badge';
import { Button } from '../ui/button';
import { DropdownMenu, DropdownMenuContent, DropdownMenuTrigger } from '../ui/dropdown-menu';
import { cn } from './_utils';
import { toText } from '../utils/text';

export interface RecordHeaderMetaField {
  key: string;
  icon?: React.ReactNode;
  prefix?: string;
}

export interface RecordHeaderAction {
  label: string;
  icon?: React.ReactNode;
  onClick: () => void;
  variant?: 'primary' | 'ghost';
  testId?: string;
}

export interface RecordHeaderConfig {
  titleFields?: string[];
  avatarFrom?: string[];
  metaFields?: RecordHeaderMetaField[];
}

export interface RecordHeaderProps {
  config?: RecordHeaderConfig;
  record: Record<string, unknown>;
  recordId: string;
  collectionLabel: string;
  fallbackTitle: string;
  actions?: RecordHeaderAction[];
  /** Items rendered inside the `…` More-actions DropdownMenu */
  moreMenu?: React.ReactNode;
  showPresence?: boolean;
}

/** http(s) URLs and data:image URIs are treated as avatar images, not initials sources. */
function isImageUrl(value: unknown): boolean {
  return typeof value === 'string' && /^(https?:\/\/|data:image\/)/i.test(value.trim());
}

function computeAvatarImage(
  record: Record<string, unknown>,
  avatarFrom: string[] | undefined
): string | null {
  if (!avatarFrom) return null;
  for (const f of avatarFrom) {
    const v = record[f];
    if (typeof v === 'string' && isImageUrl(v)) return v.trim();
  }
  return null;
}

function computeInitials(
  record: Record<string, unknown>,
  avatarFrom: string[] | undefined,
  fallbackTitle: string
): string {
  if (avatarFrom && avatarFrom.length > 0) {
    const chars = avatarFrom
      .map((f) => {
        const v = record[f];
        return typeof v === 'string' && v.length > 0 && !isImageUrl(v)
          ? v.charAt(0).toUpperCase()
          : '';
      })
      .filter(Boolean)
      .join('');
    if (chars) return chars.slice(0, 2);
  }
  const parts = fallbackTitle.trim().split(/\s+/);
  if (parts.length >= 2) {
    return (parts[0].charAt(0) + parts[1].charAt(0)).toUpperCase();
  }
  return fallbackTitle.charAt(0).toUpperCase() || '?';
}

function computeTitle(
  record: Record<string, unknown>,
  titleFields: string[] | undefined,
  fallbackTitle: string
): string {
  if (titleFields && titleFields.length > 0) {
    const joined = titleFields
      .map((f) => record[f])
      .filter((v) => v !== null && v !== undefined && v !== '')
      .map(String)
      .join(' ')
      .trim();
    if (joined) return joined;
  }
  return fallbackTitle;
}

export function RecordHeader({
  config,
  record,
  recordId,
  collectionLabel,
  fallbackTitle,
  actions,
  moreMenu,
  showPresence = false,
}: RecordHeaderProps): React.ReactElement {
  const [copied, setCopied] = useState(false);
  // Tracks the URL that failed to load so a record/config change retries cleanly.
  const [failedAvatarUrl, setFailedAvatarUrl] = useState<string | null>(null);

  const initials = useMemo(
    () => computeInitials(record, config?.avatarFrom, fallbackTitle),
    [record, config?.avatarFrom, fallbackTitle]
  );

  const avatarUrl = useMemo(
    () => computeAvatarImage(record, config?.avatarFrom),
    [record, config?.avatarFrom]
  );
  const showAvatarImage = avatarUrl !== null && avatarUrl !== failedAvatarUrl;

  const title = useMemo(
    () => computeTitle(record, config?.titleFields, fallbackTitle),
    [record, config?.titleFields, fallbackTitle]
  );

  const copyId = useCallback(() => {
    void navigator.clipboard.writeText(recordId).then(() => {
      setCopied(true);
      window.setTimeout(() => setCopied(false), 1200);
    });
  }, [recordId]);

  return (
    <header
      data-component="RecordHeader"
      className="flex flex-col gap-4 sm:flex-row sm:items-start sm:justify-between"
    >
      <div className="flex min-w-0 items-start gap-4">
        <div className="kelta-hero-avatar" aria-hidden="true">
          {showAvatarImage ? (
            <img
              src={avatarUrl}
              alt=""
              className="kelta-hero-avatar-img"
              onError={() => setFailedAvatarUrl(avatarUrl)}
            />
          ) : (
            initials
          )}
          {showPresence && <span className="kelta-presence-dot" aria-hidden="true" />}
        </div>

        <div className="min-w-0 space-y-1.5">
          <div className="flex items-center gap-2 kelta-copy-host">
            <Badge variant="secondary" className="font-medium">
              {collectionLabel}
            </Badge>
            <span className="font-mono text-xs text-muted-foreground">{recordId}</span>
            <button
              type="button"
              className="kelta-copy-btn"
              onClick={copyId}
              aria-label={copied ? 'Record ID copied' : 'Copy record ID'}
            >
              {copied ? (
                <Check className="h-3.5 w-3.5 text-emerald-500" aria-hidden="true" />
              ) : (
                <Copy className="h-3.5 w-3.5" aria-hidden="true" />
              )}
            </button>
          </div>

          <h1 className="kelta-page-title truncate">{title}</h1>

          {config?.metaFields && config.metaFields.length > 0 && (
            <div className="flex flex-wrap items-center gap-x-3 gap-y-1 pt-1 text-[13px] text-muted-foreground">
              {config.metaFields
                .map((m, idx) => {
                  const value = record[m.key];
                  if (value === null || value === undefined || value === '') return null;
                  return (
                    <span key={`${m.key}-${idx}`} className="inline-flex items-center gap-1.5">
                      {m.icon}
                      <span className="truncate">
                        {m.prefix}
                        {toText(value)}
                      </span>
                    </span>
                  );
                })
                .filter(Boolean)
                .reduce<React.ReactNode[]>((acc, node, idx) => {
                  if (idx > 0) {
                    acc.push(
                      <span
                        key={`sep-${idx}`}
                        aria-hidden="true"
                        className="text-muted-foreground/50"
                      >
                        ·
                      </span>
                    );
                  }
                  acc.push(node);
                  return acc;
                }, [])}
            </div>
          )}
        </div>
      </div>

      {(actions?.length || moreMenu) && (
        <div className="flex flex-shrink-0 items-center gap-2">
          {actions?.map((action, idx) => (
            <Button
              key={`${action.label}-${idx}`}
              size="sm"
              variant={action.variant === 'primary' ? 'default' : 'outline'}
              onClick={action.onClick}
              data-testid={action.testId}
              className={cn(action.variant === 'primary' && 'shadow-sm')}
            >
              {action.icon && <span className="mr-1.5 inline-flex">{action.icon}</span>}
              {action.label}
            </Button>
          ))}
          {moreMenu && (
            <DropdownMenu>
              <DropdownMenuTrigger asChild>
                <Button size="sm" variant="outline" aria-label="More actions">
                  <MoreHorizontal className="h-4 w-4" aria-hidden="true" />
                </Button>
              </DropdownMenuTrigger>
              <DropdownMenuContent align="end">{moreMenu}</DropdownMenuContent>
            </DropdownMenu>
          )}
        </div>
      )}
    </header>
  );
}
