import { PrismaPg } from '@prisma/adapter-pg';
import { PrismaClient } from './generated/prisma/client.js';
import type { Config } from './config.js';

export interface Database {
  readonly client?: PrismaClient;
  check(): Promise<void>;
  close(): Promise<void>;
}

export function createDatabase(config: Config) {
  const adapter = new PrismaPg({
    connectionString: config.databaseUrl,
    max: 5,
    connectionTimeoutMillis: config.databaseTimeoutMs,
    query_timeout: config.databaseTimeoutMs,
    statement_timeout: config.databaseTimeoutMs,
  });
  const client = new PrismaClient({ adapter, log: [] });
  return {
    client,
    async check() {
      const metadata = await client.serviceMetadata.findUnique({ where: { key: 'schema_version' } });
      if (metadata?.value !== '12') throw new Error('Database baseline is missing or incompatible');
    },
    async close() { await client.$disconnect(); },
  } satisfies Database & { client: PrismaClient };
}
