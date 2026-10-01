# Spec Delta

## ADDED Requirements

### Requirement: Recorder failure terminates dictation
Android SHALL leave recording/processing and clear pending insertion when microphone preparation, delayed start, or stop fails. It SHALL release recorder and wake-lock resources and SHALL NOT upload an unsuccessfully stopped recording. Delayed callbacks from canceled recordings SHALL NOT start a later recording.

#### Scenario: Stop fails on a brief recording
- **WHEN** the recorder cannot finish a playable recording
- **THEN** Android reports a recording failure, returns idle, releases resources, and makes no transcription request

#### Scenario: Delayed start fails or becomes stale
- **WHEN** microphone start fails or its recording was canceled before the delayed start runs
- **THEN** failure returns idle or the stale callback does nothing, and no unusable audio is uploaded

### Requirement: Android processing has a terminal deadline
Android SHALL terminate processing within a ten-minute client budget, cancel the upload, and clear pending insertion. A late response SHALL NOT copy text or alter a newer recording. Timeout/failure SHALL leave the original local recording available and permit a new recording.

#### Scenario: Server never finishes
- **WHEN** an upload or transcription response exceeds the processing budget
- **THEN** Android reports timeout, leaves processing, and accepts a new recording without waiting for the old network worker

### Requirement: Android reports explicit upload failures
Android SHALL distinguish invalid recording, connection/timeout, HTTP rejection, and invalid response from successful transcription. It SHALL release HTTP streams/connections and SHALL NOT expose audio, transcripts, recording paths or response bodies in failure feedback/logs.

#### Scenario: Error response
- **WHEN** the server returns non-200, ok:false, or a malformed success payload
- **THEN** Android reports failure and clears pending insertion without copying text
