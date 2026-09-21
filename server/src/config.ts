import { isAbsolute, relative, resolve, sep } from 'node:path';
import { z } from 'zod';

const integer = (fallback: string, min: number, max: number) =>
  z.string().regex(/^\d+$/).default(fallback).transform(Number).pipe(z.number().int().min(min).max(max));
const boolean = (fallback: 'true' | 'false') => z.enum(['true', 'false']).default(fallback).transform((value) => value === 'true');
const absolutePath = z.string().min(1).refine(isAbsolute).transform((value) => resolve(value));
const optionalAbsolutePath = z.union([z.literal(''), absolutePath]).default('');
const schema = z.object({
  NODE_ENV: z.enum(['development', 'test', 'production']).default('development'),
  HOST: z.string().min(1).default('0.0.0.0'),
  PORT: integer('3000', 1, 65535),
  LOG_LEVEL: z.enum(['fatal', 'error', 'warn', 'info', 'debug', 'trace', 'silent']).default('info'),
  DATABASE_URL: z.string().refine((value) => {
    try {
      const url = new URL(value);
      return ['postgresql:', 'postgres:'].includes(url.protocol) && !!url.hostname && url.pathname.length > 1;
    } catch { return false; }
  }),
  DATABASE_TIMEOUT_MS: integer('2000', 100, 30000),
  SETUP_TOKEN_HASH: z.union([z.literal(''), z.string().regex(/^[a-f0-9]{64}$/)]).default(''),
  PAIRING_CODE_TTL_SECONDS: integer('600', 60, 3600),
  AUTH_RATE_LIMIT_MAX: integer('10', 1, 1000),
  MAX_UPLOAD_BYTES: integer('1073741824', 1, 2147483647),
  API_REQUEST_TIMEOUT_MS: integer('10000', 1000, 300000),
  UPLOAD_REQUEST_TIMEOUT_MS: integer('3600000', 10000, 86400000),
  UPLOAD_LEASE_MS: integer('120000', 5000, 3600000),
  UPLOAD_RECOVERY_INTERVAL_MS: integer('30000', 1000, 3600000),
  UPLOAD_SESSION_TTL_MS: integer('604800000', 60000, 2592000000),
  MAX_UPLOAD_CHUNK_BYTES: integer('8388608', 65536, 67108864),
  DERIVATIVE_WORKER_ENABLED: boolean('true'),
  FCM_ENABLED: boolean('false'),
  SYNC_CHANGE_RETENTION_DAYS: integer('90', 7, 3650),
  DERIVATIVE_POLL_INTERVAL_MS: integer('1000', 100, 60000),
  DERIVATIVE_MAX_ATTEMPTS: integer('5', 1, 20),
  DERIVATIVE_TOOL_TIMEOUT_MS: integer('600000', 1000, 3600000),
  DERIVATIVE_BACKLOG_WARNING: integer('100', 1, 1000000),
  MEDIA_DEV_ROOT: absolutePath,
  MEDIA_PROD_ROOT: absolutePath,
  // Optional, local-only catalogue projection. It is deliberately separate
  // from the media root so it can never be mistaken for uploaded media.
  CATALOG_ROOT: optionalAbsolutePath,
}).superRefine((config, ctx) => {
  const overlaps = (parent: string, child: string) => {
    const path = relative(parent, child);
    return path === '' || (path !== '..' && !path.startsWith(`..${sep}`) && !isAbsolute(path));
  };
  if (overlaps(config.MEDIA_DEV_ROOT, config.MEDIA_PROD_ROOT) || overlaps(config.MEDIA_PROD_ROOT, config.MEDIA_DEV_ROOT)) {
    ctx.addIssue({ code: 'custom', path: ['MEDIA_PROD_ROOT'], message: 'Media roots must be separate' });
  }
  if (config.CATALOG_ROOT && (overlaps(config.MEDIA_DEV_ROOT, config.CATALOG_ROOT) ||
      overlaps(config.MEDIA_PROD_ROOT, config.CATALOG_ROOT))) {
    ctx.addIssue({ code: 'custom', path: ['CATALOG_ROOT'], message: 'Catalogue root must be separate from media roots' });
  }
  if (config.UPLOAD_REQUEST_TIMEOUT_MS < config.API_REQUEST_TIMEOUT_MS) {
    ctx.addIssue({ code: 'custom', path: ['UPLOAD_REQUEST_TIMEOUT_MS'],
      message: 'Upload timeout must be at least the regular API timeout' });
  }
  if (config.UPLOAD_RECOVERY_INTERVAL_MS >= config.UPLOAD_LEASE_MS) {
    ctx.addIssue({ code: 'custom', path: ['UPLOAD_RECOVERY_INTERVAL_MS'],
      message: 'Upload recovery interval must be shorter than the upload lease' });
  }
  if (config.UPLOAD_SESSION_TTL_MS <= config.UPLOAD_LEASE_MS) {
    ctx.addIssue({ code: 'custom', path: ['UPLOAD_SESSION_TTL_MS'],
      message: 'Upload session TTL must be longer than the upload lease' });
  }
});

export class ConfigurationError extends Error {
  constructor(readonly fields: string[]) {
    super(`Invalid environment variables: ${fields.join(', ')}`);
    this.name = 'ConfigurationError';
  }
}

export function loadConfig(env: NodeJS.ProcessEnv = process.env) {
  const result = schema.safeParse(env);
  if (!result.success) {
    throw new ConfigurationError([...new Set(result.error.issues.map((issue) => issue.path.join('.')))]);
  }
  const value = result.data;
  return Object.freeze({
    environment: value.NODE_ENV,
    host: value.HOST,
    port: value.PORT,
    logLevel: value.LOG_LEVEL,
    setupTokenHash: value.SETUP_TOKEN_HASH,
    pairingCodeTtlSeconds: value.PAIRING_CODE_TTL_SECONDS,
    authRateLimitMax: value.AUTH_RATE_LIMIT_MAX,
    maxUploadBytes: value.MAX_UPLOAD_BYTES,
    apiRequestTimeoutMs: value.API_REQUEST_TIMEOUT_MS,
    uploadRequestTimeoutMs: value.UPLOAD_REQUEST_TIMEOUT_MS,
    uploadLeaseMs: value.UPLOAD_LEASE_MS,
    uploadRecoveryIntervalMs: value.UPLOAD_RECOVERY_INTERVAL_MS,
    uploadSessionTtlMs: value.UPLOAD_SESSION_TTL_MS,
    maxUploadChunkBytes: value.MAX_UPLOAD_CHUNK_BYTES,
    derivativeWorkerEnabled: value.DERIVATIVE_WORKER_ENABLED,
    fcmEnabled: value.FCM_ENABLED,
    syncChangeRetentionDays: value.SYNC_CHANGE_RETENTION_DAYS,
    derivativePollIntervalMs: value.DERIVATIVE_POLL_INTERVAL_MS,
    derivativeMaxAttempts: value.DERIVATIVE_MAX_ATTEMPTS,
    derivativeToolTimeoutMs: value.DERIVATIVE_TOOL_TIMEOUT_MS,
    derivativeBacklogWarning: value.DERIVATIVE_BACKLOG_WARNING,
    databaseUrl: value.DATABASE_URL,
    databaseTimeoutMs: value.DATABASE_TIMEOUT_MS,
    mediaRoot: value.NODE_ENV === 'production' ? value.MEDIA_PROD_ROOT : value.MEDIA_DEV_ROOT,
    catalogRoot: value.CATALOG_ROOT || undefined,
  });
}
export type Config = ReturnType<typeof loadConfig>;
