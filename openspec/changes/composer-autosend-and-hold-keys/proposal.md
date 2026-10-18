## Why

Daily dictation now targets T3 Code (web composer in Chrome), not Paseo. The careful
single-dispatch composer flow only ran for Paseo packages; every other app took a
legacy path that **replaced** the user's draft with `ACTION_SET_TEXT`, then fired
`ACTION_IME_ENTER` twice and clicked the first node anywhere in the active window whose
description *contained* "send" at fixed 60/240 ms delays. T3 Code's submit control is
named `Submit message`, `Queue message` or `Steer message` depending on state and is
enabled only after React processes the edit, so auto-send was intermittent and drafts
were lost. Auto-send state was invisible outside the dashboard, the app played sounds
the user does not want, and double presses on the volume keys are hard to perform.

## What Changes

- Every app uses the composer flow: insertion is **appended** to the existing draft,
  then that composer's own submit control (exact accessible names, including T3 Code's)
  is clicked once when enabled, and confirmation is reported (`Sent ✓` / `Queued ✓` /
  `Steered ✓`). Echo comparison tolerates web whitespace differences; web placeholders
  reported as text are not drafts. A bounded subtree walk covers accessibility
  providers whose text search returns nothing, and the search widens at most two
  ancestor levels past the nearest control group.
- The saved composer stays valid when the keyboard closes or focus moves to a button;
  focus on a *different* input still cancels.
- The pill shows `AUTO-SEND` / `NO SEND` whenever Dictation Mode is on, including
  while recording/processing, and refreshes on any auto-send change.
- All sounds removed (haptics and the pill remain).
- **BREAKING (gesture):** double presses become 500 ms holds. Taps act on release.
  Mode off: hold Up enables Dictation Mode; an Up tap raises volume with the slider;
  Down is untouched. Mode on: tap Up record/stop, hold Up disable; tap Down
  insert/send, hold Down toggle auto-send.
- Version 1.4.0 (versionCode 26).

## Impact

Android only: `VoiceVaultKeyService`, `PaseoSelection`, `FloatingPillOverlay`, pill
layout, `VoiceVaultService`; `SoundEffects` and `VolumeUpTiming` removed. No backend,
storage, recording-recovery or signing change.
