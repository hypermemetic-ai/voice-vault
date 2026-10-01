# Design

## Context

See proposal.md. VoiceVaultService starts the microphone after a 90 ms chirp delay, advertises recording before microphone start, and currently ignores start/stop failures. It uses a single network executor and an inactivity read timeout. Existing insertion generation guards must survive this repair. Dirty linked worktrees contain separate older changes and must not be reconciled.

## Goals / Non-Goals

Goals: terminal recorder failures, an absolute UI processing deadline, private useful errors, and retry availability after cancellation.
Non-goals: server cascade changes, real audio/profile exposure or device operation. The operator authorized scoped delivery and requested serving the APK at the existing URL.

## Decisions

- Own the delayed start Runnable and capture generation. Stop/cancel/destroy removes it; successful stop requires actual microphone start. Recorder release is independent of stop success. Merely logging failures leaves invalid recording state.
- Centralize terminal service failure so recording/processing, pending insertion, timer, recorder, wake lock and foreground lifetime are cleared consistently.
- Extract pure Java DictationUpload for HTTP transfer, category-only failures, response byte limit, cancellation flag, and finally cleanup. Keep JSON validation in the Android service. This supports loopback tests without Android/inference.
- Use a main-thread generation-guarded processing deadline; invalidate generation before canceling an upload. Use a cached worker pool so an old blocked network call cannot hold up a new recording. Disconnect off the main thread because HTTP disconnect may block while I/O owns connection locks. Existing inactivity timeouts remain a second bound.
- Validate ok and string text before accepting success. Preserve original local audio on upload failure; stop failures cannot promise valid audio.
- Test extracted production lifecycle methods with deterministic Android/recorder/network stubs plus actual loopback helper requests. Compile all Android sources against existing SDK into temporary classes without packaging/signing an APK.

## Risks / Trade-offs

- Device microphone/service rules require later device validation → describe test limits and leave signing/runtime untouched.
- Canceling a native network call may not immediately release its worker → invalidate UI callbacks immediately, disconnect on a worker, and keep finite socket timeouts.
- Ten minutes can be insufficient for unusually long recordings → preserve the existing client budget and local original; show timeout honestly.

## Migration Plan

Build using the existing stock script in an isolated copy, verify the expected signing certificate, retain the prior published APK, and atomically publish the repaired APK at the existing URL. Verify downloaded bytes against the signed build. The operator chose download delivery; device installation and microphone acceptance are not performed by the agent. Roll back to the retained prior APK if needed; no store migration.
