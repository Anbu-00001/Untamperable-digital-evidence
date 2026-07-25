'use strict';

const config = require('../config');
const { MemoryPackageStore, storageRefFor } = require('./memoryStore');
const { FilesystemPackageStore, isSafeEventId } = require('./filesystemStore');
const support = require('./support');

/**
 * Storage boundary for proof packages and their media (research/02 §8 Step 8).
 *
 * Two properties hold in every driver, and a test driver that relaxed either
 * would let a real violation pass CI:
 *
 *  1. **Immutable per event.** A stored package is never overwritten. A
 *     byte-identical resubmission succeeds idempotently — the app's sync worker
 *     retries, and a retry is not an error — but a submission that would
 *     *change* a stored package is a 409. Accepting the change would let anyone
 *     with network access rewrite the history the signature exists to protect.
 *
 *  2. **Content-addressed media.** Media is keyed by its SHA-256, which the
 *     route has already checked against the signed package. Identical bytes are
 *     stored once, and the store cannot physically hold media that no
 *     validly-signed package commits to.
 *
 * ---- Interface ------------------------------------------------------------
 *   putPackage(pkg)                      -> { eventId, created }  | ConflictError
 *   getPackage(eventId)                  -> package | null
 *   putMedia(eventId, bytes, sha256Hex)  -> { storageRef }        | NotFoundError
 *   getMedia(eventId)                    -> Buffer | null
 *   findPreviousPackage(installId, beforeWallClockMillis) -> package | null
 *   listEventIds()                       -> string[]
 * ---------------------------------------------------------------------------
 *
 * Firebase Cloud Storage is deliberately absent: it has required a linked
 * billing account since 2026-02-03, while Firestore remains free on Spark.
 * See ADR-0006 §1.
 */
function createStore(driver = config.store.driver, options = {}) {
  switch (driver) {
    case 'memory':
      return new MemoryPackageStore();
    case 'filesystem':
      return new FilesystemPackageStore(options.dataDir || config.store.dataDir);
    default:
      throw new Error(`Unknown STORE_DRIVER "${driver}". Supported drivers: memory, filesystem.`);
  }
}

/**
 * Process-wide store used by the routes, created on first use so that importing
 * the app (as tests do) does not touch the filesystem until something is stored.
 */
let sharedStore = null;
function getSharedStore() {
  if (sharedStore === null) sharedStore = createStore();
  return sharedStore;
}

/** Test seam: replaces the shared store. Returns the previous one. */
function setSharedStore(store) {
  const previous = sharedStore;
  sharedStore = store;
  return previous;
}

module.exports = {
  createStore,
  getSharedStore,
  setSharedStore,
  storageRefFor,
  isSafeEventId,
  ConflictError: support.ConflictError,
  NotFoundError: support.NotFoundError,
  selectPreviousPackage: support.selectPreviousPackage,
  samePackage: support.samePackage,
};
