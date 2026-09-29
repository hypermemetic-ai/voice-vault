import test, { before, after } from "node:test";
import assert from "node:assert/strict";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { spawnSync } from "node:child_process";

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
const source = path.join(root, "android/src/ai/hypermemetic/voicevault");
let classes;
before(() => {
  classes = fs.mkdtempSync(path.join(os.tmpdir(), "voice-vault-state-"));
  const compiled = spawnSync("javac", ["--release", "11", "-d", classes,
    path.join(source, "FloatingStatus.java"),
    path.join(source, "PendingDictation.java"),
    path.join(root, "test/fixtures/AndroidStateHarness.java")], { encoding: "utf8" });
  assert.equal(compiled.status, 0, compiled.error?.message || compiled.stderr);
});
after(() => { if (classes) fs.rmSync(classes, { recursive: true, force: true }); });

for (const [scenario, description] of [
  ["timer", "feedback restores the latest recording timer"],
  ["processing", "feedback restores processing when recording ends underneath it"],
  ["completion", "feedback preserves completion lifetime without resurrecting expired status"],
  ["replacement", "rapid toggles replace feedback and restart its lifetime"],
  ["idle", "idle and mode-off feedback dismisses without creating a recording"],
  ["new-recording", "old completion timeout cannot hide a new recording"],
  ["fresh", "pending insertion consumes only the fresh recording once"],
  ["context", "pending insertion is canceled when the target app or window changes"],
  ["failure", "failed and empty transcriptions clear pending insertion"],
  ["cancel", "late results after cancellation cannot satisfy a new insertion request"],
]) {
  test(description, () => {
    const result = spawnSync("java", ["-cp", classes,
      "ai.hypermemetic.voicevault.AndroidStateHarness", scenario], { encoding: "utf8" });
    assert.equal(result.status, 0, result.error?.message || result.stderr);
  });
}
