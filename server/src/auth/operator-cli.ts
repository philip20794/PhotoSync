import { loadConfig, ConfigurationError } from '../config.js';
import { createDatabase } from '../database.js';
import { OperatorCommandError, createOperatorService, type UserSelector } from './operator.js';

class UsageError extends Error {
  constructor() {
    super('Usage: operator create-pairing-code (--user <uuid> | --display-name <name>)');
  }
}
function usage(): never {
  throw new UsageError();
}

export function parseOperatorArguments(args: string[]): UserSelector {
  if (args[0] !== 'create-pairing-code' || args.length !== 3) usage();
  const value = args[2]?.trim();
  if (!value) usage();
  if (args[1] === '--user') {
    if (!/^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i.test(value)) usage();
    return { userId: value };
  }
  if (args[1] === '--display-name') return { displayName: value };
  return usage();
}

let database: ReturnType<typeof createDatabase> | undefined;
try {
  const selector = parseOperatorArguments(process.argv.slice(2));
  const config = loadConfig();
  database = createDatabase(config);
  await database.check();
  const result = await createOperatorService(database.client, config).createPairingCode(selector);
  process.stdout.write(
    `Nutzer: ${result.user.displayName} (${result.user.id})\n` +
    `Pairing-Code: ${result.code}\n` +
    `Gültigkeitsdauer: ${result.validForSeconds} Sekunden (bis ${result.expiresAt.toISOString()})\n`,
  );
} catch (error) {
  if (error instanceof OperatorCommandError || error instanceof ConfigurationError || error instanceof UsageError) {
    process.stderr.write(`${error.message}\n`);
  } else {
    process.stderr.write('Operator command failed\n');
  }
  process.exitCode = 1;
} finally {
  await database?.close();
}
