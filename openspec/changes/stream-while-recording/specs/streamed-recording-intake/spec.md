# Spec Delta

## Purpose

Lets a recording be sent to the server while it is being made, so that most
of its transcription is finished by the time the user stops.

## ADDED Requirements

### Requirement: Append while recording
The server SHALL accept PCM audio for a recording in ordered, offset-addressed appends before the recording exists, SHALL treat repeated or overlapping appends idempotently, and SHALL report a gap with the number of bytes it holds.

#### Scenario: Retried append
- **WHEN** the same append is sent twice
- **THEN** the session holds the bytes once and both answers report the same received count

#### Scenario: Gap
- **WHEN** an append starts beyond the bytes the server holds
- **THEN** the server answers 409 with the received count and stores nothing

### Requirement: Pieces transcribed ahead of time
The server SHALL transcribe each piece that the incremental cutter finalizes during streaming, and SHALL reuse that result when the final recording yields a piece with the same boundaries and audio.

#### Scenario: Long streamed recording
- **WHEN** 3 minutes of synthetic audio are appended and then finished
- **THEN** the stub transcriber is called during streaming for the finalized pieces and, after finish, only for pieces that were not yet transcribed

#### Scenario: Different audio
- **WHEN** a cached piece's audio differs from the final recording's piece
- **THEN** the cached result is not used

### Requirement: Verified finish
Finishing a stream SHALL create the recording only when the streamed audio matches the client's length and SHA-256 of the canonical WAV, and SHALL then behave exactly like an upload of that WAV.

#### Scenario: Matching finish
- **WHEN** the client finishes with the correct length and hash
- **THEN** the response has the same fields as an upload, one history entry exists, and a later upload of the same WAV with the same id is answered as a replay

#### Scenario: Mismatch
- **WHEN** the client finishes with a wrong hash
- **THEN** the server answers 409 `stream_mismatch`, creates no recording, and a following full upload of the WAV succeeds

### Requirement: Streaming never risks the dictation
The app SHALL keep a complete local WAV of every recording, SHALL keep recording when streaming fails, and SHALL fall back to uploading the whole WAV with the same identity whenever finishing does not succeed.

#### Scenario: Server unreachable during recording
- **WHEN** appends fail while recording
- **THEN** recording continues and, at stop, the whole WAV is uploaded through the existing upload path

### Requirement: Abandoned sessions are removed
Stream sessions without activity for more than one hour SHALL be deleted.

#### Scenario: Abandoned session
- **WHEN** a session file is older than one hour at a sweep
- **THEN** it is removed and finishing it answers `no_stream`
