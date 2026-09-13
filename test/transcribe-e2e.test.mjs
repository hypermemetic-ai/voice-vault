/**
 * Ticket item 5: end-to-end `POST /api/transcribe` latency.
 *
 * Opt-in because it exercises the real backend cascade (warm daemon and/or
 * `handy` on the RTX A2000) and needs ffmpeg with the `flite` filter to
 * synthesise speech:
 *
 *   VOICE_VAULT_E2E=1 npm test
 *   VOICE_VAULT_E2E=1 VOICE_VAULT_E2E_LIMIT_MS=2000 node --test test/transcribe-e2e.test.mjs
 *
 * The bound guards against the regression this ticket fixes: a request that
 * hangs on the 10-minute socket timeout instead of completing in ~1-2 s.
 */

import test from "node:test";
import assert from "node:assert/strict";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { pathToFileURL } from "node:url";

const RUN_E2E = process.env.VOICE_VAULT_E2E === "1";
const LIMIT_MS = Number(process.env.VOICE_VAULT_E2E_LIMIT_MS || 2_000);
const HELPERS = new URL("./helpers/audio.mjs", import.meta.url);

test(
  `end-to-end POST /api/transcribe with synthetic speech completes in < ${LIMIT_MS}ms`,
  { skip: RUN_E2E ? false : "set VOICE_VAULT_E2E=1 to run against the real Whisper backends" },
  async (t) => {
    const helpers = await import(HELPERS.href);
    if (!helpers.speechFixturesAvailable()) {
      t.skip("ffmpeg with the flite filter is required to synthesise speech");
      return;
    }

    const dir = fs.mkdtempSync(path.join(os.tmpdir(), "vv-e2e-"));
    process.env.VOICE_VAULT_DB = path.join(dir, "history.db");
    process.env.VOICE_VAULT_STORAGE = path.join(dir, "raw");

    const { createAppServer } = await import(
      pathToFileURL(path.join(import.meta.dirname, "..", "src", "server.mjs")).href
    );
    const files = helpers.fixtures();
    const server = createAppServer({ rawDir: path.join(dir, "raw") });
    await new Promise((resolve) => server.listen(0, "127.0.0.1", resolve));
    const base = `http://127.0.0.1:${server.address().port}`;

    try {
      const form = new FormData();
      form.append("audio", new Blob([fs.readFileSync(files.a1)], { type: "audio/wav" }), "speech.wav");
      const started = Date.now();
      const response = await fetch(`${base}/api/transcribe`, { method: "POST", body: form });
      const body = await response.json();
      const elapsedMs = Date.now() - started;

      console.log(
        `      [e2e] ${elapsedMs}ms · backend=${body.backend} · text=${JSON.stringify(body.text)}`,
      );
      assert.equal(response.status, 200);
      assert.equal(body.ok, true);
      assert.ok(body.text.length > 0, `expected a transcript, got ${JSON.stringify(body)}`);
      assert.ok(
        elapsedMs < LIMIT_MS,
        `POST /api/transcribe took ${elapsedMs}ms (limit ${LIMIT_MS}ms, backend ${body.backend})`,
      );
    } finally {
      server.closeAllConnections?.();
      await new Promise((resolve) => server.close(resolve));
      fs.rmSync(dir, { recursive: true, force: true });
    }
  },
);
