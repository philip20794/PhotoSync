import { pino, type DestinationStream } from 'pino';
import type { Config } from './config.js';

export function createLogger(level: Config['logLevel'], stream?: DestinationStream) {
  const options = {
    level,
    base: { service: 'photosync-server' },
    redact: ['password', 'token', 'databaseUrl', 'authorization', 'req.headers.authorization', 'req.headers.cookie'],
    // Allowlist only: no credentials, bodies, query strings or raw database errors.
    serializers: {
      req: (request: { method: string }) => ({ method: request.method }),
      res: (reply: { statusCode: number }) => ({ statusCode: reply.statusCode }),
      err: () => ({ type: 'InternalError' }),
    },
  };
  return stream ? pino(options, stream) : pino(options);
}
