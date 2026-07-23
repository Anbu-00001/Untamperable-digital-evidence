# Academic Literature Survey — Reality Lock

*Prepared for: Zeroth Review, Mobile Application Development PBL, Dept. of CSE*
*Scope note: every paper below was located via live search across Google Scholar, arXiv, IEEE Xplore, ACM Digital Library, Semantic Scholar, and Crossref between July 2026 search sessions, and its title/author list/venue was independently confirmed against at least one primary bibliographic source (arXiv API, Crossref API, publisher page, or Semantic Scholar) before being included here. Nothing below is invented; where a search area came up thin, that is stated explicitly rather than padded out.*

---

## 1. Trusted Camera / Trusted Sensing on Mobile Devices

Hardware- and software-level mechanisms that let a mobile device prove a photo, video, or sensor reading genuinely came from its own sensor at the claimed instant — the foundational problem Reality Lock's capture module has to solve.

1. **"Trusted Cameras on Mobile Devices Based on SRAM Physically Unclonable Functions"**
   Rosario Arjona, Miguel A. Prada-Delgado, Javier Arcenegui, Iluminada Baturone. *Sensors*, 18(10):3352, 2018.
   Proposes binding a camera's identity to a Physically Unclonable Function (PUF) derived from the SRAM already present in a device's Bluetooth Low Energy chip, so no extra hardware is needed. The PUF is used both to generate a unique device fingerprint and to protect a challenge–response protocol that authenticates firmware updates and control commands sent to the camera, giving the camera itself (not just its output file) a cryptographic identity.
   DOI: https://doi.org/10.3390/s18103352

2. **"Trusted Operations on Sensor Data"**
   Hassaan Janjua, Wouter Joosen, Sam Michiels, Danny Hughes. *Sensors*, 18(5):1364, 2018.
   Presents a framework that captures data from both internal and external sensors of a mobile phone and lets arbitrary processing operations run on that data while a Trusted Execution Environment (TEE) — rather than dedicated external hardware — continuously attests that the chain of trust from raw sensor reading to final output has not been broken. Directly relevant to Reality Lock's need to keep the "capture → hash → sign" pipeline verifiable end-to-end on commodity Android hardware.
   DOI: https://doi.org/10.3390/s18051364

3. **"In-sensor cryptographic signature generation to link a physical process and an immutable digital entity"**
   Fernando Cardes, Sebastian Bürgel, Xinyue Yuan, Qianchen Yu, Arianna Rubino, Jihyun Lee, Raziyeh Bounik, Vijay Viswam, Andreas Hierlemann, Felix Franke. *Nature Electronics*, published online 24 March 2026.
   ETH Zurich team's prototype sensor chip that digitally signs the raw data it produces (image, video, or audio) at the moment of capture, inside the chip itself, so tampering after the fact requires physically attacking the sensor. The authors explicitly propose anchoring these signatures in a public, immutable ledger (e.g., a blockchain) so that anyone downstream can verify a piece of media traces back to a real physical capture event. Discussed further as a primary related-work anchor in Section 9.
   DOI: https://doi.org/10.1038/s41928-026-01593-5

*Coverage note: this was one of the stronger topic areas — hardware-rooted trusted-sensing research is active and directly transferable to Reality Lock's design, even though none of the three papers targets Android app-layer capture specifically (Reality Lock, lacking custom silicon, has to approximate this with OS-level attestation/TEE instead of a bespoke sensor chip).*

---

## 2. Tamper-Evident Mobile Evidence / Verifiable Provenance of Photo & Video

Work specifically about proving media was *not* altered after capture, as opposed to merely detecting what kind of manipulation occurred.

1. **"Tamper-evident Image using JPEG Fixed Points"**
   Zhaofeng Si, Siwei Lyu. arXiv:2504.17594 (2025); also in *Proceedings of the 1st ACM Workshop on Deepfake, Deception, and Disinformation Security*, 2025.
   Observes that repeated JPEG compression/decompression cycles converge to a stable "fixed point" image that survives further re-compression unchanged as long as the same quantization table is used. Any tampering knocks the image off this fixed point, making the deviation detectable by simply re-running the JPEG transform — an elegant, metadata-free tamper-evidence signal that needs no extra cryptography or storage.
   URL: https://arxiv.org/abs/2504.17594

2. **"Trusted Tamper-Evident Data Provenance"**
   Mohammad M. Bany Taha, Sivadon Chaisiri, Ryan K. L. Ko. *2015 IEEE Trustcom/BigDataSE/ISPA*, pp. 646–653.
   Uses the Trusted Platform Module (TPM) already present in many computing devices to seal provenance logs so that any tampering is both detectable and — because logs are also mirrored to backup servers — recoverable. The paper frames the core legal problem plainly: data provenance is not currently admissible in court because its integrity cannot be guaranteed, which is precisely the gap Reality Lock's proof package is meant to close for citizen-captured media.
   DOI: https://doi.org/10.1109/TRUSTCOM.2015.430

3. **"The Birthmark Standard: Privacy-Preserving Photo Authentication via Hardware Roots of Trust and Consortium Blockchain"**
   Sam Ryan. arXiv:2602.04933 (2026) — independent technical specification and security analysis, not yet peer-reviewed.
   Proposes authenticating photos using the camera sensor's own manufacturing-unique noise pattern (photo-response non-uniformity / NUC maps) to derive a cryptographic key, rather than relying on embedded metadata that gets stripped by re-compression. Certificates are anonymized (proving "a genuine sensor took this" without identifying which device) and authentication records are posted to a consortium blockchain run by journalism organizations. Validated with a Raspberry Pi 4 prototype. Discussed further in Section 9 as a second anchor candidate.
   URL: https://arxiv.org/abs/2602.04933

4. **"Proof of Authenticity of General IoT Information with Tamper-Evident Sensors and Blockchain"**
   Kenji Saito. Accepted for the *2025 IEEE Region 10 Humanitarian Technology Conference (R10-HTC)*; arXiv:2512.18560.
   Sensors periodically sign their own readouts and link successive readings with redundant hash chains (each entry references both its immediate predecessor and an earlier "a-past" entry), then submit compact cryptographic evidence — not the raw data itself — to a blockchain via Merkle trees. This "a-past linkage" survives data loss without needing every reading anchored on-chain, aimed at humanitarian/disaster-response deployments where connectivity is intermittent, a constraint Reality Lock will also face in the field.
   URL: https://arxiv.org/abs/2512.18560

---

## 3. Digital Forensics & Chain of Custody for Mobile/Smartphone Evidence

Whether and how smartphone-captured evidence can be considered "forensically sound" and admissible.

1. **"When is Digital Evidence Forensically Sound?"**
   Rodney McKemmish. In *Advances in Digital Forensics IV* (IFIP International Federation for Information Processing), Springer, 2008.
   One of the foundational papers defining "forensic soundness" for digital evidence, articulating principles such as minimal handling of the original and accounting for any change made during acquisition — the baseline vocabulary that later mobile-specific soundness papers (below) build on.
   DOI: https://doi.org/10.1007/978-0-387-84927-0_1

2. **"What does 'forensically sound' really mean?"**
   Eoghan Casey. *Digital Investigation*, 4(2):49–50, 2007.
   A short but heavily cited critique arguing "forensically sound" had become a marketing buzzword used loosely by vendors, and proposing it be tied to a concrete, falsifiable standard of practice instead — a caution Reality Lock's own "tamper-evident" claims should be held to.
   DOI: https://doi.org/10.1016/j.diin.2007.05.001

3. **"A Forensically Sound Adversary Model for Mobile Devices"**
   Quang Do, Ben Martini, Kim-Kwang Raymond Choo. *PLOS ONE*, 10(9):e0138449, 2015.
   Builds an explicit adversary/threat model for smartphone forensic acquisition (what an attacker or investigator can and cannot do to evidence on the device without invalidating it), extending the companion methodology paper (Martini, Do & Choo, arXiv:1506.05527) that proposes a device-agnostic evidence collection and analysis process for Android specifically.
   DOI: https://doi.org/10.1371/journal.pone.0138449

4. **"Digital Evidence Chain of Custody: Navigating New Realities of Digital Forensics"**
   Sohom Nath, Kyle Summers, Jusop Baek, Gail-Joon Ahn. *Proceedings — 2024 IEEE 6th International Conference on Trust, Privacy and Security in Intelligent Systems, and Applications (TPS-ISA)*, pp. 11–20.
   A 2024 systematization-of-knowledge (SoK) that classifies contemporary chain-of-custody practice into Traditional Paper Trail, System-oriented, and Infrastructure-driven categories, and catalogues the challenges ubiquitous mobile-device usage creates for maintaining an unbroken, legally defensible custody record — a useful map of where a system like Reality Lock's verification module would sit.
   DOI: https://doi.org/10.1109/TPS-ISA62245.2024.00012

---

## 4. Blockchain for Digital Evidence / Chain of Custody

Five distinct proposals, spanning 2018–2024, for anchoring evidence integrity on a distributed ledger — useful because they show genuinely different architectural choices Reality Lock will have to pick between for its "future blockchain extension."

1. **"B-CoC: A Blockchain-based Chain of Custody for Evidences Management in Digital Forensics"**
   Silvia Bonomi, Marco Casini, Claudio Ciccotelli. *Proceedings of the 1st International Conference on Blockchain Economics, Security and Protocols (Tokenomics 2019)*; preprint arXiv:1807.10359 (2018).
   Builds the chain of custody on a private Ethereum network so that every transfer of evidence — from initial seizure onward — is logged immutably and only accessible to authorized parties, with a working prototype evaluated end-to-end. One of the earliest concrete blockchain-CoC implementations.
   URL: https://arxiv.org/abs/1807.10359

2. **"Forensic-chain: Blockchain based digital forensics chain of custody with PoC in Hyperledger Composer"**
   Auqib Hamid Lone, Roohie Naaz Mir. *Digital Investigation*, 28:44–55, 2019.
   Chooses a permissioned Hyperledger Fabric/Composer ledger instead of public Ethereum, reflecting a design trade-off (private consortium control vs. Bonomi et al.'s public-chain approach) that recurs throughout this literature. Cryptographic hashes of forensic artifacts are recorded on-chain to create tamper-evident custody records, demonstrated with a proof-of-concept.
   DOI: https://doi.org/10.1016/j.diin.2019.01.002

3. **"Blockchain based chain of custody"** *(subtitle: towards real time tamper-proof evidence management)*
   Liza Ahmad, Salam Khanji, Farkhund Iqbal, Faouzi Kamoun. *Proceedings of the 15th International Conference on Availability, Reliability and Security (ARES '20)*, article 79, pp. 1–8.
   Distinguishes itself by targeting *real-time* evidence logging (rather than post-hoc batch recording) so that custody transfer events are written to the ledger as they happen — closer in spirit to Reality Lock's "capture-time" anchoring requirement than the earlier batch-oriented proposals.
   DOI: https://doi.org/10.1145/3407023.3409199

4. **"Blockchain-based digital chain of custody multimedia evidence preservation framework for internet-of-things"**
   Sakshi, Aruna Malik, Ajay K. Sharma. *Journal of Information Security and Applications*, 77:103579, 2023.
   The most topically relevant blockchain-CoC paper found for Reality Lock specifically because it targets *multimedia* evidence (not generic case files) generated by IoT/mobile-class sensing devices, addressing preservation of photo/video evidence chain of custody under resource constraints typical of edge devices.
   DOI: https://doi.org/10.1016/j.jisa.2023.103579

5. **"Potential applicability of blockchain technology in the maintenance of chain of custody in forensic casework"**
   Harsh Patil, R. K. Kohli, Sorabh Puri, Pooja Puri. *Egyptian Journal of Forensic Sciences*, 14:8, 2024.
   A review-style paper (rather than a new system) that surveys the field's issues — evidence loss, theft, tampering, manipulation inside custody systems — and argues blockchain's append-only, hash-linked structure is well suited to solving them, while flagging that a "future research agenda" is still needed since the area remains emergent. Useful as a synthesis reference alongside the four concrete implementations above.
   DOI: https://doi.org/10.1186/s41935-023-00383-w

---

## 5. Deepfake Detection — Comprehensive Surveys

Background/context citations for the PPT's existing "AI Deepfake Detection" bullet — three recent, broad surveys rather than narrow point-solution papers.

1. **"Deepfake Detection: A Comprehensive Survey from the Reliability Perspective"**
   Tianyi Wang, Xin Liao, Kam Pui Chow, Xiaodong Lin, Yinglong Wang. *ACM Computing Surveys*, 57(3), article 74, 2024 (also arXiv:2211.10881).
   Reframes the survey question away from raw accuracy leaderboards and toward *reliability*: transferability across datasets, interpretability of a detector's decision, and robustness to real-world conditions — the three properties the authors argue actually determine whether a detector is useful in a legal/forensic setting, directly relevant to how Reality Lock might eventually integrate deepfake screening as a secondary signal.
   DOI: https://doi.org/10.1145/3699710

2. **"A Survey on Speech Deepfake Detection"**
   Menglu Li, Yasaman Ahmadiadli, Xiao-Ping Zhang. *ACM Computing Surveys*, 57(7), article 172, 2025 (also arXiv:2404.13914).
   Systematically analyzes over 200 papers on audio deepfake detection through March 2024, covering model architectures, datasets, evaluation metrics, and open-source tooling — included because Reality Lock's "event capture" scope explicitly includes audio/video, not just still images, and audio deepfakes are a distinct threat surface from image/video ones.
   DOI: https://doi.org/10.1145/3714458

3. **"A Comprehensive Review of Deepfake Detection Techniques: From Traditional Machine Learning to Advanced Deep Learning Architectures"**
   Ahmad Raza, Abdul Basit, Asjad Amin, Zeeshan Ahmad Arfeen, Muhammad I. Masud, Umar Fayyaz, Touqeer Ahmed Jumani. *AI* (MDPI), 7(2):68, 2026.
   A 2026 systematic review spanning 2018–2025 that benchmarks classical ML, CNN, and transformer-based detectors on FaceForensics++, DFDC, and Celeb-DF; its headline finding — that detectors of every methodological class lose 10–15% accuracy on out-of-distribution data because they learn dataset-specific compression artifacts rather than generalizable deepfake signatures — is a directly useful caution against relying on deepfake-detection alone and a strong argument *for* Reality Lock's capture-time, provenance-first approach as a complement.
   DOI: https://doi.org/10.3390/ai7020068

---

## 6. Content Authenticity / Content Provenance (C2PA)

Peer-reviewed and independent technical analyses of the C2PA (Coalition for Content Provenance and Authenticity) standard that Reality Lock's proof-package format should be positioned against.

1. **"Verifying Provenance of Digital Media: Security Analysis of C2PA and its Implementation"**
   Enis Golaszewski, Neal Krawetz, Alan T. Sherman, Edward Zieglar, Sai K. Matukumalli, Roberto Yus, Carson L. Kegley, Michael Barthel, William Bowman, Bharg Barot, Kaur Kullman. Cryptology ePrint Archive, Report 2026/804 (full technical version); companion policy brief "...Why the C2PA Specifications Fall Short," arXiv:2604.24890, April 2026.
   The first independent, comprehensive security analysis of C2PA, including the first formal-methods analysis of its core protocols (authored by a team including researchers affiliated with UMBC, Hacker Factor, and the U.S. National Security Agency). It finds that C2PA's trusted-timestamp mechanism allows timestamps to be silently replaced, and that validators can be tricked into accepting manifests signed with already-revoked/compromised certificates — concrete, citable evidence that the dominant industry content-provenance standard is not yet trustworthy enough for legal-evidence-grade use, which is exactly the gap Reality Lock argues it fills.
   URL: https://eprint.iacr.org/2026/804

2. **"Interoperable Provenance Authentication of Broadcast Media using Open Standards-based Metadata, Watermarking and Cryptography"**
   John C. Simmons, Joseph M. Winograd. arXiv:2405.12336, 2024.
   Analyzes what happens to C2PA's cryptographic metadata once broadcast news content is re-posted to social media (where re-encoding strips it), and proposes combining C2PA's metadata approach with ATSC audio/video watermarking so provenance survives re-transcoding — relevant to Reality Lock's own concern about metadata surviving compression/sharing.
   URL: https://arxiv.org/abs/2405.12336

3. **"On the Difficulty of Constructing a Robust and Publicly-Detectable Watermark"**
   Jaiden Fairoze, Guillermo Ortiz-Jimenez, Mel Vecerik, Somesh Jha, Sven Gowal. arXiv:2502.04901, 2025.
   Not about C2PA directly, but a formal argument (from a team including Google DeepMind researchers) that publicly-detectable, robust watermarks are fundamentally hard to construct without trading off security — a theoretical caution relevant to any content-provenance scheme (C2PA included) that leans on watermarking rather than capture-time signing the way Reality Lock does.
   URL: https://arxiv.org/abs/2502.04901

*Coverage note: this area is very active but young — most rigorous, peer-review-track security analysis of C2PA specifically dates to 2026 and is still moving through preprint servers rather than final conference/journal publication, which is stated here rather than glossed over.*

---

## 7. GPS Spoofing Detection on Smartphones/Mobile Devices

1. **"A Machine Learning Based Smartphone App for GPS Spoofing Detection"**
   Javier Campos, Kristen Johnson, Jonathan Neeley, Staci Roesch, Farha Jahan, Quamar Niyaz, Khair Al Shamaileh. *Security and Privacy in Communication Networks (SecureComm 2020)*, LNICST vol. 336, Springer, pp. 235–241.
   Launches real spoofing attacks against a GPS receiver using a low-cost SDR device (LimeSDR), then trains ML classifiers (Random Forest and SVM hit 99.5% accuracy, KNN 99.4%) on signal-to-noise ratio and satellite-tracking-count features. The authors go a step further than most papers in this space and ship an actual Android app that notifies the user in real time when spoofing is detected — the closest thing found to an off-the-shelf component Reality Lock could adapt for GPS-integrity checking.
   DOI: https://doi.org/10.1007/978-3-030-63095-9_13

2. **"GNSS/GPS Spoofing and Jamming Identification Using Machine Learning and Deep Learning"**
   Ali Ghanbarzade, Hossein Soleimani. arXiv:2501.02352, 2025.
   A broader survey of ML/DL-based GNSS spoofing and jamming detection approaches, framing the two threats (spoofing vs. jamming) side by side and comparing feature sets and model families across the recent literature — useful as a wider-lens complement to the smartphone-specific paper above.
   URL: https://arxiv.org/abs/2501.02352

3. **"Survey of Detection Techniques for GPS Spoofing in Connected Vehicles: Taxonomy, Evaluation, and Future Research Directions"**
   Ying He. *Informatica*, 49(27), 2025.
   Vehicular rather than handheld-mobile in focus, but its taxonomy of spoofing-detection strategies (signal-level, data-level, cross-sensor consistency checks) generalizes directly to a phone-based capture app that, like a connected vehicle, has multiple onboard sensors it can cross-check GPS against.
   DOI: https://doi.org/10.31449/inf.v49i27.9504

*Coverage note: dedicated, peer-reviewed research on GPS spoofing detection specifically for handheld smartphone apps (as opposed to GNSS receivers, connected vehicles, or UAVs) is thin — the Campos et al. paper above is the one strong hit; the other two are included as the best available adjacent literature.*

---

## 8. Sensor Fusion for Authentication / Multimodal Evidence Integrity

Combining multiple on-device sensor streams to establish trust — directly relevant to Reality Lock's fusion of photo/video + GPS + motion sensors into one proof package.

1. **"Multi-motion sensor behavior based continuous authentication on smartphones using gated two-tower transformer fusion networks"**
   Chengmei Zhao, Feng Gao, Zhihao Shen. *Computers & Security*, 139:103698, 2024.
   Fuses multiple motion-sensor streams (accelerometer, gyroscope, etc.) through a gated two-tower transformer architecture to continuously authenticate the phone's user based on behavior rather than a single check at unlock time — the specific fusion architecture is a candidate reference for how Reality Lock could combine motion-sensor streams into a single "this device was actually being carried/used naturally" integrity signal.
   DOI: https://doi.org/10.1016/j.cose.2023.103698

2. **"Performance Analysis of Motion-Sensor Behavior for User Authentication on Smartphones"**
   Chao Shen, Tianwen Yu, Sheng Yuan, Yunpeng Li, Xiaohong Guan. *Sensors*, 16(3):345, 2016.
   An earlier, foundational study establishing that accelerometer/gyroscope-derived motion behavior carries enough signal to authenticate a smartphone user, systematically comparing feature sets and classifiers — cited here as the baseline the 2024 transformer paper above builds on.
   DOI: https://doi.org/10.3390/s16030345

3. **"Context-Aware Decision Fusion for Multimodal Access Control Under Contradictory Biometric Evidence"**
   Yasser Hmimou, Azedine Khiat, Hassna Bensag, Zineb Hidila, Mohamed Tabaa. *Computers* (MDPI), 15(4):208, 2026.
   Addresses what happens when different modalities in a multimodal system *disagree* — proposing a context-aware decision-fusion layer to resolve contradictory evidence rather than assuming modalities always agree. Directly relevant to Reality Lock's fusion problem: what should the proof package do if, say, GPS says "stationary" while the accelerometer says "in motion"?
   DOI: https://doi.org/10.3390/computers15040208

---

## 9. Closest Related-Work Anchor(s) for the "Reality Lock" Concept as a Whole

No single published paper combines *all* of Reality Lock's elements — real-time capture, GPS, motion-sensor fusion, cryptographic hash-and-sign, and a tamper-evident proof package for a general "real-world event" (as opposed to a narrower IoT-log or single-photo use case). Three papers, however, each cover a large overlapping slice of the idea and together make a strong composite anchor for the related-work section:

1. **Cardes et al., "In-sensor cryptographic signature generation to link a physical process and an immutable digital entity,"** *Nature Electronics*, 2026 (full citation in Section 1). This is the closest single match in *mechanism*: cryptographic signing at the moment of physical capture, with the explicit design goal of linking a real-world physical event to an immutable digital record, and an explicit proposal to anchor those signatures on a blockchain-style public ledger — essentially Reality Lock's core pipeline, minus the multi-sensor (GPS + motion) fusion layer.

2. **Ryan, "The Birthmark Standard: Privacy-Preserving Photo Authentication via Hardware Roots of Trust and Consortium Blockchain,"** arXiv:2602.04933 (full citation in Section 2). Closest match in *systems architecture*: hardware-rooted authentication plus a consortium blockchain plus formally-verified privacy properties, though scoped to still photos only (no GPS/motion fusion, no video) and not yet peer-reviewed.

3. **Saito, "Proof of Authenticity of General IoT Information with Tamper-Evident Sensors and Blockchain,"** IEEE R10-HTC 2025 (full citation in Section 2). Closest match in *deployment conditions*: designed for exactly the intermittent-connectivity, resource-constrained field conditions a mobile "capture the moment it happens" app will encounter, with a concrete chain-and-anchor mechanism for surviving data loss — but framed around generic IoT sensor logs, not phone-camera-based event capture.

Worth noting for completeness, though it is a deployed open-source *system* rather than a peer-reviewed paper and is therefore not counted among the citation list above: **ProofMode** (Guardian Project / WITNESS, ongoing since ~2017) is the closest real-world prior-art *product* to Reality Lock — an Android/iOS app that signs photos/videos at capture time with an OpenPGP key and bundles a SHA-256 hash with GPS, network, and device-sensor metadata to produce court-admissible "self-authenticating evidence" under US Federal Rule of Evidence 902. It has no accompanying peer-reviewed paper describing its design, which is itself notable: it suggests Reality Lock, if it undertakes rigorous evaluation and publishes results, could occupy an academic gap that a 9-year-old real-world deployment has not filled.

---

## Synthesized Research Gap

Across all nine search areas, a clear pattern emerges: the building blocks Reality Lock needs already exist as separate, mature-ish research threads, but nobody has assembled them into one system aimed at general-purpose, real-time, multi-sensor event proof. Hardware- and TEE-rooted trusted sensing (Section 1) shows how to make a *single* sensor's output provably authentic at capture time, and the 2026 Nature Electronics work even extends this to blockchain anchoring — but it stops at one modality (typically image/video) and does not address fusing GPS or motion data into the same proof. Tamper-evidence and forensic-soundness research (Sections 2–3) supplies the vocabulary and legal bar Reality Lock's proof package must clear to be useful as evidence, and a genuinely large, fast-growing body of blockchain chain-of-custody work (Section 4) shows at least five distinct, workable ledger architectures for anchoring evidence integrity — yet essentially all of that work assumes evidence *already collected by conventional means* is being logged after the fact, rather than being fused and signed at the exact moment of capture by the sensing device itself. Deepfake-detection surveys (Section 5) and C2PA security analyses (Section 6) both converge on the same sobering conclusion from opposite directions: post-hoc detection of fakes is losing an arms race against generative AI, and the industry's leading *prevention*-side standard (C2PA) has now had its core security claims formally shown to fail under independent analysis (Golaszewski et al., 2026) — which strengthens rather than weakens the case for a capture-time, hardware/OS-attested alternative. Finally, GPS-spoofing detection (Section 7) and sensor-fusion authentication (Section 8) are each active subfields in their own right, but neither literature is written with the goal of *combining* location integrity and motion-sensor behavior into a single, jointly-signed proof of a real-world event; they solve authentication and spoofing-detection problems, not evidentiary-fusion problems.

The gap Reality Lock is positioned to fill, in short, is the *integration* gap, not a missing primitive: capture-time cryptographic signing (Section 1/9), tamper-evident packaging (Section 2), a legally-informed notion of forensic soundness (Section 3), a ledger-anchoring strategy chosen from the now-mature menu of blockchain CoC designs (Section 4), an honest acknowledgment that AI-based post-hoc verification (Section 5) and today's metadata-based provenance standard (Section 6) are both insufficient on their own, resilience to environmental attacks like GPS spoofing (Section 7), and multi-sensor fusion that can flag internal contradictions rather than silently trusting every stream (Section 8) — assembled into one mobile-first pipeline for a *general real-world event*, not a single photo (Ryan, 2026), a single IoT sensor log (Saito, 2025), or a single camera chip (Cardes et al., 2026). No paper found in this survey attempts that specific synthesis at the application layer on commodity Android hardware, which is exactly the space the next PBL review's system design should stake out.

---

### Bibliographic Summary

28 real, independently verified papers/preprints given full citation entries across 9 topic areas, plus one additional closely related companion paper (Martini, Do & Choo, arXiv:1506.05527) referenced inline in Section 3 — 29 distinct real citations in total. Several papers are cross-referenced under more than one topic where relevant (e.g., Cardes et al. under Sections 1 and 9; Ryan and Saito under Sections 2 and 9). All DOIs/arXiv IDs were checked against Crossref, the arXiv API, or publisher pages directly; two items (Ryan 2026 and the Golaszewski et al. 2026 companion arXiv brief) are explicitly flagged as not-yet-peer-reviewed preprints rather than presented as finalized publications.
