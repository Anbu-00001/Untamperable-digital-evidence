'use strict';

const fs = require('fs');
const path = require('path');
const { ConflictError, NotFoundError, samePackage, selectPreviousPackage } = require('./support');
const { storageRefFor } = require('./memoryStore');

const PACKAGES_SUBDIR = 'packages';
const MEDIA_SUBDIR = 'media';
const PACKAGE_EXTENSION = '.json';

/**
 * Durable store backed by an ordinary directory tree.
 *
 * ```
 *   <dataDir>/packages/<eventId>.json     the proof package, written once
 *   <dataDir>/media/<sha256>              the media bytes, addressed by content
 * ```
 *
 * Media is named by its own digest, so the layout is inherently
 * de-duplicating and there is no such thing as "the wrong version" of an
 * object. A package file is written with the `wx` flag — create-or-fail — so
 * immutability is enforced by the filesystem itself rather than by a
 * check-then-write race.
 *
 * NOTE ON DURABILITY: this survives restarts only where the process has a real
 * disk. A free-tier host such as Render has an ephemeral filesystem and cannot
 * attach a persistent disk, so a redeploy discards everything here. See
 * ADR-0006 §1 — Firestore is the free-tier durable option for packages.
 */
class FilesystemPackageStore {
  constructor(dataDir) {
    this.dataDir = dataDir;
    this.packagesDir = path.join(dataDir, PACKAGES_SUBDIR);
    this.mediaDir = path.join(dataDir, MEDIA_SUBDIR);
    fs.mkdirSync(this.packagesDir, { recursive: true });
    fs.mkdirSync(this.mediaDir, { recursive: true });
  }

  get driverName() {
    return 'filesystem';
  }

  putPackage(pkg) {
    const file = this._packageFile(pkg.eventId);
    const body = JSON.stringify(pkg, null, 2);
    try {
      // 'wx' = create exclusively. If the file exists this throws EEXIST rather
      // than truncating it, which is the immutability guarantee: there is no
      // window between "does it exist?" and "write it" for a concurrent request
      // to slip through.
      fs.writeFileSync(file, body, { flag: 'wx' });
      return { eventId: pkg.eventId, created: true };
    } catch (err) {
      if (err.code !== 'EEXIST') throw err;
    }

    // Already present. A byte-identical resubmission is the app's sync worker
    // retrying, which must succeed idempotently; anything else is an attempt to
    // rewrite stored evidence.
    const existing = this._readPackageFile(file);
    if (existing && samePackage(existing, pkg)) {
      return { eventId: pkg.eventId, created: false };
    }
    throw new ConflictError(
      `event ${pkg.eventId} is already stored with different content; ` +
        'stored proof packages are immutable',
    );
  }

  getPackage(eventId) {
    if (!isSafeEventId(eventId)) return null;
    return this._readPackageFile(this._packageFile(eventId));
  }

  putMedia(eventId, bytes, sha256Hex) {
    if (!this.getPackage(eventId)) {
      throw new NotFoundError(`no stored package for event ${eventId}`);
    }
    if (!/^[0-9a-f]{64}$/.test(sha256Hex)) {
      // Guards the path join: the digest becomes a filename.
      throw new Error(`refusing to store media under a non-digest name: ${sha256Hex}`);
    }
    const file = path.join(this.mediaDir, sha256Hex);
    // Content-addressed, so identical bytes need writing only once. Re-writing
    // would be harmless but pointless.
    if (!fs.existsSync(file)) {
      fs.writeFileSync(file, bytes, { flag: 'wx' });
    }
    return { storageRef: storageRefFor(sha256Hex) };
  }

  getMedia(eventId) {
    const pkg = this.getPackage(eventId);
    if (!pkg) return null;
    // The package names its own media by digest — no separate index to fall out
    // of step with the objects on disk.
    const file = path.join(this.mediaDir, pkg.media.sha256);
    if (!fs.existsSync(file)) return null;
    return fs.readFileSync(file);
  }

  findPreviousPackage(installId, beforeWallClockMillis) {
    // A full scan. Acceptable at coursework scale and honest about it: an index
    // would be mutable state that could disagree with the objects on disk, and
    // the correctness of the location cross-check matters more here than its
    // cost. Revisit if the store ever grows past a few thousand events.
    return selectPreviousPackage(this._allPackages(), installId, beforeWallClockMillis);
  }

  listEventIds() {
    return fs
      .readdirSync(this.packagesDir)
      .filter((name) => name.endsWith(PACKAGE_EXTENSION))
      .map((name) => path.basename(name, PACKAGE_EXTENSION));
  }

  _allPackages() {
    return this.listEventIds()
      .map((id) => this.getPackage(id))
      .filter(Boolean);
  }

  _packageFile(eventId) {
    return path.join(this.packagesDir, eventId + PACKAGE_EXTENSION);
  }

  /** Returns null rather than throwing: one corrupt file must not break a scan. */
  _readPackageFile(file) {
    try {
      return JSON.parse(fs.readFileSync(file, 'utf8'));
    } catch {
      return null;
    }
  }
}

/**
 * `eventId` arrives from the URL and is used to build a path, so it is
 * whitelisted to the UUID shape the schema already requires. Without this,
 * `GET /proof/..%2F..%2Fetc%2Fpasswd` would be a path-traversal read.
 */
function isSafeEventId(eventId) {
  return (
    typeof eventId === 'string' &&
    /^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$/.test(eventId)
  );
}

module.exports = { FilesystemPackageStore, isSafeEventId };
