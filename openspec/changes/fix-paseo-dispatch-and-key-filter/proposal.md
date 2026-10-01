# Proposal

## Why
The operator reports that text is inserted but not sent, Down can expose the Android volume UI, and the removal of double-Down broke a used control. The existing synthetic tests miss the key-filter timeout and automatic scrolling/overlay events.

## What Changes
- Restore double-Down auto-send toggling with explicit feedback and a bounded single-press delay.
- Return consumed volume keys before accessibility work begins.
- Revalidate the composer on automatic scrolling/system overlays instead of treating every event as navigation.
- Activate the uniquely validated Paseo Send control with one native tap and report dispatch/confirmation failure explicitly.
- Verify actual Android actions in an isolated offline emulator and publish the signed update.

## Capabilities

### New Capabilities
None.

### Modified Capabilities
- `bounded-transcription-and-insertion`: responsive volume shortcuts, explicit send status and single dispatch despite ordinary UI events.

## Impact
Android accessibility service/configuration, shortcut timing, offline fixtures, setup text, signed public APK. No backend changes, private data inputs, real messages or phone installation.
