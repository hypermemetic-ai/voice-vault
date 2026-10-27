# long-recording-transcription Specification

## Purpose
Lets a recording of any length be transcribed by the configured model within a
bounded GPU footprint, by transcribing it in pause-aligned pieces whose results
join into one transcript.

## Requirements

### Requirement: Bounded pieces
Speech audio longer than one piece SHALL be transcribed as consecutive pieces, none longer than the configured maximum (default 45 s), so the model never receives a whole long recording at once. Audio no longer than one piece SHALL be sent as a single request, unchanged from before.

#### Scenario: Long recording
- **WHEN** a 16-minute synthetic recording is transcribed with a stub backend
- **THEN** the backend receives only pieces of at most 45 s, and their audio in order covers the whole input

#### Scenario: Short recording
- **WHEN** a 20-second recording is transcribed
- **THEN** the backend receives exactly one request containing the whole speech audio

### Requirement: Pause-aligned cuts
A cut SHALL fall in the quietest stretch inside the cut window (default 20–40 s from the piece start) when that window contains a pause, so no spoken word is split between pieces.

#### Scenario: Pause inside the window
- **WHEN** synthetic speech has a 400 ms silence at 27 s and continuous sound elsewhere in the window
- **THEN** the first piece ends inside that silence

### Requirement: Forced cuts overlap
When the cut window contains no pause, the cut SHALL fall at the maximum piece length and the next piece SHALL start a configured overlap (default 1.5 s) earlier. The words repeated across that seam SHALL appear once in the joined transcript.

#### Scenario: Continuous speech
- **WHEN** a stub backend returns text whose last words repeat at the start of the next piece's text across a forced cut
- **THEN** the joined transcript contains the repeated words once

#### Scenario: No accidental dedup at pause cuts
- **WHEN** two pieces split at a pause happen to end and begin with the same word
- **THEN** both occurrences are kept

### Requirement: One joined transcript
The transcript returned for a recording SHALL be the piece texts in order, joined with single spaces and then cleaned and normalized once as a whole. The response SHALL keep its existing fields and report the piece count and each piece's backend.

#### Scenario: Join and report
- **WHEN** three pieces return "Alpha.", "Beta." and "Gamma."
- **THEN** the transcript is "Alpha. Beta. Gamma." and the outcome reports three pieces with their backends

### Requirement: Incremental cutting
The piece cutter SHALL accept audio appended in arbitrary increments and SHALL emit a piece as soon as its cut is final, producing the same pieces as cutting the complete audio at once.

#### Scenario: Appended audio
- **WHEN** the same synthetic audio is fed to the cutter in one call and in 37 irregular increments followed by a finish
- **THEN** both runs produce identical piece boundaries
