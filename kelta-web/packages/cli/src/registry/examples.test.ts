import { describe, it, expect } from 'vitest';
import { z } from 'zod';
import type { Command } from 'commander';
import { allCommands } from './registry.js';
import { globalOptions, type CommandRunner } from './bind.js';
import { createProgram } from './program.js';
import { defineCommand, type RegisteredCommand } from './types.js';

/** POSIX-ish word splitting: quotes and backslash escapes, no expansion. */
function splitArgs(cmd: string): string[] {
  const words: string[] = [];
  let word: string | undefined;
  for (let i = 0; i < cmd.length; i++) {
    const ch = cmd[i];
    if (ch === "'") {
      const end = cmd.indexOf("'", i + 1);
      if (end < 0) throw new Error('unterminated single quote');
      word = (word ?? '') + cmd.slice(i + 1, end);
      i = end;
    } else if (ch === '"') {
      word ??= '';
      for (i++; cmd[i] !== '"'; i++) {
        if (i >= cmd.length) throw new Error('unterminated double quote');
        if (cmd[i] === '\\' && '"\\$`'.includes(cmd[i + 1])) i++;
        word += cmd[i];
      }
    } else if (ch === '\\') {
      word = (word ?? '') + cmd[++i];
    } else if (/\s/.test(ch)) {
      if (word !== undefined) words.push(word);
      word = undefined;
    } else if ('|;&<>`$()'.includes(ch)) {
      throw new Error(`unquoted shell metacharacter "${ch}" — an example is one invocation`);
    } else {
      word = (word ?? '') + ch;
    }
  }
  if (word !== undefined) words.push(word);
  return words;
}

function allSubcommands(command: Command): Command[] {
  return [command, ...command.commands.flatMap(allSubcommands)];
}

function fullName(def: RegisteredCommand): string {
  return ['kelta', def.group, def.name].filter(Boolean).join(' ');
}

/**
 * Parse one example through the command's own commander binding and zod
 * input. The runner replaces dispatch, so no handler, prompt or network runs.
 */
async function exampleProblem(def: RegisteredCommand, cmd: string): Promise<string | undefined> {
  let invoked = false;
  const validate: CommandRunner = (leaf, bound, rawInput) => {
    globalOptions(leaf);
    bound.input.parse(rawInput);
    invoked = true;
    return Promise.resolve();
  };
  try {
    const [bin, ...args] = splitArgs(cmd);
    if (bin !== 'kelta') return `"${cmd}" does not start with kelta`;
    const prefix = fullName(def).split(' ').slice(1);
    if (prefix.some((word, i) => args[i] !== word)) {
      return `"${cmd}" does not invoke ${fullName(def)}`;
    }
    const program = createProgram([def], validate);
    // a dropped extra word is a typo in an example
    for (const command of allSubcommands(program)) command.allowExcessArguments(false);
    await program.parseAsync(args, { from: 'user' });
  } catch (error) {
    return `"${cmd}": ${error instanceof Error ? error.message : String(error)}`;
  }
  return invoked ? undefined : `"${cmd}" did not reach ${fullName(def)}`;
}

async function exampleProblems(def: RegisteredCommand): Promise<string[]> {
  if (def.examples.length === 0) return [`${fullName(def)} declares no examples`];
  const problems = await Promise.all(def.examples.map((e) => exampleProblem(def, e.cmd)));
  return problems.filter((problem): problem is string => problem !== undefined);
}

const fixture = (examples: { cmd: string }[]): RegisteredCommand =>
  defineCommand({
    group: 'orders',
    name: 'ship',
    summary: 'fixture',
    positionals: [{ name: 'orderId', description: 'order', required: true }],
    options: [{ flag: '--carrier <name>', description: 'carrier' }],
    examples,
    input: z.object({ orderId: z.string(), carrier: z.enum(['ups', 'dhl']).optional() }),
    handler: () => {
      throw new Error('the handler must never run');
    },
  });

describe('command examples', () => {
  it.each(allCommands.map((def) => [fullName(def), def] as const))(
    '%s has examples that parse',
    async (_name, def) => {
      expect(await exampleProblems(def)).toEqual([]);
    }
  );

  it('passes a well-formed fixture example without running its handler', async () => {
    expect(
      await exampleProblems(fixture([{ cmd: "kelta orders ship ord_1 --carrier 'ups'" }]))
    ).toEqual([]);
  });

  it('names a command with zero examples', async () => {
    expect(await exampleProblems(fixture([]))).toEqual(['kelta orders ship declares no examples']);
  });

  it.each([
    ['an unknown option', 'kelta orders ship ord_1 --speed fast', /unknown option '--speed'/],
    ['a missing required positional', 'kelta orders ship', /missing required argument 'orderId'/],
    ['an input the zod schema rejects', 'kelta orders ship ord_1 --carrier fedex', /carrier/],
    ['an extra positional', 'kelta orders ship ord_1 ord_2', /too many arguments/],
    ['another command', 'kelta orders list', /does not invoke kelta orders ship/],
    ['an invalid --output', 'kelta orders ship ord_1 --output xml', /Invalid --output/],
  ])('fails an example with %s', async (_case, cmd, message) => {
    const problems = await exampleProblems(fixture([{ cmd }]));
    expect(problems).toHaveLength(1);
    expect(problems[0]).toMatch(message);
  });
});
