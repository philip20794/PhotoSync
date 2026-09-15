import { randomUUID } from 'node:crypto';
import Fastify from 'fastify';
import rateLimit from '@fastify/rate-limit';
import { ApiError } from './errors.js';
import { createAuthService } from './auth/service.js';
import { registerAuthRoutes } from './auth/routes.js';
import { createMediaService } from './media/service.js';
import { registerMediaRoutes } from './media/routes.js';
import { createDerivativeWorker } from './media/derivatives.js';
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
    requestTimeout: 10000,
    return503OnClosing: true,
  });

  const auth = database.client ? createAuthService(database.client, config) : undefined;
  const media = database.client ? createMediaService(database.client, config) : undefined;
  const derivativeWorker = database.client && config.derivativeWorkerEnabled
    ? createDerivativeWorker(database.client, config, logger) : undefined;
  app.decorateRequest('principal', null);
  app.addHook('onRequest', async (request, reply) => {
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
  app.addHook('onReady', async () => { await derivativeWorker?.start(); });
  app.addHook('onClose', async () => { await derivativeWorker?.stop(); await database.close(); });

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

  // Readiness: database including migration baseline, and accessible selected media root.
  app.get('/health', { config: { public: true } }, async (request, reply) => {
    const results = await Promise.allSettled([database.check(), checkMediaRoot(config.mediaRoot)]);
    const checks = {
      database: results[0]?.status === 'fulfilled' ? 'ok' : 'error',
      media: results[1]?.status === 'fulfilled' ? 'ok' : 'error',
    };
    const healthy = checks.database === 'ok' && checks.media === 'ok';
    reply.header('cache-control', 'no-store');
    if (!healthy) request.log.warn({ event: 'health_failed', checks }, 'Readiness check failed');
    return reply.code(healthy ? 200 : 503).send({ status: healthy ? 'ok' : 'error', checks });
  });
  return app;
}
