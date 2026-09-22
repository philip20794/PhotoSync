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
const loginBody = z.object({
  username: name,
  password: z.string().min(1).max(1024),
  deviceName: name,
}).strict();
const profileBody = z.object({ displayName: name, deviceName: name }).strict();
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

  app.post('/v1/auth/login', {
    config: { public: true, rateLimit: { max: config.authRateLimitMax, timeWindow: 60000 } },
    bodyLimit: 4096,
  }, async (request, reply) => {
    const result = await service().login(parse(loginBody, request.body));
    request.log.info({ event: 'device_login', deviceId: result.device.id }, 'Device logged in');
    return reply.code(201).send(result);
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
