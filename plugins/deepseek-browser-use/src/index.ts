import type { Context } from '@deepseek-ai/cordis';
import Schema from '@deepseek-ai/schemastery';
import { BrowserClient } from './client.js';
import { BrowserSessions } from './sessions.js';
import { createTools } from './tools.js';
import type {} from '@deepseek-ai/dsh-tools';
import type {} from '@deepseek-ai/dsh-agent';

export const name = 'litongjava-browser-use';
export const inject = ['tools', 'agents'];
export interface Config {
  baseUrl: string; browser: 'auto' | 'chrome' | 'chromium' | 'edge' | 'firefox'; headless: boolean; timeoutMs: number;
  maxTextChars: number; maxResponseBytes: number; maxUploadBytes: number;
}
export const Config = Schema.object({
  baseUrl: Schema.string().default('http://127.0.0.1:10049'),
  browser: Schema.union(['auto', 'chrome', 'chromium', 'edge', 'firefox']).default('chrome'),
  headless: Schema.boolean().default(false),
  timeoutMs: Schema.number().step(1).min(1).max(2147483647).default(60000),
  maxTextChars: Schema.number().step(1).min(1000).default(24000),
  maxResponseBytes: Schema.number().step(1).min(1024).default(16777216),
  maxUploadBytes: Schema.number().step(1).min(1).default(8388608),
});

/** Host-side tools, scoped by exact live Agent identity; the Java service remains externally owned. */
export function apply(ctx: Context, input: Partial<Config> = {}): void {
  const config = Config(input) as Config;
  const client = new BrowserClient(config);
  const sessions = new BrowserSessions(client, { browser: config.browser, headless: config.headless });
  ctx.effect(() => () => sessions.dispose(), 'deepseek-browser-use.sessions');
  for (const tool of createTools(ctx, client, sessions, config)) ctx.tools.register(tool);
}
