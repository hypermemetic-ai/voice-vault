# Repair Paseo screen discovery and delayed readiness

## Why

The user still sees inserted text without submission in 1.2.18. That build
requires an application-specific composer view ID and starts a two-second
readiness timeout before inserting text. Synthetic Android tests reproduce
both weaknesses; neither establishes the exact state of the user's phone.

## What Changes

Use OS input focus and the nearest native input group with accessible controls,
without application view IDs, placeholder identity or layout assumptions.
Refresh exact submit semantics before selection and activation. Start a separate
five-second readiness allowance at the first confirmed insertion echo, with no
mandatory delay when Send is ready. Preserve cancellation, one accepted dispatch,
local confirmation, feedback and hardware shortcuts. Deliver signed 1.2.19 via
the existing APK route.

## Impact

Android insertion/submit selection, synthetic fixtures, release metadata and APK.
No backend changes, phone operation, real chats, real audio or model downloads.
