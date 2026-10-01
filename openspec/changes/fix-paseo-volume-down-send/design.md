# Design

## Context

See proposal.md. Local pinned Paseo source uses exact primary labels and a separate toolbar below the editor; optional voice controls and exported wrappers vary. Existing tests cover static synthetic layouts but production cancels when geometry changes immediately before click, requires neighboring nodes in flattened layouts, and limits readiness by event-driven attempt count. Double Down changes the same preference used by the dashboard.

## Goals / Non-Goals

Goals: reliable single submit during normal React Native rendering and straightforward Down behavior.
Non-goals: altering Paseo, sending real messages, changing generic app submission, migrating deliberate auto-send settings or requiring phone debugging.

## Decisions

- Preserve exact package, primary labels, local below-editor right-edge geometry, unique candidate, native identity and exact text checks. Remove optional sibling/ancestor requirements that depend on accessibility export shape. Broad substring matching and arbitrary ancestor clicks remain excluded.
- Preserve a saved composer identity across ordinary geometry changes. If its bounds change between selecting and refreshing the target, schedule a fresh bounded selection instead of canceling. Text edits/remounts/navigation still cancel.
- Replace seven event-driven attempts with a two-second uptime deadline. Checks run immediately after insertion/echo, coalesce events, and keep one fallback timer capped by the remaining budget. This allows quick success without imposing sleep or letting event bursts consume time that did not elapse.
- Down directly invokes finish/insert and suppresses repeats within 220 ms; auto-send is controlled by the explicit dashboard checkbox. Preserve existing preference values and add insertion-only feedback to explain disabled auto-send.
- Add extracted production-method tests for layout change, burst events, optional-peer absence and key dispatch, retaining ambiguity/manual edit/remount/rejected click coverage.

## Risks / Trade-offs

- Actual phone accessibility export is unavailable → reproduce the failures in production methods and describe native/device validation limits honestly.
- Looser ancestry selection → retain exact labels, unique visible/enabled candidate and strict local geometry; never retry an attempted dispatch.
- Existing auto-send OFF preference may have come from the old hidden gesture → preserve it rather than overriding deliberate choices, and make OFF visible after insertion.

## Migration Plan

Version 1.2.16 (code 20), isolated stock build, unchanged certificate, atomic publication, retained 1.2.15 rollback APK and served-byte hash check. Operator installs from the same URL; no ADB request or device operation.
