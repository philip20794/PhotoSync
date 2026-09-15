import { hashSecret, newSetupToken } from './secrets.js';

// Explicit local operator command, never called during server startup.
// The plaintext is displayed once; only SETUP_TOKEN_HASH belongs in environment files.
const setupToken = newSetupToken();
process.stdout.write(JSON.stringify({ setupToken, SETUP_TOKEN_HASH: hashSecret(setupToken, 'setup') }) + '\n');
