# Design

## D1. Rule

Over the audio from the piece start to at most one maximum piece length, find
the first run of complete 25 ms frames (10 ms hop) below -50 dBFS that lasts at
least `longPauseMs` (default 2000), does not start at the piece's first frame,
and ends before a complete louder frame. Cut at the middle of that run. Only
when no such run exists do the existing window and forced-cut rules apply.

The run must start after the first frame because the second half of a cut
pause starts the next piece, and leading silence is no reason to cut. It must
end before louder audio, so trailing silence stays in the last piece.

## D2. Determinism

The threshold is absolute and only complete frames are read, so the first
qualifying run in a prefix of the audio is also the first in any longer audio.
Incremental and batch cutting give the same boundaries, and a cut is final as
soon as the louder frame after the pause has arrived.

## D3. Single request

The transcriber always cuts and keeps the single-request path when the result
is one piece. Recordings no longer than one piece and without a long pause
behave as before.
