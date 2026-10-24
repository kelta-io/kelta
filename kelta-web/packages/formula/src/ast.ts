import { FormulaException } from './errors';
import type { FormulaContext } from './types';

export type FormulaAst =
  | { kind: 'literal'; value: unknown }
  | { kind: 'fieldRef'; fieldName: string }
  | { kind: 'binaryOp'; operator: BinaryOperator; left: FormulaAst; right: FormulaAst }
  | { kind: 'unaryOp'; operator: UnaryOperator; operand: FormulaAst }
  | { kind: 'functionCall'; functionName: string; arguments: FormulaAst[] };

export type BinaryOperator =
  '+' | '-' | '*' | '/' | '>' | '<' | '>=' | '<=' | '=' | '==' | '!=' | '<>' | '&&' | '||';

export type UnaryOperator = '-' | '!';

export function evaluateAst(node: FormulaAst, context: FormulaContext): unknown {
  switch (node.kind) {
    case 'literal':
      return node.value;
    case 'fieldRef': {
      const v = context.fieldValues[node.fieldName];
      return v === undefined ? null : v;
    }
    case 'binaryOp':
      return evaluateBinary(
        node.operator,
        evaluateAst(node.left, context),
        evaluateAst(node.right, context)
      );
    case 'unaryOp':
      return evaluateUnary(node.operator, evaluateAst(node.operand, context));
    case 'functionCall': {
      const fn = context.functions.get(node.functionName.toUpperCase());
      if (!fn) throw new FormulaException(`Unknown function: ${node.functionName}`);
      const args = node.arguments.map((a) => evaluateAst(a, context));
      return fn.execute(args, context);
    }
  }
}

function evaluateBinary(op: BinaryOperator, left: unknown, right: unknown): unknown {
  switch (op) {
    case '+':
      return add(left, right);
    case '-':
      return toDouble(left) - toDouble(right);
    case '*':
      return toDouble(left) * toDouble(right);
    case '/': {
      const divisor = toDouble(right);
      if (divisor === 0) throw new FormulaException('Division by zero');
      return toDouble(left) / divisor;
    }
    case '>':
      return compare(left, right) > 0;
    case '<':
      return compare(left, right) < 0;
    case '>=':
      return compare(left, right) >= 0;
    case '<=':
      return compare(left, right) <= 0;
    case '=':
    case '==':
      return objectEquals(left, right);
    case '!=':
    case '<>':
      return !objectEquals(left, right);
    case '&&':
      return toBoolean(left) && toBoolean(right);
    case '||':
      return toBoolean(left) || toBoolean(right);
  }
}

function evaluateUnary(op: UnaryOperator, val: unknown): unknown {
  switch (op) {
    case '-':
      return -toDouble(val);
    case '!':
      return !toBoolean(val);
  }
}

function add(a: unknown, b: unknown): unknown {
  if (typeof a === 'string' || typeof b === 'string') {
    return stringify(a) + stringify(b);
  }
  return toDouble(a) + toDouble(b);
}

function stringify(v: unknown): string {
  if (v === null) return 'null';
  if (v === undefined) return 'null';
  return String(v);
}

function compare(a: unknown, b: unknown): number {
  if (a === null || a === undefined || b === null || b === undefined) return 0;
  if (typeof a === 'number' && typeof b === 'number') {
    return a < b ? -1 : a > b ? 1 : 0;
  }
  if (typeof a === 'number' || typeof b === 'number') {
    const da = toDouble(a);
    const db = toDouble(b);
    return da < db ? -1 : da > db ? 1 : 0;
  }
  const sa = String(a);
  const sb = String(b);
  return sa < sb ? -1 : sa > sb ? 1 : 0;
}

function objectEquals(a: unknown, b: unknown): boolean {
  if ((a === null || a === undefined) && (b === null || b === undefined)) return true;
  if (a === null || a === undefined || b === null || b === undefined) return false;
  if (typeof a === 'number' && typeof b === 'number') return a === b;
  return a === b;
}

export function toDouble(value: unknown): number {
  if (value === null || value === undefined) return 0;
  if (typeof value === 'number') return value;
  if (typeof value === 'boolean') return value ? 1 : 0;
  const n = Number(String(value));
  if (Number.isNaN(n)) throw new FormulaException(`Cannot convert to number: ${String(value)}`);
  return n;
}

export function toBoolean(value: unknown): boolean {
  if (value === null || value === undefined) return false;
  if (typeof value === 'boolean') return value;
  if (typeof value === 'number') return value !== 0;
  return String(value).toLowerCase() === 'true';
}

export function extractFieldRefs(node: FormulaAst, out: Set<string> = new Set()): Set<string> {
  switch (node.kind) {
    case 'literal':
      break;
    case 'fieldRef':
      out.add(node.fieldName);
      break;
    case 'binaryOp':
      extractFieldRefs(node.left, out);
      extractFieldRefs(node.right, out);
      break;
    case 'unaryOp':
      extractFieldRefs(node.operand, out);
      break;
    case 'functionCall':
      for (const arg of node.arguments) extractFieldRefs(arg, out);
      break;
  }
  return out;
}
