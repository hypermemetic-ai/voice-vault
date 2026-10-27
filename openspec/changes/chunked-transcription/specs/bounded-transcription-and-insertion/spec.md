# Spec Delta

## MODIFIED Requirements

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

## ADDED Requirements

### Requirement: Daemon audio length bound
The warm ASR daemon SHALL refuse WAV input longer than a configured bound (default 120 s) with an explicit error before passing it to the model, so an oversized request cannot crash the model process.

#### Scenario: Oversized request
- **WHEN** the daemon receives a request for a 10-minute WAV
- **THEN** it answers with an error naming the length bound, and its model process keeps running
