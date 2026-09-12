/**
 * Target-speaker gate: keep only the enrolled user's speech segments.
 *
 * Given PCM16 audio, a VAD segmentation and an enrolled voiceprint, this
 * embeds every speech segment, scores it against the gallery and applies a
 * length-adaptive accept/reject decision. The caller decides what to do with
 * the result (Voice Vault concatenates accepted segments and sends only those
 * to Whisper).
 */

import { segmentSpeech, concatenateSegments, DEFAULT_VAD_OPTIONS } from "./vad.mjs";
import {
  DEFAULT_DECISION,
  decideSegment,
  scoreAgainstGallery,
} from "./speaker.mjs";

export const MAX_REPORTED_SEGMENTS = 50;

/**
 * @param {object} params
 * @param {Int16Array} params.pcm 16 kHz mono PCM16
 * @param {object} params.profile enrolled voiceprint (from profile.mjs)
 * @param {object} params.embedder speaker embedder instance
 * @param {number} [params.sampleRate]
 * @param {object} [params.vad] VAD option overrides
 * @param {object} [params.decision] decision option overrides
 */
export async function gateAudio({
  pcm,
  profile,
  embedder,
  sampleRate = 16000,
  vad = {},
  decision = {},
}) {
  const started = Date.now();
  const segmentation = segmentSpeech(pcm, sampleRate, { ...DEFAULT_VAD_OPTIONS, ...vad });
  const segments = segmentation.segments;

  if (segments.length === 0) {
    return {
      enabled: true,
      reason: "no_speech_segments",
      segments: [],
      decisions: [],
      kept: 0,
      rejected: 0,
      gateMs: Date.now() - started,
      embedMs: 0,
      segmentation,
      acceptedPcm: new Int16Array(0),
    };
  }

  const clips = segments.map((segment) => pcm.subarray(segment.startSample, segment.endSample));
  const embedStarted = Date.now();
  const embeddings = await embedder.embedBatch(clips, sampleRate);
  const embedMs = Date.now() - embedStarted;

  const decisions = segments.map((segment, index) => {
    const embedding = embeddings[index].embedding;
    const scored = scoreAgainstGallery(embedding, profile.gallery);
    const verdict = decideSegment(scored.best, segment.durationMs, profile, {
      ...DEFAULT_DECISION,
      ...decision,
    });
    return {
      index,
      startMs: Number(segment.startMs.toFixed(1)),
      endMs: Number(segment.endMs.toFixed(1)),
      durationMs: Number(segment.durationMs.toFixed(1)),
      bestScore: Number(scored.best.toFixed(6)),
      meanScore: Number(scored.mean.toFixed(6)),
      ...verdict,
    };
  });

  const acceptedSegments = segments.filter((_, index) => decisions[index].accepted);
  const rejectedSegments = segments.filter((_, index) => !decisions[index].accepted);

  return {
    enabled: true,
    reason: null,
    segments: decisions.slice(0, MAX_REPORTED_SEGMENTS),
    segmentsOmitted: Math.max(0, decisions.length - MAX_REPORTED_SEGMENTS),
    decisions,
    acceptedSegments,
    rejectedSegments,
    kept: acceptedSegments.length,
    rejected: rejectedSegments.length,
    acceptedPcm: concatenateSegments(pcm, acceptedSegments),
    gateMs: Date.now() - started,
    embedMs,
    segmentation: {
      noiseFloorDb: Number(segmentation.noiseFloorDb.toFixed(2)),
      thresholdDb: Number(segmentation.thresholdDb.toFixed(2)),
      speechRatio: Number(segmentation.speechRatio.toFixed(4)),
      fullSpan: segmentation.fullSpan,
    },
    embedder: embedder.info ? embedder.info() : null,
  };
}

/** Compact gate summary suitable for an HTTP response. */
export function summarizeGate(gate) {
  if (!gate || gate.enabled !== true) {
    return gate || { enabled: false };
  }
  return {
    enabled: true,
    reason: gate.reason || null,
    kept: gate.kept,
    rejected: gate.rejected,
    segmentCount: gate.decisions ? gate.decisions.length : 0,
    segments: gate.segments,
    segmentsOmitted: gate.segmentsOmitted || 0,
    gateMs: gate.gateMs,
    embedMs: gate.embedMs,
    segmentation: gate.segmentation,
    embedder: gate.embedder
      ? {
          backend: gate.embedder.backend,
          backendFamily: gate.embedder.backendFamily,
          dim: gate.embedder.dim,
          provider: gate.embedder.provider,
          modelId: gate.embedder.modelId,
        }
      : null,
  };
}
