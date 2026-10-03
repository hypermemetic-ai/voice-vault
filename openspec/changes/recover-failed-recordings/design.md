# Design

## Context

See proposal.md for the recovery gap. `VoiceVaultService` records into the app's existing recording directory, leaves originals on upload failure, saves only successful nonblank text into `HistoryManager`, and prunes audio without knowing its outcome. `HistoryManager` discards blank transcripts and replaces its local cache with a remote history page. Server `/api/transcribe` saves raw bytes, awaits `transcribeAudioFile`, and only then inserts its SQLite row. `/api/audio/:id` therefore cannot expose a failed upload that has no row.

The existing ten-minute client processing deadline and recording-generation guards already prevent late dictation insertion. Keep these controls; recovery needs its own durable identity and attempt ownership rather than reusing a former accessibility target.

An urgent metadata-only audit on 2026-10-02 found two Voice Vault ENOSPC server failures at 22:20:49 and 22:22:11 CDT. Sanitized stacks identify `src/transcriber.mjs:526`, the temporary filtered-audio write, reached through `src/server.mjs:429` after raw upload storage. This establishes two disk-full processing failures, without identifying or inspecting private recordings. Initially available filesystem space was approximately 16 MiB; an operator-authorized deletion of one regenerable pip cache file freed approximately 527 MiB. A subsequent synthetic write and fsync passed. Both services remained active, dashboard HEAD returned 200, and Whisper health reported ready. No real inference was run.

Current specs retain the bounded backend cascade, explicit terminal failures, fresh insertion ownership and local speaker boundary. `recording-recovery` adds durability and retrieval; it does not replace those contracts.

## Goals / Non-Goals

**Goals:** Make a finalized original independently useful when transcription fails; expose unresolved items offline; keep retries tied to one recording; make the observed temporary-storage failure recoverable; preserve existing successful dictation and older upload clients.

**Non-Goals:** Browser offline recording persistence, automatic background retry, recovery of bytes never recorded or already deleted, new inference backends, changes to voice gating, cloud audio storage, wholesale machine cleanup, and deployment during planning. Backend activation and APK delivery belong to the subsequent implementation assignment.

## Decisions

### 1. Add a durable recording index independent of transcript cache

Use a versioned JSON recording index in app-private storage, with stock Android atomic-file replacement and recovery from its last valid version. Associate entries with audio in the existing external app recording directory or its existing private-directory fallback. Include a generated UUID, original capture time, known duration, owned relative filename/location, optional server identity, state, safe error category, attempt generation and optional transcript. Keep audio paths out of UI, API errors and logs.

Persist capture intent before starting the recorder, then mark the entry pending only after a successful stop, nonempty finalized audio and durable metadata save. Do not start upload when that transition fails. Retain surviving files on storage failure so startup reconciliation can expose them. Recognize interrupted capture separately from finalized audio; a failed recorder stop does not establish usable audio.

An independent atomic JSON index follows the existing dependency-free history storage while protecting failure entries from transcript-cache pruning and replacement. Android SQLite would also provide transactions but adds a second storage representation and migration route without a current query need; keep this change on a small atomic index with extracted state logic for hermetic tests.

### 2. Keep durable state separate from live attempt ownership

Use durable states `recording`, `pending`, `processing`, `failed`, `interrupted`, `recovered`, `transcribed`, `no_speech` and `speaker_rejected`, with separate audio availability. After restart, entries that were processing without a live owner become interrupted. Only transcribed/no-speech/speaker-rejected outcomes qualify for normal audio retention. Missing or unusable audio does not turn a failure into success.

Persist transitions before announcing them, guard results by recording UUID and attempt generation, and retain originals after timeout or recovery cancellation. Explicit cancellation of an active capture retains its current discard meaning; distinguish it from canceling processing on already finalized audio. Do not treat a valid empty response or gate rejection as a server failure.

### 3. Make History a merged recording view

Extend `HistoryManager.Entry` and rendering to accept recordings without text. Merge durable local entries, existing cached transcripts and remote entries by known recording/server identity; never replace the durable store with a remote page or infer identity from equal text. Preserve original capture ordering when retries finish later. Keep the current successful-text dashboard preview based on newest nonblank transcript, so unresolved entries do not erase it.

Render timestamp, known duration, status/reason and audio availability. Failed/interrupted/recovered entries expose Retry transcription, playback, export and deletion. Transcript copying remains explicit. Apply the existing transcript display limit to resolved transcript history only; unresolved items remain discoverable. Existing local transcript-only entries keep their text even where no audio association can be proven.

### 4. Adopt existing Android dictation files conservatively

Reconcile both known app recording locations at startup/upgrade. Limit adoption to the established dictation filename family and supported formats; exclude voice enrollment, unrelated files, symlinks and active captures. Link by an existing owned filename before creating an entry, so repeated scans are idempotent. Use filename capture time where valid, otherwise file metadata, and derive duration locally when available.

Adopt unlisted audio as `recovered` with previous outcome unknown; do not call it failed or automatically transcribe it. Empty or unplayable files remain visible as audio unavailable. Distinguish resolved entries whose audio was intentionally pruned so reconciliation does not recreate them.

Do not automatically bulk-import existing server orphan files: legacy server deletion removes the row but leaves raw audio, so blind scanning could revive deliberately deleted entries. Preserve those files. Targeted recovery of the two reported server uploads can be a separate operator-guided action if Android discovery does not recover the originals; their existence has not been checked in this audit.

### 5. Reuse transport, isolate manual recovery from dictation

Add a bounded service-owned recovery action accepting a stored UUID, never an arbitrary path from an Intent. Resolve the owned audio internally and reuse `DictationUpload` validation, finite timeouts, response limits and asynchronous disconnection. Reject starting recovery while dictation or another recovery owns the workflow; disable repeated taps for the active item.

Recovery writes its result into the selected entry and updates History. It must not call the former dictation insertion/auto-send completion path or automatically replace the clipboard. The user can copy the recovered text explicitly. A service-owned operation is preferred to an Activity-owned thread because screen recreation must not duplicate an upload or lose ownership. No scheduler or automatic retry is introduced.

### 6. Index server intake before inference and preserve failures

Extend the existing recording table additively with safe error category, updated/attempt metadata, content fingerprint and stored terminal response metadata as needed. Reserve an intake row, write raw audio atomically with a completed-write check, then commit its pending/processing state before invoking inference. A failed audio write leaves an honest failure/unavailable state when metadata remains writable; a database write failure never produces a saved/success claim. Any finalized orphan survives for later deliberate recovery.

Wrap processing so errors update that same row to failed, retain the raw original, and return its identity plus a bounded category. Map ENOSPC/SQLITE_FULL to `storage_full`; map conversion, backend, timeout and unknown failures to fixed categories without raw paths or backend bodies. Preserve the existing successful response fields. Startup reconciliation changes persisted in-flight states without a current owner to interrupted; it does not automatically run inference.

Checking free space can provide helpful early feedback, but it is not a correctness guarantee: another process can consume space after the check. Handle failed writes at intake, temporary-audio processing and metadata commit. Avoid introducing a cache cleaner or a machine-wide retention policy into the recording server.

### 7. Add stable identity and explicit server retry

New Android entries send their UUID in optional `X-Recording-Id`. Validate it against a bounded safe identifier format and resolve every filename under the owned raw directory. Old clients continue to get a generated server identity. Persist a fingerprint of accepted audio; the same identity with different bytes is a conflict and never overwrites an original.

Repeated `/api/transcribe` delivery for a completed identity returns its saved response without inference; an identity currently processing returns a structured busy response. Add `GET /api/recording/:id` for status/outcome lookup and `POST /api/recording/:id/transcribe` for explicit retry of failed/interrupted records with available audio. Completed records return their stored outcome; retry does not implicitly reprocess successful speech. Claim attempt ownership atomically before starting work so concurrent requests cannot double-process.

After response loss, lookup the known stable identity first. Apply a completed outcome, show a live pending state, or explicitly retry a failed/interrupted server record. If no server copy was accepted, re-upload the local original using the same identity. When a legacy server ignores identity or lacks lookup/retry, Android can still retain local failures and retry its ordinary upload, but server deduplication is only guaranteed with the updated server. Deploy the additive server support before the new client.

### 8. Retrieval and deletion use only owned copies

Playback prefers local original audio; a known server copy is an alternate source. Add a narrow Android content provider or equivalent stock-platform export route with temporary grants for user-initiated sharing; do not expose arbitrary filesystem paths. Avoid copying long audio into memory for playback/export. Missing/invalid audio disables impossible actions with a clear explanation.

Deletion requires explicit user confirmation and cannot race an active retry. Delete the selected owned local audio and metadata; when a server copy is known, use the existing delete route extended to remove its owned raw copy as well. A local deletion marker prevents stale remote refresh or an incomplete cleanup from resurrecting the item. Report partial deletion and permit retry of cleanup; do not claim all copies were removed if the server is unavailable.

## Risks / Trade-offs

- Storage remains tight after the one-file emergency cleanup -> report real persistence failures and preserve unresolved audio; further package-cache cleanup is an operational follow-up, not automatic recording eviction.
- Unresolved audio can grow beyond the normal retention limit -> provide explicit delete/export and storage-failure feedback rather than silently losing speech.
- A crash between audio and metadata writes can leave partial state -> atomic writes, intake/capture markers and idempotent startup reconciliation keep surviving bytes discoverable.
- Older Android files cannot prove their previous result -> label outcome unknown and retain existing transcript history without guessing associations.
- A timeout can occur after server completion -> stable identity, lookup and stored outcomes prevent duplicate rows and repeated inference with updated clients/server.
- Hermetic source tests do not prove device microphone, playback permissions or service lifecycle behavior -> compile all sources and leave explicit device acceptance for the later release assignment.

## Migration Plan

1. During apply, use temporary synthetic stores to verify the additive database migration, pending/failed rows, stable identity and retry routes. Preserve existing records and source API behavior; do not read private production rows into agent inputs.
2. Implement Android index migration/discovery and merged History without clearing app data or replacing the existing signing identity. Test upgrades from legacy transcript-only cache and orphan dictation fixtures.
3. Run the existing hermetic recording/state and backend suites plus dedicated recovery tests with speaker auto-download off; compile Android sources to temporary classes without packaging for source validation.
4. The subsequent authorized implementation assignment delivers source through the repository's established PR path and carries through ordinary activation: additive server support first, then an APK published with the unchanged signing certificate. Coordinate activation around active requests and preserve unrelated tasks. Device installation and microphone/playback acceptance remain operator-owned unless explicitly included in that assignment. Planning performs no activation or APK build.
5. Roll back code/artifacts without clearing durable recording metadata or deleting unresolved audio. Do not downgrade away from recording recovery until unresolved entries have been exported or remain usable by the retained app; old pruning code does not protect them. Rollback must preserve the additive database columns and local index for forward recovery.
