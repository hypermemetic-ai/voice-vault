/**
 * Voice enrollment: turn 1..N reference clips into a persisted voiceprint.
 *
 * Enrollment extracts one normalized embedding per clip, self-calibrates the
 * acceptance threshold from the intra-speaker pairwise similarities
 * (mu - strictness * sigma), and stores the gallery in SQLite. The reference
 * audio itself is never persisted - only the embeddings.
 */

import {
  deleteVoiceProfile,
  getVoiceGallery,
  getVoiceProfileRecord,
  saveVoiceProfile,
} from "./db.mjs";
import {
  DEFAULT_CALIBRATION,
  calibrateGallery,
  createSpeakerEmbedder,
  decideSegment,
  scoreAgainstGallery,
} from "./speaker.mjs";

export const MAX_ENROLL_SAMPLES = 8;
export const RECOMMENDED_ENROLL_SAMPLES = 3;
/** Clips shorter than this carry too little speaker information to enroll. */
export const MIN_ENROLL_SAMPLE_MS = 800;

/** Load the persisted voiceprint (with decoded gallery) or null. */
export function loadVoiceProfile() {
  const record = getVoiceProfileRecord();
  if (!record) return null;
  const gallery = getVoiceGallery();
  if (gallery.length === 0) return null;
  return profileFromRecord(record, gallery.map((item) => item.embedding));
}

function profileFromRecord(record, gallery) {
  let calibration = {};
  let metadata = {};
  try {
    calibration = JSON.parse(record.calibration_json || "{}");
  } catch {}
  try {
    metadata = JSON.parse(record.metadata_json || "{}");
  } catch {}
  return {
    enrolled: true,
    modelId: record.model_id,
    modelLabel: record.model_label,
    backend: record.backend,
    backendFamily: record.backend_family,
    dim: record.embedding_dim,
    sampleCount: record.sample_count,
    threshold: record.threshold,
    mu: record.mu,
    sigma: record.sigma,
    calibrationMode: record.calibration_mode,
    calibration,
    metadata,
    createdAt: record.created_at,
    updatedAt: record.updated_at,
    gallery,
  };
}

export function clearVoiceProfile() {
  deleteVoiceProfile();
  return { ok: true, enrolled: false };
}

/**
 * A stored gallery is only usable with the embedder that produced it: vector
 * dimensions and model identity have to match, otherwise cosine similarities
 * are meaningless. Callers degrade to ungated transcription when incompatible.
 */
export function profileCompatibility(profile, embedder) {
  if (!profile) return { compatible: false, reason: "no_profile" };
  if (!embedder) return { compatible: false, reason: "no_embedder" };
  if (profile.dim !== embedder.dim) {
    return {
      compatible: false,
      reason: `embedding_dim_mismatch:${profile.dim}:${embedder.dim}`,
    };
  }
  if (profile.backendFamily !== embedder.backendFamily) {
    return {
      compatible: false,
      reason: `backend_mismatch:${profile.backendFamily}:${embedder.backendFamily}`,
    };
  }
  if (profile.backendFamily === "onnx" && profile.modelId !== embedder.modelId) {
    return {
      compatible: false,
      reason: `model_mismatch:${profile.modelId}:${embedder.modelId}`,
    };
  }
  return { compatible: true, reason: null };
}

/**
 * Enroll a voiceprint from decoded PCM16 clips.
 *
 * @param {Int16Array[]} clips
 * @param {{embedder?: object, sampleRate?: number, modelId?: string}} options
 */
export async function enrollVoice(clips, options = {}) {
  const sampleRate = options.sampleRate || 16000;
  const usable = (clips || []).filter((clip) => clip && clip.length > 0);
  if (usable.length === 0) {
    throw Object.assign(new Error("No usable enrollment audio provided"), { statusCode: 400 });
  }
  if (usable.length > MAX_ENROLL_SAMPLES) {
    throw Object.assign(
      new Error(`Too many enrollment clips (max ${MAX_ENROLL_SAMPLES})`),
      { statusCode: 400 },
    );
  }

  const tooShort = [];
  const accepted = [];
  usable.forEach((clip, index) => {
    const durationMs = (clip.length / sampleRate) * 1000;
    if (durationMs < MIN_ENROLL_SAMPLE_MS) tooShort.push({ index, durationMs: Math.round(durationMs) });
    else accepted.push(clip);
  });
  if (accepted.length === 0) {
    throw Object.assign(
      new Error(
        `Every enrollment clip was shorter than ${MIN_ENROLL_SAMPLE_MS} ms; record ~5 s of speech per clip`,
      ),
      { statusCode: 400, details: { tooShort } },
    );
  }

  const embedder = options.embedder || (await createSpeakerEmbedder(options));
  const started = Date.now();
  const results = await embedder.embedBatch(accepted, sampleRate);
  const embedMs = Date.now() - started;
  const embeddings = results.map((result) => result.embedding);
  const durationsMs = accepted.map((clip) => Math.round((clip.length / sampleRate) * 1000));

  const calibration = calibrateGallery(embeddings, options.calibration || {});
  const info = embedder.info();
  const now = new Date().toISOString();

  const profileInput = {
    createdAt: now,
    updatedAt: now,
    modelId: info.modelId,
    modelLabel: info.modelLabel,
    backend: info.backend,
    backendFamily: info.backendFamily,
    dim: embeddings[0]?.length || info.dim,
    threshold: calibration.threshold,
    mu: calibration.mu,
    sigma: calibration.sigma,
    calibrationMode: calibration.mode,
    calibration,
    metadata: {
      sampleRate,
      durationsMs,
      embedMs,
      clipCount: embeddings.length,
      skippedShortClips: tooShort,
      backend: info.backend,
      provider: info.provider,
      fbank: info.fbank,
    },
  };

  saveVoiceProfile(
    profileInput,
    embeddings.map((embedding, index) => ({ embedding, durationMs: durationsMs[index] })),
  );

  return {
    profile: loadVoiceProfile(),
    embeddings,
    calibration,
    durationsMs,
    embedMs,
    embedder: info,
    tooShort,
  };
}

/**
 * Score a single clip against the enrolled profile (used by /api/profile/verify
 * and for benchmark reporting).
 */
export function verifyAgainstProfile(embedding, durationMs, profile, options = {}) {
  const scored = scoreAgainstGallery(embedding, profile.gallery);
  const decision = decideSegment(scored.best, durationMs, profile, {
    ...(options.decision || {}),
  });
  return {
    ...decision,
    best: scored.best,
    mean: scored.mean,
    galleryScores: scored.scores,
  };
}

/** Public (embedding-free) status payload. */
export function describeProfile(profile, extra = {}) {
  if (!profile) {
    return {
      ok: true,
      enrolled: false,
      gating: { enabled: false, reason: "not_enrolled" },
      ...extra,
    };
  }
  return {
    ok: true,
    enrolled: true,
    modelId: profile.modelId,
    modelLabel: profile.modelLabel,
    backend: profile.backend,
    backendFamily: profile.backendFamily,
    dim: profile.dim,
    sampleCount: profile.sampleCount,
    threshold: profile.threshold,
    mu: profile.mu,
    sigma: profile.sigma,
    calibrationMode: profile.calibrationMode,
    strictness: profile.calibration?.strictness ?? DEFAULT_CALIBRATION.strictness,
    pairCount: profile.calibration?.pairCount ?? 0,
    durationsMs: profile.metadata?.durationsMs || [],
    createdAt: profile.createdAt,
    updatedAt: profile.updatedAt,
    gating: { enabled: true, reason: null },
    ...extra,
  };
}
