'use strict';

const express = require('express');
const helmet = require('helmet');
const cors = require('cors');
const morgan = require('morgan');
const rateLimit = require('express-rate-limit');

const config = require('./config');
const healthRouter = require('./routes/health');
const proofRouter = require('./routes/proof');
const verifyRouter = require('./routes/verify');

/**
 * Builds the Express app. Kept separate from server.js so it can be imported
 * by tests without binding a port.
 *
 * @param overrides optional per-instance settings. Only `rateLimit` is
 *        supported, so a test can drive the limiter to its threshold in a few
 *        requests instead of the hundreds production allows. Anything omitted
 *        falls back to the deployed configuration, so the shape under test stays
 *        the shape that ships.
 */
function createApp(overrides = {}) {
  const app = express();
  const limits = { ...config.rateLimit, ...(overrides.rateLimit || {}) };

  // How many proxies to count in from the right of X-Forwarded-For. See the
  // long note on `trustProxyHops` in config: `true` here would let any caller
  // forge the address the limiter keys on, which is the whole attack this
  // setting exists to prevent.
  app.set('trust proxy', config.trustProxyHops);

  const limiter = (limit) =>
    rateLimit({
      windowMs: limits.windowMs,
      limit,
      standardHeaders: 'draft-7',
      legacyHeaders: false,
      // Matches the app's own error envelope; the library's default is a bare
      // string, which a client parsing JSON would choke on.
      handler: (req, res) => {
        res.status(429).json({
          error: 'rate_limited',
          message: 'too many requests from this address; retry later',
        });
      },
    });

  app.use(helmet());
  app.use(
    cors({
      origin: config.allowedOrigins.includes('*') ? true : config.allowedOrigins,
    }),
  );
  if (config.env !== 'test') {
    app.use(morgan(config.env === 'development' ? 'dev' : 'combined'));
  }
  app.use(express.json({ limit: config.maxJsonBytes }));

  // Health keeps its own, much looser bucket: it is not free to serve (it lists
  // the store), but 429-ing the platform's health checker would take the service
  // out of rotation — a self-inflicted outage in the name of hardening.
  // ONE limiter instance shared by /proof and /verify, not one each: separate
  // instances keep separate counters, which would quietly grant a caller the
  // full allowance twice over.
  const apiLimiter = limiter(limits.limit);
  app.use(config.routes.health, limiter(limits.healthLimit), healthRouter);
  app.use(config.routes.proof, apiLimiter, proofRouter);
  app.use(config.routes.verify, apiLimiter, verifyRouter);

  // 404 fallthrough.
  app.use((req, res) => {
    res.status(404).json({ error: 'not_found', path: req.path });
  });

  // Central error handler.
  // eslint-disable-next-line no-unused-vars
  app.use((err, req, res, next) => {
    const status = err.status || 500;
    res.status(status).json({
      error: err.code || 'internal_error',
      message: err.message,
    });
  });

  return app;
}

module.exports = { createApp };
