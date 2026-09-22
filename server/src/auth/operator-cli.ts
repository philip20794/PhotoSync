import { loadConfig, ConfigurationError } from '../config.js';
import { createDatabase } from '../database.js';
import { OperatorCommandError, createOperatorService } from './operator.js';

type Command =
  | { kind: 'user-list' }
  | { kind: 'user-create'; username: string }
  | { kind: 'user-set-password'; username: string }
  | { kind: 'device-list'; username?: string }
  | { kind: 'device-revoke'; id: string };

const usageText = [
  'Usage:',
  '  operator user list',
  '  operator user create <username>',
  '  operator user set-password <username>',
  '  operator device list [username]',
  '  operator device revoke <device-uuid>',
].join('\n');

class UsageError extends Error {
  constructor(message = usageText) {
    super(message);
  }
}

export function parseOperatorArguments(args: string[]): Command {
  if (args[0] === 'user' && args[1] === 'list' && args.length === 2) return { kind: 'user-list' };
  if (args[0] === 'user' && args[1] === 'create' && args.length === 3 && args[2]?.trim()) {
    return { kind: 'user-create', username: args[2] };
  }
  if (args[0] === 'user' && args[1] === 'set-password' && args.length === 3 && args[2]?.trim()) {
    return { kind: 'user-set-password', username: args[2] };
  }
  if (args[0] === 'device' && args[1] === 'list' && (args.length === 2 || (args.length === 3 && args[2]?.trim()))) {
    return { kind: 'device-list', ...(args[2] ? { username: args[2] } : {}) };
  }
  if (args[0] === 'device' && args[1] === 'revoke' && args.length === 3 &&
      /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i.test(args[2] ?? '')) {
    return { kind: 'device-revoke', id: args[2]! };
  }
  throw new UsageError();
}

async function readHidden(prompt: string): Promise<string> {
  if (!process.stdin.isTTY || !process.stdout.isTTY || typeof process.stdin.setRawMode !== 'function') {
    throw new UsageError('Password entry requires an interactive TTY');
  }
  process.stdout.write(prompt);
  process.stdin.setRawMode(true);
  process.stdin.resume();
  process.stdin.setEncoding('utf8');
  return new Promise((resolve, reject) => {
    let value = '';
    const finish = (error?: Error) => {
      process.stdin.setRawMode(false);
      process.stdin.pause();
      process.stdin.removeListener('data', onData);
      process.stdout.write('\n');
      if (error) reject(error);
      else resolve(value);
    };
    const onData = (chunk: string) => {
      for (const character of chunk) {
        if (character === '\r' || character === '\n') return finish();
        if (character === '\u0003') return finish(new UsageError('Cancelled'));
        if (character === '\u007f' || character === '\b') value = value.slice(0, -1);
        else if (character >= ' ') value += character;
      }
    };
    process.stdin.on('data', onData);
  });
}

let database: ReturnType<typeof createDatabase> | undefined;
try {
  const command = parseOperatorArguments(process.argv.slice(2));
  const config = loadConfig();
  database = createDatabase(config);
  await database.check();
  const operator = createOperatorService(database.client);

  if (command.kind === 'user-list') {
    const users = await operator.listUsers();
    users.forEach((user) => process.stdout.write(`${user.username}\t${user.id}\t${user.displayName}\n`));
  } else if (command.kind === 'user-create') {
    const user = await operator.createUser(command.username);
    process.stdout.write(`User created: ${user.username} (${user.id})\n`);
  } else if (command.kind === 'user-set-password') {
    const first = await readHidden('Password: ');
    const second = await readHidden('Repeat password: ');
    if (first !== second) throw new UsageError('Passwords do not match');
    const user = await operator.setPassword(command.username, first);
    process.stdout.write(`Password updated: ${user.username}\n`);
  } else if (command.kind === 'device-list') {
    const devices = await operator.listDevices(command.username);
    devices.forEach((device) => process.stdout.write(
      `${device.user.username}\t${device.id}\t${device.name}\t${device.revokedAt?.toISOString() ?? 'active'}\n`,
    ));
  } else {
    await operator.revokeDevice(command.id);
    process.stdout.write(`Device revoked: ${command.id}\n`);
  }
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
