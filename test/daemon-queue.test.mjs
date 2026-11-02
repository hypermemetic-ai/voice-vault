/**
 * Hermetic tests for the warm daemon's request queue.
 *
 * The daemon module starts a server and spawns `handy` at import time, so it is
 * never imported here. These tests drive `src/daemon-queue.mjs` with a fake
 * writer and kill switch. No daemon, socket, GPU or model is touched.
 */

import test from "node:test";
import assert from "node:assert/strict";

import {
  DEFAULT_WEDGE_TIMEOUT_MS,
  createDaemonQueue,
  resolveWedgeTimeoutMs,
} from "../src/daemon-queue.mjs";

function fakeChild(options = {}) {
  const writes = [];
  let kills = 0;
  const queue = createDaemonQueue({
    write: (request) => writes.push(request),
    killChild: () => { kills += 1; },
    ...options,
  });
  return { queue, writes, kills: () => kills };
}

test("a slow reply past the client's 15 s bound does not kill the warm model", (t) => {
  t.mock.timers.enable({ apis: ["setTimeout"] });
  const { queue, writes, kills } = fakeChild({ wedgeTimeoutMs: 120_000 });
  queue.markReady("Vulkan0");

  const request = queue.enqueue("piece-1", "/tmp/piece-1.wav");
  assert.deepEqual(writes, [{ id: "piece-1", wav: "/tmp/piece-1.wav" }]);

  t.mock.timers.tick(60_000);
  assert.equal(kills(), 0);
  assert.equal(queue.status().inFlight, true);

  assert.equal(queue.handleReply({ id: "piece-1", text: "slow but fine", transcribe_ms: 60_000 }), true);
  return request.promise.then((result) => {
    assert.equal(result.text, "slow but fine");
    assert.equal(result.backend, "Vulkan0");
    assert.equal(queue.status().inFlight, false);
  });
});

test("no reply within the wedge limit kills the child and rejects the request", async (t) => {
  t.mock.timers.enable({ apis: ["setTimeout"] });
  const { queue, kills } = fakeChild({ wedgeTimeoutMs: 120_000 });
  queue.markReady("Vulkan0");

  const request = queue.enqueue("piece-1", "/tmp/piece-1.wav");
  t.mock.timers.tick(120_000);

  await assert.rejects(request.promise, /wedged: no reply after 120000ms/);
  assert.equal(kills(), 1);
  assert.equal(queue.status().inFlight, false);
});

test("a cancelled queued request never reaches handy", async (t) => {
  t.mock.timers.enable({ apis: ["setTimeout"] });
  const { queue, writes } = fakeChild();
  queue.markReady("Vulkan0");

  const first = queue.enqueue("piece-1", "/tmp/piece-1.wav");
  const abandoned = queue.enqueue("piece-2", "/tmp/piece-2.wav");
  const third = queue.enqueue("piece-3", "/tmp/piece-3.wav");

  assert.equal(abandoned.cancel(), true);
  await assert.rejects(abandoned.promise, /Client disconnected/);

  queue.handleReply({ id: "piece-1", text: "one" });
  await first.promise;
  assert.deepEqual(writes.map((w) => w.id), ["piece-1", "piece-3"]);

  queue.handleReply({ id: "piece-3", text: "three" });
  assert.equal((await third.promise).text, "three");
});

test("cancelling a request already in flight lets it finish", async (t) => {
  t.mock.timers.enable({ apis: ["setTimeout"] });
  const { queue, kills } = fakeChild();
  queue.markReady("Vulkan0");

  const request = queue.enqueue("piece-1", "/tmp/piece-1.wav");
  assert.equal(request.cancel(), false);
  assert.equal(kills(), 0);

  queue.handleReply({ id: "piece-1", text: "done" });
  assert.equal((await request.promise).text, "done");
});

test("requests wait for the model to load", async (t) => {
  t.mock.timers.enable({ apis: ["setTimeout"] });
  const { queue, writes } = fakeChild();

  const request = queue.enqueue("piece-1", "/tmp/piece-1.wav");
  assert.equal(writes.length, 0);
  assert.equal(queue.status().queueLength, 1);

  queue.markReady("Vulkan0");
  assert.equal(writes.length, 1);
  queue.handleReply({ id: "piece-1", ok: false, error: "decode failed" });
  await assert.rejects(request.promise, /decode failed/);
});

test("a child exit fails the in-flight and queued requests", async (t) => {
  t.mock.timers.enable({ apis: ["setTimeout"] });
  const { queue } = fakeChild();
  queue.markReady("Vulkan0");

  const inFlight = queue.enqueue("piece-1", "/tmp/piece-1.wav");
  const queued = queue.enqueue("piece-2", "/tmp/piece-2.wav");
  queue.childExited(1);

  await assert.rejects(inFlight.promise, /exited unexpectedly \(code 1\)/);
  await assert.rejects(queued.promise, /restarted/);
  assert.deepEqual(queue.status(), { ready: false, backend: "Vulkan0", queueLength: 0, inFlight: false });
});

test("a reply for an unknown id is reported, not routed", (t) => {
  t.mock.timers.enable({ apis: ["setTimeout"] });
  const { queue } = fakeChild();
  queue.markReady("Vulkan0");
  queue.enqueue("piece-1", "/tmp/piece-1.wav");
  assert.equal(queue.handleReply({ id: "stale", text: "late" }), false);
  assert.equal(queue.status().inFlight, true);
});

test("WHISPER_WEDGE_TIMEOUT_MS is read per call and falls back to 120 s", () => {
  assert.equal(DEFAULT_WEDGE_TIMEOUT_MS, 120_000);
  assert.equal(resolveWedgeTimeoutMs({}), DEFAULT_WEDGE_TIMEOUT_MS);
  assert.equal(resolveWedgeTimeoutMs({ WHISPER_WEDGE_TIMEOUT_MS: "60000" }), 60_000);
  assert.equal(resolveWedgeTimeoutMs({ WHISPER_WEDGE_TIMEOUT_MS: "nope" }), DEFAULT_WEDGE_TIMEOUT_MS);
  assert.equal(resolveWedgeTimeoutMs({ WHISPER_WEDGE_TIMEOUT_MS: "0" }), DEFAULT_WEDGE_TIMEOUT_MS);
});
