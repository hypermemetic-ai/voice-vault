## 1. Composer auto-send

- [x] 1.1 Route every app through the composer flow; remove IME-enter / broad-button path
- [x] 1.2 Append dictation to the existing draft; normalized echo; placeholder-as-text handling
- [x] 1.3 T3 Code submit names; bounded walk when text search is empty; limited widening
- [x] 1.4 Saved composer survives closed keyboard / button focus; other focused input cancels
- [x] 1.5 Sent / Queued / Steered confirmation; 3 s confirmation budget

## 2. Feedback and keys

- [x] 2.1 Pill shows AUTO-SEND / NO SEND in Dictation Mode, refreshed on preference change
- [x] 2.2 Remove sounds
- [x] 2.3 Replace double presses with 500 ms holds; taps on release; mode-off Up tap raises volume
- [x] 2.4 Update in-app help, README, version

## 3. Verification and delivery

- [x] 3.1 Synthetic harness coverage (web composer, keys) and hermetic suite
- [x] 3.2 Signed APK published with unchanged certificate via the established PR path
- [ ] 3.3 Operator acceptance on device in T3 Code (operator-owned)
