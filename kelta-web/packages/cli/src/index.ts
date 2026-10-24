#!/usr/bin/env node

import { createProgram, runCli } from './registry/program.js';
import { allCommands } from './registry/registry.js';

void runCli(createProgram(allCommands), process.argv.slice(2));
