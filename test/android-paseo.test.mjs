import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, rmSync, readFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { spawnSync } from 'node:child_process';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const src = path.join(root, 'android/src/ai/hypermemetic/voicevault');
test('Paseo selectors and bounded echo/readiness policy', () => {
  const out = mkdtempSync(path.join(tmpdir(), 'paseo-test-'));
  try {
    for (const args of [
      ['javac', '--release', '11', '-d', out, path.join(src, 'PaseoSelection.java'), path.join(root, 'test/fixtures/PaseoHarness.java')],
      ['java', '-cp', out, 'ai.hypermemetic.voicevault.PaseoHarness'],
    ]) {
      const result = spawnSync(args[0], args.slice(1), { encoding: 'utf8' });
      assert.equal(result.status, 0, result.stderr || result.error?.message);
    }
  } finally { rmSync(out, { recursive: true, force: true }); }
});
test('native path is isolated from generic multi-send and retains clipboard/history', () => {
  const s = readFileSync(path.join(src, 'VoiceVaultKeyService.java'), 'utf8');
  assert.match(s, /if \(PaseoSelection\.isPaseo\(pkg\)\) \{\s*pasteIntoPaseo\(appRoot, completedTranscript\);\s*return;/);
  assert.match(s, /readPasteText\(transcript\)/);
  assert.match(s, /mPendingDictation\.complete\(recordingId, text, packageName, windowId\)/);
  assert.match(s, /mPaseoGeneration\+\+;\s*clearFlowComposer\(\);\s*boolean accepted/);
  assert.match(s, /if \(!editor\.text\.isEmpty\(\)\)/);
  assert.match(s, /paseoFeedback\("Copied —/);
  assert.match(s, /ACTION_IME_ENTER/); // unchanged other-app path
});
