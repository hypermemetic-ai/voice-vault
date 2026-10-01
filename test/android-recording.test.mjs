import test, { before, after } from "node:test";
import assert from "node:assert/strict";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { spawnSync } from "node:child_process";
import { fileURLToPath } from "node:url";

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
const sourceDir = path.join(root, "android/src/ai/hypermemetic/voicevault");
let temp;
before(() => {
  temp = fs.mkdtempSync(path.join(os.tmpdir(), "vv-recorder-"));
  const service = fs.readFileSync(path.join(sourceDir, "VoiceVaultService.java"), "utf8");
  const between = (start, end) => {
    const a = service.indexOf(start);
    const b = service.indexOf(end, a);
    assert.ok(a >= 0 && b > a);
    return service.slice(a, b);
  };
  const methods = between("    private void startRecording()", "    private void onTranscriptionSuccess(")
    + between("    private void cancelTimers()", "    private void sendExplicitBroadcast(")
    + between("    @Override\n    public void onDestroy()", "\n}");
  const fixture = fs.readFileSync(path.join(root, "test/fixtures/RecordingServiceHarness.java"), "utf8");
  const harness = path.join(temp, "RecordingServiceHarness.java");
  fs.writeFileSync(harness, fixture.replace("    // PRODUCTION_METHODS", methods));
  const compiled = spawnSync("javac", ["--release", "11", "-d", temp,
    path.join(sourceDir, "DictationUpload.java"), harness,
    path.join(root, "test/fixtures/DictationUploadHarness.java")], { encoding: "utf8" });
  assert.equal(compiled.status, 0, compiled.stderr || compiled.error?.message);
});
after(() => { if (temp) fs.rmSync(temp, { recursive: true, force: true }); });

for (const [scenario, title] of [
  ["prepare", "microphone prepare failure releases recording resources"],
  ["start", "delayed microphone start failure returns idle without upload"],
  ["early-stop", "stopping before the chirp delay prevents empty-audio upload"],
  ["stop", "recorder stop failure releases resources and skips upload"],
  ["stale-start", "canceled delayed start cannot start a replacement recorder"],
  ["timeout", "processing deadline cancels upload and ignores late output after retry"],
  ["cancel", "canceling processing ignores late failure and releases connection"],
  ["destroy", "service destruction releases the microphone, wake lock and delayed callbacks"],
  ["success", "valid response completes once and removes the processing deadline"],
  ["okfalse", "ok:false is a terminal error rather than no speech"],
  ["malformed", "malformed success payload clears processing and pending insertion"],
  ["badjson", "invalid JSON clears processing and pending insertion"],
]) {
  test(title, () => {
    const result = spawnSync("java", ["-cp", temp,
      "ai.hypermemetic.voicevault.RecordingServiceHarness", scenario], { encoding: "utf8", timeout: 10000 });
    assert.equal(result.status, 0, result.stderr || result.error?.message);
  });
}

test("real HTTP helper handles synthetic uploads, rejection, timeout, cancellation and response limits", () => {
  const result = spawnSync("java", ["-cp", temp,
    "ai.hypermemetic.voicevault.DictationUploadHarness"], { encoding: "utf8", timeout: 15000 });
  assert.equal(result.status, 0, result.stderr || result.error?.message);
});
