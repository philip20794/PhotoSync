import { randomUUID } from 'node:crypto';
import Fastify from 'fastify';
import rateLimit from '@fastify/rate-limit';
import { ApiError } from './errors.js';
import { createAuthService } from './auth/service.js';
import { registerAuthRoutes } from './auth/routes.js';
import { createMediaService } from './media/service.js';
import { registerMediaRoutes } from './media/routes.js';
import { registerSyncRoutes } from './sync/routes.js';
import { createPushWorker } from './sync/push.js';
import { createChangeJournalWorker } from './sync/retention.js';
import { createDerivativeWorker } from './media/derivatives.js';
import { createOriginalIntegrityWorker } from './media/originals.js';
import { createMediaCatalogue } from './media/catalog.js';
import type { Logger } from 'pino';
import type { Config } from './config.js';
import type { Database } from './database.js';
import { createLogger } from './logger.js';
import { checkMediaRoot } from './storage.js';

export function buildApp(config: Config, database: Database, logger: Logger = createLogger(config.logLevel)) {
  const app = Fastify({
    loggerInstance: logger,
    genReqId: () => randomUUID(),
    requestIdHeader: false,
    bodyLimit: 1024 * 1024,
    requestTimeout: config.uploadRequestTimeoutMs,
    return503OnClosing: true,
  });

  const auth = database.client ? createAuthService(database.client, config) : undefined;
  const media = database.client ? createMediaService(database.client, config) : undefined;
  const pushWorker = database.client && config.fcmEnabled ? createPushWorker(database.client, logger) : undefined;
  const journalWorker = database.client ? createChangeJournalWorker(database.client, config.syncChangeRetentionDays, logger) : undefined;
  const derivativeWorker = database.client && config.derivativeWorkerEnabled
    ? createDerivativeWorker(database.client, config, logger) : undefined;
  const originalWorker = database.client ? createOriginalIntegrityWorker(database.client, config, logger) : undefined;
  const catalogue = database.client && config.catalogRoot
    ? createMediaCatalogue(database.client, config, logger) : undefined;
  app.decorateRequest('principal', null);
  const requestDeadlines = new WeakMap<object, NodeJS.Timeout>();
  const clearDeadline = (request: object) => {
    const timer = requestDeadlines.get(request);
    if (timer) clearTimeout(timer);
    requestDeadlines.delete(request);
  };
  app.addHook('onRequest', async (request, reply) => {
    const upload = (request.routeOptions.config as { longRunningUpload?: boolean }).longRunningUpload === true;
    const timeoutMs = upload ? config.uploadRequestTimeoutMs : config.apiRequestTimeoutMs;
    if (typeof request.raw.setTimeout === 'function') request.raw.setTimeout(timeoutMs);
    const deadline = setTimeout(() => {
      if (!reply.sent) request.raw.destroy(new Error('Request deadline exceeded'));
    }, timeoutMs);
    deadline.unref();
    requestDeadlines.set(request, deadline);
    reply.header('x-request-id', request.id);
    reply.header('cache-control', 'no-store');
    // Fail closed for every route, including future APIs, unless explicitly public.
    if (request.routeOptions.config.public !== true) {
      if (!auth) throw new ApiError(401, 'UNAUTHORIZED', 'Valid device credentials required');
      request.principal = await auth.authenticate(request.headers.authorization);
    }
  });
  // Keep original bytes as a stream. The upload handler performs its own strict size checks.
  app.addContentTypeParser('application/octet-stream', (_request, payload, done) => {
    done(null, payload);
  });
  app.register(rateLimit, { global: false });
  app.register(async (routes) => { registerAuthRoutes(routes, config, auth); });
  app.register(async (routes) => { registerMediaRoutes(routes, config, media); });
  app.register(async (routes) => { registerSyncRoutes(routes, database.client); });
  app.addHook('onResponse', async (request) => { clearDeadline(request); });
  app.addHook('onError', async (request) => { clearDeadline(request); });
  app.addHook('onRequestAbort', async (request) => { clearDeadline(request); });
  app.addHook('onReady', async () => {
    await media?.startUploadRecovery();
    await originalWorker?.start();
    await catalogue?.start();
    await media?.startTrashCleanup();
    await derivativeWorker?.start();
    pushWorker?.start();
    journalWorker?.start();
  });
  app.addHook('onClose', async () => {
    await originalWorker?.stop();
    await catalogue?.stop();
    await media?.stopUploadRecovery();
    await media?.stopTrashCleanup();
    await derivativeWorker?.stop();
    await pushWorker?.stop();
    await journalWorker?.stop();
    await database.close();
  });

  app.setNotFoundHandler((request, reply) => {
    return reply.code(404).send({ error: { code: 'NOT_FOUND', message: 'Route not found', requestId: request.id } });
  });
  app.setErrorHandler((error, request, reply) => {
    const status = error instanceof ApiError ? error.statusCode : error instanceof Error && 'statusCode' in error && typeof error.statusCode === 'number'
      && error.statusCode >= 400 && error.statusCode < 500 ? error.statusCode : 500;
    const code = error instanceof ApiError ? error.code : status === 429 ? 'RATE_LIMITED' : status >= 500 ? 'INTERNAL_ERROR' : 'INVALID_REQUEST';
    if (status === 401) reply.header('www-authenticate', 'Bearer');
    request.log[status >= 500 ? 'error' : 'warn']({ event: 'request_failed', statusCode: status }, 'Request failed');
    return reply.code(status).send({ error: {
      code, message: error instanceof ApiError ? error.message : status >= 500 ? 'Internal server error' : status === 429 ? 'Too many requests' : 'Invalid request', requestId: request.id,
    } });
  });

  app.get('/health/live', { config: { public: true } }, async (_request, reply) => {
    reply.header('cache-control', 'no-store');
    return { status: 'ok' };
  });

  // Readiness checks hard dependencies. A derivative queue backlog is reported as degraded
  // but does not cause an orchestrator restart loop.
  app.get('/health', { config: { public: true } }, async (request, reply) => {
    const results = await Promise.allSettled([
      database.check(),
      checkMediaRoot(config.mediaRoot),
      config.derivativeWorkerEnabled ? derivativeWorker?.diagnostics() : Promise.resolve(undefined),
      originalWorker?.diagnostics(),
    ]);
    const derivativeDetails = !config.derivativeWorkerEnabled
      ? { status: 'disabled', worker: 'disabled' }
      : results[2]?.status === 'fulfilled' && results[2].value
        ? results[2].value
        : { status: 'error', worker: 'unavailable', ffmpeg: 'error', ffprobe: 'error' };
    const originalDetails = !database.client
      ? { status: 'disabled', errors: 0, checkedInCycle: 0, lastCycleAt: null }
      : results[3]?.status === 'fulfilled' && results[3].value
        ? results[3].value
        : { status: 'error', errors: 0, checkedInCycle: 0, lastCycleAt: null };
    const checks = {
      database: results[0]?.status === 'fulfilled' ? 'ok' : 'error',
      media: results[1]?.status === 'fulfilled' ? 'ok' : 'error',
      derivatives: derivativeDetails.status,
      originals: originalDetails.status,
    };
    const healthy = checks.database === 'ok' && checks.media === 'ok' && checks.derivatives !== 'error' && checks.originals !== 'error';
    reply.header('cache-control', 'no-store');
    if (!healthy) request.log.warn({ event: 'health_failed', checks }, 'Readiness check failed');
    else if (checks.derivatives === 'degraded') {
      request.log.warn({ event: 'derivative_health_degraded', derivatives: derivativeDetails }, 'Derivative processing is degraded');
    }
    if (checks.originals === 'degraded') {
      request.log.error({ event: 'original_health_degraded', originals: originalDetails }, 'Stored originals are degraded');
    }
    return reply.code(healthy ? 200 : 503).send({
      status: healthy ? 'ok' : 'error',
      checks,
      derivatives: derivativeDetails,
      originals: originalDetails,
    });
  });
  return app;
}
