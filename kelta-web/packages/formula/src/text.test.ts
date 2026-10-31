import { describe, it, expect } from 'vitest';
import { toText } from './text';

describe('toText', () => {
  it('keeps String() semantics for primitives and nullish values', () => {
    expect(toText('abc')).toBe('abc');
    expect(toText(42)).toBe('42');
    expect(toText(1.5)).toBe('1.5');
    expect(toText(true)).toBe('true');
    expect(toText(10n)).toBe('10');
    expect(toText(null)).toBe('null');
    expect(toText(undefined)).toBe('undefined');
    expect(toText(Symbol('s'))).toBe('Symbol(s)');
  });

  it('keeps String() semantics for Dates', () => {
    const date = new Date('2026-01-02T03:04:05Z');
    expect(toText(date)).toBe(String(date));
  });

  it('joins arrays like Array.prototype.toString', () => {
    expect(toText([1, 'a', true])).toBe('1,a,true');
    expect(toText([1, null, undefined, 2])).toBe('1,,,2');
    expect(toText([1, [2, 3]])).toBe('1,2,3');
    expect(toText([])).toBe('');
  });

  it('JSON-encodes plain objects instead of printing [object Object]', () => {
    expect(toText({ id: 'r1', name: 'Acme' })).toBe('{"id":"r1","name":"Acme"}');
    expect(toText([{ a: 1 }])).toBe('{"a":1}');
  });
});
