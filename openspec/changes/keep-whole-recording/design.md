# Design

## Context

`transcribeAudioFile` converts to 16 kHz mono, runs the whole-file
`prepareAudioForTranscription` (from qq-dictation) and, when it reports no
speech, retries on a 150 Hz high-passed copy (low-frequency rescue). The
result's `wavBytes` is the trimmed span, which feeds the speaker gate, the
piece cutter and the model.

## Decisions

### D1. Use the trim only as a speech/no-speech decision
`prepareAudioForTranscription` still decides whether there is any speech. When
there is, the audio passed on is the full converted WAV (`converted.wav`), or
the full high-passed copy (`rescue-highpass.wav`) when the rescue found the
speech. *Alternative:* detect bounds on a high-passed copy and keep trimming.
Rejected: on the 42 s example the high-passed trim still kept only 2 s, while
the whole recording holds 87 words.

### D2. No change in qq-dictation
The shared recognizer is used by other clients. Voice-vault simply stops
using its trimmed bytes.

### D3. No configuration switch
Rollback is a revert. A switch would keep a known speech-dropping path alive.

## Risks / Trade-offs

- [The model may write text during long silences] → Bark produced 0 words on
  30 s of silence, brown noise and pink noise; `cleanTranscript` still removes
  known silence phrases.
- [More audio per request] → About 1 s per minute of silence; chunking bounds
  GPU memory for long recordings.

## Migration Plan

Merge with the chunked-transcription change, restart `voice-vault.service`.
Verify by running the 127 s car recording through `transcribeAudioFile`
locally (no server, no history entry) and checking the word count only.
