# Spec Delta

## Purpose

Defines where accepted recording audio is kept from intake to archive, so
transcription starts from fast storage while the audio stays retrievable and
fast storage never fills up with old recordings.

## ADDED Requirements

### Requirement: Staged intake
When a staging folder is configured, an accepted upload SHALL be durably written to it before transcription starts, and transcription SHALL read the staged copy. Without a configured staging folder, intake SHALL write to the archive folder as before.

#### Scenario: Staging configured
- **WHEN** a recording is uploaded to a server configured with a staging folder
- **THEN** the stub transcriber receives a path inside the staging folder

#### Scenario: Staging not configured
- **WHEN** a recording is uploaded to a server without a staging folder
- **THEN** the stub transcriber receives a path inside the archive folder, as before

### Requirement: Archive after the attempt
After a transcription attempt finishes and its response is sent, the staged audio SHALL be moved to the archive folder and the staged copy removed, whatever the attempt's outcome. A move SHALL NOT run while an attempt for that recording is in progress.

#### Scenario: Successful attempt
- **WHEN** an upload is transcribed successfully
- **THEN** shortly after the response, the audio exists only in the archive folder with identical bytes

#### Scenario: Failed attempt
- **WHEN** transcription fails for an upload
- **THEN** the audio still moves to the archive folder and a later retry transcribes it from there

### Requirement: Staging does not accumulate
Audio left in the staging folder with no attempt in progress SHALL be moved to the archive at server start and by a periodic sweep. A failed move SHALL leave the staged copy in place and be retried by a later sweep.

#### Scenario: Leftover after a crash
- **WHEN** the server starts and the staging folder holds a recording's audio with no attempt in progress
- **THEN** that audio is moved to the archive folder

#### Scenario: Archive temporarily failing
- **WHEN** moving to the archive fails once
- **THEN** the staged copy remains retrievable, and a later sweep completes the move

### Requirement: Fallback to the archive
If the staged write fails (for example no space left on fast storage), intake SHALL write the upload to the archive folder instead and continue normally.

#### Scenario: Fast storage full
- **WHEN** writing to the staging folder fails with ENOSPC
- **THEN** the upload is stored in the archive folder and transcribed normally

### Requirement: Retrievable from either location
Playback, retry, delete and the duplicate-upload check SHALL find a recording's audio whether it is currently staged or archived. Delete SHALL remove every copy.

#### Scenario: Retry and delete across locations
- **WHEN** a recording's audio is staged and then archived
- **THEN** `GET /api/audio/:id` serves identical bytes before and after the move, and `DELETE /api/recording/:id` leaves no copy in either folder
