# Native readiness — voice-vault

## Canonical baseline and source map
OpenSpec capability specs govern the evolving current baseline. Existing exact
contracts, client policy and technical/rationale documents below are retained
by reference, not duplicated as a rival specification. Proposed requirements
and stale historical status do not become delivered behavior through this map.

- [`local-speaker-boundary`](../openspec/specs/local-speaker-boundary/spec.md): Maintain local speaker/profile processing and honest optional-gate fallback. Evidence: `README.md`, `src/gate.mjs`, `src/profile.mjs`, `src/speaker.mjs`, `src/transcriber.mjs`, `test/gate.test.mjs`, `test/speaker.test.mjs` (read, not model-executed here).
- [`bounded-transcription-and-insertion`](../openspec/specs/bounded-transcription-and-insertion/spec.md): Avoid wedged backends and stale Android insertion without claiming real-device latency. Evidence: `src/transcriber.mjs`, `test/transcriber.test.mjs`, `android/src/ai/hypermemetic/voicevault/PendingDictation.java`, `android/src/ai/hypermemetic/voicevault/FloatingStatus.java`, `test/android-state.test.mjs`.

## Source/test orientation
Read README.md; backend is src/server.mjs/transcriber.mjs, speaker/profile/gate/VAD/WAV modules and SQLite storage. Android source/build routes are in android/; see native-Paseo fixtures under test/native-paseo. For hermetic readiness use `node --test test/wav.test.mjs test/vad.test.mjs test/transcriber.test.mjs test/android-state.test.mjs` with speaker auto-download off. Full npm test, test:e2e and verify have distinct dependency/hardware tiers.

## Safety and ownership
Keep recordings, transcripts and voice profiles local/private; no real audio, profiles or stores in development agent/cloud inputs. Tests must use synthetic/temporary state, stub backends and no model downloads. Preserve signing identity exactly; do not rebuild/install APKs, operate phones/microphones or call real inference for docs. scripts/verify-ticket.mjs is product verification, not obsolete orchestration. Existing server/desktop/AGY/Pi clients and dirty linked Android worktrees remain: do not change runtime or reconcile them by reset.

## Verification recorded 2026-10-01
Executed: `node --test test/wav.test.mjs test/vad.test.mjs test/transcriber.test.mjs test/android-state.test.mjs`. Exit 0; elapsed 4.08 s. Passed.

Other linked tests are existing evidence routes, NOT claimed run in this phase.
No broad regression suite, deployment/build, account/provider/model replacement
or primary-session launch was performed. Stock OpenSpec initialization and strict
spec validation receipts, generated-skill hashes, preservation manifests and
scoped migration patches are private at
`/home/qqp/.local/state/workflow-brownfield-20261001/voice-vault/`.

## Coverage gaps and next-use boundaries
Recording durability/history/API authentication, native-Paseo/clipboard adapter
and full UI/device lifecycle coverage are not exhaustively baselined here.
Existing source/runbooks/tests remain authoritative supporting routes.
`src/transcriber.mjs` currently imports the endpoint recognizer by absolute path
from `/home/qqp/projects/qq-dictation/src/recognizer.mjs`: that checkout is a real
source dependency, not retirement debris. Portability is not fixed by readiness.
Speaker
model/fixture tests are named evidence opportunities, not claimed executed by
this migration. Local data/privacy/signing ownership is preserved.

Primary product source is unchanged, but two dirty linked worktrees retain substantive Android/APK work. .architect state and proposed Orca repair evidence are retained while local clients/dependencies remain; no new primary stream was launched. Deterministic VAD/WAV, stub backend failure and pure Java state tests were run, not ONNX/speaker calibration, real GPU/device/native-Paseo or latency acceptance. README historical measurements and reliability language are not newly established live evidence.

Git delivery is outstanding: migration changes are intentionally uncommitted.
Do not add all dirty/untracked product files merely to deliver this documentation.
Use the private scoped receipt after review; preserve the existing index and user work.
