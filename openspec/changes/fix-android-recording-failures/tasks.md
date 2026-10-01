# Tasks

## 1. Android recording and upload recovery

- [x] 1.1 Repair recorder ownership/failure cleanup, processing deadline/cancellation, private upload feedback and strict response handling; add production lifecycle and loopback transport regressions and verify `node --test test/android-recording.test.mjs` passes.

## 2. Integration checks

- [x] 2.1 Compile Android sources to temporary classes without APK packaging, run existing hermetic audio/backend/Android checks, validate the OpenSpec change strictly, and record source validation and device/deployment limits in the change verification notes.

## 3. Authorized delivery

- [x] 3.1 Update AGENTS.md to honor operator-authorized app repair, build/publish the repaired APK with the existing certificate, retain the prior APK for rollback, and verify the served APK matches the new build.
