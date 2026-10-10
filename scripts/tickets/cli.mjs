#!/usr/bin/env node
import { readFile, writeFile } from 'node:fs/promises';
import { parseInput, validateAndSummarize, TicketValidationError } from './results.mjs';

const HELP = "dsb-tickets [--input file|-] [--format input|dsb] [--date YYYY-MM-DD] [--summary] [--out file]\nOffline validation. UTF-8 JSON output; exit 0 on success, 1 on failure.\n";
let outputPath;

async function emit(value) {
  const json = JSON.stringify(value, null, 2) + String.fromCharCode(10);
  if (outputPath) {
    await writeFile(outputPath, json, 'utf8');
  } else {
    process.stdout.write(json);
  }
}

async function main() {
  const args = process.argv.slice(2);
  if (args.length === 1 && ['--help', '-h'].includes(args[0])) {
    process.stdout.write(HELP);
    return;
  }
  const settings = { input: '-', format: 'input', date: undefined, summary: false };
  for (let index = 0; index < args.length; index += 1) {
    const key = args[index];
    if (key === '--summary') {
      settings.summary = true;
      continue;
    }
    if (!['--input', '--format', '--date', '--out'].includes(key) || args[index + 1] === undefined || args[index + 1].startsWith('--')) {
      throw new TicketValidationError('USAGE', HELP.trim());
    }
    index += 1;
    if (key === '--out') {
      outputPath = args[index];
    } else {
      settings[key.slice(2)] = args[index];
    }
  }
  let input;
  if (settings.input === '-') {
    const chunks = [];
    for await (const chunk of process.stdin) {
      chunks.push(chunk);
    }
    input = Buffer.concat(chunks).toString('utf8');
  } else {
    input = await readFile(settings.input, 'utf8');
  }
  const result = validateAndSummarize(parseInput(input, settings.format), settings.date);
  if (settings.summary) {
    const { options, ...summary } = result;
    await emit(summary);
  } else {
    await emit(result);
  }
}

try {
  await main();
} catch (error) {
  const known = error instanceof TicketValidationError;
  const result = { valid: false, error: { code: known ? error.code : 'INPUT_ERROR', message: known ? error.message : 'Unable to read or validate input' } };
  try {
    await emit(result);
  } catch {
    process.stdout.write(JSON.stringify(result) + String.fromCharCode(10));
  }
  process.exitCode = 1;
}
