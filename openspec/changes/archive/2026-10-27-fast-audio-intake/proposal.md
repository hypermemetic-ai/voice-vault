# Proposal

## Why

Since 2026-10-09 the server's raw-audio folder lives on the cold-tier volume
(`/srv/qqcachedpilot`, rotational, shared with other agents' jobs). The server
persists every upload there before transcribing: a file fsync and a directory
fsync. Those two barriers measured 0.2–0.5 s each on 2026-10-27, versus about
0.01 s on the system SSD, and several seconds while other jobs load the volume.
Short dictations went from a 0.78 s median server time before the move to about
1.5 s after, with outliers up to 9 s. In 19 of the 25 slow short clips, almost
all the extra time was spent outside transcription. The database already lives
on the SSD.

## What Changes

- Accepted uploads are written and fsynced to a staging folder on fast storage,
  and transcription reads them from there.
- After the attempt finishes (success or failure) and the response has been
  sent, the audio moves to the existing raw-audio archive, and the staged copy
  is deleted.
- Staging never accumulates. Anything left there (crash, failed move) is moved
  at startup and by a periodic sweep. A failed move keeps the staged copy and
  retries later.
- If staging cannot accept the file (no space, missing folder), intake writes
  directly to the archive exactly as today.
- Playback, retry, delete and duplicate-upload checks find a recording's audio
  in either place.
- Staging is opt-in by configuration. Without it, behaviour is unchanged.

## Capabilities

### New Capabilities
- `recording-audio-storage`: where accepted recording audio is kept, from
  intake through archive, and the guarantees that it stays retrievable and
  that fast storage does not fill up.

### Modified Capabilities
- (none)

## Impact

- Code: `src/server.mjs` (intake write path, audio lookup, archive queue,
  sweep), server tests, README environment table.
- Runtime: `voice-vault.service` gets `VOICE_VAULT_STAGING_DIR` pointing at an
  SSD folder next to the database (`~/.recordings-qq-ssd/voice-vault/incoming`).
  The SSD is nearly full (about 4.5 GB free), so the staging footprint is
  bounded to in-flight recordings (≤ 100 MB each, typically < 1 MB).
- API: unchanged.
- Latency: expected −0.4 to −1 s per dictation typically, and removal of most
  multi-second outliers.
