# Reality Lock — Backend

Node.js + Express verification & storage service. **Phase 1 skeleton** — see
`../research/09_PROJECT_PHASES.md` for what each later phase adds here.

## What works today (Phase 1)
- `GET /health` — liveness/info.
- `POST /proof` — validates a proof package against the shared schema
  (`../docs/design/proof-package.schema.json`); returns validation errors or a
  `not_implemented_phase_5` persistence note.
- `POST /verify` — validates format and returns a per-check verdict breakdown
  (cryptographic checks are stubbed as `not_implemented_phase_5`).
- `npm run validate:schema` — checks the example package validates (CI gate seed).

## Config
All runtime config is read from the environment (see `.env.example`); nothing
is hardcoded. Copy `.env.example` to `.env` to override defaults from
`src/config/index.js`.

## Run
```bash
npm install
npm run validate:schema     # verify schema + example
npm run dev                 # start with --watch on http://localhost:3000
# or: npm start
```

## Structure
```
src/
  config/index.js      env-driven configuration (single source)
  app.js               express app factory (testable, no port binding)
  server.js            boots app; fails fast if schema won't compile
  routes/              health, proof, verify
  services/            proofSchema (ajv), hashService (SHA-256)
scripts/
  validate-schema.js   standalone schema/example verification
```
