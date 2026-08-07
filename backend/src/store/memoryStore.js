'use strict';

const { ConflictError, NotFoundError, samePackage, selectPreviousPackage } = require('./support');

/**
 * In-process store, for tests. Enforces exactly the same immutability and
 * content-addressing rules as the filesystem driver — a test store that was
 * more permissive than production would let a real violation pass CI.
 */
class MemoryPackageStore {
  constructor() {
    this.packages = new Map(); // eventId -> package
    this.media = new Map(); // sha256hex -> Buffer
    this.mediaByEvent = new Map(); // eventId -> sha256hex
    this.anchors = new Map(); // eventId -> anchor record
  }

  get driverName() {
    return 'memory';
  }

  putPackage(pkg) {
    const existing = this.packages.get(pkg.eventId);
    if (existing) {
      if (!samePackage(existing, pkg)) {
        throw new ConflictError(
          `event ${pkg.eventId} is already stored with different content; ` +
            'stored proof packages are immutable',
        );
      }
      return { eventId: pkg.eventId, created: false };
    }
    this.packages.set(pkg.eventId, pkg);
    return { eventId: pkg.eventId, created: true };
  }

  getPackage(eventId) {
    return this.packages.get(eventId) || null;
  }

  putMedia(eventId, bytes, sha256Hex) {
    if (!this.packages.has(eventId)) {
      throw new NotFoundError(`no stored package for event ${eventId}`);
    }
    this.media.set(sha256Hex, bytes);
    this.mediaByEvent.set(eventId, sha256Hex);
    return { storageRef: storageRefFor(sha256Hex) };
  }

  getMedia(eventId) {
    const hash = this.mediaByEvent.get(eventId);
    if (!hash) return null;
    return this.media.get(hash) || null;
  }

  putAnchor(eventId, anchor) {
    if (!this.packages.has(eventId)) {
      throw new NotFoundError(`no stored package for event ${eventId}`);
    }
    const existing = this.anchors.get(eventId);
    if (existing) return { created: false, anchor: existing };
    this.anchors.set(eventId, anchor);
    return { created: true, anchor };
  }

  getAnchor(eventId) {
    return this.anchors.get(eventId) || null;
  }

  findPreviousPackage(installId, beforeWallClockMillis, predicate) {
    return selectPreviousPackage(
      [...this.packages.values()],
      installId,
      beforeWallClockMillis,
      predicate,
    );
  }

  listEventIds() {
    return [...this.packages.keys()];
  }
}

/**
 * The reference recorded in `media.storageRef`. Content-addressed, so it names
 * the bytes rather than a mutable location — the same reference stays correct
 * whichever driver holds them.
 */
function storageRefFor(sha256Hex) {
  return `sha256:${sha256Hex}`;
}

module.exports = { MemoryPackageStore, storageRefFor };
