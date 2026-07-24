'use strict';

const test = require('node:test');
const assert = require('node:assert');
const crypto = require('crypto');
const canonicalize = require('canonicalize');

const { merkleRoot2Leaf, verifyProofPackage, PASS, FAIL, UNAVAILABLE } =
  require('../src/services/proofVerifier');
const { sha256Hex } = require('../src/services/hashService');

/**
 * The cross-implementation test vector, asserted identically here and in
 * Android's CryptoCoreTest. Both sides check a fixed known answer rather than
 * each other, so a drift in either is caught immediately and locally instead of
 * surfacing later as an unexplained signature mismatch on a real capture.
 *
 * Source of truth: MerkleTree.CROSS_IMPL_TEST_VECTOR (android/.../crypto/MerkleTree.kt)
 */
const VECTOR = {
  mediaInput: 'reality-lock-media-test-vector',
  metadataInput: 'reality-lock-metadata-test-vector',
  mediaHash: '0c8655110a97d6ffb2f8ae15d551e4cff818b5e6e05e6260f39842426a942fea',
  metadataHash: '695cadf134d0a6cee0afd0480f31378d211c21189d38f4dcfdf3a5bdfdabb391',
  root: '63e7fd2d0841a4776b1ddba3dc9503c9a91779e862b62bdffdaf828b6c792270',
  rootWithLeavesSwapped: '89e64f139506d0893f4cbca9a77cd223937cb801977704145e1d0165e8feca39',
};

test('cross-implementation vector: leaf digests match Android', () => {
  assert.strictEqual(sha256Hex(Buffer.from(VECTOR.mediaInput, 'utf8')), VECTOR.mediaHash);
  assert.strictEqual(sha256Hex(Buffer.from(VECTOR.metadataInput, 'utf8')), VECTOR.metadataHash);
});

test('cross-implementation vector: Merkle root matches Android', () => {
  assert.strictEqual(merkleRoot2Leaf(VECTOR.mediaHash, VECTOR.metadataHash), VECTOR.root);
});

test('Merkle composition is positional, not order-independent', () => {
  assert.strictEqual(
    merkleRoot2Leaf(VECTOR.metadataHash, VECTOR.mediaHash),
    VECTOR.rootWithLeavesSwapped
  );
  assert.notStrictEqual(VECTOR.root, VECTOR.rootWithLeavesSwapped);
});

test('Merkle concatenates raw digest bytes, not hex text', () => {
  // Pins the reading of the spec's ambiguous `‖`.
  const hexTextInterpretation = sha256Hex(VECTOR.mediaHash + VECTOR.metadataHash);
  assert.notStrictEqual(hexTextInterpretation, VECTOR.root);
});

// --- end-to-end over a synthetic but genuinely signed package ---------------

/** Builds a real signed package with an ephemeral P-256 key. */
function buildSignedPackage() {
  const media = Buffer.from('a small pretend JPEG');
  const mediaHash = sha256Hex(media);

  const metadata = {
    location: null,
    timestamp: {
      wallClockMillis: 1784812345678,
      iso8601: '2026-07-23T09:12:25.678Z',
      elapsedRealtimeNanos: 894512000000000,
      wallClockOffsetMillis: 1783917833678,
      gpsTimeMillis: null,
    },
    motion: null,
    device: {
      installId: 'a1b2c3d4-e5f6-4a7b-8c9d-0e1f2a3b4c5d',
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
      eventId: '3f2504e0-4f89-41d3-9a0c-0305e82c3301',
      media: { mimeType: 'image/jpeg', byteLength: media.length, sha256: mediaHash, storageRef: null },
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
    },
  };
}

test('a genuine package passes every cryptographic check', () => {
  const { pkg, media } = buildSignedPackage();
  const { checks } = verifyProofPackage(pkg, media);

  assert.strictEqual(checks.mediaHashMatch, PASS);
  assert.strictEqual(checks.metadataHashMatch, PASS);
  assert.strictEqual(checks.merkleRootMatch, PASS);
  assert.strictEqual(checks.signatureValid, PASS);
});

test('flipping one bit of the media fails the media leaf', () => {
  const { pkg, media } = buildSignedPackage();
  const tampered = Buffer.from(media);
  tampered[0] ^= 0x01;

  const { checks, verdict } = verifyProofPackage(pkg, tampered);

  assert.strictEqual(checks.mediaHashMatch, FAIL);
  assert.strictEqual(verdict, 'failed');
});

test('changing one metadata field fails the metadata leaf', () => {
  const { pkg, media } = buildSignedPackage();
  pkg.metadata.timestamp.wallClockMillis += 1;

  const { checks, verdict } = verifyProofPackage(pkg, media);

  assert.strictEqual(checks.metadataHashMatch, FAIL);
  assert.strictEqual(verdict, 'failed');
});

test('substituting the signature fails signature verification', () => {
  const { pkg, media } = buildSignedPackage();
  const other = buildSignedPackage();
  pkg.signature.value = other.pkg.signature.value;

  const { checks, verdict } = verifyProofPackage(pkg, media);

  assert.strictEqual(checks.signatureValid, FAIL);
  assert.strictEqual(verdict, 'failed');
});

test('a rewritten root that matches its leaves still fails the signature', () => {
  // The strongest single case: an attacker edits the metadata AND recomputes
  // the leaf and root so the tree is internally consistent. Only the signature
  // catches this — which is exactly why the root is signed.
  const { pkg, media } = buildSignedPackage();
  pkg.metadata.device.model = 'NotTheRealDevice';
  pkg.merkle.leaves.metadata = sha256Hex(Buffer.from(canonicalize(pkg.metadata), 'utf8'));
  pkg.merkle.root = merkleRoot2Leaf(pkg.merkle.leaves.media, pkg.merkle.leaves.metadata);

  const { checks, verdict } = verifyProofPackage(pkg, media);

  assert.strictEqual(checks.metadataHashMatch, PASS, 'the tree is internally consistent');
  assert.strictEqual(checks.merkleRootMatch, PASS, 'the tree is internally consistent');
  assert.strictEqual(checks.signatureValid, FAIL, 'but the signature does not cover the new root');
  assert.strictEqual(verdict, 'failed');
});

test('missing media reports unavailable, never pass', () => {
  const { pkg } = buildSignedPackage();
  const { checks, verdict } = verifyProofPackage(pkg, undefined);

  assert.strictEqual(checks.mediaHashMatch, UNAVAILABLE);
  assert.notStrictEqual(verdict, 'verified');
});

test('an unattested package does not claim hardware backing', () => {
  const { pkg, media } = buildSignedPackage();
  const { checks, verdict } = verifyProofPackage(pkg, media);

  assert.strictEqual(checks.attestationPresent, FAIL);
  assert.notStrictEqual(verdict, 'verified');
});
