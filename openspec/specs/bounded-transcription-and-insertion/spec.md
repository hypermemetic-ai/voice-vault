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
Transcription SHALL try warm daemon then configured direct dGPU, iGPU and CPU tiers, reject malformed/non-200/ok:false daemon replies and bound daemon waits to at most 15 seconds. The cascade SHALL run per piece of a recording, so a piece that falls back does not repeat pieces that already succeeded. Every tier SHALL load the same configured model.

#### Scenario: Daemon rejects
- **WHEN** a stub daemon returns an error or fails to answer within its bound
- **THEN** transcription falls through to the next configured tier rather than hanging

#### Scenario: One piece falls back
- **WHEN** the stub daemon fails only for the second of three pieces
- **THEN** only that piece is sent to the next tier, and the transcript contains all three pieces in order

#### Scenario: Direct tiers use the configured model
- **WHEN** the configured model is `bark-3.0-clean-Q8_0`
- **THEN** a direct `handy` tier is invoked with `--model bark-3.0-clean-Q8_0`, not `turbo`

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

### Requirement: Daemon audio length bound
The warm ASR daemon SHALL refuse WAV input longer than a configured bound (default 120 s) with an explicit error before passing it to the model, so an oversized request cannot crash the model process.

#### Scenario: Oversized request
- **WHEN** the daemon receives a request for a 10-minute WAV
- **THEN** it answers with an error naming the length bound, and its model process keeps running

### Requirement: Whole recording reaches the model
When a recording contains detected speech, transcription SHALL send the whole converted recording to the model, not a trimmed span. A recording with no detected speech, including after the low-frequency rescue, SHALL still return no speech without calling the model.

#### Scenario: Silence around speech
- **WHEN** a synthetic recording has 4 s of silence, 3 s of speech-like sound and 4 s of silence
- **THEN** the stub transcriber receives audio as long as the whole 11 s recording

#### Scenario: No speech
- **WHEN** a synthetic recording is silent throughout
- **THEN** the result reports no speech and the stub transcriber is not called
