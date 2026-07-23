# Cryptographic & Secure-Architecture Design Research — "Reality Lock"

Companion to `00_PPT_TRANSCRIPT.md`. This document answers, decisively, the cryptography and
security-architecture questions needed to turn the PPT's Security row ("SHA-256 Hashing +
Encryption", with "Blockchain Integration" listed as **Future**) into an implementable Phase 3
design. Every recommendation below is chosen to be buildable by **two students in one semester**
with **free/open-source tooling** — no enterprise infrastructure, no paid CAs, no mainnet gas fees.

---

## 1. Hashing — SHA-256 vs SHA-3 vs BLAKE3, and what exactly to hash

### Decision: **keep SHA-256**, do not switch to SHA-3 or BLAKE3.

**Why SHA-256 is correct here, not a compromise:**
- SHA-256 is still NIST/FIPS 180-4 approved, has no known practical collision or preimage
  attacks, and is the algorithm the PPT already commits to (Slide 8: "SHA-256 Hashing +
  Encryption"; Slide 9 block diagram: "Cryptographic Hash Generation (SHA-256)"). Changing it
  without a concrete forcing reason would just create inconsistency with your own submitted design.
- It is available with **zero extra dependencies** via `java.security.MessageDigest.getInstance("SHA-256")`
  on every Android API level, and is hardware-accelerated on virtually all ARMv8 phones via the
  ARMv8 Cryptography Extensions, so throughput is not a real bottleneck for hashing one photo/video
  per capture event (this is not a bulk-data-pipeline workload).
- SHA-3 (Keccak) exists as a structurally-different backup standard (different internal
  construction than SHA-2, so a future SHA-2 break wouldn't break SHA-3 too) but offers no
  practical advantage for this project and is *slower* than SHA-256 on typical hardware without
  dedicated Keccak acceleration.
- BLAKE3 is genuinely faster — benchmarks show it reaching ~15.8 GB/s vs SHA-256's ~0.65 GB/s on
  modern CPUs, largely because of tree-hashing parallelism ([slaptijack.com benchmark](https://slaptijack.com/programming/blake3-vs-sha256-performance.html), [SHA-256 vs BLAKE3 comparison](https://ssojet.com/compare-hashing-algorithms/sha-256-vs-blake3)) — but that speed matters for
  bulk file verification / dedup pipelines, not for hashing a single event bundle a few times a
  minute on a phone. BLAKE3 also has **no first-party Android/JVM implementation**; you'd depend on
  either a pure-Kotlin re-implementation (`ch.trancee:blake-hash`, functionally fine but unaudited
  relative to SHA-256) or a JNI-compiled native library (`alephium/blake3-jni`, `blake3jni`) that
  you'd have to cross-compile for `arm64-v8a`/`armeabi-v7a` yourself — real integration risk for a
  one-semester project ([BLAKE3 Android library options](https://github.com/trancee/blake-hash), [blake3-jni](https://github.com/alephium/blake3-jni)).
- **Verdict**: SHA-256 via `MessageDigest` is the right choice for correctness, auditability (a
  reviewer/examiner can independently verify with `sha256sum`), zero dependency risk, and
  consistency with what you already presented. Mention BLAKE3/SHA-3 explicitly in your literature
  survey as *considered alternatives*, not adopted ones — that shows research depth without
  introducing integration risk.

### What exactly should be hashed — not just the media file

Hashing only the media file (`SHA-256(photo.jpg)`) proves the *photo bytes* haven't changed, but
proves nothing about the GPS/timestamp/sensor metadata being bound to that photo — an attacker
could keep the real photo and swap the metadata file next to it. You must bind media + context
together cryptographically. Two viable designs, and a decisive pick:

**Design A — flat concatenation (simple, works, but all-or-nothing):**
```
canonicalBundle = canonicalJSON({
  mediaHash, gpsLat, gpsLon, gpsAccuracy, timestampUtc,
  accelerometer, gyroscope, deviceId, appVersion
})
proofHash = SHA-256(canonicalBundle)
```
Problem: to verify *any single field*, you must reveal *every* field, because they're all mashed
into one hash input. Fine for an MVP, weak if you ever want to show "GPS was valid" to a verifier
without showing raw sensor traces.

**Design B — hash-of-hashes / Merkle composition (recommended):**
Hash each semantic field (or field group) independently, then combine the individual hashes into
a small Merkle tree, and sign only the root:

```
leaf1 = SHA-256(mediaFileBytes)                     // media
leaf2 = SHA-256(canonicalJSON(gpsLat,gpsLon,acc))    // location
leaf3 = SHA-256(canonicalJSON(timestampUtc, ...))    // timestamp
leaf4 = SHA-256(canonicalJSON(accel, gyro))          // motion
leaf5 = SHA-256(canonicalJSON(deviceId, appVersion)) // device identity

node12 = SHA-256(leaf1 || leaf2)
node34 = SHA-256(leaf3 || leaf4)
node55 = SHA-256(leaf5 || leaf5)      // duplicate odd leaf (standard Merkle convention)
node1234 = SHA-256(node12 || node34)
root = SHA-256(node1234 || node55)

signature = Sign(privateKey, root)
```
Why this matters for *this exact system*: it gives you **selective disclosure** — you can later
reveal "GPS was 12.9x, 77.5x, accuracy 4m" plus the 2–3 sibling hashes needed to recompute the
root, and a verifier can confirm that GPS leaf was indeed part of the originally-signed bundle
**without you exposing the raw accelerometer trace or device identifiers**. This is exactly the
technique used in Merkle-based verifiable credentials / selective-disclosure systems: leaves are
attribute hashes, siblings along the path let a holder prove inclusion of one claim without
revealing the others, and a single signature over the root implicitly authenticates every leaf
([W3C-CCG Merkle Disclosure Proof 2021](https://w3c-ccg.github.io/Merkle-Disclosure-2021/), [CBOR Merkle tree proofs draft](https://ietf-scitt.github.io/draft-steele-cose-merkle-tree-proofs/draft-steele-cose-merkle-tree-proofs.html)).
For a college semester project, even a **flat 2-leaf tree** — `root = SHA-256(mediaHash || metadataHash)`
— is enough to demonstrate the concept correctly and is what Section 8's pipeline uses; present the
full 5-leaf tree as the "designed-for" architecture in your report even if you implement the 2-leaf
version first.

### Canonical serialization: don't skip this step

Hashing a JSON string directly is dangerous because JSON permits many byte-different encodings
of the same logical data (key order, whitespace, number formatting all vary), so two "identical"
metadata objects can hash differently, breaking verification. Use a **canonical form**:
- **RFC 8785 (JSON Canonicalization Scheme, JCS)** defines exactly this: sorted keys, strict
  ECMAScript-style number serialization, I-JSON (RFC 7493) constraints — byte-identical output for
  logically-identical data ([RFC 8785](https://www.rfc-editor.org/info/rfc8785/), [JCS explainer](https://jsonic.io/guides/json-canonicalization)).
  A ready-made Java library implements it directly: **`io.github.erdtman:java-json-canonicalization`**
  (`JsonCanonicalizer(jsonString).getEncodedString()`), 28 KB, no other dependencies, drops straight
  into an Android Gradle build ([erdtman/java-json-canonicalization](https://github.com/erdtman/java-json-canonicalization)).
- Alternative: CBOR (RFC 8949) with its "deterministic encoding" mode achieves the same property in
  a binary format ([CBOR determinism](https://cborbook.com/part_2/determinism.html)), but JCS/JSON is simpler to debug and log during a student
  project, so **use RFC 8785 JCS**, not CBOR, unless you specifically need binary compactness.

---

## 2. Digital signatures — RSA-2048 vs ECDSA P-256 vs Ed25519, and where the key lives

### Decision: **ECDSA with the P-256 curve (`SHA256withECDSA`), key generated inside Android Keystore, StrongBox-backed where available, falling back to TEE.**

**Performance comparison (why not RSA-2048):**
On comparable hardware, Ed25519 does ~50,000 signs/sec, ECDSA P-256 ~30,000 signs/sec, RSA-2048
only ~1,500 signs/sec — RSA is roughly an order of magnitude more CPU-expensive to sign with.
CPU-cycle studies show ECDSA-P256 and Ed25519 both sit around 10⁶ cycles per operation vs ~10⁷ for
RSA-2048, i.e. **~10× more CPU (and battery) per signature for RSA** ([RSA vs ECDSA vs Ed25519 comparison](https://dev.to/kanywst/digital-signatures-mechanics-and-go-benchmarks-rsa-vs-ecdsa-vs-ed25519-2d36), [LightDSA performance analysis](https://arxiv.org/pdf/2505.23773)).
RSA key *generation* is also dramatically slower (seconds, vs milliseconds for EC curves) and RSA
signatures/keys are 4–8× larger, meaning bigger proof packages uploaded over mobile data
([key type comparison](https://getacert.com/learn/key-types-explained)). None of this matters for a server validating a handful of
signatures, but it matters on a battery/CPU-constrained phone signing every captured event.

**ECDSA P-256 vs Ed25519 — why P-256, not Ed25519, for *this* project specifically:**
Ed25519 is cryptographically excellent and slightly faster/simpler than ECDSA (no per-signature
random nonce to get wrong), but **hardware-backed key support is the deciding factor here**:
StrongBox (Android's dedicated secure-element tier, API 28+) documents support for **RSA-2048, AES,
ECDSA/ECDH on P-256, and HMAC-SHA256** — it does **not** list Ed25519/Curve25519 in its supported
algorithm set ([Android hardware-backed Keystore docs](https://source.android.com/docs/security/features/keystore)). Ed25519 (Curve25519) signing support was only added to the
*TEE-level* KeyMint 2.0 HAL starting **Android 13 (API 33)** ([Guardsquare Android Keystore overview](https://www.guardsquare.com/blog/android-keystore)) — meaning on
older devices, or devices whose StrongBox chip predates Curve25519, requesting an Ed25519 key would
either fail or silently downgrade to software-only (non-hardware-backed) storage, defeating the
point of using the Keystore at all. **ECDSA P-256 is supported by StrongBox from API 28 onward and
by TEE-backed Keystore since API 23** — it is the one algorithm with the widest, most reliable
hardware-backing story across the actual Android devices your two examiners/testers will use in a
2026 classroom demo. Recommend: use ECDSA P-256 as the implemented algorithm; mention Ed25519 in
your report as the natural upgrade path once you can assume API 33+ StrongBox-with-Curve25519
devices.

**Where the private key lives — Android Keystore System + StrongBox, concretely:**
The private signing key must **never leave secure hardware** and must never be exported as raw
key material, even to your own app process. Android's Keystore system provides exactly this:
generate the keypair with the `"AndroidKeyStore"` provider, and the private key handle returned is
a token — cryptographic operations happen inside the TEE/StrongBox chip, and the private key bytes
are physically inaccessible to the OS or app ([Android Keystore system docs](https://developer.android.com/privacy-and-security/keystore)).

```kotlin
import java.security.KeyPairGenerator
import java.security.KeyStore
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException

private const val KEY_ALIAS = "reality_lock_event_signing_key"

fun getOrCreateSigningKey(context: Context): KeyStore.PrivateKeyEntry {
    val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    if (!keyStore.containsAlias(KEY_ALIAS)) {
        val kpg = KeyPairGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore"
        )
        val baseSpec = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY
        )
            .setDigests(KeyProperties.DIGEST_SHA256)
            .setAlgorithmParameterSpec(java.security.spec.ECGenParameterSpec("secp256r1")) // P-256

        try {
            // Prefer hardware secure element (StrongBox) if the device has it
            val hasStrongBox = context.packageManager.hasSystemFeature(
                android.content.pm.PackageManager.FEATURE_STRONGBOX_KEYSTORE
            )
            kpg.initialize(baseSpec.setIsStrongBoxBacked(hasStrongBox).build())
        } catch (e: StrongBoxUnavailableException) {
            // Fall back to TEE-backed key (still hardware-backed, just not the SE tier)
            kpg.initialize(baseSpec.setIsStrongBoxBacked(false).build())
        }
        kpg.generateKeyPair()
    }
    return keyStore.getEntry(KEY_ALIAS, null) as KeyStore.PrivateKeyEntry
}

fun signRootHash(privateKeyEntry: KeyStore.PrivateKeyEntry, rootHash: ByteArray): ByteArray {
    val signature = java.security.Signature.getInstance("SHA256withECDSA")
    signature.initSign(privateKeyEntry.privateKey)
    signature.update(rootHash)
    return signature.sign()
}
```

Key API names to cite in your report: `KeyGenParameterSpec.Builder`, `KeyProperties.PURPOSE_SIGN`,
`KeyProperties.KEY_ALGORITHM_EC`, `setIsStrongBoxBacked(true)`, `StrongBoxUnavailableException`,
`FEATURE_STRONGBOX_KEYSTORE`, `Signature.getInstance("SHA256withECDSA")`. StrongBox itself is an
embedded secure element / integrated secure enclave with its own CPU, its own secure storage, a
true hardware RNG, and package-tamper resistance — a materially higher security tier than plain
TEE-backed keys ([StrongBox / TEE detail](https://developer.android.com/privacy-and-security/keystore)). You should also enable **key attestation**
(`setAttestationChallenge(...)` on the spec) so the verification backend can later confirm the
public key really came out of secure hardware on a genuine device, not a software keystore on an
emulator.

---

## 3. Device/app integrity attestation — Play Integrity API

SafetyNet Attestation (2017) is **fully deprecated and shut down** — Google ended the migration
window on **20 May 2025**, after which apps still calling the old SafetyNet endpoint broke outright
([SafetyNet deprecation](https://medium.com/@ab3masta/the-safetynet-attestation-api-is-deprecated-and-is-being-discontinued-and-replaced-by-the-new-play-312c9c364df7)). You must use the **Play Integrity API**, which Google introduced in 2021 to
consolidate device, app, and licensing checks into one signal ([Play Integrity overview](https://en.wikipedia.org/wiki/Play_Integrity_API)).

**What it verifies:**
- **App integrity** — is this APK the one you actually published (unmodified, signed with your
  key), obtained through Google Play (not a repackaged/sideloaded clone)?
- **Device integrity** — is this a genuine, unmodified Android device/OS (not rooted, not an
  emulator, not running a compromised system image)? Reported as a nuanced verdict (e.g.
  `MEETS_DEVICE_INTEGRITY`, `MEETS_STRONG_INTEGRITY`, `MEETS_BASIC_INTEGRITY`), which is a real
  improvement over SafetyNet's coarser basicIntegrity/ctsProfileMatch flags ([Play Integrity vs SafetyNet](https://proandroiddev.com/play-integrity-api-googles-new-security-gatekeeper-and-why-safetynet-is-gone-e204f35278a8?gi=a32d97f2c5a3)).
- **Account/licensing integrity** — did a legitimate, licensed Google Play account install/obtain
  this app (anti-abuse signal), something SafetyNet didn't offer at all.

**Concrete API call flow (Standard request — the recommended modern flow):**
1. One-time warm-up: `val manager = IntegrityManagerFactory.createStandard(applicationContext)`,
   then `manager.prepareIntegrityToken(PrepareIntegrityTokenRequest.builder().setCloudProjectNumber(N).build())`
   to get a `StandardIntegrityTokenProvider` cached in memory (reduces per-request latency).
2. At the moment you're about to finalize a proof package: compute
   `requestHash = SHA-256(canonicalize(rootHash + eventId))` (max 500 bytes, never put raw PII in
   it), then call
   `tokenProvider.request(StandardIntegrityTokenRequest.builder().setRequestHash(requestHash).build())`.
3. You receive an **encrypted** `StandardIntegrityToken`. Send it to your own backend alongside the
   proof package.
4. **Your backend** (not the phone) calls Google's decode endpoint:
   `POST playintegrity.googleapis.com/v1/PACKAGE_NAME:decodeIntegrityToken` with a Google Cloud
   service-account access token scoped to `playintegrity`. Google decrypts and returns a plaintext
   verdict JSON with `deviceRecognitionVerdict`, `appIntegrity` (e.g. `PLAY_RECOGNIZED` vs
   `UNRECOGNIZED_VERSION`/tampered), `accountDetails`, and an echoed `requestHash` you must compare
   against what you expected, to defeat replay ([Play Integrity standard request flow](https://developer.android.com/google/play/integrity/standard), [setup docs](https://developer.android.com/google/play/integrity/setup)).
5. Only if the verdicts are acceptable does your backend mark the proof package as
   **"generated by an attested, untampered app instance on a genuine device"** — store that verdict
   (or the raw token) inside the proof package as one more field the verification module checks.

**Setup requirement:** you need a Google Play Console app entry (internal testing track is enough —
you do not need a public production release) linked to a Google Cloud project, plus a small backend
service (Node.js/Python fits the PPT's own stated backend stack) to hold the service-account
credentials and call `decodeIntegrityToken` — token decryption **must happen server-side**, never
on-device, because client-side "verification" of your own token is meaningless (the compromised
device is exactly what you don't trust) ([Play Integrity setup](https://developer.android.com/google/play/integrity/setup)).

**Known limitations to state honestly in your literature survey (this is good academic nuance):**
independent security researchers note Play Integrity verdicts are *signals*, not cryptographic
proof — sophisticated rooting/hooking frameworks and certain emulator setups have at times evaded
detection, and verdicts can have false positives/negatives; it should be treated as **defense in
depth**, not an unconditional guarantee ([Guardsquare on Play Integrity limitations](https://www.guardsquare.com/blog/google-play-integrity-api-app-attestation), [Approov on Play Integrity limitations](https://approov.io/blog/limitations-of-google-play-integrity-api-ex-safetynet)).

---

## 4. Trusted timestamping — RFC 3161 TSP and OpenTimestamps

### RFC 3161 (Time-Stamp Protocol) — feasible, and here's exactly how

RFC 3161 defines a standard request/response over a Time Stamping Authority (TSA): you send the
TSA a hash (not the raw file) plus a policy OID, and it returns a **signed token** binding that
hash to a specific UTC time, signed by the TSA's certificate — providing evidentiary, legally-
recognized proof the hash existed at that time ([RFC 3161 spec](https://www.rfc-editor.org/rfc/rfc3161.html), [free TSA usage explainer](https://tyde.systems/post/2025-04-24-tts/)).

**Free, no-registration TSA endpoints exist and are practical for a student project:**
- **FreeTSA** (`http://freetsa.org/tsr`) — free RFC 3161 SaaS timestamping, independently-audited
  time sources, no signup required ([FreeTSA](https://www.freetsa.org/index_en.php)). Concrete flow with OpenSSL:
  ```bash
  openssl ts -query -data data.txt -cert -sha256 -no_nonce -out data.txt.tsq
  curl -s -H 'Content-Type: application/timestamp-query' \
       --data-binary @data.txt.tsq http://freetsa.org/tsr -o data.txt.tsr
  wget http://freetsa.org/files/tsa.crt http://freetsa.org/files/cacert.pem
  openssl ts -verify -in data.txt.tsr -data data.txt -CAfile cacert.pem
  ```
  ([FreeTSA usage steps](https://www.digistamp.com/technical/software-alternatives/using-openssl-to-request-timestamps))
- **DigiCert TSA** (`http://timestamp.digicert.com`) — another free, no-signup RFC 3161 endpoint
  commonly used for Authenticode-style timestamping ([DigiCert TSA](https://knowledge.digicert.com/general-information/rfc3161-compliant-time-stamp-authority-server)).

**Implementation note specific to Android:** there is no built-in Android API for constructing an
RFC 3161 `TimeStampReq`/parsing a `TimeStampResp` (these are ASN.1 DER structures). Don't try to
hand-roll ASN.1 encoding on-device. Two practical options:
1. **Recommended**: do timestamping **at your backend**, not on the phone — the phone sends the
   event's `rootHash` to your Node.js/Python backend over HTTPS (already part of your architecture
   for uploads), and the backend uses a mature TSP client library — for a JVM/Kotlin backend,
   **Bouncy Castle's `org.bouncycastle.tsp` package** (`bcpkix-jdk18on` artifact, classes
   `TimeStampRequestGenerator`, `TimeStampResponse`) implements RFC 3161 request/response handling
   directly; for a Python backend, `rfc3161ng`.
2. If you want it fully on-device, Bouncy Castle (`org.bouncycastle:bcpkix-jdk18on`) can be added
   as an Android Gradle dependency and used identically — it's pure Java, no native code — but the
   backend option keeps your APK smaller and your TSA credentials/network policy centralized.

### OpenTimestamps — the free, Bitcoin-anchored alternative

OpenTimestamps proves a hash existed at/before a certain time by committing it into the Bitcoin
blockchain, but **never one hash per transaction** — it uses Merkle-tree aggregation so thousands
of users' hashes share a single Bitcoin transaction, at zero cost to the submitter ([OpenTimestamps overview](https://petertodd.org/2016/opentimestamps-announcement), [Wikipedia: OpenTimestamps](https://en.wikipedia.org/wiki/OpenTimestamps)):

1. **Client side**: your app/backend computes `SHA-256(rootHash)` and submits it to one or more
   public **calendar servers** (e.g. `alice.btc.calendar.opentimestamps.org`,
   `bob.btc.calendar.opentimestamps.org`) via the `ots stamp` command or the calendar's HTTP API.
2. **Calendar server aggregation**: the server batches many clients' submitted hashes into a
   Merkle tree over a short window and commits **only the Merkle root** into a single Bitcoin
   transaction (via `OP_RETURN`). Your client immediately receives a **partial proof** — a `.ots`
   file containing the Merkle path (sibling hashes) from your hash up to that pending root
   ([OpenTimestamps calendar server model](https://github.com/opentimestamps/opentimestamps-server/blob/master/README.md)).
3. **Upgrade to full proof**: once the Bitcoin transaction confirms (roughly 10 minutes to a few
   hours depending on batching/fee policy), running `ots upgrade FILE.ots` fetches the completed
   proof, now anchored to a specific Bitcoin block header's Merkle root.
4. **Verification, forever, offline**: to verify later, a verifier recomputes the hash chain from
   your document hash up through the sibling hashes in the `.ots` file to the Bitcoin block's
   Merkle root, then checks that root against any independent copy of the Bitcoin blockchain (their
   own node, a block explorer, etc.). **Crucially, you don't need to trust the calendar server
   long-term** — once upgraded, the proof's validity rests only on Bitcoin's own security, and the
   `.ots` file can be verified by anyone with just Bitcoin block headers, no calendar server
   involvement.

**Feasibility for a student project:** yes — it's genuinely free (no wallet, no gas, no faucet
needed — you're a submitter, not a Bitcoin transactor), a Java library exists
(`com.eternitywall:java-opentimestamps` — usable directly from a Kotlin backend or even the
Android app itself since it's pure networking + hash-chain math, no ASN.1), and it requires zero
infrastructure of your own. Its only weakness for a **live classroom demo** is latency: the Bitcoin
confirmation step can take from ~10 minutes up to a few hours, so a "verify this NOW" demo would
have to pre-timestamp assets in advance, or demonstrate the flow up to the "pending" partial-proof
stage live and show a previously-upgraded proof for the "confirmed" stage.

---

## 5. Blockchain anchoring — comparing the three options, and one decisive pick

The PPT lists this as a **Future** item, so what's needed for Zeroth Review is a concrete, feasible
*design*, not a production deployment. Comparing the three candidates:

| | **(a) OpenTimestamps on Bitcoin** | **(b) Ethereum L2 testnet (Polygon)** | **(c) Hyperledger Fabric (private)** |
|---|---|---|---|
| Cost | Free forever (aggregated, no tx fee to submitter) | Free on testnet (faucet POL/ETH); ~$0.015/tx on Polygon mainnet ([Polygon PoS avg tx fee](https://tokenterminal.com/explorer/projects/polygon/metrics/transaction-fee-average)) | Free (self-hosted) but heavy ops cost |
| Setup complexity | Low — install `opentimestamps-client`, done | Low-medium — write ~30-line Solidity contract, deploy via Remix, call via web3j | High — Docker containers, ordering service, MSP/certificate authorities, chaincode language, peer/channel config; explicitly documented as requiring "deep understanding... specialised knowledge" ([Fabric setup complexity](https://www.educative.io/answers/hyperledger-fabric-vs-public-blockchain)) |
| Confirmation latency | ~10 min – few hrs (batched into Bitcoin blocks) | ~2 seconds (Polygon PoS block time) | Near-instant (no PoW/PoS, permissioned) but you own the ops burden |
| Demo-ability (viva) | Good conceptually, but slow live-verification | **Excellent** — you can show your own Solidity contract, deploy live, call live, and it confirms in seconds | Good in theory, but the setup overhead itself becomes the demo risk |
| "Do we look like we understand blockchain internals?" | Moderate (using someone else's protocol) | **High** — you wrote and deployed the smart contract yourselves | High, if it works — but two students maintaining a permissioned network for one feature is disproportionate effort |
| Public infra already running | Yes (calendar servers are free public infra) | Yes (Polygon Amoy testnet is a live, maintained public testnet, faucet-funded) | No — you must stand up your own network from scratch |

**Decisive recommendation: (b) Polygon (Ethereum L2) testnet with a custom minimal Solidity
contract.** Reasoning: Hyperledger Fabric is disproportionately heavy for two students to stand up
and keep running reliably for a semester-project demo — multiple sources independently confirm its
setup/maintenance burden is high and requires specialized expertise that isn't the point of this
course ([Fabric complexity](https://zebpay.com/blog/hyperledger-fabric-vs-public-blockchains), [Fabric pros/cons](https://www.verytechnology.com/insights/the-pros-and-cons-of-hyperledger-fabric)). OpenTimestamps is excellent and genuinely the *cheapest and
most decentralization-honest* option, and is worth implementing too (see Section 8's optional
step), but its Bitcoin confirmation latency makes it a poor **live demo** experience compared to
Polygon's ~2-second blocks. A custom Solidity contract on **Polygon Amoy testnet** (the current
official Polygon PoS testnet, replacing the deprecated Mumbai testnet, anchored to Ethereum Sepolia
— [Polygon Amoy intro](https://polygon.technology/blog/introducing-the-amoy-testnet-for-polygon-pos), [Amoy RPC/chain details](https://thirdweb.com/polygon-amoy-testnet)) gives you: free faucet-funded test tokens (no real money), fast
confirmations suitable for a live viva, and — importantly — lets you demonstrate actual smart-
contract literacy (functions, events, gas) which examiners in this course are likely to probe.

**Minimal smart contract design (the entire "future" feature in one file):**
```solidity
// SPDX-License-Identifier: MIT
pragma solidity ^0.8.19;

contract RealityLockAnchor {
    struct Anchor {
        uint256 timestamp;   // block.timestamp when anchored
        address submitter;   // who anchored it
        bool exists;
    }

    mapping(bytes32 => Anchor) private anchors;

    event HashAnchored(bytes32 indexed proofHash, address indexed submitter, uint256 timestamp);

    // Anyone (your backend relay) can anchor a proof-package root hash
    function anchorHash(bytes32 proofHash) external {
        require(!anchors[proofHash].exists, "Already anchored");
        anchors[proofHash] = Anchor(block.timestamp, msg.sender, true);
        emit HashAnchored(proofHash, msg.sender, block.timestamp);
    }

    // Verification module calls this (read-only, no gas) to check anchoring
    function verifyHash(bytes32 proofHash)
        external view
        returns (bool exists, uint256 timestamp, address submitter)
    {
        Anchor memory a = anchors[proofHash];
        return (a.exists, a.timestamp, a.submitter);
    }
}
```
This mirrors the standard minimal "proof of existence" pattern (`mapping(bytes32 => ...)` keyed by
the document/proof hash, with a boolean/timestamp value and a `storeProof`/`checkDocument`-style
function pair) that's a well-established teaching example for this exact use case ([proof-of-existence Solidity example](https://github.com/ramyhardan/proof-of-existence)).
Deploy once via Remix IDE + MetaMask against Polygon Amoy; call it from your Kotlin backend (not
directly from the Android app — keep private keys for the anchoring wallet off the phone) using
**web3j** (`org.web3j:core`), the standard lightweight, actively-maintained Java/Android/Kotlin
library for talking to Ethereum-compatible JSON-RPC nodes and auto-generating type-safe contract
wrappers from your Solidity ABI ([web3j for Android](https://blog.web3labs.com/setting-up-the-latest-web3j-library-for-android-development/), [web3j repo](https://github.com/LFDT-web3j/web3j)).

---

## 6. GPS/location spoofing detection

Layered, in order of strength — no single check is bulletproof, so combine them:

1. **`Location.isMock()` (API 31+) / `Location.isFromMockProvider()` (pre-31)** — a boolean flag
   Android itself attaches to any `Location` object that came from a mock-location provider (e.g. a
   developer-settings fake-GPS app). Check this first and reject/flag any capture where it's true
   ([mock location detection overview](https://blog.anmolthedeveloper.com/how-to-detect-fake-gps-and-mock-location-in-android-apps-a-developers-security-guide)).
2. **`GnssMeasurement` raw signal analysis (API 24+)** — go beneath the resolved lat/long and
   inspect the raw satellite signals themselves: per-satellite carrier-to-noise density (`Cn0DbHz`,
   typically 10–50 dB-Hz for genuine sky-visible signals) and automatic gain control level (`AGC`,
   which should sit near a nominal baseline; anomalous/uniform AGC or unnaturally clean/uniform C/N0
   across all satellites is a spoofing red flag, since real multipath/atmospheric noise varies
   satellite-to-satellite) ([Raw GNSS measurements docs](https://developer.android.com/develop/sensors-and-location/sensors/gnss), [GNSS jamming/spoofing detection study](https://navi.ion.org/content/navi/69/3/navi.537.full.pdf)).
3. **Cross-validation against a second, independent position source** — compare the GNSS-derived
   fix against a `FusedLocationProviderClient` reading (which blends Wi-Fi/cell-tower positioning),
   and flag captures where the two diverge beyond a plausible error radius (e.g. >500 m). This is
   one of four empirically-validated detection methods identified in peer-reviewed research:
   comparing GNSS vs network location, checking the mock-location flag, comparing GNSS vs system
   clock (a spoofed GNSS feed sometimes carries a skewed time), and AGC/C-N0 signal-metric analysis
   ([GNSS spoofing detection methods](https://navi.ion.org/content/69/3/navi.537)).
4. **Physical plausibility checks between consecutive events** — compute Haversine distance between
   this event's location and the user's last captured event, divide by elapsed time; if implied
   speed exceeds a generous physical bound (e.g. >300 km/h, to allow for air travel) flag as
   suspicious "teleportation."

**Honest limitation to state in your report:** on a rooted device, tools like Magisk/Xposed modules
can hide the mock-location flag entirely so `isMock()`/`isFromMockProvider()` return `false` even
for spoofed coordinates ([root-hiding limitation](https://blog.anmolthedeveloper.com/how-to-detect-fake-gps-and-mock-location-in-android-apps-a-developers-security-guide)) — which is precisely why location checks must be combined with
Play Integrity's device-integrity verdict (Section 3): a rooted/compromised device that could hide
mock-location flags is exactly the device class Play Integrity is designed to flag as
`MEETS_BASIC_INTEGRITY` or worse, giving you a second, independent line of defense.

---

## 7. Tamper-evidence vs tamper-proof — the precise nuance for your literature survey

This distinction is the single most important conceptual point your literature-survey slide needs
to get exactly right, because it's also the main thing academic reviewers will probe you on.

**What the hash + signature scheme cryptographically *achieves*:**
- **Integrity**: SHA-256 is (for all practical/known-attack purposes) collision- and
  preimage-resistant — flipping even one bit anywhere in the hashed input changes the digest
  completely and unpredictably, so any post-hoc modification of the media or metadata is
  detectable by recomputing the hash and comparing ([hash-based tamper detection explainer](https://wesignature.com/blog/document-hashing-prevents-digital-tampering/)).
- **Authenticity / non-repudiation**: the ECDSA signature over the root hash, produced by a private
  key that never leaves secure hardware, proves the signature could only have been produced by the
  device/app holding that specific key — so a verifier knows *which* attested app instance vouched
  for this exact bundle of bytes, and the signer cannot later deny having signed it.
- Together these two properties are precisely what's meant by **tamper-evidence**: the system
  *detects* any modification made *after* the hash was computed. This is the same property digital
  signature platforms rely on for documents — if a signed PDF is edited afterward, verification
  fails and viewers show a "modified since signing" warning ([tamper-evidence in e-signing](https://legittai.com/blog/tamper-evidence-and-document-integrity-hashing-seals-and-post-sign-locking)).

**What it does *not* achieve — the "trusted capture boundary" problem:**
Hash-and-sign schemes say nothing about whether the bytes being hashed **accurately represent
reality** at the moment of capture — they only vouch for "these exact bytes, whatever they are,
have not changed since T0." A hash cannot distinguish between:
- A genuine photo of a real accident scene, vs.
- A photo of a *screen displaying* a fake/AI-generated accident image (the "analog hole" /
  re-recording attack — you're photographing a photo, and the hash is computed honestly over that
  re-recorded, but fraudulent, capture), vs.
- Sensor readings fed to the app by a modified/emulated sensor HAL before your code ever calls
  `MessageDigest.digest()`.

In other words: **everything upstream of the hash computation is a "trusted capture boundary" (or
Trusted Computing Base, TCB) problem, and Reality Lock's cryptography cannot solve it by itself** —
it can only make tampering *after* that boundary detectable, not tampering *before/at* it. This is
exactly why Play Integrity (Section 3, proving an untampered app + genuine device produced the
capture) and GNSS/location cross-validation (Section 6, catching spoofed sensor inputs) are
necessary *complements* to hashing, not redundant with it — they push the trust boundary as early
and as close to the physical sensors as is feasible on commodity hardware, but they still cannot
fully close it (e.g., photographing a screen remains a fundamentally hard, unsolved problem even
for industry systems). This exact gap is why the serious industry approach to this problem
(C2PA / Content Credentials, and vendors like Truepic) invests specifically in *secure capture
pipelines* — locking down the camera stack itself, signing inside a controlled SDK the moment
pixels leave the sensor — rather than relying on hash-and-sign alone, and even those systems
explicitly do not claim to solve the "photo of a screen" analog-hole problem ([C2PA how-it-works](https://contentauthenticity.org/how-it-works), [Truepic capture-time signing](https://www.truepic.com/blog/truepics-technology-provides-authenticity-and-content-verification-via-tamper-evident-imagery)).
**State this explicitly on your literature-survey slide**: Reality Lock provides *cryptographic
integrity and authenticity of the recorded bundle*, not *forensic proof that the recorded bundle
depicts an undoctored real-world event* — those are two different, commonly-conflated properties,
and the gap between them is itself the acknowledged open problem in this entire research area.

---

## 8. Recommended end-to-end crypto pipeline (Phase 3: Security Implementation)

Concrete, step-by-step, library-named, and directly matched to the PPT's own Phase 3 scope
("Encryption, Digital signature, Tamper detection") and Slide 9 block diagram stages 3–6.

**Step 1 — Capture (Phase 2, feeds into Phase 3):**
CameraX captures the photo/video; simultaneously read `FusedLocationProviderClient` (high-accuracy
priority) for GPS, `SensorManager` snapshots of `Sensor.TYPE_ACCELEROMETER` /
`Sensor.TYPE_GYROSCOPE`, `Build.MODEL`/`Build.FINGERPRINT` for device info, and both
`System.currentTimeMillis()` (wall clock) and `SystemClock.elapsedRealtimeNanos()` (monotonic, hard
to spoof via clock changes) for the timestamp. Also read `location.isMock()`/`isFromMockProvider()`
right here (Section 6).

**Step 2 — Hash the media:**
```kotlin
val digest = java.security.MessageDigest.getInstance("SHA-256")
DigestInputStream(mediaFile.inputStream(), digest).use { it.readBytes() } // stream, don't load whole video into RAM
val mediaHash = digest.digest()
```

**Step 3 — Canonicalize and hash the metadata:**
Build the metadata object, canonicalize with `io.github.erdtman:java-json-canonicalization`
(`JsonCanonicalizer(json).getEncodedString()`), then `SHA-256` the canonical bytes → `metadataHash`.

**Step 4 — Compose the root (hash-of-hashes, Section 1):**
`rootHash = SHA-256(mediaHash + metadataHash)` (2-leaf Merkle root minimum; extend to the 5-leaf
tree from Section 1 for selective disclosure if time permits).

**Step 5 — Sign the root with the hardware-backed key (Section 2):**
`Signature.getInstance("SHA256withECDSA")` using the `AndroidKeyStore`-resident P-256 private key
(StrongBox-backed where `FEATURE_STRONGBOX_KEYSTORE` is available, TEE fallback otherwise).

**Step 6 — Attest the app/device (Section 3):**
Request a Play Integrity **Standard** token with `requestHash = SHA-256(rootHash + eventId)`;
attach the encrypted token to the package for later server-side `decodeIntegrityToken` verification.

**Step 7 — Assemble the Proof Package** (matches Slide 9's checklist exactly):
`{ mediaFile, canonicalMetadataJson, mediaHash, metadataHash, rootHash, signature (Base64),
signing public key + attestation cert chain, Play Integrity token }`.

**Step 8 — Secure storage:**
Upload media + JSON manifest to Firebase Storage / any cloud object store (per PPT Slide 8), as an
immutable, versioned object (never overwrite in place — write new object per event).

**Step 9 — (Optional now / "Future" per PPT) Anchor externally:**
Backend submits `rootHash` to OpenTimestamps calendar servers (free, Section 4) **and/or** calls
`RealityLockAnchor.anchorHash(rootHash)` on Polygon Amoy testnet via web3j (Section 5); store the
returned `.ots` proof bytes or transaction hash in the package.

**Step 10 — Verification module** (implements Slide 9's "Hash Match / Signature Verify / Time
Validate / Location Verify / Integrity Check → Authenticity Result"):
1. Recompute `mediaHash` from the stored media file; compare — mismatch ⇒ **media tampered**.
2. Recanonicalize stored metadata fields, recompute `metadataHash`; compare — mismatch ⇒
   **metadata tampered**.
3. Recompute `rootHash`; verify with `Signature.getInstance("SHA256withECDSA").initVerify(publicKey)`
   — failure ⇒ **signature invalid / package forged**. Also validate the public key's attestation
   certificate chain up to Google's root, to confirm it really came from secure hardware.
4. Check `timestampUtc` plausibility (not in the future beyond clock-skew tolerance, monotonic
   clock consistent with wall clock).
5. Check location plausibility (`isMock` false, GNSS-vs-fused-location agreement, speed/distance
   sanity vs. prior events).
6. If a Play Integrity token is present, backend calls `decodeIntegrityToken` and checks verdicts.
7. If an anchor proof is present, verify the OpenTimestamps `.ots` Merkle path against a Bitcoin
   block header, and/or call `RealityLockAnchor.verifyHash(rootHash)` (a free, gas-less `view` call)
   on Polygon.
8. Emit **Authenticity Result: Valid / Tampered**, with a per-check breakdown (which specific
   check failed) rather than a single opaque boolean — this is both better engineering and better
   for your demo, since you can show exactly which layer catches a deliberately-tampered test file.

**Full library/API manifest for the report's Phase 3 slide:**
`java.security.MessageDigest` ("SHA-256"), `java.security.Signature` ("SHA256withECDSA"),
`java.security.KeyPairGenerator` + `"AndroidKeyStore"` provider, `android.security.keystore.KeyGenParameterSpec`,
`android.security.keystore.KeyProperties`, `setIsStrongBoxBacked`, `androidx.camera` (CameraX),
`com.google.android.gms.location.FusedLocationProviderClient`, `android.hardware.SensorManager`,
`android.location.Location.isMock`/`isFromMockProvider`, `android.location.GnssMeasurement`,
`com.google.android.play:integrity` (`IntegrityManagerFactory.createStandard`), `io.github.erdtman:java-json-canonicalization`,
`org.bouncycastle:bcpkix-jdk18on` (backend RFC 3161 TSP client), `com.eternitywall:java-opentimestamps`
(optional), `org.web3j:core` (optional Polygon contract calls).

---

## Recommended Security Architecture (decisive summary)

| Layer | Exact choice |
|---|---|
| Hash function | **SHA-256** (`java.security.MessageDigest`), not SHA-3/BLAKE3 |
| What's hashed | Media hash **+** canonical-JSON (RFC 8785 JCS) metadata hash, combined via a **hash-of-hashes / Merkle root**, not the flat media file alone |
| Signature algorithm | **ECDSA P-256**, `Signature.getInstance("SHA256withECDSA")` — not RSA-2048 (too CPU/battery-heavy), not Ed25519 yet (StrongBox doesn't support it) |
| Key storage | **Android Keystore**, `KeyGenParameterSpec` + `KeyProperties.PURPOSE_SIGN`, **StrongBox-backed** (`setIsStrongBoxBacked(true)`) with automatic TEE fallback on `StrongBoxUnavailableException` |
| App/device attestation | **Play Integrity API, Standard request flow**, verdict decoded server-side only |
| Location integrity | `Location.isMock()`/`isFromMockProvider()` **+** `GnssMeasurement` C/N0-AGC analysis **+** fused-location cross-check **+** speed/distance plausibility |
| Trusted timestamp (optional, doable now) | **FreeTSA** RFC 3161 endpoint called from the backend via Bouncy Castle `org.bouncycastle.tsp` |
| Blockchain anchoring ("Future" per PPT) | **Polygon Amoy testnet + custom `RealityLockAnchor.sol`** contract, called from backend via **web3j**; OpenTimestamps as a free complementary/backup anchor |
| Storage | Firebase Storage / cloud object store, immutable per-event objects (matches PPT Slide 8) |
| Literature-survey framing | Cryptography here delivers **tamper-evidence** (integrity + authenticity of the recorded bundle) — it explicitly does **not** deliver forensic proof that the content depicts an undoctored real event; that gap is the acknowledged, unsolved "trusted capture boundary" problem shared with industry systems like C2PA/Truepic |

This architecture uses only free tooling (Android SDK, Bouncy Castle, Play Console's free Play
Integrity tier, FreeTSA, OpenTimestamps, a Polygon testnet faucet), requires no enterprise
infrastructure, and is scoped so that Phase 3 (Encryption, Digital signature, Tamper detection) is
fully implementable by two students: Steps 1–8 of Section 8 are the semester-realistic core; Step 9
(external anchoring) is the "Future" line item, already fully designed and ready to build once core
Phase 3 is stable.
