# local-speaker-boundary

## Purpose
Maintain local speaker/profile processing and honest optional-gate fallback.

## Authority and evidence
This is a current implemented/accepted baseline, not a proposal or full live
certification. It governs this capability as it evolves; linked contracts retain
exact supporting detail. Planned work and coverage limits remain outside the
baseline in [native readiness](../../../docs/native-readiness.md).

Evidence (paths relative to repository root): `README.md`, `src/gate.mjs`, `src/profile.mjs`, `src/speaker.mjs`, `src/transcriber.mjs`, `test/gate.test.mjs`, `test/speaker.test.mjs` (read, not model-executed here).

## Requirements

### Requirement: Local enrollment
Enrollment SHALL store normalized compatible gallery vectors/calibration in local SQLite rather than retaining reference enrollment audio.

#### Scenario: Compatible enrollment
- **WHEN** synthetic reference clips are enrolled through the established profile boundary
- **THEN** vectors/calibration are retained while reference audio is not persisted as enrollment evidence

### Requirement: Accepted segments only
When an enrolled compatible gate is active, only accepted speech segments SHALL reach transcription; all-rejected audio SHALL return an empty rejected outcome without calling Whisper.

#### Scenario: All segments rejected
- **WHEN** a gated clip has no accepted target-speaker segment
- **THEN** no rejected words are transcribed

### Requirement: Honest fallback
Absent/incompatible profiles or unavailable gate dependencies SHALL follow the documented ungated fallback and report gate status rather than pretending speaker rejection occurred.

#### Scenario: Incompatible profile
- **WHEN** the selected embedder cannot compare with the retained profile model
- **THEN** transcription reports the fallback instead of claiming compatible gate enforcement
