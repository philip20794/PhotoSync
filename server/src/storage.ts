import { constants } from 'node:fs';
import { access, stat } from 'node:fs/promises';

// Infrastructure check only. Never create a missing media root or media files.
export async function checkMediaRoot(root: string): Promise<void> {
  if (!(await stat(root)).isDirectory()) throw new Error('Media root is not a directory');
  await access(root, constants.R_OK | constants.W_OK | constants.X_OK);
}
