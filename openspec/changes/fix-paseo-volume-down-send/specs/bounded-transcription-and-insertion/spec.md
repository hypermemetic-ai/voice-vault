# Spec Delta

## ADDED Requirements

### Requirement: Paseo submission survives normal rendering
After exact text insertion, Android SHALL wait up to two seconds for a unique, visible, enabled, exact-labeled primary Paseo send control adjacent below the same composer. Normal resizing, optional neighboring controls and repeated content events SHALL NOT cancel or prematurely exhaust readiness. Dispatch SHALL occur at most once; changed text, composer identity, app or window SHALL cancel.

#### Scenario: Multiline resize during send validation
- **WHEN** the same composer and its toolbar resize or move while the expected text remains unchanged
- **THEN** Android revalidates the current send control and sends once when ready

#### Scenario: Content events before React enables Send
- **WHEN** multiple content events arrive while Send is initially disabled
- **THEN** Android retains its time budget and sends once when Send becomes ready within two seconds

#### Scenario: No neighboring voice control
- **WHEN** a unique exact primary send control appears in the composer's lower toolbar without exported neighboring controls
- **THEN** Android can dispatch that control without requiring optional peers

#### Scenario: Ambiguous or unavailable control
- **WHEN** no unique eligible primary control becomes ready before the deadline
- **THEN** Android retains the inserted draft and reports that manual sending is needed, without clicking other controls

### Requirement: Volume Down does not change auto-send preference
In Dictation Mode, a Volume Down press SHALL directly finish/wait for transcription and insert the selected text, auto-sending when the explicit setting is enabled. Rapid repeated Down presses SHALL be debounced and SHALL NOT toggle auto-send. An explicit disabled auto-send setting SHALL be preserved and explained after insertion.

#### Scenario: Repeated Down press
- **WHEN** Down is pressed again within the debounce interval
- **THEN** no second insertion is triggered and the auto-send preference is unchanged

#### Scenario: Explicit insertion-only setting
- **WHEN** auto-send is disabled in the dashboard
- **THEN** Down inserts without sending and reports that auto-send is off
