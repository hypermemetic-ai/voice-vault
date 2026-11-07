# Proposal

## Why

Recording 731ae919 (2026-11-07, 131 s) lost its last statement: 14 s of clear
speech after a 9 s pause. All the audio reached the server, and the cutter
made its final piece 90–131 s (41 s, under the 45 s maximum), with the pause
inside it. Parakeet TDT 0.6B v3 returned the text before the pause and nothing
after it. The same 14 s alone, or any piece that started after the pause,
transcribed in full. Synthetic splices showed that neither silence nor length
alone triggers it (a 46 s clip holding 22 s of silence and 40–43 s of
continuous speech both transcribed fully), so the failure can't be predicted
from the audio. The cure is to keep long silences out of the middle of
model requests.

## What Changes

- A pause of at least 2 s (frames below -50 dBFS) is always a cut, at its
  middle, wherever it falls in a piece. That includes the last piece and
  recordings shorter than one piece. Leading and trailing silence are not cuts.
- A long-pause cut is final as soon as speech resumes. While streaming, the
  piece before a long pause is therefore transcribed during the pause itself.
- `VOICE_VAULT_PIECE_LONG_PAUSE_MS` sets the length; `0` turns the rule off.

## Impact

- `src/chunker.mjs`, `src/transcriber.mjs` (the single-request path now means
  "one piece", not "no longer than the maximum"), tests and README.
- More, shorter requests for dictations with long thinking pauses. Each costs
  well under a second on the warm daemon.
