# Verification

## Diagnosis and scope

The operator confirmed that Volume Down inserts text but does not send in Paseo. A new extracted production-method regression failed before the repair with `normal multiline layout must revalidate and send once`. After the repair it passes along with burst content events before Send becomes enabled, missing optional toolbar peers, a terminal readiness deadline and production Down key dispatch/debounce tests.

Pinned local Paseo source at `/tmp/paseo-dictation-source` supports exact primary labels and a separate toolbar below the input, optional controls and intrinsic multiline resize. This is evidence for the repaired failure modes, not proof of the exact physical phone event sequence or its saved auto-send setting.

Removed Down's hidden setting toggle and seven-attempt readiness budget. Existing explicit auto-send preference values are preserved; disabled auto-send now reports `Inserted — auto-send OFF`.

## Source checks

- 73 hermetic WAV/VAD/backend/Android state, dictation, keys, pill, Paseo, timing and recording tests passed with `VOICE_VAULT_SPEAKER_AUTODOWNLOAD=off`.
- Tests execute extracted production service/key methods and real loopback upload behavior with synthetic data. The Paseo tree/IPC harness is a deterministic model, not a device latency measurement or a React Native runtime.
- `openspec validate fix-paseo-volume-down-send --strict` and `git diff --check` passed.
- Native fixture expectations were updated for the two-second deadline and insertion-only feedback; no new native/emulator/physical-phone run is claimed. Historical native evidence does not certify the newer 1.2.16 production source.

## Delivery

Release 1.2.16 (versionCode 20) built successfully with the stock Android script in an isolated copy. Original certificate verified: `24af79af967d37806aac2b8d822b4381563764f076d9b37c1eeff6f1d8141df0`.

- Rollback artifact: `/home/qqp/.local/state/voice-vault-repair-20261001/VoiceVault-1.2.15-before-paseo-repair.apk`.
- Release artifact: `/home/qqp/.local/state/voice-vault-repair-20261001/VoiceVault-1.2.16.apk`.
- Atomically published to `public/voice-vault.apk`; download `https://qq-box.tail580136.ts.net:3443/voice-vault.apk?v=20` returned HTTP 200 and 210942 bytes matching the signed build.
- APK SHA-256: `b5439b458c336628dd1f5e3579daed731a08a362cc98a7caa315859bc5dc7ec6`.

No ADB/device installation, real chat send or backend restart was performed. Existing explicit preferences remain intact. The operator's updated working agreements are applied in AGENTS.md; scoped source/test/release Git delivery follows the established PR merge path, with unrelated readiness migration files left for their owner.
