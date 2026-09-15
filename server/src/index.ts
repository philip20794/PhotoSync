import { buildApp } from './app.js';
import { ConfigurationError, loadConfig } from './config.js';
import { createDatabase } from './database.js';
import { createLogger } from './logger.js';
import { checkMediaRoot } from './storage.js';

let logger = createLogger('info');
let app: ReturnType<typeof buildApp> | undefined;
let database: ReturnType<typeof createDatabase> | undefined;
let stage = 'configuration';
try {
  const config = loadConfig();
  logger = createLogger(config.logLevel);
  stage = 'media_directory';
  await checkMediaRoot(config.mediaRoot);
  stage = 'database';
  database = createDatabase(config);
  await database.check();
  app = buildApp(config, database, logger);
  stage = 'listen';
  await app.listen({ host: config.host, port: config.port });
  logger.info({ event: 'started', environment: config.environment }, 'Backend ready');

  let stopping = false;
  for (const signal of ['SIGINT', 'SIGTERM'] as const) {
    process.once(signal, () => {
      if (stopping) return;
      stopping = true;
      logger.info({ event: 'shutdown', signal }, 'Stopping backend');
      const deadline = setTimeout(() => {
        logger.fatal({ event: 'shutdown_timeout' }, 'Shutdown timed out');
        process.exit(1);
      }, 10000);
      deadline.unref();
      void app!.close().catch(() => {
        logger.error({ event: 'shutdown_failed' }, 'Shutdown failed');
        process.exitCode = 1;
      }).finally(() => clearTimeout(deadline));
    });
  }
} catch (error) {
  logger.fatal({ event: 'startup_failed', stage,
    ...(error instanceof ConfigurationError ? { fields: error.fields } : {}),
  }, 'Backend startup failed');
  try {
    if (app) await app.close();
    else if (database) await database.close();
  } catch { logger.error({ event: 'cleanup_failed' }, 'Startup cleanup failed'); }
  process.exitCode = 1;
}
