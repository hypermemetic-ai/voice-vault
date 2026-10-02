# Design

The OS focused input establishes the native editor identity. Unfocused insertion
falls back to unique editable discovery. Each readiness check queries ancestors
for exact accessible Send/Submit/Queue actions, refreshes candidates and validates
native membership. The nearest group with controls remains the boundary even
while it contains Stop rather than Send; an unrelated global action is excluded
when Android exposes that boundary. Ambiguity prevents activation.

SET_TEXT confirmation retains a two-second bound. The first exact native draft
echo starts a separate five-second Send readiness bound. Repeated content events
do not extend it. A content event schedules an immediate check; otherwise a
100ms poll rereads live state. Ready controls dispatch immediately. Stop is never
an eligible action. Manual draft changes, remounts and context changes cancel.

ACTION_CLICK remains first; one gesture is allowed only after rejection. Accepted
activation is never retried. Composer reset confirms local submission, not remote
server acceptance. Android accessibility semantics remain a dependency: a UI
with no accessible submit action or no distinguishable input group may require
manual sending. No claim is made about the user's phone before testing this APK.
