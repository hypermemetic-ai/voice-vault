# Spec Delta

## MODIFIED Requirements

### Requirement: Bounded pieces
Speech audio longer than one piece SHALL be transcribed as consecutive pieces, none longer than the configured maximum (default 45 s), so the model never receives a whole long recording at once. Audio that makes one piece (no longer than the maximum and without a long pause) SHALL be sent as a single request, unchanged from before.

#### Scenario: Long recording
- **WHEN** a 16-minute synthetic recording is transcribed with a stub backend
- **THEN** the backend receives only pieces of at most 45 s, and their audio in order covers the whole input

#### Scenario: Short recording
- **WHEN** a 20-second recording is transcribed
- **THEN** the backend receives exactly one request containing the whole speech audio

## ADDED Requirements

### Requirement: Long-pause cuts
A pause of at least the configured length (default 2 s, below -50 dBFS) that has audio before and after it SHALL be a cut at its middle, in any piece, including the last piece and recordings shorter than one piece. Leading and trailing silence SHALL NOT be cut. The cut SHALL be final as soon as louder audio follows the pause.

#### Scenario: Pause before the last statement
- **WHEN** a 41 s recording holds 18 s of speech, a 9 s pause and 14 s of speech
- **THEN** it is transcribed as two pieces split at 22.5 s

#### Scenario: Short pause and edge silence
- **WHEN** a recording has only a 1.5 s pause, or silence only at its start and end
- **THEN** it is one piece

#### Scenario: Streaming
- **WHEN** 5 s of speech and a 3 s pause have been appended and then 1 s of speech arrives
- **THEN** the cutter emits the first piece, ending in the middle of the pause, before finish
