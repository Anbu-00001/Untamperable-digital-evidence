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

# The image mirrors the real repository layout — backend/ and docs/design/ side
# by side under /app — rather than flattening it. backend/src/config derives
# every shared path (the proof schema, the store's data directory) by climbing
# up from its own file location, and that is the exact code that must also run
# unmodified on a bare checkout. A flattened image previously broke that
# derivation silently: the schema path happened to still resolve (by an
# unrelated coincidence in where the old layout placed it), so the app booted
# and answered /health looking healthy, while the store's data directory
# resolved to a path that was never created and could never be written to —
# meaning every proof package synced from a phone failed, invisibly, until a
# real device tried it against a live deployment.
COPY backend/package.json backend/package-lock.json ./backend/
RUN cd backend && npm ci --omit=dev

COPY backend/src ./backend/src
COPY backend/scripts ./backend/scripts
COPY docs/design ./docs/design
# The pinned Google attestation roots. Omitting this line is not a degraded
# mode: `loadRoots()` fails fast, so the container exits non-zero on boot and the
# deploy fails outright — which is exactly what happened on Render 2026-08-06
# ("ENOENT: /app/backend/data/google-attestation-roots.pem", exit status 1).
#
# That outage had TWO independent causes, and either alone reproduces it: the
# `*.pem` rule in .gitignore kept the file out of the repository, and this COPY
# was missing so it would not have reached the image even once committed. Both
# are fixed; the guard below is what stops a third variant getting this far.
COPY backend/data ./backend/data

# Fail the build if the schema cannot be compiled, rather than at first request.
RUN node backend/scripts/validate-schema.js
# Same reasoning for the trust anchors: prove they load while the image is being
# built, where the error is attached to a build log a human is already reading,
# instead of at container start where it surfaces as a crash-looping deploy.
RUN node -e "require('/app/backend/src/services/attestationRoots').loadRoots()"

# The store's data directory, created and owned by the `node` user up front.
# COPY leaves files root-owned by default, and USER node below then has no
# permission to mkdir anywhere under a root-owned tree — this is what actually
# produced the EACCES the first real deploy hit.
RUN mkdir -p backend/.data && chown -R node:node /app

# Informational only — the platform injects the real PORT at runtime.
EXPOSE 3000

# Run unprivileged; the base image ships a `node` user.
USER node

CMD ["node", "backend/src/server.js"]
