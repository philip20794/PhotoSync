import type { FastifyInstance } from 'fastify';
import type { Config } from '../config.js';
import { ApiError } from '../errors.js';
import type { AuthService, Principal } from './service.js';
import { z } from 'zod';

declare module 'fastify' {
  interface FastifyContextConfig { public?: boolean }
  interface FastifyRequest { principal: Principal | null }
}
const name = z.string().trim().min(1).max(80);
const setupBody = z.object({ displayName: name, deviceName: name }).strict();
const pairBody = z.object({ code: z.string().min(1).max(39), deviceName: name, displayName: name.optional() }).strict();
const profileBody = z.object({ displayName: name, deviceName: name }).strict();
const codeBody = z.object({ purpose: z.enum(['partner', 'device']) }).strict();
const idParams = z.object({ id: z.uuid() }).strict();

function parse<T>(schema: z.ZodType<T>, input: unknown): T {
  const result = schema.safeParse(input);
  if (!result.success) throw new ApiError(400, 'INVALID_REQUEST', 'Invalid request');
  return result.data;
}

export function registerAuthRoutes(app: FastifyInstance, config: Config, auth: AuthService | undefined) {
  const service = () => {
    if (!auth) throw new ApiError(503, 'AUTH_UNAVAILABLE', 'Authentication service unavailable');
    return auth;
  };
  const rateLimit = { max: config.authRateLimitMax, timeWindow: 60000 };
  const limited = { rateLimit };
  const publicLimited = { public: true, rateLimit };
  app.post('/v1/auth/setup', { config: publicLimited, bodyLimit: 4096 }, async (request, reply) => {
    const result = await service().setup(request.headers.authorization, parse(setupBody, request.body));
    request.log.info({ event: 'instance_configured', deviceId: result.device.id }, 'Instance configured');
    return reply.code(201).send(result);
  });
  app.post('/v1/auth/pair', { config: publicLimited, bodyLimit: 4096 }, async (request, reply) => {
    const { code, ...body } = parse(pairBody, request.body);
    const result = await service().redeem(code, body);
    request.log.info({ event: 'device_paired', deviceId: result.device.id }, 'Device paired');
    return reply.code(201).send(result);
  });
  app.post('/v1/auth/pairing-codes', { config: limited, bodyLimit: 4096 }, async (request, reply) => {
    const { purpose } = parse(codeBody, request.body);
    return reply.code(201).send(await service().createCode(request.principal!, purpose));
  });
  app.delete('/v1/auth/pairing-codes/:id', async (request, reply) => {
    await service().revokeCode(request.principal!, parse(idParams, request.params).id);
    return reply.code(204).send();
  });
  app.get('/v1/me', async (request) => service().me(request.principal!));
  app.patch('/v1/me', { bodyLimit: 4096 }, async (request) =>
    service().updateProfile(request.principal!, parse(profileBody, request.body)));
  app.get('/v1/devices', async (request) => service().devices(request.principal!));
  app.delete('/v1/devices/:id', async (request, reply) => {
    const { id } = parse(idParams, request.params);
    await service().revokeDevice(request.principal!, id);
    request.log.info({ event: 'device_revoked', deviceId: id }, 'Device revoked');
    return reply.code(204).send();
  });
}
