// Only explicitly constructed public errors may expose their safe message.
export class ApiError extends Error {
  constructor(readonly statusCode: number, readonly code: string, message: string) {
    super(message);
    this.name = 'ApiError';
  }
}
