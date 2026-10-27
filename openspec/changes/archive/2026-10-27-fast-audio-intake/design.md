# Design

## Context

See proposal.md (Why). Intake today (`src/server.mjs`, `POST /api/transcribe`):
insert row (`intake`) → write `<rawDir>/<id><ext>.part`, fsync, rename, fsync
`rawDir` → `pending` → `processRecording` runs `transcribeAudioFile(ownedPath(rec))`
→ finish row → respond. `ownedPath` resolves `<rawDir>/<raw_filename>`. It
refuses symlinks and names that do not start with `<id>.`. Every audio
consumer goes through `ownedPath`/`available`: playback, retry, delete,
duplicate-upload fingerprint, and the `audio_available` view field.

Measured on qq-box: fsync on the cold-tier volume 180–540 ms (multi-second
under load), on the SSD 5–12 ms. The DB is already on the SSD.

## Goals / Non-Goals

**Goals:** take the cold-tier fsyncs off the response path; keep every
durability and retrievability guarantee that exists today; bound the SSD
footprint to in-flight audio.

**Non-Goals:** changing the DB, the archive location, retention policy, the API,
or the Android client. Making transcription itself faster.

## Decisions

### D1. Opt-in staging folder
`createAppServer({ stagingDir })`, defaulting to `process.env.VOICE_VAULT_STAGING_DIR`.
No default path means other hosts and tests behave exactly as today. On qq-box,
activation sets it to `~/.recordings-qq-ssd/voice-vault/incoming`, the SSD
folder that already holds the DB. Its contents mirror the archive's naming:
`<raw_filename>`, plus `.part` temporaries.

*Alternative:* tmpfs. Rejected: a crash before archiving would lose the server
copy, and the phone's copy is not guaranteed retained forever.

### D2. Same write protocol, different folder
Intake writes `.part` → fsync → rename → fsync directory in the staging folder,
the same code with the folder swapped. On any write error from the staging
attempt, it removes its own `.part` and repeats the existing protocol in
`rawDir`. That covers ENOSPC, EACCES, a missing folder, or any other error. The
row and response are unaffected, and the fallback is logged once per request.

### D3. One resolver for two locations
`locate(rec)` returns the staged file if it is a regular non-symlink file, else
the archived one, else null. It applies the same owner and name checks as
today. `ownedPath` becomes `locate`. Delete unlinks both candidates if present.
The "cleanup incomplete" check covers both folders.

### D4. Archive queue
A single in-process FIFO, so at most one move at a time and no competition with
itself on the slow disk. Jobs are enqueued:
- right after `processRecording` sends its response (success or failure);
- after an intake failure that left a staged file;
- by the sweep.

A move job for `<name>`:
1. skip if the recording has a live attempt (`liveAttempts`); a later sweep
   picks it up;
2. copy staged → `<rawDir>/<name>.part`, fsync, rename to `<rawDir>/<name>`,
   fsync `rawDir`;
3. re-check the staged copy is unchanged (size and mtime) and no attempt
   started, then unlink the staged copy.

If the destination already exists with identical bytes (a crash between rename
and unlink), only the staged copy is unlinked. If it exists with different
bytes, both are kept and a warning is logged; this should never happen because
identities are immutable. Errors leave the staged copy and are retried by the
next sweep.

Moving runs after the response, so ffmpeg/transcription reads never race with
unlink. A retry that starts mid-move resolves the staged copy, which is deleted
only after step 3's check.

### D5. Sweep
At `createAppServer` start, and then every `VOICE_VAULT_STAGING_SWEEP_MS`
(default 10 min, timer `unref`'d), list the staging folder:
- enqueue each non-`.part` file whose name is a valid owned name;
- delete `.part` files older than 1 hour (abandoned intakes; the existing
  intake already removes its own stale `.part` on retry).

Files with no matching row are left alone and logged; they are never deleted
by the sweep. The server exposes `close()` cleanup of the timer through the
existing server `close` event, so tests exit cleanly.

### D6. Tests
Use `createAppServer` with temp `rawDir` and `stagingDir`, a stub transcriber
that records the path it received, and an injectable `recordingFiles`
(already supported) or a wrapper that throws ENOSPC on staging writes. Wait for
the archive queue to drain with an exported test hook
(`server.archiveIdle(): Promise`).

## Risks / Trade-offs

- [The SSD is 99% full] → Only in-flight audio is staged, and ENOSPC falls back
  to the archive (D2). Activation checks free space.
- [Crash after response but before archive] → The staged copy is fsynced. The
  startup sweep archives it.
- [Two copies briefly exist] → Bounded by one in-flight move. Lookup prefers
  staged, and bytes are identical by construction.
- [The archive volume is slow and moves queue up during a burst] → The queue is
  serial, and staging holds at most the backlog. Each move is about 1 s.

## Migration Plan

1. Merge; create `~/.recordings-qq-ssd/voice-vault/incoming` (mode 0700). Add
   drop-in `~/.config/systemd/user/voice-vault.service.d/staging.conf` with
   `Environment=VOICE_VAULT_STAGING_DIR=/home/qqp/.recordings-qq-ssd/voice-vault/incoming`.
   Run daemon-reload and restart `voice-vault.service`.
2. Verify with one synthetic upload under a test id (`X-Recording-Id:
   activation-check-<date>`). Confirm the response, that staging is empty
   within seconds, and that the archive holds the file. Then
   `DELETE /api/recording/<id>` removes it from history and disk.
3. Rollback: remove the drop-in and restart. Archived audio is unaffected; run
   one sweep first if staging is non-empty.
