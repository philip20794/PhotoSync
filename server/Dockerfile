FROM node:24-bookworm-slim AS dependencies
RUN apt-get update && apt-get install -y --no-install-recommends openssl ca-certificates ffmpeg libheif-examples && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci

FROM dependencies AS development
COPY tsconfig.json prisma.config.ts ./
COPY prisma ./prisma
COPY src ./src
COPY test ./test
RUN DATABASE_URL=postgresql://unused:unused@localhost:5432/build npm run prisma:generate
RUN npm run typecheck && npm run build && chown -R node:node /app
USER node
CMD ["npm", "test"]

FROM development AS migration
CMD ["npm", "run", "migrate:deploy"]

FROM dependencies AS production-dependencies
RUN npm prune --omit=dev

FROM node:24-bookworm-slim AS runtime
RUN apt-get update && apt-get install -y --no-install-recommends ffmpeg ca-certificates libheif-examples && rm -rf /var/lib/apt/lists/*
ENV NODE_ENV=production
WORKDIR /app
COPY --from=production-dependencies --chown=node:node /app/node_modules ./node_modules
COPY --from=development --chown=node:node /app/package.json ./
COPY --from=development --chown=node:node /app/dist ./dist
USER node
EXPOSE 3000
CMD ["node", "dist/index.js"]
