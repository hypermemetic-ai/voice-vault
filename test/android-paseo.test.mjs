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
  assert.match(s, /mFlowDispatched = true/);
  assert.match(s, /tapPaseo\(target, generation\)/);
  assert.match(s, /Send not confirmed — tap Send/);
  const adapter = s.split('private static final class PaseoTree')[1].split('private void paseoFeedback')[0];
  assert.match(adapter, /n\.label = desc != null \? desc\.toString\(\) : hint != null \? hint\.toString\(\)/);
  assert.match(adapter, /n\.text = PaseoSelection\.draftText\(n\.editable,\s*Build\.VERSION\.SDK_INT >= Build\.VERSION_CODES\.O && info\.isShowingHintText\(\), text\)/);
  const entry = s.slice(s.indexOf('private void pasteIntoPaseo('), s.indexOf('private boolean validPaseo('));
  const insertion = s.split('private void insertPaseo(')[1].split('private void checkPaseo(')[0];
  assert.match(entry, /PaseoSelection\.composer\(tree\.root\)/);
  assert.match(insertion, /currentComposer\(root, mFlowComposer\)/);
  assert.doesNotMatch(insertion, /new PaseoTree|postDelayed/);
  assert.match(s, /saved\.refresh\(\)/);
  assert.match(s, /saved\.equals\(focused\)/);
  assert.match(insertion, /ACTION_SET_TEXT/);
  assert.doesNotMatch(entry + insertion, /draft already exists|draft exists|editor\.text\.isEmpty\(\)/);
  assert.match(s, /mFlowGate\.check\(tree == null \? null : tree\.root, editor, expected,/);
  assert.match(s, /target\.refresh\(\)/);
  assert.match(s, /new PaseoTree\(root, band\)/);
  const observation = s.split('private void observePaseoFlow()')[1].split('private CharSequence readPasteText')[0];
  assert.doesNotMatch(observation, /new PaseoTree/);
  assert.match(s, /paseoFeedback\("Copied —/);
  assert.match(s, /ACTION_IME_ENTER/); // unchanged other-app path
});
