import type { Context } from '@deepseek-ai/cordis';
import Schema from '@deepseek-ai/schemastery';
import { BrowserClient } from './client.js';
import { BrowserSessions } from './sessions.js';
import { createTools } from './tools.js';
import { BackendManager, defaultRepoDir, validateBackendOptions } from './backend.js';
import type {} from '@deepseek-ai/dsh-tools';
import type {} from '@deepseek-ai/dsh-agent';

export const name = 'litongjava-browser-use';
export const inject = ['tools', 'agents'];
export interface Config {
  baseUrl: string; browser: 'auto' | 'chrome' | 'chromium' | 'edge' | 'firefox'; headless: boolean; timeoutMs: number;
  maxTextChars: number; maxResponseBytes: number; maxUploadBytes: number;
  backendAutoStart: boolean; backendAutoUpdate: boolean; backendInstallDependencies: boolean;
  backendRepoDir: string; backendRepository: 'gitee' | 'github'; backendStartupTimeoutMs: number;
}
export const Config = Schema.object({
  baseUrl: Schema.string().default('http://127.0.0.1:10049'),
  browser: Schema.union(['auto', 'chrome', 'chromium', 'edge', 'firefox']).default('chrome'),
  headless: Schema.boolean().default(false),
  timeoutMs: Schema.number().step(1).min(1).max(2147483647).default(60000),
  maxTextChars: Schema.number().step(1).min(1000).default(24000),
  maxResponseBytes: Schema.number().step(1).min(1024).default(16777216),
  maxUploadBytes: Schema.number().step(1).min(1).default(8388608),
  backendAutoStart: Schema.boolean().default(false),
  backendAutoUpdate: Schema.boolean().default(true),
  backendInstallDependencies: Schema.boolean().default(true),
  backendRepoDir: Schema.string().default(defaultRepoDir()),
  backendRepository: Schema.union(['gitee', 'github']).default('gitee'),
  backendStartupTimeoutMs: Schema.number().step(1).min(1000).max(2147483647).default(120000),
});

/** Host-side tools and optional Windows backend bootstrap. Backend lifetime is independent of individual sessions. */
export function apply(ctx: Context, input: Partial<Config> = {}): void {
  const config = Config(input) as Config;
  const backend = new BackendManager({ baseUrl: config.baseUrl, repoDir: config.backendRepoDir, repository: config.backendRepository,
    autoUpdate: config.backendAutoUpdate, installDependencies: config.backendInstallDependencies, startupTimeoutMs: config.backendStartupTimeoutMs });
  if (config.backendAutoStart) {
    validateBackendOptions(backend.options);
    backend.begin('start');
  }
  const client = new BrowserClient({ ...config, ...(config.backendAutoStart ? { ensureBackend: signal => backend.ensure(signal) } : {}) });
  const sessions = new BrowserSessions(client, { browser: config.browser, headless: config.headless });
  ctx.effect(() => () => sessions.dispose(), 'deepseek-browser-use.sessions');
  for (const tool of createTools(ctx, client, sessions, config)) ctx.tools.register(tool);
  ctx.tools.register({
    name: 'dsb_backend',
    description: 'Manage the local Windows browser backend: install missing Git/JDK/Maven, clone, fast-forward update, build, start, or restart an idle owned server. Operations run in the background. Poll status for progress and errors. status inspects the current operation; inspect refreshes server health. Existing browser sessions are never forcibly restarted.',
    parameters: { type: 'object', properties: { action: { type: 'string', enum: ['status', 'inspect', 'prepare', 'start', 'update', 'restart'] } }, required: ['action'], additionalProperties: false },
    output: { schema: { type: 'object', additionalProperties: true }, render: (_args, value) => [{ type: 'text', text: JSON.stringify(value) }] },
    async execute(raw, exec) {
      exec.signal.throwIfAborted();
      if (!exec.agent || ctx.agents.get(exec.agent.id) !== exec.agent) throw new Error('Backend management requires a live Harness Agent');
      const args = raw as { action?: string };
      if (!args || Object.keys(args).some(key => key !== 'action') || !['status', 'inspect', 'prepare', 'start', 'update', 'restart'].includes(args.action ?? '')) throw new Error('Invalid backend action');
      if (args.action === 'status') return backend.status();
      return backend.begin(args.action === 'inspect' ? 'status' : args.action as 'prepare' | 'start' | 'update' | 'restart');
    },
  });
}
