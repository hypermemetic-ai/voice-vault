## ADDED Requirements

### Requirement: Dictation appends to any app's composer
Android SHALL insert a completed dictation into the focused application's composer
by appending it to the existing draft, separated by one space, and SHALL NOT discard
the draft. A placeholder reported as editor text SHALL be treated as an empty draft.
Echo confirmation SHALL compare whitespace-normalized text.

#### Scenario: Draft already typed
- **WHEN** the composer contains "fix the bug" and the transcript is "and add a test"
- **THEN** the composer contains "fix the bug and add a test"

#### Scenario: Web echo whitespace differs
- **WHEN** the editor reports the written draft with a trailing paragraph break
- **THEN** insertion is confirmed

### Requirement: Composer submit for every app
Android SHALL use the same single-dispatch composer flow for every application. It
SHALL click only an enabled control inside the composer whose accessible name exactly
matches a submit name (including `Submit message`, `Queue message`, `Steer message`),
SHALL NOT dispatch IME enter or click controls matched by substring, and SHALL report
the confirmed outcome.

#### Scenario: Agent running in T3 Code
- **WHEN** the composer's control is named `Queue message`
- **THEN** it is clicked once and `Queued ✓` is shown after the composer clears

#### Scenario: Provider without text search
- **WHEN** accessibility text search returns no nodes and the submit control sits one level above a wrapper holding other controls
- **THEN** a bounded walk finds the control and the draft is sent once

### Requirement: Visible auto-send state
The floating pill SHALL show the auto-send state whenever Dictation Mode is on,
including while recording and processing.

#### Scenario: Auto-send toggled
- **WHEN** auto-send is toggled from the volume key or the dashboard
- **THEN** the pill shows `AUTO-SEND` or `NO SEND` accordingly

### Requirement: Volume key holds
Volume key taps SHALL act on release and 500 ms holds SHALL act once while held,
replacing double presses. With Dictation Mode off, a Volume Up tap SHALL raise the
volume with the system slider and Volume Down SHALL pass through.

#### Scenario: Hold Volume Down in Dictation Mode
- **WHEN** Volume Down is held for 500 ms
- **THEN** auto-send toggles once and no insertion occurs

### Requirement: Silent feedback
Voice Vault SHALL NOT play sounds for recording, completion or mode changes.

#### Scenario: Start recording
- **WHEN** recording starts
- **THEN** only haptic and pill feedback occur
