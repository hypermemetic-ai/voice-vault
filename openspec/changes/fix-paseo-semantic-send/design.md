# Design

Paseo source exposes testID `message-input-root` around its composer. React Native
0.81.5 exports this as a view ID and preserves the named ancestor. A prototype
in the public v0.10.2 native host confirms that ACTION_SET_TEXT reaches the JS
state, the new Send Pressable belongs to this ancestor, and ACTION_CLICK invokes
the mock handler once. Request Android's report-view-IDs flag.

Walk the saved editor's refreshed parent chain within the same app/window to
that named ancestor. Refresh and read only its subtree; use native handle
equality to associate the cached tree editor with the authoritative refreshed
editor. Select one visible, enabled, clickable exact primary action under that
owner, regardless of bounds or child order. Refresh/revalidate action identity,
semantic eligibility, ancestry and exact draft immediately before dispatch.
Keep pre/post-dispatch deadlines and cancellation. Never infer server acceptance
from a cleared native draft.

Use ACTION_CLICK first. A false return permits one live-control gesture; a true
return never permits a second activation, even if the expected reset is absent.
Different failure branches have different pill feedback. A missing named owner
fails safely rather than searching global buttons or guessing coordinates.

Two verified weaknesses: pinned 1.2.17 rejects a functional moved RN primary,
and a controlled cached disabled-node snapshot prevents selection despite a
ready refreshed node. These are reproductions of implementation weaknesses,
not identification of the operator's exact phone failure. Full Paseo JS/server
and the physical phone remain outside these tests.

Build in an isolated directory; preserve certificate, retain old APK, atomically
publish, verify HTTPS served bytes, commit/push and merge a scoped PR. Preserve
unrelated untracked readiness documents and linked worktrees.
