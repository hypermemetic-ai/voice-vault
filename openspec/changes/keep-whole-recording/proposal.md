# Proposal

## Why

Dictations made in loud places (a closed car with the engine running) lose
whole sentences. The audio is fine and the model understands it. The loss happens
before the model: the whole-file voice-activity trim (`prepareAudioForTranscription`)
cuts what it judges to be leading and trailing silence. Under loud
low-frequency noise it judges speech to have ended early and drops the rest.

Measured on 2026-10-27 with the live Bark model (word counts only, no history
changes):

- A 127 s car recording: the trim kept the first 17 s. 14 words were stored;
  the whole recording gives 250.
- A 42 s recording: the trim kept 1 s. 1 word was stored; the whole recording
  gives 87.

The trim exists to stop Whisper from inventing text during silence. Bark does
not: 30 s of digital silence, 30 s of brown noise (engine-like rumble) and 30 s
of pink noise each produce 0 words.

## What Changes

- When a recording contains speech, the model receives the whole recording
  (16 kHz mono, as converted), not the trimmed span.
- The no-speech check is unchanged: a recording with no detected speech, even
  after the low-frequency rescue, still returns "no speech" without calling the
  model.
- When the low-frequency rescue is what found speech, the whole high-passed
  copy is sent, as the rescue already does for its trimmed span.
- The speaker gate, when enrolled, receives the whole recording and does its
  own per-segment selection as before.
- Old recordings are not re-transcribed.

## Capabilities

### Modified Capabilities
- `bounded-transcription-and-insertion`: speech recordings reach the model
  whole.

## Impact

- Code: `src/transcriber.mjs` (`transcribeAudioFile`), tests in
  `test/transcriber.test.mjs`.
- Latency: leading and trailing silence now reach the model, about 1 s of
  extra processing per minute of silence. Long recordings are split into
  bounded pieces by the chunked-transcription change, so GPU memory stays
  bounded.
- `durationMs` and `audioSentMs` now report the whole recording's length.
