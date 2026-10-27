# bounded-transcription-and-insertion

## Purpose
Avoid wedged backends and stale Android insertion without claiming real-device latency.

## Authority and evidence
This is a current implemented/accepted baseline, not a proposal or full live
certification. It governs this capability as it evolves; linked contracts retain
exact supporting detail. Planned work and coverage limits remain outside the
baseline in [native readiness](../../../docs/native-readiness.md).

Evidence (paths relative to repository root): `src/transcriber.mjs`, `test/transcriber.test.mjs`, `android/src/ai/hypermemetic/voicevault/PendingDictation.java`, `android/src/ai/hypermemetic/voicevault/FloatingStatus.java`, `test/android-state.test.mjs`.

## Requirements

### Requirement: Bounded cascade
Transcription SHALL try warm daemon then configured direct dGPU, iGPU and CPU tiers, reject malformed/non-200/ok:false daemon replies and bound daemon waits to at most 15 seconds.

#### Scenario: Daemon rejects
- **WHEN** a stub daemon returns an error or fails to answer within its bound
- **THEN** transcription falls through to the next configured tier rather than hanging

### Requirement: Explicit terminal failure
All-backend failure SHALL return failure/attempt evidence rather than success with an invented empty transcript; hung direct processes SHALL have a timeout.

#### Scenario: Every tier fails
- **WHEN** all stub backends reject the request
- **THEN** the caller receives terminal failure with attempt history

### Requirement: Fresh insertion ownership
Android pending insertion SHALL consume only a fresh recording once, and SHALL clear on failure/cancel or target app/window change so late output cannot satisfy a newer request.

#### Scenario: Context changes before completion
- **WHEN** a transcription finishes after its target context was replaced
- **THEN** the stale result is not inserted into the new context
