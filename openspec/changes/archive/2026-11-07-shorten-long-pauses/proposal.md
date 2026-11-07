# Proposal

## Why

Recording 731ae919 (2026-11-07, 131 s) lost its last statement: 14 s of clear
speech after a 9 s pause. All the audio reached the server, and the cutter
made its final piece 90–131 s (41 s, under the 45 s maximum), with the pause
inside it. Parakeet TDT 0.6B v3 returned the text before the pause and nothing
after it. The same 14 s alone transcribed in full.

Cutting at long pauses instead was tried and rejected. In recording f9a245d6
it isolated short phrases between long pauses ("is that all", "crouch throw
the") into pieces of a few words in mostly silence, and the model returned
nothing for each of them. Shortening the pauses kept both recordings whole.

## What Changes

- Before audio is sent to the model, each run of at least 2 s below -50 dBFS
  is shortened to 1 s (its first and last 500 ms). This applies to single
  requests, to pieces, and to pieces transcribed while streaming.
- Piece boundaries and the streaming cache keys are unchanged: the shortening
  runs on each request's audio only.
- `VOICE_VAULT_PIECE_LONG_PAUSE_MS` sets the length; `0` turns it off.

## Impact

- `src/chunker.mjs` (`squeezePauses`), `src/transcriber.mjs`,
  `src/stream-session.mjs`, tests and README.
- Requests get shorter when there are long pauses. Audio above -50 dBFS
  (speech, or room noise loud enough to mask it) is never removed.
