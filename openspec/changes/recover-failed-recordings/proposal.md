# Proposal

## Why

The operator reports server errors and two recordings that cannot be retrieved from the app. Current Android code retains audio after upload failure but exposes only nonempty transcripts in History; the server writes raw audio before transcription but creates its database entry only after transcription succeeds, leaving failed attempts outside accessible history.

## What Changes

- Persist an Android recording entry and its audio association before upload, including stable identity, capture time, duration, processing state and a safe failure category.
- Show failed, interrupted and recovered recordings in the existing History drawer, including recordings without transcript text, with playback, export, manual Retry transcription and explicit deletion.
- Keep unresolved audio and its metadata across app restarts, server-history refresh and ordinary retention pruning. Storage failure must be visible; recording durability cannot be promised when local saving fails.
- Discover existing unlisted Android dictation files on upgrade, label their previous outcome unknown, and make surviving valid audio recoverable without guessing that it failed.
- Retry the same recording on demand, update its existing history entry, prevent concurrent duplicate attempts, and keep recovery results outside the old dictation insertion/auto-send context.
- Register server recordings before running inference, persist safe failure/interrupted states, and add stable upload identity plus lookup/retry support so response loss does not create duplicate server records.
- Cover restart, error, retention, history synchronization and retry behavior with synthetic recordings, temporary storage and stub transcription.

## Capabilities

### New Capabilities

- `recording-recovery`: Durable recording availability, visible failure history, protected retention, recovery of unlisted Android audio, and explicit retry of the same recording.

### Modified Capabilities

None. Existing bounded transcription, terminal failure and fresh insertion requirements continue to apply; recovery adds a separate recording lifecycle without changing the backend cascade or speaker boundary.

## Impact

Android `VoiceVaultService`, `HistoryManager`, `MainActivity`, `DictationUpload`, API helpers and narrowly scoped playback/export resources; server upload/history/audio routes and SQLite recording metadata; hermetic Android and server recovery tests. Server API changes are additive and preserve existing clients without recording identities. Initial client scope is the native Android app; browser recording persistence is a separate change.

This change captures planning only. Implementation, signed APK delivery and backend activation require a subsequent apply assignment; no device operation, private recording inspection, inference, signing change or running-service change is performed here. Recovering the two reported recordings remains conditional on their audio still existing.
