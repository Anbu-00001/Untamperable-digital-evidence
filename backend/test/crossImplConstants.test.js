'use strict';

const fs = require('fs');
const path = require('path');
const test = require('node:test');
const assert = require('node:assert');

const config = require('../src/config');

/**
 * The Android device computes an ADVISORY location/timestamp answer and this
 * backend computes the authoritative one. If their thresholds drift, the two
 * disagree for no reason but drift — so this test reads the Kotlin source of
 * truth itself rather than a hand-copied number (the project's standing rule:
 * a test named for a contract must load that contract).
 */
const INTEGRITY_CONFIG = path.resolve(
  __dirname, '..', '..',
  'android/app/src/main/kotlin/com/realitylock/app/core/config/IntegrityConfig.kt',
);

/** Evaluates a `const val NAME: Type = <expr>` whose expr is digits, `_`, `.`, `L`, `*`. */
function kotlinConst(source, name) {
  const match = source.match(new RegExp(`const val ${name}\\s*:\\s*\\w+\\s*=\\s*([^\\n]+)`));
  assert.ok(match, `const val ${name} not found in IntegrityConfig.kt — was it renamed?`);
  const expr = match[1].trim().replace(/_/g, '').replace(/(\d)L\b/g, '$1');
  assert.match(expr, /^[\d.\s*]+$/, `unexpected expression for ${name}: ${match[1]}`);
  return expr.split('*').reduce((acc, part) => acc * Number(part.trim()), 1);
}

const kotlin = fs.readFileSync(INTEGRITY_CONFIG, 'utf8');
const p = config.plausibility;

for (const [kotlinName, backendValue] of [
  ['EARTH_RADIUS_METERS', p.earthRadiusMeters],
  ['MAX_PLAUSIBLE_SPEED_KMH', p.maxPlausibleSpeedKmh],
  ['MIN_ELAPSED_MILLIS_FOR_SPEED', p.minElapsedMillisForSpeed],
  ['MIN_DISTANCE_METERS_FOR_SPEED', p.minDistanceMetersForSpeed],
  ['MAX_FUTURE_SKEW_MILLIS', p.maxFutureSkewMillis],
]) {
  test(`Android IntegrityConfig.${kotlinName} equals the backend default`, () => {
    assert.strictEqual(kotlinConst(kotlin, kotlinName), backendValue);
  });
}
