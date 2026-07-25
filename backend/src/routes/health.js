'use strict';

const express = require('express');
const config = require('../config');
const { getSharedStore } = require('../store');

const router = express.Router();

router.get('/', (req, res) => {
  res.json({
    status: 'ok',
    service: config.serviceName,
    env: config.env,
    time: new Date().toISOString(),
    // Which persistence driver is live, and how many events it holds. Surfaced
    // because "the sync worker reported success" and "the packages are actually
    // stored" are separate claims, and a deployment on an ephemeral filesystem
    // can silently lose the second one (ADR-0006 §1).
    store: storeStatus(),
  });
});

function storeStatus() {
  try {
    const store = getSharedStore();
    return { driver: store.driverName, events: store.listEventIds().length };
  } catch (err) {
    // A health check must report a broken store, not fail to answer.
    return { driver: config.store.driver, error: err.message };
  }
}

module.exports = router;
