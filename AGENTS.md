# Working agreements

Complete the requested usable outcome. A request to change a repository includes normal Git delivery: commit and push your own completed, scoped changes to its established remote and follow its required PR/merge path. Carry through ordinary activation when the requested outcome requires it. Do not seek fresh permission merely because the next step is called commit, push, merge or activation. Escalate concrete conflicts, scope expansion or disruptive effects; preserve unrelated work and running tasks.

Honor specific privacy/release rules and explicit local-only requests. Smoke-test, readiness-only and worker-assignment restrictions apply to those assignments; do not extend them to subsequently authorized native work.

For repository work, aim for zero unattended Git work. At task start and before completion, run `node /home/qqp/projects/qq-workflows/scripts/git-status.mjs` from the checkout being worked on. Use `--cached` when an offline check is appropriate; remote freshness is then unknown. Investigate other worktrees or PRs only when the task involves them.

Report what was delivered and verified. For work remaining, identify its owner and next step or concrete blocker. An omitted authorized delivery step is unfinished work, not an accomplishment.

--- project-doc ---

# voice-vault

Read README.md; backend is src/server.mjs/transcriber.mjs, speaker/profile/gate/VAD/WAV modules and SQLite storage. Android source/build routes are in android/; see native-Paseo fixtures under test/native-paseo. For hermetic readiness use `node --test test/wav.test.mjs test/vad.test.mjs test/transcriber.test.mjs test/android-state.test.mjs` with speaker auto-download off. Full npm test, test:e2e and verify have distinct dependency/hardware tiers.

Keep recordings, transcripts and voice profiles local/private; no real audio, profiles or stores in development agent/cloud inputs. Tests must use synthetic/temporary state, stub backends and no model downloads. Preserve signing identity exactly; do not rebuild/install APKs, operate phones/microphones or call real inference for docs. scripts/verify-ticket.mjs is product verification, not obsolete orchestration. Existing server/desktop/AGY/Pi clients and dirty linked Android worktrees remain: do not change runtime or reconcile them by reset.

## Native entry
Use the existing stock Codex login/model configuration from this repository root.
Current capability baselines are in `openspec/specs/`; exact supporting contracts
and coverage limits are mapped in `docs/native-readiness.md`. OpenSpec uses the
stock `spec-driven` schema.
Do not add an orchestrator, custom schema, wrappers or CLI dependencies.
Preserve unrelated local edits, staged work, branches and worktrees. Repository
readiness does not authorize deployment, shared-runtime changes or a second
concurrent primary conversation.
