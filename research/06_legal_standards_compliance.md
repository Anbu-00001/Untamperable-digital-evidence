# Legal, Regulatory & Standards Context for "Reality Lock"

**Project:** Reality Lock — Tamper-Evident Event Proof System for Mobile Devices
**Prepared for:** Zeroth Review, Literature Survey / Ethical Considerations section
**Scope:** Desk research grounding only.

> **DISCLAIMER — READ FIRST:** This document is academic literature-review research
> compiled for a college mobile-application-development course project. It is **not
> legal advice**. It does not constitute a certified legal opinion, is not a
> substitute for consultation with a qualified advocate or forensic expert, and
> must not be relied upon for real litigation, real insurance claims, or real law
> enforcement use. All statements about "what would satisfy" a law or standard are
> the authors' academic interpretation for the purpose of motivating a design, not
> a claim of legal compliance. Where court judgments are cited, the case names and
> holdings are given for literature-survey context, not as legal counsel on how a
> specific piece of evidence would be treated in a specific case.

---

## Table of Contents
1. [Indian Law — Section 65B / BSA Section 63 electronic evidence certificate](#1-indian-law)
2. [ISO/IEC 27037:2012 — International digital evidence standard](#2-isoiec-270372012)
3. [Data Privacy — India's DPDP Act 2023 and GDPR comparison](#3-data-privacy)
4. [Insurance Industry — Fraud detection via photo/video metadata](#4-insurance-industry)
5. [Journalism Standards — IPTC metadata & Content Authenticity Initiative](#5-journalism-standards)
6. [Law Enforcement/Forensics — Chain-of-custody principles (NIST SP 800-86, ACPO, e-Sakshya)](#6-law-enforcementforensics)
7. [Honest Limitations — What tamper-evidence does and does NOT prove](#7-honest-limitations)
8. [Design Implications](#8-design-implications)

---

## 1. Indian Law

### 1.1 Section 65B, Indian Evidence Act, 1872 (as it stood until mid-2024)

Section 65B governs the admissibility of "electronic records" (anything printed,
stored, recorded, or copied from a computer output) as evidence in Indian courts,
without requiring the original device/medium to be physically produced. Its
central requirement is a **Section 65B(4) certificate**:

- The certificate must identify the electronic record, describe the manner in
  which it was produced, and give particulars of the device involved, and it
  must deal with the statutory conditions of admissibility (i.e., the computer
  was in regular use, the information was regularly fed in, the computer was
  operating properly, and the output reproduces the information in the ordinary
  course of activity).
- It must be **signed by a person occupying a "responsible official position"
  in relation to the operation of the device or the management of the relevant
  activities** — i.e., the person in *control* of the device, not necessarily
  its *owner* ([Law Web summary of Rajasthan HC ruling](https://www.lawweb.in/2025/09/rajasthan-hc-section-65b-certificate.html)).
- The Supreme Court, in **Anvar P.V. v. P.K. Basheer** (2014) and definitively in
  **Arjun Panditrao Khotkar v. Kailash Kushanrao Gorantyal** (2020), held that the
  65B(4) certificate is a **mandatory condition precedent** to admissibility of
  electronic evidence tendered as secondary evidence (i.e., not the original
  device itself) — a certificate cannot be dispensed with just because it is
  inconvenient to obtain ([Arjun Panditrao Khotkar judgment, IndianKanoon](https://indiankanoon.org/doc/172105947/); [ksandk case summary](https://ksandk.com/litigation/section-65b-of-indian-evidence-act/); [Cyril Amarchand Mangaldas summary](https://corporate.cyrilamarchandblogs.com/2020/07/section-65b-of-the-indian-evidence-act-1872-requirements-for-admissibility-of-electronic-evidence-revisited-by-the-supreme-court/)).
- The certificate requirement is **waived only if the original electronic
  device/medium itself is produced** as primary evidence before the court
  ([LiveLaw analysis](https://www.livelaw.in/columns/arjun-panditrao-decision-the-time-to-revisit-s65b-of-indian-evidence-act-a-scientific-legal-analysis-162590)).
- Courts have separately flagged the need for **chain-of-custody rules**:
  retention-period rules for digital evidence, stamping, and record maintenance
  practices, though these are largely left to procedural/forensic practice
  rather than spelled out in the statute itself ([Mondaq — "Resolving the Conundrum"](https://www.mondaq.com/india/trials-appeals-compensation/979312/resolving-the-conundrum-section-65b-of-the-indian-evidence-act-1872)).

### 1.2 Section 63, Bharatiya Sakshya Adhiniyam (BSA), 2023 — the successor law

The BSA replaced the Indian Evidence Act entirely, effective **1 July 2024**.
Section 63 is the direct successor to Section 65B, with meaningful changes
relevant to a project like Reality Lock:

- Section 63 **explicitly widens scope** to cover records "stored, recorded or
  copied in optical or magnetic media or **semiconductor memory** which is
  produced by a **computer or any communication device**" — i.e., it expressly
  contemplates smartphones and similar devices, whereas the old Section 65B's
  wording was more computer-centric ([RK Dewan summary](https://www.rkdewan.com/articles/electronic-records-now-governed-by-section-63-of-the-bhartiya-sakshya-adhiniyam-2023/); [KSandK Section 63 explainer](https://ksandk.com/litigation/section-63-bharatiya-sakshya-adhiniyam-2023/)).
- **Dual certification is now required** under Section 63(4): (a) a certificate
  from the **person in charge of the computer/communication device**, giving
  device particulars and describing how the record was produced, **and** (b) a
  certificate from an **independent expert**. This is a structural change from
  the single-certificate regime under old Section 65B ([ksandk — Section 63 BSA explainer](https://ksandk.com/litigation/section-63-bharatiya-sakshya-adhiniyam-2023/); [Bhatt & Joshi Associates](https://bhattandjoshiassociates.com/electronic-evidence-under-bsa-2023-section-63-certificate-requirements-supreme-court-interpretation/)).
- The **Schedule to the BSA explicitly requires the expert to state the hash
  value of the electronic/digital record and the algorithm used to compute
  it** in their certificate — this is a direct, named recognition of
  cryptographic hashing as part of the statutory evidentiary chain
  ([Corpotech Legal — hash value analysis](https://corpotechlegal.com/admissibility-electronic-evidence-sec-63-bsa/); [Corpotech Legal — certificate scope](https://corpotechlegal.com/certificate-bsa-2023-for-admissibility-of-electronic-evidence/)).
- As under the old law, a Section 63(4)-equivalent certificate is unnecessary
  only if the **original device itself** is produced as evidence in court.

### 1.3 How Reality Lock's output maps onto a Section 65B / Section 63 certificate

**What it can plausibly help with:**
- Reality Lock's hash generation at capture time, combined with a device-signed
  proof package, produces exactly the kind of artifact BSA's Schedule now
  contemplates — a **stated hash value + algorithm**, tied to a specific device.
  A Reality Lock export could plausibly serve as a *technical annexure* to a
  certificate, or as supporting material that helps whoever signs the
  human/legal Section 63(4) certificate substantiate their claims about "how the
  record was produced" and "particulars of the device."
- Because BSA explicitly names communication devices (smartphones) and
  semiconductor memory, a mobile-native tamper-evidence tool is well aligned
  with what the amended law is now built to receive.

**What it cannot, by itself, satisfy — and this is important intellectual
honesty for a literature survey:**
- **Reality Lock is software, not a certifying legal person.** Section 65B(4)/
  Section 63(4) certificates must be **signed by a natural person** occupying a
  responsible position (device custodian) and, under the BSA, **also by an
  independent human expert**. An app cannot sign itself into legal existence as
  that certifying authority — a human still has to attest, in a legal document,
  to matters like "the device was operating properly" and "the information was
  fed in the ordinary course of activity." Reality Lock can *generate the
  evidentiary material* (hash, timestamp, signature, sensor log) that a human
  certifier would *reference and rely on*, but it cannot replace the certifier.
- **Courts require the certificate to originate from whoever controls the
  device**, not from a third-party app vendor — so if Reality Lock is a
  third-party app installed on a user's own phone, the "person in charge of the
  device" is presumably the user/device owner, but they would still need to
  understand and personally certify the technical claims, likely with expert
  assistance, which the BSA now makes mandatory.
- Reality Lock proves **data integrity from the moment of capture** (nothing
  altered afterward), but a court certificate must also speak to facts Reality
  Lock cannot independently know or attest — e.g., whether "the computer was
  operating properly throughout" in the statutory sense, or broader chain-of-
  custody facts after the file leaves the device (how it was transferred,
  stored, and produced in court).
- Therefore, Reality Lock is best framed in the project's literature survey as
  a **technical evidence-generation and integrity-assurance tool that produces
  inputs for a Section 63 certificate process**, not as a device that itself
  "generates a legally admissible certificate." A template/export feature that
  mirrors the *structure* of a Section 63(4)/Schedule certificate (hash value +
  algorithm + device particulars + production method) is a legitimate and
  valuable design feature — but the document would still need human/expert
  countersignature to have legal force.

**Sources:** [Section 65B of Indian Evidence Act — LinkedIn/Xperts Legal](https://www.linkedin.com/pulse/section-65b-indian-evidence-act-xperts-legal) · [Cyril Amarchand Mangaldas, "Section 65B Requirements Revisited by the Supreme Court" (2020)](https://corporate.cyrilamarchandblogs.com/2020/07/section-65b-of-the-indian-evidence-act-1872-requirements-for-admissibility-of-electronic-evidence-revisited-by-the-supreme-court/) · [Cyril Amarchand Mangaldas, "SC on Admissibility of Electronic Evidence" (2021)](https://corporate.cyrilamarchandblogs.com/2021/01/supreme-court-on-the-admissibility-of-electronic-evidence-under-section-65b-of-the-evidence-act/) · [Arjun Panditrao Khotkar v. Kailash Kushanrao Gorantyal, IndianKanoon](https://indiankanoon.org/doc/172105947/) · [LiveLaw — Arjun Panditrao analysis](https://www.livelaw.in/columns/arjun-panditrao-decision-the-time-to-revisit-s65b-of-indian-evidence-act-a-scientific-legal-analysis-162590) · [ksandk — Section 65B verdict summary](https://ksandk.com/litigation/section-65b-of-indian-evidence-act/) · [Law Web — Rajasthan HC on certificate ownership vs. control](https://www.lawweb.in/2025/09/rajasthan-hc-section-65b-certificate.html) · [Section 63, Bharatiya Sakshya Adhiniyam, 2023 — IndianKanoon](https://indiankanoon.org/doc/125020475/) · [ksandk — Section 63 BSA 2023 explainer](https://ksandk.com/litigation/section-63-bharatiya-sakshya-adhiniyam-2023/) · [RK Dewan & Co. — Section 63 BSA analysis](https://www.rkdewan.com/articles/electronic-records-now-governed-by-section-63-of-the-bhartiya-sakshya-adhiniyam-2023/) · [Bhatt & Joshi Associates — Section 63 BSA certificate requirements](https://bhattandjoshiassociates.com/electronic-evidence-under-bsa-2023-section-63-certificate-requirements-supreme-court-interpretation/) · [Corpotech Legal — Hash value & Section 63 BSA](https://corpotechlegal.com/admissibility-electronic-evidence-sec-63-bsa/) · [Corpotech Legal — Certificate scope under BSA 2023](https://corpotechlegal.com/certificate-bsa-2023-for-admissibility-of-electronic-evidence/) · [Mondaq — "Resolving the Conundrum: Section 65B"](https://www.mondaq.com/india/trials-appeals-compensation/979312/resolving-the-conundrum-section-65b-of-the-indian-evidence-act-1872)

---

## 2. ISO/IEC 27037:2012

**ISO/IEC 27037:2012**, *"Information technology — Security techniques —
Guidelines for identification, collection, acquisition and preservation of
digital evidence,"* is the leading international standard for handling
potential digital evidence so that it remains defensible in legal or
disciplinary proceedings ([ISO official standard page](https://www.iso.org/standard/44381.html); [ISO Online Browsing Platform](https://www.iso.org/obp/ui/#iso:std:iso-iec:27037:ed-1:v1:en)).

### Core structure — four processes
1. **Identification** — recognizing and locating potential sources of digital
   evidence (in Reality Lock's case: the phone's camera sensor, GPS chip,
   accelerometer/gyroscope, and the resulting media file).
2. **Collection** — the physical/logical gathering of devices or data where the
   original device may be removable or retained.
3. **Acquisition** — creating a forensically sound copy/image of the data
   (bit-for-bit, or in Reality Lock's case, a hash-verified export) without
   altering the source.
4. **Preservation** — safeguarding the integrity of the evidence throughout its
   entire lifecycle, from the moment of capture to its eventual presentation.

The standard explicitly covers **digital still and video cameras** as a named
device category, alongside mobile phones, PDAs, memory cards, and standard
computers, which makes it directly applicable to a smartphone-based capture
tool like Reality Lock ([Konfirmity — ISO/IEC 27037 overview](https://www.konfirmity.com/glossary/iso-iec-27037)).

### Core principles relevant to Reality Lock's design
- **Minimizing alteration**: methodology should avoid changing the evidence
  during handling wherever possible, and where change is unavoidable, it must
  be justified, documented, and explainable.
- **Chain of custody** at three layers — a distinction worth adopting directly
  into Reality Lock's design vocabulary:
  - *Physical custody* (where is the device/storage medium physically kept),
  - *Logical custody* (hash values, electronic seals, qualified/trusted
    timestamps),
  - *Documentary custody* (records and access logs of who touched the evidence,
    when, and why)
  ([TrueScreen — "Digital Chain of Custody: ISO 27037 and Best Practices"](https://truescreen.io/articles/digital-chain-of-custody-guide/)).
- **Documentation**: every action performed on evidence must be recorded so
  that an independent third party could review the process and reach the same
  conclusions — directly analogous to the ACPO "audit trail" principle (see
  §6 below).
- **Defined roles**: the standard introduces a **Digital Evidence First
  Responder (DEFR)** — who performs initial identification/collection — and a
  **Digital Evidence Specialist (DES)** — who manages more complex
  acquisition/analysis. For Reality Lock, the *app itself* effectively acts as
  an automated DEFR at the point of capture (auto-hashing, auto-sealing before
  a human ever touches the file), which is a strong design frame to describe
  in the literature survey.
- **Jurisdictional exchange**: the standard is explicitly written to support
  evidence being handled/exchanged across organizational and legal
  jurisdictions, relevant since Reality Lock's long-term scope includes
  cross-agency integration (law enforcement, insurance, smart cities).

### Mapping to Reality Lock's design
| ISO/IEC 27037 concept | Reality Lock's design equivalent |
|---|---|
| Identification | Auto-detection of capture sensors (camera, GPS, IMU) at the moment of event capture |
| Collection/Acquisition | On-device hash + signing immediately after capture, before any transfer |
| Preservation | Signed, hashed "proof package" stored tamper-evidently (local + optionally remote) |
| Logical chain of custody | Hash values, digital signature, cryptographic timestamp embedded in the proof package |
| Documentary chain of custody | An in-app custody log recording every export/access/transfer event |
| Minimizing alteration | Original media file never modified after capture; all verification is read-only against the hash |

**Sources:** [ISO/IEC 27037:2012 official page](https://www.iso.org/standard/44381.html) · [ISO OBP entry](https://www.iso.org/obp/ui/#iso:std:iso-iec:27037:ed-1:v1:en) · [UNODC E4J summary](https://www.unodc.org/e4j/data/_university_uni_/guidelines_for_identification_collection_acquisition_and_preservation_of_digital_evidence.html?lng=en&match=guidelines+for+identification) · [Konfirmity — ISO/IEC 27037 glossary entry](https://www.konfirmity.com/glossary/iso-iec-27037) · [TrueScreen — Digital Chain of Custody guide](https://truescreen.io/articles/digital-chain-of-custody-guide/) · [BSB Edge — ISO 27037:2012 standard summary](https://www.bsbedge.com/standard/information-technology-security-techniques-guidelines-for-identification-collection-acquisition-and-preservation-of-digital-evidence-iso-iec-27037-2012/ISO44381) · [Foro Evidencias Electrónicas — ISO 27037 overview](http://foroevidenciaselectronicas.org/en/iso-27037-electronic-evidence-management-guidelines/)

---

## 3. Data Privacy

### 3.1 India's Digital Personal Data Protection Act (DPDP Act), 2023

The DPDP Act, 2023, plus its operationalizing **Digital Personal Data
Protection Rules, 2025** (notified November 2025), form India's principal data
protection framework and would apply directly to Reality Lock, since the app
collects precise GPS coordinates, photos/videos (which may capture bystanders'
faces — arguably biometric-adjacent personal data), and device identifiers.

**Key obligations for an app like Reality Lock, as a "Data Fiduciary":**

- **Lawful purpose + explicit, informed consent**: personal data may only be
  processed for a lawful purpose for which the Data Principal (user, and
  incidentally, any bystander whose face/location is captured) has given
  consent, or under specified "legitimate uses." Consent must be **free,
  specific, informed, unconditional, and unambiguous**, given through clear
  affirmative action, and **not bundled** with other permissions ([ksandk — Regulation of Biometric Data under DPDP Act](https://ksandk.com/data-protection-and-data-privacy/regulation-of-biometric-data-under-the-dpdp-act/); [Muhami — Biometric Data Compliance under DPDPA](https://muhami.ae/articles/how-is-biometric-data-protected-under-indian-law/)).
- **Notice requirement**: before processing, the Data Fiduciary must give a
  clear, itemized notice describing exactly what data is collected, the
  specific purpose, and how to withdraw consent or complain to the Data
  Protection Board of India. The notice must be understandable *independent*
  of other information the app might present ([PIB press release on DPDP Rules 2025](https://www.pib.gov.in/PressReleasePage.aspx?PRID=2190655); [Lexology — DPDP Rules 2025 operationalizing consent](https://www.lexology.com/library/detail.aspx?g=7e3af947-10aa-4712-bc1e-54179a613409)).
- **Data minimisation**: collect only data that is legally and operationally
  necessary for the stated purpose — relevant to Reality Lock's design because
  continuous background GPS/motion logging beyond what is needed to prove a
  *specific captured event* could be legally risky, not just privacy-unfriendly.
- **Storage limitation & purpose limitation**: data should be deleted once its
  purpose is fulfilled unless retention is legally required (e.g., for an
  active legal/insurance claim); some processing/consent logs must be retained
  for defined periods (rules discuss ~1 year retention windows for certain
  audit/consent records) ([DPDP Rules 2025 — Wikipedia summary](https://en.wikipedia.org/wiki/Digital_Personal_Data_Protection_Rules,_2025)).
- **Security safeguards**: encryption and access controls are expected —
  directly aligned with Reality Lock's cryptographic design anyway.
- **Children's data**: if any bystander/user might be under 18, **verifiable
  parental consent** is mandated — a real edge case for a capture app used in
  public spaces.
- **Extraterritorial reach**: the Act also applies to processing outside India
  if it is for offering goods/services to individuals in India — relevant if
  Reality Lock's backend/cloud storage is hosted abroad.
- **Bystander problem, specifically**: DPDP's definition of "personal data"
  covers any data about an identifiable individual. A photo/video that
  incidentally captures a third party's face and the GPS/timestamp of that
  moment plausibly makes that bystander a "Data Principal" too — even though
  they never used the app or gave consent. This is a genuine, unresolved
  tension for any evidence-capture app and is worth flagging explicitly in the
  literature survey (see §7).

### 3.2 GDPR (EU) — comparison, since long-term scope includes broader/foreign integration

- The GDPR treats **biometric data as "special category data"** under Article
  9, alongside health data, racial/ethnic origin, etc., requiring **explicit**
  consent (a stricter bar than ordinary consent) or another narrowly defined
  Article 9(2) legal basis. **The DPDP Act, by contrast, has no special
  category of data at all — it treats all personal data (including biometric
  and location data) under one uniform framework**, with no heightened
  safeguard tier ([Securiti — DPDP Act vs GDPR](https://securiti.ai/india-digital-personal-data-protection-act-vs-gdpr/); [IAPP — Biometrics in the EU: GDPR & AI Act](https://iapp.org/news/a/biometrics-in-the-eu-navigating-the-gdpr-ai-act/); [GDPR Local — Biometric Data GDPR Compliance](https://gdprlocal.com/biometric-data-gdpr-compliance-made-simple/)).
- Both regimes converge on the *definition* of valid consent (free, specific,
  informed, unambiguous, revocable as easily as given) — so Reality Lock's
  consent UX can largely be designed once and satisfy both, but an EU/global
  rollout would need an explicit **extra "special category" consent gate**
  specifically for any biometric-like face data captured incidentally, which
  Indian law does not currently force but GDPR does.
- GDPR also has a broader menu of lawful bases beyond consent (e.g.,
  legitimate interest), while DPDP leans much more heavily on consent as the
  primary basis — meaning an India-first version of Reality Lock will need
  robust consent flows since it cannot as easily fall back on other legal
  bases ([Consent.in — DPDP vs GDPR comparison](https://www.consent.in/blog/dpdp-vs-gdpr); [Bird & Bird — Decrypting India's New Data Protection Law](https://www.twobirds.com/en/insights/2023/global/decrypting-indias-new-data-protection-law-key-insights-and-lessons-learned)).

### 3.3 Consent / minimization design features implied for the app
- Explicit, itemized, un-bundled consent screens for: (a) camera/microphone
  access, (b) precise GPS access, (c) motion-sensor access, (d) any cloud
  upload/sharing of the proof package.
- A **separate, additional notice/consent affordance aimed at bystanders**
  captured incidentally in frame — e.g., a visible on-screen indicator during
  capture ("this device is recording a tamper-evident record") and a
  post-capture blur/redaction option for faces not relevant to the proven
  event.
- On-device data minimisation: only log GPS/motion data for a short window
  bounded to the capture event itself, not continuous background tracking.
- A clear retention/deletion policy exposed to the user, with a manual "delete
  proof package" action, subject to legal-hold overrides when a package is
  attached to an active claim/case.
- A DPDP-style layered notice (short notice + detailed notice) and a
  visible link to withdraw consent / contact the Data Protection Board
  equivalent contact point within the app.

**Sources:** [DPDP Act 2023 full text — MeitY](https://www.meity.gov.in/static/uploads/2024/06/2bf1f0e9f04e6fb4f8fef35e82c42aa5.pdf) · [PRS India — DPDP Bill 2023 legislative brief](https://prsindia.org/billtrack/digital-personal-data-protection-bill-2023) · [DPDP Act, 2023 — Wikipedia](https://en.wikipedia.org/wiki/Digital_Personal_Data_Protection_Act,_2023) · [Digital Personal Data Protection Rules, 2025 — Wikipedia](https://en.wikipedia.org/wiki/Digital_Personal_Data_Protection_Rules,_2025) · [PIB — DPDP Rules 2025 press release](https://www.pib.gov.in/PressReleasePage.aspx?PRID=2190655) · [PIB — DPDP Rules 2025 notification PDF](https://static.pib.gov.in/WriteReadData/specificdocs/documents/2025/nov/doc20251117695301.pdf) · [Lexology — Operationalising consent under DPDP Rules 2025](https://www.lexology.com/library/detail.aspx?g=7e3af947-10aa-4712-bc1e-54179a613409) · [ksandk — Regulation of Biometric Data under DPDP Act](https://ksandk.com/data-protection-and-data-privacy/regulation-of-biometric-data-under-the-dpdp-act/) · [azb — Biometric Data Regulation in India](https://www.azbpartners.com/bank/biometric-data-regulation-in-india-legal-landscape-and-risks/) · [Muhami — Biometric Data Compliance under DPDPA 2023](https://muhami.ae/articles/how-is-biometric-data-protected-under-indian-law/) · [Securiti — DPDP Act vs GDPR](https://securiti.ai/india-digital-personal-data-protection-act-vs-gdpr/) · [Consent.in — DPDP vs GDPR](https://www.consent.in/blog/dpdp-vs-gdpr) · [Bird & Bird — Decrypting India's Data Protection Law](https://www.twobirds.com/en/insights/2023/global/decrypting-indias-new-data-protection-law-key-insights-and-lessons-learned) · [IAPP — Biometrics in the EU: GDPR & AI Act](https://iapp.org/news/a/biometrics-in-the-eu-navigating-the-gdpr-ai-act/) · [GDPR Local — Biometric Data GDPR Compliance](https://gdprlocal.com/biometric-data-gdpr-compliance-made-simple/)

---

## 4. Insurance Industry

### 4.1 How metadata is used today for fraud detection

- Insurers routinely extract **embedded metadata** (timestamp, GPS coordinates,
  device ID, EXIF data) from claim photos/videos and cross-check it against
  the claim's stated date, time, and location — mismatches are a strong fraud
  signal ([Verisk — Digital Media Fraud in Insurance Claims](https://www.verisk.com/blog/breaking-down-digital-media-fraud-for-claims-in-the-ai-era/); [VAARHAFT — Insurance Image Fraud Detection Guide](https://www.vaarhaft.com/blog/insurance)).
- A common fraud pattern the industry specifically watches for: claimants
  downloading pre-existing damage photos from the internet and submitting them
  as if freshly taken — undetectable without an adjuster site visit **unless**
  metadata/provenance is checked ([Verisk blog](https://www.verisk.com/blog/breaking-down-digital-media-fraud-for-claims-in-the-ai-era/)).
- AI-driven "digital media forensics" products (e.g., Verisk's offering) now
  screen claim photos/videos/PDFs for **reused images, manipulation artifacts,
  deepfakes, and tampered documents** before payout ([Verisk — Digital Media Forensics product page](https://www.verisk.com/products/digital-media-forensics/); [InsurTech Amsterdam — AI detecting manipulated images](https://insurtechamsterdam.com/blog/ai-detect-manipulated-images-fake-documents-insurance)).

### 4.2 Real apps/products that require specific capture metadata or camera apps

- **Truepic** is the closest existing real-world analogue to Reality Lock:
  a camera app/SDK that cryptographically signs photos/videos at capture with
  "trusted" metadata (time, date, GPS location) so the image is proven
  unedited from the moment of capture, binding it to a specific device via
  secure-enclave-signed timestamps and sensor-noise fingerprints. It is
  actively used by **major insurance companies** (its "Truepic Vision" product
  line is explicitly built for remote insurance inspections), as well as by
  banks, humanitarian organizations, and for citizen-journalism verification.
  Enterprise adopters cited include Equifax, EXL Service, Ford Motor Company,
  and Palomar ([TechCrunch — Truepic Microsoft-led funding round](https://techcrunch.com/2021/09/14/microsofts-m12-leads-26m-investment-into-truepic/); [Truepic — Technology overview](https://www.truepic.com/blog/truepics-technology-provides-authenticity-and-content-verification-via-tamper-evident-imagery); [Truepic — Reducing fraudulent claims](https://www.truepic.com/blog/reduce-fraudulent-claims); [Truepic Vision — fraud prevention](https://www.truepic.com/vision/fraud-prevention-detection)).
- **VAARHAFT's "SafeCam"** is another real example: an SMS-delivered,
  browser-based camera link sent to claimants above a fraud-risk threshold; it
  verifies the photo was taken of a genuine, physically-present 3D scene and
  actively blocks "photo of a photo/screen" attempts — precisely the kind of
  liveness/anti-restaging check a tamper-evidence app should consider adding
  ([VAARHAFT — Mastering Photo Verification in Insurance Claims](https://www.vaarhaft.com/blog/verifying-photos-in-insurance-claims); [VAARHAFT — Insurance Image Fraud Detection Guide](https://www.vaarhaft.com/blog/insurance)).
- Industry guidance for contractors/adjusters (e.g., "PHOTO iD," "SnapProof")
  now treats **timestamped, geotagged photos taken within 24–48 hours of an
  incident** as close to an evidentiary norm for validating sudden-damage
  claims — images lacking this metadata are explicitly called out as harder to
  trust ([PHOTO iD — Avoiding claim rejections with photo documentation](https://photoidapp.net/avoid-insurance-claim-rejections-with-photo-documentation/); [SnapProof — How to timestamp photos for insurance claims](https://snapproof.pro/blog/timestamp-photos-insurance-claims/)).

### 4.3 India-specific context (IRDAI)

- Under IRDAI rules, motor insurance losses **below ₹50,000 do not require a
  mandatory physical surveyor visit**, which has pushed insurers toward
  **AI/app-based photo and video assessment** for low-severity claims — exactly
  the segment where a tamper-evident capture app has the most leverage, since
  there is no human adjuster cross-check ([Upstox — Car insurance survey exemption rules](https://upstox.com/news/personal-finance/insurance/car-insurance-survey-not-required-for-loss-below-50-000-says-finance-ministry-check-irdai-rules-here/article-186369/)).
- Losses at or above ₹50,000 still require a **registered surveyor and loss
  assessor**, so Reality Lock's proof package would function as *supporting*
  evidence for the surveyor, not a replacement for the statutory survey
  requirement, at least for higher-value claims.
- Staged accidents, inflated damage, and recycled photos are explicitly named
  as the leading fraud vectors AI claim-screening tools target in the Indian
  motor insurance market ([RGA — Global Claims Views: India fraud detection tools](https://www.rgare.com/knowledge-center/article/global-claims-views-india---fraud-detection-tools); [Insurnest — Photo Damage Estimation AI Agent](https://insurnest.com/agent-details/insurance/claims/photo-damage-estimation-ai-agent-in-claims-for-personal-auto-insurance/)).

**Sources:** [Verisk — Digital Media Fraud in Insurance Claims: AI Deepfakes & Detection Guide](https://www.verisk.com/blog/breaking-down-digital-media-fraud-for-claims-in-the-ai-era/) · [Verisk — Digital Media Forensics product](https://www.verisk.com/products/digital-media-forensics/) · [Verisk — "Detect the Undetectable" campaign](https://www.verisk.com/resources/campaigns/detect-the-undetectable/) · [VAARHAFT — Insurance Image Fraud Detection Guide](https://www.vaarhaft.com/blog/insurance) · [VAARHAFT — Mastering Photo Verification in Insurance Claims](https://www.vaarhaft.com/blog/verifying-photos-in-insurance-claims) · [InsurTech Amsterdam — AI detecting manipulated images/fake documents](https://insurtechamsterdam.com/blog/ai-detect-manipulated-images-fake-documents-insurance) · [Salviol — Is That Claim Photo Real?](https://www.salviol.com/post/ai-image-fraud-detection-insurance) · [TruthScan — Spotting Fake Damage Images](https://truthscan.com/blog/fake-damage-images-in-insurance-claim/) · [TechCrunch — Truepic funding & technology](https://techcrunch.com/2021/09/14/microsofts-m12-leads-26m-investment-into-truepic/) · [Truepic — Tamper-Evident Imagery Technology](https://www.truepic.com/blog/truepics-technology-provides-authenticity-and-content-verification-via-tamper-evident-imagery) · [Truepic Vision — Fraud Prevention & Detection](https://www.truepic.com/vision/fraud-prevention-detection) · [PHOTO iD — Avoiding Claim Rejections with Photo Documentation](https://photoidapp.net/avoid-insurance-claim-rejections-with-photo-documentation/) · [SnapProof — Timestamping Photos for Insurance Claims](https://snapproof.pro/blog/timestamp-photos-insurance-claims/) · [Upstox — IRDAI motor survey exemption rules](https://upstox.com/news/personal-finance/insurance/car-insurance-survey-not-required-for-loss-below-50-000-says-finance-ministry-check-irdai-rules-here/article-186369/) · [RGA — Global Claims Views: India fraud detection tools](https://www.rgare.com/knowledge-center/article/global-claims-views-india---fraud-detection-tools)

---

## 5. Journalism Standards

### 5.1 IPTC Photo Metadata Standard

- The **IPTC Photo Metadata Standard** (maintained by the International Press
  Telecommunications Council, jointly developed with Adobe using XMP
  technology) is the de facto industry schema for embedding structured,
  machine-readable metadata into press/stock photos, comprising **IPTC Core**
  and **IPTC Extension** ([IPTC Photo Metadata Standard](https://iptc.org/standards/photo-metadata/iptc-standard/)).
- Before publication, professional workflows require nonempty **Creator,
  CreditLine, and DateCreated** fields at minimum, plus optional but
  widely-used fields for precise location, named people, and rights
  information ([Fastio — IPTC Metadata Management for Photographers](https://fast.io/resources/iptc-metadata-management-photographers/); [LegalClarity — IPTC Standard: Attribution and Rights](https://legalclarity.org/iptc-photo-metadata-standard-attribution-and-rights/)).
- The **2025.1 revision (ratified November 2025)** added four new fields
  specifically for AI-generated/AI-assisted content: *AI System Used, AI
  System Version Used, AI Prompt Information, AI Prompt Writer Name* — showing
  the standard actively evolving toward provenance/authenticity concerns very
  close to Reality Lock's problem space ([Numonic — IPTC 2025.1 and C2PA](https://www.numonic.ai/blog/iptc-2025-c2pa-ai-provenance-metadata)).
- **ExifTool** is cited as the de facto industry tool for reading/writing/
  validating these tags in newsroom CMS ingestion pipelines.

### 5.2 IPTC's Media Provenance work and C2PA

- IPTC now positions itself as delivering **"cryptographically-verifiable
  provenance metadata"** to the news industry, explicitly building on and
  liaising with the **Coalition for Content Provenance and Authenticity
  (C2PA)** ([IPTC — Media Provenance](https://iptc.org/media-provenance/)).
- IPTC runs a **"Verified News Publisher" certificate program**: publishers
  apply, undergo identity verification, and receive certificates from
  established Certificate Authorities, letting content validators confirm a
  news object came from a verified publisher and was not tampered with since
  publication — but **IPTC is explicit that this verifies publisher identity
  only, not the truthfulness of the content itself** ([IPTC — Media Provenance](https://iptc.org/media-provenance/)) — an important parallel limitation to Reality Lock's own (see §7).

### 5.3 Content Authenticity Initiative (CAI) and C2PA — news industry adoption

- CAI was founded in **2019 by Adobe, The New York Times, and Twitter**;
  **C2PA** (the technical standards body) was formed in **February 2021** when
  Microsoft and the BBC joined Adobe, Arm, Intel, and Truepic ([Adobe blog — C2PA founding](https://blog.adobe.com/en/publish/2021/02/22/adobe-continues-content-authenticity-commitment-founder-c2pa-standards-org); [Wikipedia — Content Authenticity Initiative](https://en.wikipedia.org/wiki/Content_Authenticity_Initiative)).
- C2PA's steering committee today includes **Adobe, BBC, Google, Intel,
  Microsoft, OpenAI, Sony, and Truepic**, with ~120 member companies; named
  news-industry collaborators explicitly include **the Associated Press, BBC,
  Reuters, and The New York Times**, alongside camera makers (Leica, Nikon,
  Canon) and mobile chipmakers (Qualcomm) ([C2PA Wiki](https://c2pa.wiki/); [C2PA specifications](https://spec.c2pa.org/specifications/specifications/2.4/explainer/Explainer.html)).
- The output artifact is called a **"Content Credential"** — described as a
  "nutrition label" for media, recording who produced content, when, and what
  tools/edits were applied ([Content Authenticity Initiative — How it Works](https://contentauthenticity.org/how-it-works); [Content Credentials site](https://contentcredentials.org/)).

### 5.4 Verifying citizen journalism / UGC — published newsroom guidelines

- The **BBC's UGC ("User-Generated Content") Hub**, started in 2005 and grown
  to ~20 staff, is one of the earliest and most-documented newsroom
  verification units, having pioneered practices such as: never assuming
  uploaded content is what it claims to be; the "golden rule" of **phoning the
  original poster** to verify; taking care/duty-of-care when contacting
  sources in traumatic breaking-news situations; and publishing **explicit
  disclaimers on air/online whenever UGC could not be independently verified**
  ([IJNet — BBC's best practices for verifying UGC](https://ijnet.org/en/story/bbcs-best-practices-verifying-user-generated-content); [Nieman Reports — Inside the BBC's Verification Hub](https://niemanreports.org/inside-the-bbcs-verification-hub/); [Medium — BBC UGC Hub overview](https://medium.com/@kevonpaynter/bbc-s-ugc-hub-is-among-the-first-of-it-s-kind-and-it-focuses-on-social-media-verification-of-user-7d5843636d60)).
- The broader **"Verification Handbook"** project (DataJournalism.com /
  European Journalism Centre) codifies these practices industry-wide, with
  dedicated chapters on verifying UGC and presenting it responsibly in
  investigative reporting ([DataJournalism.com — Verifying User-Generated Content](https://datajournalism.com/read/handbook/verification-1/verifying-user-generated-content/3-verifying-user-generated-content); [DataJournalism.com — Presenting UGC in investigative reporting](https://datajournalism.com/read/handbook/verification-2/chapter-9-presenting-ugc-in-investigative-reporting)).
- **WITNESS Media Lab** (a human-rights-focused organization) also publishes
  verification resources specifically aimed at citizen/eyewitness video used
  in human-rights and conflict documentation ([WITNESS Media Lab — Verification Resources](https://lab.witness.org/portfolio_page/verification/)).

**Sources:** [IPTC Photo Metadata Standard](https://iptc.org/standards/photo-metadata/iptc-standard/) · [IPTC — Media Provenance](https://iptc.org/media-provenance/) · [Fastio — IPTC Metadata Management for Photographers](https://fast.io/resources/iptc-metadata-management-photographers/) · [LegalClarity — IPTC Photo Metadata Standard: Attribution and Rights](https://legalclarity.org/iptc-photo-metadata-standard-attribution-and-rights/) · [Numonic — IPTC 2025.1 and C2PA](https://www.numonic.ai/blog/iptc-2025-c2pa-ai-provenance-metadata) · [Wikipedia — Content Authenticity Initiative](https://en.wikipedia.org/wiki/Content_Authenticity_Initiative) · [Adobe Blog — Adobe co-founds C2PA](https://blog.adobe.com/en/publish/2021/02/22/adobe-continues-content-authenticity-commitment-founder-c2pa-standards-org) · [C2PA Wiki](https://c2pa.wiki/) · [C2PA Specification Explainer](https://spec.c2pa.org/specifications/specifications/2.4/explainer/Explainer.html) · [Content Authenticity Initiative — How it Works](https://contentauthenticity.org/how-it-works) · [Content Credentials](https://contentcredentials.org/) · [IJNet — BBC's best practices for verifying UGC](https://ijnet.org/en/story/bbcs-best-practices-verifying-user-generated-content) · [Nieman Reports — Inside the BBC's Verification Hub](https://niemanreports.org/inside-the-bbcs-verification-hub/) · [DataJournalism.com — Verifying UGC](https://datajournalism.com/read/handbook/verification-1/verifying-user-generated-content/3-verifying-user-generated-content) · [WITNESS Media Lab — Verification Resources](https://lab.witness.org/portfolio_page/verification/)

---

## 6. Law Enforcement/Forensics

### 6.1 NIST SP 800-86 — Guide to Integrating Forensic Techniques into Incident Response

- NIST SP 800-86 is written from an **IT/incident-response perspective, not a
  law-enforcement perspective**, but its process model is widely cited as a
  general digital forensics baseline: it defines forensics as the
  **"application of science to the identification, collection, examination,
  and analysis of data while preserving the integrity of the information and
  maintaining a strict chain of custody"** ([NIST SP 800-86 official page](https://csrc.nist.gov/pubs/sp/800/86/final); [NIST SP 800-86 full text PDF](https://nvlpubs.nist.gov/nistpubs/legacy/sp/nistspecialpublication800-86.pdf)).
- It structures forensic work around four **data source categories** — files,
  operating systems, network traffic, and applications — and gives concrete
  collection/examination/analysis techniques for each, plus explicit
  documentation and legal-consultation caveats (readers are told to apply its
  recommendations only after consulting legal counsel for their specific
  jurisdiction/regulatory context) ([NIST SP 800-86 official page](https://csrc.nist.gov/pubs/sp/800/86/final)).
- For Reality Lock, the directly transferable idea is: **evidence integrity
  and chain-of-custody discipline must be baked into the collection process
  itself**, not bolted on afterward — which is precisely why Reality Lock's
  hash-at-capture design (rather than hash-at-upload) is the architecturally
  correct choice.

### 6.2 ACPO Good Practice Guide — the four widely-cited principles

The UK Association of Chief Police Officers' "Good Practice Guide for Digital
Evidence" (2007, updated 2012) is the most commonly cited practical
chain-of-custody framework worldwide, built around **four principles**
([Forensic Control — ACPO guidelines explained](https://forensiccontrol.com/guides/acpo-guidelines-principles-explained/); [MSAB — What is ACPO?](https://www.msab.com/glossary/acpo-association-of-chief-of-police/)):

1. **Principle 1 (data integrity)** — *"No action taken... should change data
   which may subsequently be relied upon in court."* In mobile-capture terms:
   Reality Lock must never modify the original photo/video/sensor file after
   capture; any processing (compression, enhancement) must happen on a
   verified copy, with the original preserved and hash-anchored.
2. **Principle 2 (competence)** — if direct interaction with original data is
   unavoidable, the person doing it must be competent and able to explain
   their actions if challenged. For Reality Lock, this maps to: whoever
   operates/exports from the app in a legal context should be able to explain
   what the app did, which argues for transparent, well-documented internal
   logic rather than an unexplainable black box.
3. **Principle 3 (audit trail)** — *"An audit trail... should be created and
   preserved [so] an independent third party [can] examine those processes and
   achieve the same result."* This directly justifies a **built-in,
   automatically-generated custody/audit log** as a core Reality Lock feature,
   not an optional add-on.
4. **Principle 4 (responsibility)** — overall responsibility for compliance
   with these principles rests with a designated person in charge, not with
   individual technicians (or, by extension, not with the app vendor alone) —
   reinforcing that Reality Lock is a *tool* used within a chain of human
   responsibility, not an autonomous legal actor.

### 6.3 India's own emerging real-world precedent: e-Sakshya

- **e-Sakshya** is a real, currently-deployed Government of India (developed
  with NCRB) mobile app, launched nationally on **4 August 2024**, built
  specifically to let police **record and manage digital evidence with a
  secure chain of custody**, in direct support of the new criminal-law codes
  (Bharatiya Nagarik Suraksha Sanhita's mandatory audiovisual recording
  requirements) ([Budding Forensic Expert — What is e-Sakshya?](https://www.buddingforensicexpert.in/2026/05/what-is-e-sakshya.html); [Law Web — e-Sakshya comes to Maharashtra](https://www.lawweb.in/2025/12/esakshya-comes-to-maharashtra-how.html)).
- Its technical design is strikingly close to Reality Lock's own: it
  **generates a cryptographic hash value for every file at the point of
  capture** (so any later modification is immediately detectable), uses
  **immutable storage, timestamps, and geo-location together to form a strong
  chain of custody**, uses **QR codes to track custody/messenger handoffs**,
  and links each evidence item's "Sakshya ID" to the relevant FIR/GD number in
  **CCTNS** (India's national crime-records/FIR database) ([Budding Forensic Expert — e-Sakshya vs Crime Scene Videography](https://www.buddingforensicexpert.in/2026/05/e-sakshya-vs-crime-scene-videography.html); [Fortune IAS Circle — eSakshya](https://fortuneiascircle.com/finder/esakshya)).
- **This is directly useful evidence for the literature survey**: it shows the
  Indian state itself has already operationalized the same core technical
  idea (hash-at-capture + geo/time-stamp + immutable custody log) that Reality
  Lock proposes, but scoped specifically to police-collected evidence rather
  than citizen-captured evidence. Reality Lock's academic contribution can be
  framed as extending this same architectural pattern to *citizen-initiated*
  capture (insurance, journalism, personal safety) rather than only
  official/police capture.

**Sources:** [NIST SP 800-86 — official CSRC page](https://csrc.nist.gov/pubs/sp/800/86/final) · [NIST SP 800-86 — full text PDF](https://nvlpubs.nist.gov/nistpubs/legacy/sp/nistspecialpublication800-86.pdf) · [Forensic Control — ACPO Guidelines & Principles Explained](https://forensiccontrol.com/guides/acpo-guidelines-principles-explained/) · [MSAB — What is ACPO?](https://www.msab.com/glossary/acpo-association-of-chief-of-police/) · [Medium — The ACPO Principles](https://medium.com/@chukzy010/the-acpo-principles-10abab37ee3b) · [Budding Forensic Expert — What is e-Sakshya?](https://www.buddingforensicexpert.in/2026/05/what-is-e-sakshya.html) · [Law Web — e-Sakshya comes to Maharashtra](https://www.lawweb.in/2025/12/esakshya-comes-to-maharashtra-how.html) · [Budding Forensic Expert — e-Sakshya vs Crime Scene Videography](https://www.buddingforensicexpert.in/2026/05/e-sakshya-vs-crime-scene-videography.html) · [Fortune IAS Circle — eSakshya](https://fortuneiascircle.com/finder/esakshya) · [Tribune India — One year of new criminal laws](https://www.tribuneindia.com/news/bsb/one-year-of-new-criminal-laws-tarikh-pe-tarikh-era-over-chandigarh-logs-91-conviction-rate-under-bns)

---

## 7. Honest Limitations

This section exists to be stated plainly in the project's literature survey /
ethical-considerations section — a genuine intellectual-honesty checkpoint,
not a caveat to bury.

### 7.1 The core distinction: integrity vs. authenticity

A cryptographic hash plus digital signature can, with reasonable confidence,
establish:
- **Integrity**: this exact bitstream has not been altered since it was
  signed.
- **Provenance (device/key binding)**: this bitstream was signed by whichever
  private key produced the signature — and if that key is well-protected
  (e.g., hardware-backed secure enclave), by whichever device holds that key.
- **Non-repudiation, weakly**: assuming key custody is not compromised, the
  signer cannot easily deny having signed the record.

It **cannot**, by itself, establish:
- **Scene authenticity**: whether what the camera photographed was a genuine,
  unstaged, real-world event, or a screen, printout, model, deepfake, or
  otherwise fabricated scene held up in front of the camera. Independent
  security research on C2PA — the most mature real-world analogue to Reality
  Lock's approach — states this explicitly: *"C2PA was not designed to
  determine authenticity... it certifies the history of content, not its
  truth,"* and *"the absence of a C2PA credential says nothing definitive
  about authenticity"* either way ([TrueScreen — C2PA Standard: History, Promises and Structural Limitations](https://truescreen.io/articles/c2pa-standard-history-limitations/)). A recent independent security analysis goes
  further: *"the current C2PA specifications fail to achieve their claimed
  security goals... C2PA may mislead users, platforms, and policymakers if
  relied upon prematurely"* ([arXiv — Verifying Provenance of Digital Media: Why C2PA Falls Short](https://arxiv.org/html/2604.24890v1)).
- **Resistance to determined physical spoofing**: research on camera-based
  authentication solutions to deepfakes notes that "a determined adversary
  with a large curved monitor, careful distance calibration, and a motion
  simulator could potentially fool cryptographic authentication systems" —
  i.e., recording a very good fake with a "trusted" camera still produces a
  validly-signed but false record ([arXiv — Solutions to Deepfakes: Can Camera Hardware, Cryptography, and Deep Learning Verify Real Images?](https://arxiv.org/html/2407.04169v1)).
- **Practical stripping/removal attacks**: provenance metadata containers can
  be stripped by re-saving/re-encoding a file through a non-compliant tool,
  silently removing all credentials while leaving an otherwise-normal-looking
  file — meaning *absence* of a Reality Lock proof package proves nothing
  about the underlying content either ([TrueScreen — C2PA Structural Limitations](https://truescreen.io/articles/c2pa-standard-history-limitations/)).
- **Full legal proof, standalone**: as established in §1, Indian law requires
  a **human-signed statutory certificate** (Section 63 BSA, dual-certified by
  device custodian + expert) as a condition precedent to admissibility — a
  cryptographic artifact is evidentiary *material*, not a self-executing legal
  certificate. It still requires a recognized human certifier, and in many
  contexts, corroborating **chain-of-custody documentation** of everything
  that happened to the file after it left the device (who accessed it, how it
  was transferred, where it was stored) — none of which an on-device hash can
  attest to once the file leaves the device's custody chain.
- **Trust anchoring / key management is itself a hard, unsolved problem**: the
  entire scheme's guarantees collapse to the security of the signing key. If
  the device is rooted/compromised, if the key is extracted, or if there is no
  trusted, independently-audited Certificate Authority vouching for "this key
  belongs to this specific physical device," then the "tamper-evident"
  guarantee is only as strong as an unverified self-assertion. IPTC's own
  Verified News Publisher program makes exactly this point about its own
  scheme: certificates "verify publisher identity only — not content
  truthfulness" ([IPTC — Media Provenance](https://iptc.org/media-provenance/)).

### 7.2 A precise, citable statement for the literature survey

> *"A cryptographic hash and digital signature applied at capture time proves
> that a specific bitstream of data has not been altered since it was sealed,
> and that it was sealed using a specific cryptographic key (and, if
> hardware-backed, by a specific physical device). It does not, and cannot,
> prove that the real-world event depicted actually occurred as shown, that
> the scene was not staged or spoofed in front of the sensor, or that the
> content constitutes legally admissible proof on its own. Legal admissibility
> in India additionally requires a human-signed statutory certificate under
> Section 63 of the Bharatiya Sakshya Adhiniyam, 2023 (successor to Section
> 65B of the Indian Evidence Act, 1872), issued by a person in control of the
> device and, since 2023, countersigned by an independent technical expert.
> Reality Lock is therefore best understood as a technical evidence-generation
> and integrity-assurance layer that produces high-quality inputs for a human
> certification and chain-of-custody process — not a replacement for either."*

**Sources:** [TrueScreen — C2PA Standard: History, Promises and Structural Limitations](https://truescreen.io/articles/c2pa-standard-history-limitations/) · [arXiv — Verifying Provenance of Digital Media: Why the C2PA Specifications Fall Short](https://arxiv.org/html/2604.24890v1) · [arXiv — Solutions to Deepfakes: Can Camera Hardware, Cryptography, and Deep Learning Verify Real Images?](https://arxiv.org/html/2407.04169v1) · [TrueScreen — Why Deepfake Detection Fails and Data Authenticity Works](https://truescreen.io/articles/deepfake-detection-limits-data-authenticity/) · [C2PA FAQ](https://c2pa.org/faqs/) · [IPTC — Media Provenance](https://iptc.org/media-provenance/) · [SoftwareSeni — How C2PA Content Credentials Work and What Their Limits Are](https://www.softwareseni.com/how-c2pa-content-credentials-work-and-what-their-limits-are/)

---

## 8. Design Implications

Concrete, buildable features the app should have, each traced back to the
research above:

| # | Feature | Grounded in |
|---|---|---|
| 1 | **Hash-and-sign at the moment of capture**, before the file ever touches storage/network, using a hardware-backed key (Android Keystore / StrongBox / secure enclave equivalent) | ISO/IEC 27037 minimizing-alteration principle; ACPO Principle 1; e-Sakshya's own architecture |
| 2 | **On-device custody log**: an automatically generated, append-only log recording every subsequent access, export, or transfer of a proof package (who/what app, when, why) | ISO/IEC 27037 "documentary custody"; ACPO Principle 3 (audit trail); NIST SP 800-86 documentation emphasis |
| 3 | **A Section 63(BSA)/Section 65B-style certificate export template** — auto-populated with hash value + algorithm, device particulars, and production method — explicitly labeled as a *draft/technical annexure* requiring a human signatory (device custodian + independent expert), not a self-standing legal certificate | §1 (BSA Section 63 Schedule hash-value requirement; dual-certification requirement) |
| 4 | **Explicit, un-bundled, itemized consent screens** for camera, precise location, and motion-sensor access, shown before first use of each, with a plain-language notice and an easy withdraw-consent path | DPDP Act 2023 consent/notice requirements; DPDP Rules 2025 |
| 5 | **Bystander notice affordance**: a visible on-screen/recording indicator when actively capturing, plus an optional post-capture face-blur/redaction tool for people who are incidental to the proven event | DPDP Act's broad "personal data" definition; GDPR biometric special-category comparison |
| 6 | **Event-scoped data minimisation**: GPS/motion logging limited to a bounded window around the capture event, not continuous background tracking | DPDP Act data-minimisation principle |
| 7 | **User-facing retention/deletion controls**, with a clear default retention period and a "legal hold" override state for packages attached to an active claim/case/investigation | DPDP Act storage-limitation principle; insurance/legal record-retention practice |
| 8 | **Anti-restaging / liveness signals at capture** (e.g., detecting screen-recapture, checking sensor consistency with a live 3D scene) as an optional enhanced-assurance mode | VAARHAFT SafeCam's screen/photo-of-photo detection; C2PA's documented spoofing weaknesses (§7) |
| 9 | **A structured metadata export compatible with existing standards** (IPTC-style Creator/DateCreated/location/rights fields, and/or a C2PA-style manifest) rather than a proprietary-only format, to ease adoption by insurers/newsrooms already using these standards | IPTC Photo Metadata Standard; C2PA/Content Credentials ecosystem |
| 10 | **An explicit "what this proves / what this does not prove" disclosure surfaced to end users and to any verifier of an exported proof package** — stating plainly that the package proves data integrity and device/key binding, not real-world event authenticity, staging-free capture, or standalone legal admissibility | §7 Honest Limitations |
| 11 | **A verifier tool independent of the capture app** that can check a proof package's hash/signature without needing to trust Reality Lock's own servers — following ISO/IEC 27037's requirement that an independent third party be able to examine the process and reach the same result | ISO/IEC 27037; ACPO Principle 3 |
| 12 | **Interoperable, jurisdiction-aware design** (e.g., an EU/GDPR consent mode that adds an explicit "special category" gate for biometric-adjacent face data, distinct from the default India/DPDP consent mode) to support the project's stated long-term multi-domain/multi-region ambitions | §3.2 GDPR vs DPDP comparison |

---

*Compiled 23 July 2026 for the Reality Lock Zeroth Review literature survey.
All URLs were live and accessible at the time of research. This document
should be re-verified against primary legal sources (bare acts, official
gazette notifications, and court judgments) before any claim in it is used in
a formal academic report, and must never be treated as legal advice.*
