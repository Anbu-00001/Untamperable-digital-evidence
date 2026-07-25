'use strict';

const crypto = require('crypto');
const canonicalize = require('canonicalize');

const { merkleRoot2Leaf } = require('../../src/services/proofVerifier');
const { sha256Hex } = require('../../src/services/hashService');

/**
 * Builds a genuinely signed, schema-valid proof package with an ephemeral P-256
 * key. Shared by the verifier and route suites so there is one fixture, not two
 * that can drift apart.
 *
 * The default timestamp block is deliberately **self-consistent**, because the
 * producer computes `wallClockMillis = floor(elapsedRealtimeNanos/1e6) + offset`:
 * 894512000 + 1783917833678 = 1784812345678, and `iso8601` denotes exactly that
 * instant. Tests break one field at a time via `overrides` and get a correctly
 * re-signed document, rather than hand-editing a signed one.
 *
 * @param {object} [overrides] eventId / installId / mediaText / location /
 *        integrity, plus a `timestamp` object merged over the defaults.
 */
function buildSignedPackage(overrides = {}) {
  const media = Buffer.from(overrides.mediaText || 'a small pretend JPEG');
  const mediaHash = sha256Hex(media);

  const metadata = {
    location: overrides.location !== undefined ? overrides.location : null,
    timestamp: {
      wallClockMillis: 1784812345678,
      iso8601: '2026-07-23T13:12:25.678Z',
      elapsedRealtimeNanos: 894512000000000,
      wallClockOffsetMillis: 1783917833678,
      gpsTimeMillis: null,
      ...(overrides.timestamp || {}),
    },
    motion: null,
    device: {
      installId: overrides.installId || 'a1b2c3d4-e5f6-4a7b-8c9d-0e1f2a3b4c5d',
      model: 'CPH2591',
      manufacturer: 'OnePlus',
      sdkInt: 35,
      appVersionName: '0.1.0',
      appVersionCode: 1,
    },
  };
  const metadataHash = sha256Hex(Buffer.from(canonicalize(metadata), 'utf8'));
  const root = merkleRoot2Leaf(mediaHash, metadataHash);

  const { privateKey, publicKey } = crypto.generateKeyPairSync('ec', { namedCurve: 'prime256v1' });
  const signature = crypto
    .createSign('SHA256')
    .update(Buffer.from(root, 'hex'))
    .sign({ key: privateKey, dsaEncoding: 'der' });

  return {
    media,
    pkg: {
      schemaUrn: 'urn:realitylock:proof-package:1.0.0',
      schemaVersion: '1.0.0',
      eventId: overrides.eventId || '3f2504e0-4f89-41d3-9a0c-0305e82c3301',
      media: {
        mimeType: 'image/jpeg',
        byteLength: media.length,
        sha256: mediaHash,
        storageRef: null,
      },
      metadata,
      canonicalization: 'RFC8785',
      merkle: {
        algorithm: 'SHA-256',
        scheme: '2-leaf',
        leaves: { media: mediaHash, metadata: metadataHash },
        root,
      },
      signature: {
        algorithm: 'SHA256withECDSA',
        value: signature.toString('base64'),
        publicKey: {
          format: 'X.509',
          curve: 'secp256r1',
          value: publicKey.export({ type: 'spki', format: 'der' }).toString('base64'),
        },
        attestationCertificateChain: null,
      },
      ...(overrides.integrity !== undefined ? { integrity: overrides.integrity } : {}),
    },
  };
}

/** A schema-shaped location block at the given coordinates. */
function locationAt(latitude, longitude, extra = {}) {
  return {
    latitude,
    longitude,
    accuracyMeters: 5,
    altitudeMeters: null,
    provider: 'fused',
    fixAgeMillis: 0,
    isMock: false,
    ...extra,
  };
}

/** Reference coordinates, mirrored in Android's LocationPlausibilityTest. */
const COORDS = {
  chennai: [13.0827, 80.2707],
  bangalore: [12.9716, 77.5946],
  newYork: [40.7128, -74.006],
};

module.exports = { buildSignedPackage, locationAt, COORDS };
