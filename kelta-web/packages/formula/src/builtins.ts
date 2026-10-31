import { toDouble, toBoolean } from './ast';
import { FormulaException } from './errors';
import { toText } from './text';
import type { FormulaContext, FormulaFunction } from './types';

const isBlankValue = (v: unknown): boolean => {
  if (v === null || v === undefined) return true;
  return typeof v === 'string' && v.trim() === '';
};

export const TODAY: FormulaFunction = {
  name: 'TODAY',
  execute(): string {
    const d = new Date();
    const yyyy = d.getFullYear();
    const mm = String(d.getMonth() + 1).padStart(2, '0');
    const dd = String(d.getDate()).padStart(2, '0');
    return `${yyyy}-${mm}-${dd}`;
  },
};

export const NOW: FormulaFunction = {
  name: 'NOW',
  execute(): string {
    return new Date().toISOString();
  },
};

export const ISBLANK: FormulaFunction = {
  name: 'ISBLANK',
  execute(args: unknown[]): boolean {
    if (args.length === 0) return true;
    return isBlankValue(args[0]);
  },
};

export const BLANKVALUE: FormulaFunction = {
  name: 'BLANKVALUE',
  execute(args: unknown[]): unknown {
    if (args.length < 2) throw new FormulaException('BLANKVALUE requires 2 arguments');
    return isBlankValue(args[0]) ? args[1] : args[0];
  },
};

export const IF: FormulaFunction = {
  name: 'IF',
  execute(args: unknown[]): unknown {
    if (args.length < 3) throw new FormulaException('IF requires 3 arguments');
    return toBoolean(args[0]) ? args[1] : args[2];
  },
};

export const AND_FN: FormulaFunction = {
  name: 'AND',
  execute(args: unknown[]): boolean {
    return args.every((a) => toBoolean(a));
  },
};

export const OR_FN: FormulaFunction = {
  name: 'OR',
  execute(args: unknown[]): boolean {
    return args.some((a) => toBoolean(a));
  },
};

export const NOT_FN: FormulaFunction = {
  name: 'NOT',
  execute(args: unknown[]): boolean {
    if (args.length === 0) throw new FormulaException('NOT requires 1 argument');
    return !toBoolean(args[0]);
  },
};

export const LEN: FormulaFunction = {
  name: 'LEN',
  execute(args: unknown[]): number {
    if (args.length === 0 || args[0] === null || args[0] === undefined) return 0;
    return toText(args[0]).length;
  },
};

export const CONTAINS: FormulaFunction = {
  name: 'CONTAINS',
  execute(args: unknown[]): boolean {
    if (args.length < 2) throw new FormulaException('CONTAINS requires 2 arguments');
    const text = args[0] != null ? toText(args[0]) : '';
    const search = args[1] != null ? toText(args[1]) : '';
    return text.includes(search);
  },
};

export const UPPER: FormulaFunction = {
  name: 'UPPER',
  execute(args: unknown[]): string | null {
    if (args.length === 0 || args[0] === null || args[0] === undefined) return null;
    return toText(args[0]).toUpperCase();
  },
};

export const LOWER: FormulaFunction = {
  name: 'LOWER',
  execute(args: unknown[]): string | null {
    if (args.length === 0 || args[0] === null || args[0] === undefined) return null;
    return toText(args[0]).toLowerCase();
  },
};

export const TRIM: FormulaFunction = {
  name: 'TRIM',
  execute(args: unknown[]): string | null {
    if (args.length === 0 || args[0] === null || args[0] === undefined) return null;
    return toText(args[0]).trim();
  },
};

export const TEXT: FormulaFunction = {
  name: 'TEXT',
  execute(args: unknown[]): string {
    if (args.length === 0 || args[0] === null || args[0] === undefined) return '';
    return toText(args[0]);
  },
};

export const VALUE: FormulaFunction = {
  name: 'VALUE',
  execute(args: unknown[]): number {
    if (args.length === 0 || args[0] === null || args[0] === undefined) return 0;
    return toDouble(args[0]);
  },
};

export const ROUND: FormulaFunction = {
  name: 'ROUND',
  execute(args: unknown[]): number {
    if (args.length < 2) throw new FormulaException('ROUND requires 2 arguments');
    const num = toDouble(args[0]);
    const places = Math.trunc(toDouble(args[1]));
    const factor = Math.pow(10, places);
    return Math.round(num * factor) / factor;
  },
};

export const ABS: FormulaFunction = {
  name: 'ABS',
  execute(args: unknown[]): number {
    if (args.length === 0) throw new FormulaException('ABS requires 1 argument');
    return Math.abs(toDouble(args[0]));
  },
};

export const MAX_FN: FormulaFunction = {
  name: 'MAX',
  execute(args: unknown[]): number {
    if (args.length < 2) throw new FormulaException('MAX requires 2 arguments');
    return Math.max(toDouble(args[0]), toDouble(args[1]));
  },
};

export const MIN_FN: FormulaFunction = {
  name: 'MIN',
  execute(args: unknown[]): number {
    if (args.length < 2) throw new FormulaException('MIN requires 2 arguments');
    return Math.min(toDouble(args[0]), toDouble(args[1]));
  },
};

export const REGEX: FormulaFunction = {
  name: 'REGEX',
  execute(args: unknown[]): boolean {
    if (args.length < 2) throw new FormulaException('REGEX requires 2 arguments');
    const text = args[0] != null ? toText(args[0]) : '';
    const pattern = args[1] != null ? toText(args[1]) : '';
    const re = new RegExp(`^${pattern}$`);
    return re.test(text);
  },
};

export const DATEDIFF: FormulaFunction = {
  name: 'DATEDIFF',
  execute(args: unknown[]): number {
    if (args.length < 2) throw new FormulaException('DATEDIFF requires 2 arguments');
    const d1 = toLocalDate(args[0]);
    const d2 = toLocalDate(args[1]);
    const ms = d1.getTime() - d2.getTime();
    return Math.round(ms / (1000 * 60 * 60 * 24));
  },
};

function toLocalDate(val: unknown): Date {
  if (val instanceof Date) return val;
  if (typeof val === 'string') {
    const iso = val.length >= 10 ? val.substring(0, 10) : val;
    const m = /^(\d{4})-(\d{2})-(\d{2})$/.exec(iso);
    if (!m) throw new FormulaException(`Cannot convert to date: ${val}`);
    return new Date(Number(m[1]), Number(m[2]) - 1, Number(m[3]));
  }
  throw new FormulaException(`Cannot convert to date: ${String(val)}`);
}

export const ALL_BUILTINS: FormulaFunction[] = [
  TODAY,
  NOW,
  ISBLANK,
  BLANKVALUE,
  IF,
  AND_FN,
  OR_FN,
  NOT_FN,
  LEN,
  CONTAINS,
  UPPER,
  LOWER,
  TRIM,
  TEXT,
  VALUE,
  ROUND,
  ABS,
  MAX_FN,
  MIN_FN,
  REGEX,
  DATEDIFF,
];

export function buildBuiltinMap(extra: FormulaFunction[] = []): Map<string, FormulaFunction> {
  const m = new Map<string, FormulaFunction>();
  for (const fn of ALL_BUILTINS) m.set(fn.name.toUpperCase(), fn);
  for (const fn of extra) m.set(fn.name.toUpperCase(), fn);
  return m;
}

export type { FormulaContext, FormulaFunction };
