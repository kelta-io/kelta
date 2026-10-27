import type { LayoutRule } from '@kelta/sdk'
import type { LayoutRuleDto } from '../hooks/usePageLayout'

/**
 * Convert a server-side LayoutRuleDto (snake_cased fields, JSONB body delivered
 * as either a parsed object or a JSON string depending on serializer) into the
 * typed LayoutRule the client engine consumes.
 *
 * Returns null if the rule cannot be converted (malformed body, unknown kind);
 * callers should filter null entries out of the resulting array.
 */
export function dtoToLayoutRule(dto: LayoutRuleDto, tenantId: string): LayoutRule | null {
  const when = parseJsonField<string[]>(dto.whenEvents, [])
  const body = parseJsonField<Record<string, unknown>>(dto.body, {})
  const depends = parseJsonField<string[]>(dto.dependsOn, [])

  const baseFields = {
    id: dto.id,
    tenantId,
    layoutId: dto.layoutId,
    name: dto.name,
    description: dto.description ?? undefined,
    active: dto.active,
    when: when as LayoutRule['when'],
    dependsOn: depends.length > 0 ? depends : undefined,
    sortOrder: dto.sortOrder,
    createdAt: '',
    updatedAt: '',
  }

  switch (dto.kind) {
    case 'COMPUTE':
      return {
        ...baseFields,
        kind: 'compute',
        target: dto.targetField ?? '',
        formula: String(body.formula ?? ''),
      }
    case 'DEFAULT':
      return {
        ...baseFields,
        kind: 'default',
        target: dto.targetField ?? '',
        formula: String(body.formula ?? ''),
        triggerFields: Array.isArray(body.triggerFields)
          ? (body.triggerFields as string[])
          : undefined,
      }
    case 'VALIDATE': {
      const enforce = body.enforce === 'warn' ? 'warn' : 'block'
      return {
        ...baseFields,
        kind: 'validate',
        target: dto.targetField ?? undefined,
        formula: String(body.formula ?? ''),
        errorMessage: String(body.errorMessage ?? ''),
        enforce,
      }
    }
    case 'TRANSFORM': {
      const t = (body.transform as { type?: string; formula?: string }) ?? {}
      let transform:
        { type: 'upper' | 'lower' | 'trim' | 'titleCase' } | { type: 'formula'; formula: string }
      if (t.type === 'formula') {
        transform = { type: 'formula', formula: String(t.formula ?? '') }
      } else if (
        t.type === 'upper' ||
        t.type === 'lower' ||
        t.type === 'trim' ||
        t.type === 'titleCase'
      ) {
        transform = { type: t.type }
      } else {
        return null
      }
      return {
        ...baseFields,
        kind: 'transform',
        target: dto.targetField ?? '',
        transform,
      }
    }
    case 'SCRIPT':
      return {
        ...baseFields,
        kind: 'script',
        target: dto.targetField ?? undefined,
        expression: String(body.expression ?? ''),
        message: typeof body.message === 'string' ? body.message : undefined,
      }
    default:
      return null
  }
}

function parseJsonField<T>(raw: unknown, fallback: T): T {
  if (raw === null || raw === undefined) return fallback
  if (typeof raw === 'string') {
    try {
      return JSON.parse(raw) as T
    } catch {
      return fallback
    }
  }
  return raw as T
}

export function dtosToLayoutRules(
  dtos: LayoutRuleDto[] | undefined,
  tenantId: string
): LayoutRule[] {
  if (!dtos) return []
  return dtos
    .map((dto) => dtoToLayoutRule(dto, tenantId))
    .filter((r): r is LayoutRule => r !== null)
}
