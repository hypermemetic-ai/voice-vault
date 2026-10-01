## ADDED Requirements

### Requirement: Responsive Down shortcut
In Dictation Mode, Android SHALL consume Up and Down press/release events promptly before querying the target app. Single Down SHALL finish/insert after the double-press window; double Down SHALL toggle the persisted auto-send preference without inserting and show its new value.

#### Scenario: Slow target application
- **WHEN** Down is pressed while accessibility queries are slow
- **THEN** the key is consumed before target queries and no system volume adjustment is requested

#### Scenario: Toggle auto-send
- **WHEN** Down is pressed twice within 220 ms
- **THEN** auto-send toggles once, its new value appears in the pill, and no insertion occurs

### Requirement: Ordinary composer events preserve fresh ownership
Automatic scrolling and keyboard/system overlay events SHALL preserve a pending insertion/send when the same target app/window and native composer remain valid. Actual target replacement or manual draft changes SHALL cancel automation.

#### Scenario: Composer scrolls after insertion
- **WHEN** the inserted text echoes and the same composer scrolls or resizes
- **THEN** the request remains eligible for one validated send

### Requirement: Explicit bounded native submission
Paseo submission SHALL activate only a unique exact local primary action for the expected draft, at most once after accepted dispatch. It SHALL display send progress and a bounded confirmation or actionable failure; native activation SHALL NOT be reported as server acceptance.

#### Scenario: Native click is ineffective
- **WHEN** a validated Send control supports native touch but its accessibility click does not invoke submission
- **THEN** a single native tap activates the control

#### Scenario: Dispatch does not clear the draft
- **WHEN** an accepted activation leaves the expected draft unchanged through the confirmation deadline
- **THEN** the pill reports unconfirmed send with a manual-send instruction and automation does not dispatch again

### Requirement: Refreshed draft is authoritative
Once Android reports the exact inserted draft from a refreshed native editor, an older cached tree snapshot SHALL NOT be treated as a manual edit or clear. The flow SHALL use refreshed identity, text and geometry before activation.

#### Scenario: Tree still contains the previous draft
- **WHEN** a refreshed composer contains the inserted text but Android child enumeration still returns the previous text
- **THEN** the older snapshot does not cancel the send and the uniquely validated action remains eligible
