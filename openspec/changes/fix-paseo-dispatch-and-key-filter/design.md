# Design

## Context
See proposal.md. The current Down callback synchronously performs binder tree queries before returning true. Android key filtering has a deadline. The Paseo flow also cancels on any non-editor scroll and retains Copied after an accepted click. Native Java fixture success does not establish React Native behavior.

## Goals / Non-Goals
Goals: restore the requested shortcut, consume keys promptly, activate a validated native control once, and display actionable outcomes.
Non-goals: changes to Paseo/server, real audio/messages, or replacing unrelated work.

## Decisions
- Queue single-Down work after its 220 ms double window; perform no tree query in the key callback. A second Down cancels the pending single and toggles the saved auto-send preference with feedback. Track consumed key-up across mode changes.
- Check exact saved native composer identity/text after scrolling or system-window changes; these events alone are not proof of manual navigation. Continue to cancel on actual app/window change, editor remount/edit/clear or manual app-button click.
- Use the refreshed native editor for draft/geometry throughout readiness. Android cached tree snapshots can retain the old draft immediately after SET_TEXT; observing them after fresh exact echo caused reproducible cancellation in 1.2.16. Cache data must not override refreshed ownership.
- Prefer one accessibility gesture at the refreshed unique exact local Send control. A native tap follows the same touch route as the operator and avoids treating ACTION_CLICK acceptance as React handler delivery. If Android rejects gesture dispatch before a tap is queued, fall back to one ACTION_CLICK. Never retry accepted dispatch, including when confirmation is absent.
- Observe the original editor after dispatch within a separate short deadline. Empty/reset editor means Submitted to Paseo, not server acceptance. Unchanged text produces Send not confirmed; canceled/rejected actions show a manual-send instruction. Intermediate statuses replace Copied.
- Exercise the production service and real key injection/taps against a no-network mock composer on an isolated emulator, including a target whose accessibility click is accepted but does not submit. Keep JVM safety coverage and clearly state RN/physical-device limits.

## Risks / Trade-offs
Gesture may be canceled or obscured → no retry and explicit feedback; verify Android native tap callbacks. User may navigate after dispatch → stop observing without touching the new context. Double-Down requires a short single-press delay → preserve the operator-requested gesture. Actual Paseo on the phone remains unobserved → do not claim device certification.

## Migration Plan
Build 1.2.17/code 21 in isolation, preserve original certificate, retain previous APK, atomically publish and verify served bytes; deliver through scoped PR and merge.
