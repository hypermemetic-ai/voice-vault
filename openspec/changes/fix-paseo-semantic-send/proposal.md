# Why

The operator still gets `Inserted — send manually` on 1.2.17, although manually
pressing Paseo Send immediately submits the inserted draft. The pill merges
selection failure, an exception and rejected activation, so it does not prove
which branch failed. The selector relies on a right-aligned lower toolbar and
reads cached eligibility before refreshing the control. Native Java widgets
alone cannot validate React Native export/bridge behavior.

# What Changes

Use Paseo's stable `message-input-root` accessibility identifier and native
ancestry to bind Send to the saved focused composer. Refresh the composer
subtree before checking eligibility; remove layout rules and the screen-band
query. Prefer semantic ACTION_CLICK; a rejected action permits one native tap,
while accepted activation is never retried. Report unavailable/disabled/ambiguous
controls and action rejection distinctly. Verify actual RN 0.81.5 native
TextInput/Pressable behavior using synthetic in-memory submission, then publish
a signed 1.2.18 APK through the existing serving route.

# Capabilities

Modify bounded-transcription-and-insertion; retain recording, privacy, key
shortcuts and exact-draft/context cancellation contracts.

# Impact

Android accessibility service/selector, optional offline fixtures, release APK.
No backend/Paseo runtime change, real chat, real audio, device install or model
download. Actual phone failure remains unobserved; test evidence must state limits.
