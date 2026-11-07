# Design

## D1. Rule

Over one request's audio, find every run of 25 ms frames (10 ms hop) below
-50 dBFS lasting at least `longPauseMs` (default 2000). Keep the first and
last `PAUSE_KEEP_MS` (500) of each run and drop the middle. Runs at the start
and end of the audio count too. The same absolute threshold already marks
pauses for the cutter.

## D2. Where it runs

At the three places that write a WAV for the backend: the transcriber's
single-request path, its per-piece loop, and the stream session's background
pieces. The stream session squeezes the same piece PCM the final recording
would squeeze, so a cached result matches what a fresh request would send.
Cache keys stay built from the unsqueezed piece bounds and PCM.

## D3. Rejected: cut at long pauses

Cutting pieces at the middle of long pauses fixed 731ae919 but turned
stretches of sparse speech into short, mostly-silent pieces that the model
returned empty (f9a245d6 lost eight words). It also raised the request count
up to threefold.
