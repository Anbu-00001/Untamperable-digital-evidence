'use strict';

const express = require('express');
const helmet = require('helmet');
const cors = require('cors');
const morgan = require('morgan');

const config = require('./config');
const healthRouter = require('./routes/health');
const proofRouter = require('./routes/proof');
const verifyRouter = require('./routes/verify');

/**
 * Builds the Express app. Kept separate from server.js so it can be imported
 * by tests without binding a port.
 */
function createApp() {
  const app = express();

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

  app.use(config.routes.health, healthRouter);
  app.use(config.routes.proof, proofRouter);
  app.use(config.routes.verify, verifyRouter);

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
