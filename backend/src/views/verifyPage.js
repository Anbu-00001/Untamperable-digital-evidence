'use strict';

/**
 * The human-readable face of `GET /verify/:eventId` — what someone sees after
 * scanning the QR code on a Reality Lock certificate with their own phone.
 *
 * It renders the SAME object the JSON response carries and nothing else, so it
 * cannot disclose more than the public endpoint already does: no GPS, no photo
 * (ADR-0006 §7). It is deliberately labelled as the web verifier — capture and
 * signing happen only in the Android app, and the page says so.
 *
 * No JavaScript at all. Check details use native <details>/<summary>, which keeps
 * the page inside helmet's default CSP (`script-src 'self'`, inline styles
 * allowed) without weakening it, and works with scripts disabled.
 *
 * The three outcomes are never collapsed to two: `unavailable` is grey and says
 * "could not be checked", never green and never red — the same rule the app's
 * VerificationStatusStyle enforces (ADR-0006 §5, ADR-0008).
 */

/** Escapes text for HTML element and attribute context. Every dynamic value goes through this. */
function esc(value) {
  return String(value ?? '')
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&#39;');
}

// Group titles and per-check sentences mirror the Android app's CheckGroups.kt,
// so the phone and the web page explain a check in the same words. Each sentence
// says what the check TESTS, not what it found — true whatever the outcome.
const GROUPS = [
  {
    title: 'Integrity',
    summary: 'Whether the bytes and their record still match what was captured.',
    checks: {
      schemaValid: ['Package well formed', 'Every field the proof-package schema requires is present and well formed.'],
      mediaHashMatch: ['Photo unchanged', 'The stored photo hashes to the digest recorded at capture, so the bytes are unchanged.'],
      metadataHashMatch: ['Record unchanged', 'The metadata hashes to the digest recorded at capture, so the record is unchanged.'],
      merkleRootMatch: ['Hashes combine correctly', 'The individual hashes combine to the Merkle root this package publishes.'],
    },
  },
  {
    title: 'Signature',
    summary: 'Whether the package was signed by the key it names.',
    checks: {
      signatureValid: ['Signature valid', 'The signature over the Merkle root verifies against the public key in the package.'],
    },
  },
  {
    title: 'Attestation',
    summary: "Hardware evidence that the signing key lived in the phone's secure element.",
    checks: {
      attestationPresent: ['Attestation present', 'The package carries a hardware key-attestation certificate chain.'],
      attestationChainValid: ['Chain links valid', 'Each certificate in the attestation chain is signed by the next one up.'],
      attestationKeyBinding: ['Attested key is the signing key', 'The attested key is the same key that signed this package.'],
      attestationRootTrusted: ['Google root trusted', 'The chain ends at a pinned Google hardware-attestation root.'],
      attestationNotRevoked: ['Not revoked', "No certificate in the chain is on Google's revocation list."],
      attestationSecurityLevel: ['Secure hardware', 'The key was generated inside the TEE or StrongBox rather than in software.'],
    },
  },
  {
    title: 'Context',
    summary: 'Whether the recorded time and place are plausible. Plausible is not proof.',
    checks: {
      timestampPlausible: ['Capture time plausible', "The recorded capture time agrees with the device's clock evidence."],
      timestampAnchorValid: ['Independent timestamp valid', "An independent timestamp authority signed this package's Merkle root, so it existed no later than that authority's recorded time."],
      captureTimeNotAfterAnchor: ['Capture time before timestamp', "The device's claimed capture time is not later than the independent timestamp, allowing for ordinary clock drift."],
      locationPlausible: ['Location plausible', 'The recorded location is self-consistent and did not come from a mock provider. (The location itself is never shown here.)'],
    },
  },
];

const OUTCOME = {
  pass: { glyph: '✓', word: 'Passed', cls: 'pass' },
  fail: { glyph: '✕', word: 'Failed', cls: 'fail' },
  unavailable: { glyph: '–', word: 'Could not be checked', cls: 'na' },
};
const UNKNOWN_OUTCOME = { glyph: '?', word: 'Unrecognised result', cls: 'na' };

const VERDICT = {
  verified: {
    cls: 'pass',
    title: 'Verified',
    lead: 'Every decisive check passed.',
  },
  failed: {
    cls: 'fail',
    title: 'Failed',
    lead: 'At least one check proved a problem with this package.',
  },
  incomplete: {
    cls: 'warn',
    title: 'Incomplete',
    lead: 'This is NOT a pass. Nothing failed, but a decisive check could not run.',
  },
};

const STYLE = `
:root{--bg:#f6f7f9;--card:#fff;--ink:#111418;--muted:#5b6470;--line:#e2e5ea;
--pass:#1b7f3b;--pass-bg:#e7f5ec;--fail:#b3261e;--fail-bg:#fbeaea;--warn:#8a5a00;--warn-bg:#fff4dc;--na:#5b6470;--na-bg:#eef0f3}
@media (prefers-color-scheme:dark){:root{--bg:#0f1216;--card:#171b21;--ink:#e8ebef;--muted:#9aa3ae;--line:#2a3038;
--pass:#5fd08a;--pass-bg:#11261a;--fail:#ff8a80;--fail-bg:#2b1414;--warn:#f0c060;--warn-bg:#2a2210;--na:#9aa3ae;--na-bg:#1f242b}}
*{box-sizing:border-box}
body{margin:0;background:var(--bg);color:var(--ink);font:16px/1.5 system-ui,-apple-system,"Segoe UI",Roboto,sans-serif}
main{max-width:640px;margin:0 auto;padding:16px;overflow-wrap:anywhere}
code{font-family:ui-monospace,Menlo,Consolas,monospace;font-size:.9em;background:var(--na-bg);padding:0 4px;border-radius:4px}
.center{justify-content:center}
.bar{font-size:12px;letter-spacing:.08em;text-transform:uppercase;color:var(--muted);display:flex;justify-content:space-between;gap:8px;flex-wrap:wrap}
.card{background:var(--card);border:1px solid var(--line);border-radius:14px;padding:16px;margin-top:12px}
.verdict{text-align:center;padding:24px 16px}
.verdict .big{font-size:34px;font-weight:700;margin:4px 0}
.verdict.pass{background:var(--pass-bg);border-color:var(--pass)}.verdict.pass .big{color:var(--pass)}
.verdict.fail{background:var(--fail-bg);border-color:var(--fail)}.verdict.fail .big{color:var(--fail)}
.verdict.warn{background:var(--warn-bg);border-color:var(--warn)}.verdict.warn .big{color:var(--warn)}
.mono{font-family:ui-monospace,"JetBrains Mono",Menlo,Consolas,monospace;font-size:13px;word-break:break-all}
dl{display:grid;grid-template-columns:auto minmax(0,1fr);gap:4px 12px;margin:12px 0 0;text-align:left}
dt{color:var(--muted);font-size:13px}dd{margin:0;min-width:0}
h2{font-size:15px;margin:0 0 4px}
.sub{color:var(--muted);font-size:13px;margin:0 0 8px}
details{border-top:1px solid var(--line);padding:10px 0}
summary{cursor:pointer;display:flex;align-items:center;gap:10px;list-style:none;min-height:28px}
summary::-webkit-details-marker{display:none}
.chip{display:inline-flex;align-items:center;justify-content:center;min-width:26px;height:26px;border-radius:13px;font-weight:700;font-size:14px}
.chip.pass{background:var(--pass-bg);color:var(--pass)}.chip.fail{background:var(--fail-bg);color:var(--fail)}.chip.na{background:var(--na-bg);color:var(--na)}
.name{flex:1}.state{font-size:12px;color:var(--muted)}
details p{margin:8px 0 0 36px;color:var(--muted);font-size:14px}
.frac{float:right;font-size:13px;color:var(--muted)}
ul{margin:8px 0 0;padding-left:20px}li{margin:4px 0}
.note{font-size:13px;color:var(--muted)}
footer{margin:20px 0 8px;font-size:12px;color:var(--muted);text-align:center}
`;

function page(title, inner) {
  return `<!doctype html>
<html lang="en"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<meta name="robots" content="noindex">
<title>${esc(title)}</title>
<style>${STYLE}</style></head>
<body><main>
<div class="bar"><span>Reality Lock · Web verifier</span><span>Not the app — a public check of its output</span></div>
${inner}
<footer>Capture and signing happen only in the Reality Lock Android app. This page recomputes the verdict on the server each time it is opened. It never shows the photo or the location.</footer>
</main></body></html>`;
}

function checkRow(name, label, detail, outcome) {
  const o = OUTCOME[outcome] || UNKNOWN_OUTCOME;
  return `<details><summary><span class="chip ${o.cls}" aria-hidden="true">${o.glyph}</span>` +
    `<span class="name">${esc(label)}</span><span class="state">${esc(o.word)}</span></summary>` +
    `<p>${esc(detail)}</p><p class="mono">${esc(name)}</p></details>`;
}

function groupCard(title, summary, rows) {
  const passed = rows.filter((r) => r.outcome === 'pass').length;
  return `<section class="card"><h2>${esc(title)}<span class="frac">${passed}/${rows.length} passed</span></h2>` +
    `<p class="sub">${esc(summary)}</p>` +
    rows.map((r) => checkRow(r.name, r.label, r.detail, r.outcome)).join('') +
    '</section>';
}

/** Renders `backtick` spans (check names in the limitation text) as code. Input is already escaped. */
function codeSpans(escaped) {
  return escaped.replace(/`([^`]+)`/g, '<code>$1</code>');
}

function listCard(title, items, lead) {
  if (!items || items.length === 0) return '';
  return `<section class="card"><h2>${esc(title)}</h2>${lead ? `<p class="sub">${esc(lead)}</p>` : ''}` +
    `<ul>${items.map((i) => `<li>${codeSpans(esc(i))}</li>`).join('')}</ul></section>`;
}

/** Renders the body of a successful `GET /verify/:eventId` as a page. */
function renderVerifyPage(body) {
  const v = VERDICT[body.verdict] || {
    cls: 'warn',
    title: String(body.verdict || 'unknown'),
    lead: 'The verifier returned a verdict this page does not recognise. Treat it as not verified.',
  };
  const checks = body.checks || {};
  const seen = new Set();

  const groups = GROUPS.map((g) => {
    const rows = Object.entries(g.checks)
      .filter(([name]) => name in checks)
      .map(([name, [label, detail]]) => {
        seen.add(name);
        return { name, label, detail, outcome: checks[name] };
      });
    return rows.length ? groupCard(g.title, g.summary, rows) : '';
  });

  // A check the server reports but this page has no words for is still SHOWN —
  // hiding it would make the page claim less was checked than actually was.
  const extra = Object.keys(checks).filter((n) => !seen.has(n)).map((name) => ({
    name, label: name, detail: 'Reported by the verifier; this page has no description for it.', outcome: checks[name],
  }));
  if (extra.length) groups.push(groupCard('Other checks', 'Shown as received.', extra));

  const anchor = body.timestampAnchor;
  const facts = [
    ['Event', `<span class="mono">${esc(body.eventId)}</span>`],
    ['Captured (device clock)', esc(body.capturedAt)],
    ['Merkle root', `<span class="mono">${esc(body.merkleRoot)}</span>`],
  ];
  if (anchor) {
    facts.push(['Independent timestamp', `${esc(anchor.genTime)}<br><span class="note">${esc(anchor.authority)}</span>`]);
  }

  const inner =
    `<section class="card verdict ${v.cls}"><div class="bar center">Verdict</div>` +
    `<div class="big">${esc(v.title)}</div><div>${esc(v.lead)}</div>` +
    `<dl>${facts.map(([k, val]) => `<dt>${esc(k)}</dt><dd>${val}</dd>`).join('')}</dl></section>` +
    listCard('What this does and does not prove', body.limitations,
      'Shipped with every verdict, including a pass.') +
    listCard('Advisories', body.advisories, 'Must be seen. These do not, by themselves, condemn the package.') +
    groups.join('') +
    listCard('Verifier notes', body.notes);

  return page(`Reality Lock — ${v.title}`, inner);
}

const ERROR_TEXT = {
  invalid_event_id: ['Not a valid event ID', 'The link does not contain a Reality Lock event ID.'],
  not_found: ['No such event on this server', 'This verification service holds no package with that ID. ' +
    'It may never have been uploaded, or the server\'s store may have been reset since. ' +
    'That is not evidence about the photo either way.'],
};

/** Renders a verify error (400/404) for a browser. */
function renderVerifyError(error, eventId) {
  const [title, text] = ERROR_TEXT[error] || ['Could not verify', String(error)];
  const inner = `<section class="card verdict warn"><div class="big">${esc(title)}</div>` +
    `<div>${esc(text)}</div>${eventId ? `<p class="mono">${esc(eventId)}</p>` : ''}</section>`;
  return page(`Reality Lock — ${title}`, inner);
}

module.exports = { renderVerifyPage, renderVerifyError, esc, GROUPS };
