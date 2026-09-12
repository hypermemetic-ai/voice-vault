#!/usr/bin/env node
/**
 * Download and verify the ONNX speaker-embedding models.
 *
 * Usage:
 *   node scripts/fetch-speaker-model.mjs               # default English CAM++ model
 *   node scripts/fetch-speaker-model.mjs --all         # all registered models
 *   node scripts/fetch-speaker-model.mjs --model campplus-zh-cn-common-192
 *   node scripts/fetch-speaker-model.mjs --list
 *   node scripts/fetch-speaker-model.mjs --force       # re-download
 */

import fs from "node:fs";
import {
  DEFAULT_SPEAKER_MODEL,
  SPEAKER_MODELS,
  ensureSpeakerModel,
  modelDir,
  modelPathFor,
  sha256File,
} from "../src/speaker.mjs";

function parseArgs(argv) {
  const options = { models: [], all: false, list: false, force: false };
  for (let i = 0; i < argv.length; i += 1) {
    const arg = argv[i];
    if (arg === "--all") options.all = true;
    else if (arg === "--list") options.list = true;
    else if (arg === "--force") options.force = true;
    else if (arg === "--model") options.models.push(argv[++i]);
    else if (arg.startsWith("--model=")) options.models.push(arg.slice("--model=".length));
    else {
      console.error(`Unknown argument: ${arg}`);
      process.exit(2);
    }
  }
  return options;
}

async function main() {
  const options = parseArgs(process.argv.slice(2));

  if (options.list) {
    console.log("Registered speaker models:\n");
    for (const spec of Object.values(SPEAKER_MODELS)) {
      const installed = fs.existsSync(modelPathFor(spec.id)) ? "installed" : "not installed";
      console.log(`  ${spec.id.padEnd(34)} ${String(spec.outputDim).padStart(3)}-d  ${installed}`);
      console.log(`  ${"".padEnd(34)} ${spec.label} (${spec.language})`);
    }
    console.log(`\nModel directory: ${modelDir()}`);
    return 0;
  }

  const ids = options.all
    ? Object.keys(SPEAKER_MODELS)
    : options.models.length
      ? options.models
      : [DEFAULT_SPEAKER_MODEL];

  let failures = 0;
  for (const id of ids) {
    const spec = SPEAKER_MODELS[id];
    if (!spec) {
      console.error(`✖ Unknown model id: ${id}`);
      failures += 1;
      continue;
    }
    const target = modelPathFor(id);
    try {
      if (options.force && fs.existsSync(target)) fs.rmSync(target);
      const alreadyPresent = fs.existsSync(target);
      process.stdout.write(`${alreadyPresent ? "•" : "↓"} ${id} → ${target}\n`);
      await ensureSpeakerModel(id, { download: true, path: target });
      const digest = await sha256File(target);
      const size = fs.statSync(target).size;
      if (spec.sha256 && digest !== spec.sha256) {
        console.error(`  ✖ checksum mismatch: ${digest}`);
        failures += 1;
        continue;
      }
      console.log(
        `  ✓ ${(size / 1e6).toFixed(1)} MB · ${spec.outputDim}-d · sha256 ${digest.slice(0, 16)}…`,
      );
    } catch (error) {
      console.error(`  ✖ ${error.message}`);
      failures += 1;
    }
  }

  console.log(`\nModel directory: ${modelDir()}`);
  return failures === 0 ? 0 : 1;
}

main().then((code) => process.exit(code));
