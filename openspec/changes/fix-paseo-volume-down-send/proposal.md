# Proposal

## Why

The operator reports that Volume Down inserts text into Paseo but does not send it. Production code cancels on normal layout changes, spends retry attempts on content events, requires optional toolbar peers, and lets a second Down press disable auto-send.

## What Changes

- Keep same-composer insertion alive through normal resizing and wait for asynchronous Send readiness within a real time budget.
- Select one exact, visible, enabled primary send control in the local lower toolbar without depending on optional neighbors or exported wrapper hierarchy.
- Make Down a direct finish/insert action; debounce repeated presses and remove its hidden auto-send toggle. Keep the explicit auto-send checkbox and explain insertion-only when disabled.
- Add production-method reproductions and key dispatch tests; publish a signed 1.2.16 APK at the existing URL.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `bounded-transcription-and-insertion`: Reliable bounded Paseo submission after Volume Down, while preserving destination ownership and single dispatch.

## Impact

Android key service, Paseo selector/gate, setup wording and release version, synthetic Java tests, and published APK. Retain all existing recording fixes, signing identity, private data and unrelated worktrees. No ADB requirement, real messages or backend changes.
