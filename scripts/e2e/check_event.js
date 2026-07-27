#!/usr/bin/env node
'use strict';

/**
 * Checks one sidecar pulled off a device against BOTH contracts:
 *
 *  1. the shared JSON Schema (docs/design/proof-package.schema.json) — as a
 *     Phase-2 prefix, and as a completed package once Phase-3 fields are added;
 *  2. Phase 2's exit criteria from research/09_PROJECT_PHASES.md, which name the
 *     exact fields a capture must populate.
 *
 * Schema-validity alone is not enough: every optional field could be null and
 * the document would still validate. The criteria below assert the capture
 * actually recorded something.
 *
 * Usage: node check_event.js <sidecar.json> [--expect-location] [--expect-motion]
 *        [--phase2-prefix] [--pulled-at=<epochMillis>]
 * Exit code 0 = pass, 1 = fail.
 */

const fs = require('fs');
const path = require('path');

const backendSrc = path.resolve(__dirname, '..', '..', 'backend');
const config = require(path.join(backendSrc, 'src', 'config'));
const { loadValidator } = require(path.join(backendSrc, 'src', 'services', 'proofSchema'));

const repoRoot = path.resolve(__dirname, '..', '..');

/**
 * Reads a constant out of the app's own source instead of restating its value
 * here. These checks assert invariants the app enforces, so a literal copy in
 * this script would silently stop testing the real thing the moment the app was
 * retuned — the failure mode is a green check that no longer means anything.
 *
 * Missing constants throw rather than fall back: a rename must break the run
 * loudly, not quietly revert to a stale number.
 */
function appConst(relPath, name) {
  const src = fs.readFileSync(path.join(repoRoot, relPath), 'utf8');
  const match = src.match(new RegExp(`const val ${name}\\s*:[^=]+=\\s*"?([^"\\n]+?)"?L?\\s*$`, 'm'));
  if (!match) throw new Error(`${name} not found in ${relPath} — was it renamed?`);
  return match[1].trim();
}

function versionCatalogEntry(name) {
  const src = fs.readFileSync(path.join(repoRoot, 'android/gradle/libs.versions.toml'), 'utf8');
  const match = src.match(new RegExp(`^${name}\\s*=\\s*"([^"]+)"`, 'm'));
  if (!match) throw new Error(`${name} not found in libs.versions.toml — was it renamed?`);
  return match[1];
}

const CAPTURE_CONFIG = 'android/app/src/main/kotlin/com/realitylock/app/core/config/CaptureConfig.kt';
const EXPECTED_MIME = appConst(CAPTURE_CONFIG, 'MIME_TYPE_JPEG');
const MOTION_MAX_SKEW_MILLIS = Number(appConst(CAPTURE_CONFIG, 'MOTION_MAX_SKEW_MILLIS'));
const MIN_SDK = Number(versionCatalogEntry('minSdk'));
const MAX_CLOCK_SKEW_MILLIS = 24 * 3600 * 1000;

const args = process.argv.slice(2);
const file = args.find((a) => !a.startsWith('--'));
const expectLocation = args.includes('--expect-location');
const expectMotion = args.includes('--expect-motion');
// Opt-in legacy mode for artifacts captured before Phase 3 existed.
const phase2Prefix = args.includes('--phase2-prefix');
// When the runner knows when it pulled the file, judging clock skew against that
// instant rather than this script's clock keeps a slow pull from failing a good
// capture.
const pulledAtArg = args.find((a) => a.startsWith('--pulled-at='));
const pulledAtMillis = pulledAtArg ? Number(pulledAtArg.split('=')[1]) : Date.now();

if (!file) {
  console.error('usage: check_event.js <sidecar.json> [--expect-location] [--expect-motion]');
  process.exit(2);
}

const failures = [];
const notes = [];

function check(label, condition, detail) {
  if (condition) return;
  failures.push(detail ? `${label} — ${detail}` : label);
}

const doc = JSON.parse(fs.readFileSync(file, 'utf8'));
const { validate } = loadValidator();
const example = JSON.parse(fs.readFileSync(config.proofExamplePath, 'utf8'));

// --- 1. schema ---------------------------------------------------------------
// Validated AS-IS. This previously did two things that between them meant the
// crypto block was never checked at all: it whitelisted away "missing merkle /
// signature / media.sha256", and then it *overwrote* the document's real merkle,
// signature and media.sha256 with the example package's before validating. A
// sidecar carrying no signature, or a malformed one, passed both checks.
//
// Phase 3 has shipped, so a real capture must now be a complete, valid package.
// The prefix mode remains available behind --phase2-prefix for pre-Phase-3
// artifacts, and it has to be asked for.
if (phase2Prefix) {
  const phase3Only = new Set(["must have required property 'merkle'",
    "must have required property 'signature'",
    '/media/sha256 must be string']);
  validate(doc);
  const prefixErrors = (validate.errors || []).map((e) => `${e.instancePath} ${e.message}`.trim());
  const unexpected = prefixErrors.filter((e) => !phase3Only.has(e));
  check(
    'schema (as Phase-2 prefix)',
    unexpected.length === 0,
    unexpected.length ? `unexpected violations: ${unexpected.join('; ')}` : null
  );
} else {
  const ok = validate(doc);
  check(
    'schema (complete proof package)',
    ok,
    ok ? null : (validate.errors || []).map((e) => `${e.instancePath} ${e.message}`).join('; ')
  );
}

// --- 3. Phase 2 exit criteria ------------------------------------------------
const md = doc.metadata || {};
const ts = md.timestamp || {};
const dev = md.device || {};

check('eventId present', typeof doc.eventId === 'string' && doc.eventId.length > 0);
check('media byteLength > 0', Number(doc.media && doc.media.byteLength) > 0);
check(`media mimeType is ${EXPECTED_MIME}`, doc.media && doc.media.mimeType === EXPECTED_MIME);

// Both raw (monotonic) and derived (wall-clock) timestamps — the whole point of
// storing the offset is that the derivation can be re-checked independently.
check('raw monotonic timestamp present', Number.isFinite(ts.elapsedRealtimeNanos));
check('derived wall-clock timestamp present', Number.isFinite(ts.wallClockMillis));
check('wall-clock offset recorded', Number.isFinite(ts.wallClockOffsetMillis));
if (Number.isFinite(ts.elapsedRealtimeNanos) && Number.isFinite(ts.wallClockOffsetMillis)) {
  const derived = Math.floor(ts.elapsedRealtimeNanos / 1e6) + ts.wallClockOffsetMillis;
  check(
    'wall clock re-derives from monotonic + offset',
    derived === ts.wallClockMillis,
    `recomputed ${derived} != recorded ${ts.wallClockMillis}`
  );
}
check(
  'iso8601 agrees with wallClockMillis',
  new Date(ts.iso8601).getTime() === ts.wallClockMillis,
  `${ts.iso8601} != ${ts.wallClockMillis}`
);

// The clock-base defect: a capture stamped days from the device's real clock.
// Compared against --pulled-at when the runner supplies it, so a slow pull
// cannot produce a false failure; otherwise against this script's own clock.
// (The previous comment claimed a pull time was "passed in by the runner" while
// the code read Date.now() — nothing ever passed one in.)
const skewMillis = Math.abs(pulledAtMillis - ts.wallClockMillis);
check(
  'capture instant is close to real time',
  skewMillis < MAX_CLOCK_SKEW_MILLIS,
  `capture is ${(skewMillis / 86400000).toFixed(2)} days from now — camera clock base?`
);

check('install UUID present', /^[0-9a-f-]{36}$/i.test(dev.installId || ''));
check('device model present', typeof dev.model === 'string' && dev.model.length > 0);
check('device manufacturer present', typeof dev.manufacturer === 'string' && dev.manufacturer.length > 0);
check(`sdkInt >= minSdk (${MIN_SDK})`, Number.isInteger(dev.sdkInt) && dev.sdkInt >= MIN_SDK);

if (expectLocation) {
  const loc = md.location;
  check('location recorded', loc != null, 'expected a GNSS fix but location was null');
  if (loc) {
    check('latitude in range', loc.latitude >= -90 && loc.latitude <= 90);
    check('longitude in range', loc.longitude >= -180 && loc.longitude <= 180);
    check('accuracy present', Number.isFinite(loc.accuracyMeters) && loc.accuracyMeters >= 0);
    check('fix age present', Number.isFinite(loc.fixAgeMillis));
    check('fix not flagged as mock', loc.isMock === false);
  }
} else if (md.location == null) {
  notes.push('location absent (not required by this run)');
}

if (expectMotion) {
  const m = md.motion;
  check('motion recorded', m != null, 'expected a motion sample bound to the shutter');
  if (m) {
    check('accelerometer is a 3-vector', Array.isArray(m.accelerometer) && m.accelerometer.length === 3);
    check(
      'gyroscope is a 3-vector or null (never [])',
      m.gyroscope === null || (Array.isArray(m.gyroscope) && m.gyroscope.length === 3)
    );
    if (Number.isFinite(m.sampleElapsedRealtimeNanos) && Number.isFinite(ts.elapsedRealtimeNanos)) {
      const skew = Math.abs(ts.elapsedRealtimeNanos - m.sampleElapsedRealtimeNanos) / 1e6;
      check(
        `motion sample within ${MOTION_MAX_SKEW_MILLIS} ms of the shutter`,
        skew <= MOTION_MAX_SKEW_MILLIS,
        `${skew.toFixed(2)} ms away`
      );
      notes.push(`motion bound ${skew.toFixed(2)} ms from the shutter`);
    }
  }
} else if (md.motion == null) {
  notes.push('motion absent (not required by this run)');
}

// The proof document must not carry device-local state.
check('no mediaFilePath in the proof document', !('mediaFilePath' in doc));

// --- report ------------------------------------------------------------------
const name = path.basename(file);
if (failures.length === 0) {
  console.log(`  PASS  ${name}${notes.length ? '  (' + notes.join('; ') + ')' : ''}`);
  process.exit(0);
}
console.log(`  FAIL  ${name}`);
failures.forEach((f) => console.log(`          - ${f}`));
process.exit(1);
