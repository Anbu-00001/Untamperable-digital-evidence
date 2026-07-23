'use strict';

const express = require('express');
const config = require('../config');

const router = express.Router();

router.get('/', (req, res) => {
  res.json({
    status: 'ok',
    service: 'reality-lock-backend',
    env: config.env,
    time: new Date().toISOString(),
  });
});

module.exports = router;
