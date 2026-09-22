import { argon2id, hash, verify } from 'argon2';

const options = {
  type: argon2id,
  memoryCost: 19 * 1024,
  timeCost: 2,
  parallelism: 1,
};

export function normalizeUsername(value: string): string {
  return value.normalize('NFKC').trim().toLowerCase();
}

export function validateNewPassword(value: string): void {
  if (value.length < 12 || value.length > 1024) {
    throw new Error('Password must contain between 12 and 1024 characters');
  }
}

export async function hashPassword(value: string): Promise<string> {
  validateNewPassword(value);
  return hash(value, options);
}

export async function verifyPassword(encoded: string, value: string): Promise<boolean> {
  try {
    return await verify(encoded, value);
  } catch {
    return false;
  }
}

export const dummyPasswordHash = hash('PhotoSync invalid login sentinel', options);
