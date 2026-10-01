import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, rmSync, readFileSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import path from 'node:path';
import { spawnSync } from 'node:child_process';

const service = 'android/src/ai/hypermemetic/voicevault/VoiceVaultKeyService.java';
const sourceDir = 'android/src/ai/hypermemetic/voicevault/';
const base = '74cc0c7703277a2b17172096e1c12efa7e038719';
function between(source, start, end) {
  assert.ok(source.includes(start) && source.includes(end));
  return source.slice(source.indexOf(start), source.indexOf(end, source.indexOf(start)));
}
test('Paseo autosend survives native layout and React readiness changes', () => {
  const out = mkdtempSync(path.join(tmpdir(), 'paseo-autosend-'));
  try {
    const source = readFileSync(service, 'utf8');
    const methods = between(source, '    private void insertCompletedRecording(', '    /** An IME')
      + between(source, '    private void clearPendingComposer()', '    private CharSequence readPasteText(')
      + between(source, '    public void onAccessibilityEvent(', '    @Override\n    public void onInterrupt()');
    const file = path.join(out, 'PaseoServiceHarness.java');
    writeFileSync(file, readFileSync('test/fixtures/PaseoServiceHarness.java', 'utf8').replace('    // PRODUCTION_METHODS', methods));
    for (const args of [
      ['javac', '--release', '11', '-d', out, `${sourceDir}PaseoSelection.java`, `${sourceDir}PendingDictation.java`, file],
      ['java', '-cp', out, 'ai.hypermemetic.voicevault.PaseoServiceHarness', 'autosend'],
    ]) {
      const result = spawnSync(args[0], args.slice(1), { encoding: 'utf8' });
      assert.equal(result.status, 0, result.stderr || result.error?.message);
    }
  } finally { rmSync(out, { recursive: true, force: true }); }
});
test('cached Android editor snapshot reproduces release 1.2.16 failure and current repair', () => {
  const out = mkdtempSync(path.join(tmpdir(), 'paseo-cache-'));
  try {
    const previous = spawnSync('git', ['show', `49a24848d2b769eb60308574d94ccd4fc81bae6d:${service}`], { encoding: 'utf8' });
    assert.equal(previous.status, 0, previous.stderr);
    for (const [revision, source] of [['previous', previous.stdout], ['current', readFileSync(service, 'utf8')]]) {
      const methods = between(source, '    private void insertCompletedRecording(', '    /** An IME')
        + between(source, '    private void clearPendingComposer()', '    private CharSequence readPasteText(')
        + between(source, '    public void onAccessibilityEvent(', '    @Override\n    public void onInterrupt()');
      const file = path.join(out, 'PaseoServiceHarness.java');
      writeFileSync(file, readFileSync('test/fixtures/PaseoServiceHarness.java', 'utf8').replace('    // PRODUCTION_METHODS', methods));
      const compile = spawnSync('javac', ['--release', '11', '-d', out, `${sourceDir}PaseoSelection.java`, `${sourceDir}PendingDictation.java`, file], { encoding: 'utf8' });
      assert.equal(compile.status, 0, compile.stderr);
      const run = spawnSync('java', ['-cp', out, 'ai.hypermemetic.voicevault.PaseoServiceHarness', 'stale-cache'], { encoding: 'utf8' });
      if (revision === 'previous') {
        assert.notEqual(run.status, 0, 'previous release must reproduce stale-cache cancellation');
        assert.match(run.stderr, /cached tree draft cannot override refreshed exact echo/);
      } else assert.equal(run.status, 0, run.stderr);
    }
  } finally { rmSync(out, { recursive: true, force: true }); }
});
test('production Paseo methods: same long-tree before/after query/timer model and focused safety', () => {
  const out = mkdtempSync(path.join(tmpdir(), 'paseo-latency-'));
  try {
    const before = spawnSync('git', ['show', `${base}:${service}`], { encoding: 'utf8' });
    assert.equal(before.status, 0, before.stderr);
    for (const [revision, source] of [['before', before.stdout], ['after', readFileSync(service, 'utf8')]]) {
      // The historical production methods use an attempt-count Gate API.
      const gateFile = path.join(out, 'PaseoSelection.java');
      if (revision === 'before') {
        const gate = spawnSync('git', ['show', `b8fe788:${sourceDir}PaseoSelection.java`], { encoding: 'utf8' });
        assert.equal(gate.status, 0, gate.stderr);
        writeFileSync(gateFile, gate.stdout);
      } else writeFileSync(gateFile, readFileSync(`${sourceDir}PaseoSelection.java`, 'utf8'));
      const methods = between(source, '    private void insertCompletedRecording(', '    /** An IME')
        + between(source, '    private void clearPendingComposer()', '    private CharSequence readPasteText(')
        + between(source, '    public void onAccessibilityEvent(', '    @Override\n    public void onInterrupt()');
      const harness = readFileSync('test/fixtures/PaseoServiceHarness.java', 'utf8').replace('    // PRODUCTION_METHODS', methods);
      const file = path.join(out, 'PaseoServiceHarness.java');
      writeFileSync(file, harness);
      for (const args of [
        ['javac', '--release', '11', '-d', out, gateFile, `${sourceDir}PendingDictation.java`, file],
        ['java', '-cp', out, 'ai.hypermemetic.voicevault.PaseoServiceHarness', revision],
      ]) {
        const result = spawnSync(args[0], args.slice(1), { encoding: 'utf8' });
        assert.equal(result.status, 0, result.stderr || result.error?.message);
        if (result.stdout) console.log(result.stdout.trim());
      }
    }
  } finally { rmSync(out, { recursive: true, force: true }); }
});
