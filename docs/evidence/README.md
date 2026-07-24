# Capture evidence

Real proof sidecars pulled off a physical device, kept so that claims made in
[`../../README.md`](../../README.md) can be checked rather than taken on trust.
A project about tamper-evident evidence should not ask to be believed.

**Device:** OnePlus CPH2591 (OPPO, Android 15, `sdkInt` 35), USB-attached.
**Date:** 2026-07-24. **Build:** `appVersionName` 0.1.0.

Pulled with:
```bash
adb shell run-as com.realitylock.app cat files/captures/<eventId>.json
```

> **Redaction:** `latitude`/`longitude` are rounded to 3 decimal places (~100 m)
> and marked `_redacted`. What these files evidence is that a real GNSS fix was
> bound to a capture — not the developer's street address. Nothing else is
> altered. The captured JPEGs are deliberately **not** committed: they are
> photographs of a real person's surroundings and are not needed to verify any
> claim here.

## The two files

| File | What it shows |
|---|---|
| `2026-07-24-before-clockbase-fix.json` | The camera-clock-base defect, in production output |
| `2026-07-24-after-clockbase-fix.json` | The same capture path after the fix |

### Before — a capture backdated by 9.66 days

```json
"iso8601": "2026-07-15T00:23:52.329Z",
"elapsedRealtimeNanos": 1122148595567000,
"motion": null
```

The device wall clock at that moment was **2026-07-24 21:43 IST**. The recorded
instant was **9.66 days in the past**, and nothing in the document looked wrong:
the internal arithmetic was self-consistent
(`1122148595567000/1e6 + 1782952883734 = 1784075032329` ✓).

The cause was an assumption, not a calculation. `SensorEvent.timestamp` is
stamped on `CLOCK_BOOTTIME`, but CameraX's `ImageInfo.timestamp` uses whatever
base the camera declares in `SENSOR_INFO_TIMESTAMP_SOURCE`. This device declares
`UNKNOWN`, i.e. `CLOCK_MONOTONIC` — which **pauses during deep sleep**. The
handset had accumulated 834,704 s of sleep, and that is exactly the error.

Confirmed on-device rather than inferred:
```
$ adb shell grep btime /proc/stat        → btime 1782952883
$ adb shell cat /proc/uptime             → 1956852.85 …
$ adb shell dumpsys media.camera | grep -A1 timestampSource
      android.sensor.info.timestampSource (f0008): byte[1]
        [UNKNOWN ]
```
`btime` matches the recorded `wallClockOffsetMillis` (1782952883734) exactly,
proving the offset was right and the *camera* timestamp was on the other clock.

Two design decisions contained the damage instead of compounding it:

- **`motion` is `null`.** The sensor samples sat 9.66 days from the capture
  instant, so the skew tolerance rejected them. The honesty guard refused to
  attach data it could not stand behind — the bug's own symptom.
- **`gpsTimeMillis` was correct** (`1784909609679` = 2026-07-24T16:13:29Z). The
  independent GNSS clock disagreed with the device clock, which is precisely the
  cross-check it was put there for.

### After — the same path, corrected

```json
"iso8601": "2026-07-24T16:20:07.759Z",
"motion": { "accelerometer": [...], "gyroscope": [...],
            "sampleElapsedRealtimeNanos": 1957124024318344 }
```

- Shutter tapped at `1784910008000`; recorded `wallClockMillis` `1784910007759` —
  **0.24 s** apart, against 9.66 days before.
- `gpsTimeMillis` `1784910009875` independently agrees.
- **Motion is now bound**: the sample sits `1957124025809860 − 1957124024318344`
  = **1.49 ms** from the capture instant. On this device motion had *never*
  populated; the clock-base bug was the reason.
- Location populated with a live fused fix (`fixAgeMillis: 0`) — this closes the
  location-populated path, which until now had only ever run on an emulator that
  supplies no GNSS fix.

## Schema conformance

Both files are Phase-2 output, so they carry no `merkle`/`signature`/
`media.sha256` yet. Validated against the real schema:

```
--- raw Phase-2 sidecar ---
  must have required property 'merkle'
  must have required property 'signature'
  /media/sha256 must be string

--- same sidecar with Phase-3 fields grafted from the example ---
VALID against proof-package.schema.json
```

Every failure is a field Phase 3 has not built yet; nothing the serializer emits
is rejected. `EventSerializerSchemaTest` enforces this property in CI against
this same schema file.
