# Spec Delta

## Purpose

Keep recorded speech accessible when upload or transcription fails, and let the user retrieve its original audio and explicitly retry transcription without recording again.

## ADDED Requirements

### Requirement: Completed recordings are durable before upload
Android SHALL persist each successfully finalized recording and an associated entry with stable identity, capture time, duration and processing state before attempting transcription. A local persistence failure SHALL be reported as a storage failure without claiming the recording is safely saved.

#### Scenario: Upload fails after recording
- **WHEN** a completed recording encounters a connection failure, timeout, server error or invalid response
- **THEN** its saved entry and original audio remain available after the app restarts

#### Scenario: Local storage cannot save the recording
- **WHEN** audio finalization or metadata persistence fails because storage is unavailable
- **THEN** the app reports the storage failure, preserves any existing audio, and does not present the recording as safely saved or successfully transcribed

### Requirement: History includes unresolved recordings
Android History SHALL show failed, interrupted and recovered recordings without requiring transcript text, including capture time, known duration, audio availability and a safe status or failure reason. Refreshing server history SHALL preserve local unresolved entries; transcript display limits SHALL NOT hide them.

#### Scenario: Server returns HTTP 500
- **WHEN** a transcription attempt fails with a server error
- **THEN** History displays the recording with a failure reason and a Retry transcription action when audio is available

#### Scenario: Server history refresh omits local failure
- **WHEN** the server returns only successful transcripts or a limited history page
- **THEN** existing local failed and recovered recordings remain visible

### Requirement: Unresolved audio is protected from retention cleanup
Automatic cleanup SHALL NOT delete the audio or metadata of pending, processing, failed, interrupted or recovered recordings. Only successfully resolved recordings SHALL be eligible for ordinary retention pruning. Storage exhaustion SHALL be reported rather than silently evicting unresolved recordings.

#### Scenario: More recordings exceed the normal audio limit
- **WHEN** newer successful recordings cause ordinary retention cleanup
- **THEN** older unresolved recordings remain retrievable regardless of their age or position in History

### Requirement: Existing unlisted Android audio can be recovered
Android SHALL discover surviving unlisted dictation audio in its existing app recording locations and add stable recovery entries without automatic transcription or deletion. Unknown previous outcomes SHALL be labeled as recovered audio with outcome unknown. Repeated discovery SHALL NOT duplicate entries or import unrelated audio.

#### Scenario: Upgrade finds an old unlisted recording
- **WHEN** a surviving dictation file has no associated recording entry
- **THEN** History exposes it as recovered audio and offers playback, export and retry when it is usable

#### Scenario: Discovery runs again
- **WHEN** an already adopted file is encountered alongside unrelated app audio
- **THEN** the recovered recording still has one entry and unrelated audio is excluded

### Requirement: Original audio is retrievable from the app
Android SHALL offer playback and user-initiated export of available original recording audio, preferring its local copy when available. Missing, empty or unusable audio SHALL be identified honestly without deleting the recording entry or offering a retry that cannot read audio.

#### Scenario: Server is unreachable
- **WHEN** a failed recording has a usable local audio copy
- **THEN** the user can play or export that copy without contacting the server

#### Scenario: Audio is missing
- **WHEN** neither a local original nor a known server copy is available
- **THEN** the app retains the entry, reports audio unavailable and disables playback, export and retry

### Requirement: Retry updates the same recording
An explicit Retry transcription action SHALL use the selected saved audio and update the existing recording entry, preserving capture time and original audio. Further failures SHALL retain the item and an updated failure reason. Interrupted attempts SHALL remain manually retryable after restart; restarting SHALL NOT trigger inference automatically.

#### Scenario: Retry succeeds
- **WHEN** the user retries a failed recording and transcription succeeds
- **THEN** the same History item displays the resulting transcript without a second recording entry

#### Scenario: Retry fails or the app restarts
- **WHEN** another retry fails or processing is interrupted by restart
- **THEN** the original remains available and the item shows failed or interrupted status with a later manual retry available

### Requirement: Recovery attempts have isolated ownership
The app SHALL prevent simultaneous retry attempts for one recording and SHALL NOT interrupt an active dictation to begin recovery. Canceling recovery SHALL preserve its audio. Late results from superseded attempts SHALL NOT overwrite newer state. Recovery SHALL NOT insert or auto-send text to another app or replace the clipboard without an explicit copy action.

#### Scenario: User taps Retry repeatedly
- **WHEN** the selected recording is already processing or dictation owns the recorder workflow
- **THEN** no second recovery attempt starts and the app displays the busy state

#### Scenario: Canceled attempt returns late
- **WHEN** a canceled attempt finishes after a newer attempt or dictation begins
- **THEN** its result cannot alter the newer operation, clipboard or external input, and the retained recording remains accessible

### Requirement: Server failures remain indexed and retrievable
The server SHALL register accepted, durably saved uploads before transcription, record safe failure status if processing fails, and expose them through recording lookup, history and original audio retrieval. Interrupted processing SHALL remain identifiable after server restart. It SHALL report storage failure without claiming audio is saved when persistence fails.

#### Scenario: Temporary transcription storage fills up
- **WHEN** processing fails after the raw upload is saved
- **THEN** the same recording remains indexed as failed, its available original can be retrieved, and the response identifies the recording and a storage failure category

#### Scenario: Server restarts during processing
- **WHEN** a persisted processing attempt no longer has a live owner after restart
- **THEN** the recording is exposed as interrupted and accepts a later explicit retry

### Requirement: Upload identity survives response loss
For clients supplying stable recording identity, repeated uploads and retries SHALL refer to one server recording. Repeated delivery of an already completed upload SHALL return its stored outcome without repeating inference. Concurrent attempts SHALL NOT process the same recording twice, and conflicting content for an existing identity SHALL be rejected without overwriting its audio.

#### Scenario: Successful response is lost
- **WHEN** the client reconnects using the same identity after the server completed transcription
- **THEN** lookup or repeated upload returns the saved outcome and no duplicate server row or inference attempt is created

#### Scenario: Identity is reused with different audio
- **WHEN** an upload supplies an existing identity with different recording content
- **THEN** the server rejects the conflict and preserves the existing recording

### Requirement: Server retry is explicit and compatible
The server SHALL allow explicit retry of failed or interrupted saved recordings using the same identity and original audio. Existing upload clients without a supplied identity SHALL continue to receive the existing successful response fields. Valid no-speech and speaker-rejected outcomes SHALL remain distinct from failures.

#### Scenario: Retry existing failed server recording
- **WHEN** the user explicitly requests transcription for an indexed failure with available audio
- **THEN** processing updates that recording and a later failure retains its original

#### Scenario: Existing client uploads normally
- **WHEN** a client sends the existing raw or multipart request without stable identity
- **THEN** the server generates an identity and preserves the existing successful response contract

### Requirement: Recovery metadata and actions preserve privacy
Failure feedback and recovery logs SHALL use bounded categories without audio, transcripts, voice profiles, filesystem paths or raw server error bodies. Audio export SHALL occur only through a user-initiated action. Explicit deletion SHALL remove the selected recovery item and its associated owned copies without changing unrelated recordings.

#### Scenario: Failure contains sensitive server details
- **WHEN** a response includes a raw error body or private path
- **THEN** app feedback and recovery logs expose only the safe failure category

#### Scenario: User exports or deletes a recording
- **WHEN** the user explicitly chooses export or confirms deletion of a selected recording
- **THEN** only that recording is exported or deleted, and other recordings remain unchanged
