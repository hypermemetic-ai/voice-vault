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
    + between("    private void rememberServerCopy(", "    private void sendExplicitBroadcast(")
    + between("    @Override\n    public void onDestroy()", "\n}");
  const fixture = fs.readFileSync(path.join(root, "test/fixtures/RecordingServiceHarness.java"), "utf8");
  const harness = path.join(temp, "RecordingServiceHarness.java");
  fs.writeFileSync(harness, fixture.replace("    // PRODUCTION_METHODS", methods));
  const compiled = spawnSync("javac", ["--release", "11", "-d", temp,
    path.join(sourceDir, "RecordingIndex.java"), path.join(sourceDir, "DictationUpload.java"), harness,
    path.join(root, "test/fixtures/DictationUploadHarness.java")], { encoding: "utf8" });
  assert.equal(compiled.status, 0, compiled.stderr || compiled.error?.message);
});
after(() => { if (temp) fs.rmSync(temp, { recursive: true, force: true }); });

for (const [scenario, title] of [
  ["storage-intent", "capture intent storage failure skips microphone and upload"],
  ["storage-finalize", "finalization metadata failure retains original and skips upload"],
  ["recover-repeat", "service recovery owns one attempt and avoids insertion on success/destroy"],
  ["recover-destroy", "recovery destruction invalidates late output and retains manually retryable audio"],
  ["recover-cancel", "recovery cancellation preserves original and ignores late result"],
  ["recover-busy", "recovery cannot interrupt active dictation"],
  ["recover-failed", "repeat recovery failure retains original and original capture time"],
  ["prepare", "microphone prepare failure releases recording resources"],
  ["start", "delayed microphone start failure returns idle without upload"],
  ["early-stop", "stopping before the chirp delay prevents empty-audio upload"],
  ["capture-failure", "capture read failure releases resources and skips upload"],
  ["stale-start", "canceled delayed start cannot start a replacement recorder"],
  ["timeout", "processing deadline cancels upload and ignores late output after retry"],
  ["cancel", "canceling processing ignores late failure and releases connection"],
  ["destroy", "service destruction releases the microphone, wake lock and delayed callbacks"],
  ["success", "a verified finish completes once and removes the processing deadline"],
  ["fallback", "an incomplete stream uploads the whole WAV through the existing path"],
  ["finish-rejected", "a non-200 finish uploads the whole WAV through the existing path"],
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

const readSource = (name) => fs.readFileSync(path.join(sourceDir, name), "utf8");

test("capture is AudioRecord into a durable canonical WAV, never MediaRecorder", () => {
  const service = readSource("VoiceVaultService.java");
  assert.match(service, /new AudioRecord\(MediaRecorder\.AudioSource\.VOICE_RECOGNITION,/);
  assert.match(service, /AudioFormat\.CHANNEL_IN_MONO/);
  assert.match(service, /AudioFormat\.ENCODING_PCM_16BIT/);
  assert.match(service, /getMinBufferSize\(SAMPLE_RATE/);
  assert.match(service, /"dictation_" \+ sRecordingStartTime \+ "\.wav"/);
  assert.match(service, /mCaptureOut\.write\(WavHeader\.bytes\(0\)\)/);
  assert.match(service, /WavHeader\.finish\(mCurrentAudioFile, pcmBytes\)/);
  assert.doesNotMatch(service, /new MediaRecorder\(|setOutputFormat|setAudioEncoder|setOutputFile/);
  // Capture runs on its own thread and publishes complete, even PCM counts.
  assert.match(service, /new Thread\(\(\) -> captureLoop\(recorder, out, generation\)/);
  assert.match(service, /count = read & ~1/);
  // The old pending files still resolve, and WAV files gain a header duration.
  const store = readSource("RecordingStore.java");
  assert.match(store, /dictation_\[0-9\]\{10,17\}\\\\\.\(m4a\|mp4\|wav\)/);
  assert.match(store, /WavHeader\.durationMs\(file\)/);
});

test("canonical WAV header matches encodePcm16Wav exactly", () => {
  const wav = readSource("WavHeader.java");
  assert.match(wav, /static final int SIZE = 44;/);
  assert.match(wav, /ascii\(header, 0, "RIFF"\)/);
  assert.match(wav, /uint32\(header, 4, 36 \+ n\)/);
  assert.match(wav, /ascii\(header, 8, "WAVE"\)/);
  assert.match(wav, /ascii\(header, 12, "fmt "\)/);
  assert.match(wav, /uint32\(header, 16, 16\)/);
  assert.match(wav, /uint16\(header, 20, 1\)/);
  assert.match(wav, /uint16\(header, 22, 1\)/);
  assert.match(wav, /uint32\(header, 24, 16000\)/);
  assert.match(wav, /uint32\(header, 28, 32000\)/);
  assert.match(wav, /uint16\(header, 32, 2\)/);
  assert.match(wav, /uint16\(header, 34, 16\)/);
  assert.match(wav, /ascii\(header, 36, "data"\)/);
  assert.match(wav, /uint32\(header, 40, n\)/);
});

test("streamer appends PCM offsets and finish sends the verified WAV identity", () => {
  const streamer = readSource("RecordingStreamer.java");
  const upload = readSource("DictationUpload.java");
  const api = readSource("VoiceVaultApi.java");
  assert.match(api, /BASE_URL \+ "\/api\/recording\/" \+ id \+ "\/stream"/);
  assert.match(api, /BASE_URL \+ "\/api\/recording\/" \+ id \+ "\/stream\/finish"/);
  assert.match(streamer, /upload\.append\(endpoint \+ "\?offset=" \+ offset, pcm\)/);
  assert.match(streamer, /MAX_CHUNK = 4 \* 1024 \* 1024/);
  assert.match(streamer, /"stream_gap"/);
  assert.match(streamer, /sent = held/);
  assert.match(upload, /"application\/octet-stream"/);
  assert.match(upload, /Response append\(String endpoint, byte\[\] pcm\)/);
  assert.match(upload, /"application\/json"/);
  assert.match(upload, /\\"bytes\\":/);
  assert.match(upload, /\\"sha256\\":/);
  assert.match(upload, /\\"durationMs\\":/);
  assert.match(upload, /\\"device\\":/);
  assert.match(upload, /audio\.getName\(\)\.endsWith\("\.wav"\) \? "audio\/wav" : "audio\/mp4"/);
});

test("stop orders header patch, stream flush, finish and the WAV fallback", () => {
  const service = readSource("VoiceVaultService.java");
  const stop = service.slice(service.indexOf("    private void stopAndTranscribe()"),
    service.indexOf("    private void acceptTranscription("));
  const patch = stop.indexOf("WavHeader.finish(mCurrentAudioFile, pcmBytes)");
  const flush = stop.indexOf("streamer.flush(STREAM_FLUSH_TIMEOUT_MS)");
  const finish = stop.indexOf("upload.finish(VoiceVaultApi.finishUrl(ownedEntry.id)");
  const fallback = stop.indexOf("upload.post(VoiceVaultApi.TRANSCRIBE_URL, audioFile, durationMs, ownedEntry.id)");
  assert.ok(patch >= 0 && patch < flush, "header is patched before the stream flush");
  assert.ok(flush < finish, "the stream is flushed before finish");
  assert.ok(finish < fallback, "a failed finish falls through to the full WAV upload");
  assert.match(stop, /DictationUpload\.sha256\(audioFile\)/);
  assert.match(stop, /STREAM_FLUSH_TIMEOUT_MS = 10000L|STREAM_FLUSH_TIMEOUT_MS\)/);
  assert.match(stop, /streamer\.stop\(pcmBytes\)/);
  // Finish is best effort: any failure falls through to the upload.
  assert.match(stop, /catch \(Exception finishFailure\)/);
});
