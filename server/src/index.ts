import Fastify from 'fastify';

// Infrastruktur-Bootstrap ohne fachliche Routen oder Datenbankzugriff.
const app = Fastify({ logger: true });
for (const signal of ['SIGINT', 'SIGTERM'] as const) {
  process.once(signal, () => {
    void app.close().catch((error: unknown) => {
      app.log.error(error);
      process.exitCode = 1;
    });
  });
}
await app.listen({ host: '0.0.0.0', port: Number(process.env.PORT ?? 3000) });
