# Portable deployment for the Reality Lock backend (Railway, Fly.io, Cloud Run,
# or Render's Docker runtime). Prefer render.yaml if deploying to Render.
#
# IMPORTANT: build from the REPOSITORY ROOT, not from backend/:
#   docker build -t reality-lock-backend .
# The image needs docs/design/, which lives outside backend/. The schema is the
# contract shared with the Android app and is deliberately never duplicated.

FROM node:22-alpine

ENV NODE_ENV=production
WORKDIR /app

# Dependencies first, so edits to source don't bust the layer cache.
COPY backend/package.json backend/package-lock.json ./
RUN npm ci --omit=dev

COPY backend/src ./src
COPY backend/scripts ./scripts

# The shared schema + example, at the path src/config resolves by default
# (../../docs/design relative to backend/src/config → /docs/design here).
COPY docs/design/proof-package.schema.json /docs/design/proof-package.schema.json
COPY docs/design/examples/proof-package.example.json /docs/design/examples/proof-package.example.json

# Fail the build if the schema cannot be compiled, rather than at first request.
RUN node scripts/validate-schema.js

# Informational only — the platform injects the real PORT at runtime.
EXPOSE 3000

# Run unprivileged; the base image ships a `node` user.
USER node

CMD ["node", "src/server.js"]
