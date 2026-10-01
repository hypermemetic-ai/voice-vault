# Proposal

## Why

Android users report Processing/Error after recording. Recent server error categories include uploads without audio streams; the client continues uploading after recorder stop failure and leaves delayed-start failure in a recording state.

## What Changes

- Fail failed microphone start/stop immediately, release resources, and prevent upload of unusable recordings.
- Cancel delayed microphone start on stop/cancel/destroy and guard callbacks by recording generation.
- Give transcription requests an absolute client deadline and release HTTP resources on every outcome.
- Distinguish invalid recording, transport failure, server rejection, and malformed responses in user feedback; retain original recordings on upload failure.
- Exercise production lifecycle methods with synthetic recorder/network stubs.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `bounded-transcription-and-insertion`: Android recorder failures and request expiry must terminate processing and clear pending insertion, with actionable failure feedback.

## Impact

Android recording service, shared transcription upload helper, and hermetic Java/Node tests. The operator authorized updating AGENTS.md and delivering the repair through a signed APK build and publication at the existing download URL, explicitly choosing download delivery instead of ADB installation. Preserve the existing signing certificate and local data. Existing linked worktrees remain untouched. Source validation and deployment evidence are recorded separately.
