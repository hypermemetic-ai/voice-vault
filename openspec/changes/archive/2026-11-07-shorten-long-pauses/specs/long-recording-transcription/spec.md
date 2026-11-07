# Spec Delta

## ADDED Requirements

### Requirement: Long pauses shortened
Before audio is sent to the model, every pause of at least the configured length (default 2 s, below -50 dBFS) SHALL be shortened to 1 s, keeping its first and last 500 ms. This SHALL apply to single requests, to pieces, and to pieces transcribed while streaming. Audio above the threshold SHALL reach the model unchanged, and piece boundaries SHALL NOT depend on it.

#### Scenario: Pause before the last statement
- **WHEN** a 41 s recording holds 18 s of speech, a 9 s pause and 14 s of speech
- **THEN** the model receives one 33 s request containing both stretches of speech intact

#### Scenario: Short pause and room tone
- **WHEN** a recording has only a 1.9 s pause, or 6 s of room tone at about -40 dBFS
- **THEN** the model receives it unchanged
