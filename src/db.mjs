import { DatabaseSync } from "node:sqlite";
import { mkdirSync } from "node:fs";
import { dirname } from "node:path";
import { float32ToBlob, blobToFloat32 } from "./wav.mjs";

const DB_PATH = process.env.VOICE_VAULT_DB || "/home/qqp/recordings/voice-vault/history.db";

mkdirSync(dirname(DB_PATH), { recursive: true });

const db = new DatabaseSync(DB_PATH);

// Enable WAL mode for high concurrency and resilience
db.exec(`
  PRAGMA journal_mode = WAL;
  PRAGMA synchronous = FULL;
  CREATE TABLE IF NOT EXISTS recordings (
    id TEXT PRIMARY KEY,
    created_at TEXT NOT NULL,
    duration_ms INTEGER DEFAULT 0,
    client_device TEXT DEFAULT '',
    raw_filename TEXT NOT NULL,
    wav_filename TEXT NOT NULL,
    transcript TEXT DEFAULT '',
    status TEXT DEFAULT 'pending',
    transcribe_ms INTEGER DEFAULT 0
  );
  CREATE INDEX IF NOT EXISTS idx_recordings_created_at ON recordings(created_at DESC);

  -- Enrolled voiceprint: exactly one active profile (id = 1), plus its gallery
  -- of reference embeddings. Embeddings are stored as little-endian float32
  -- BLOBs so no audio samples ever have to be retained.
  CREATE TABLE IF NOT EXISTS voice_profile (
    id INTEGER PRIMARY KEY CHECK (id = 1),
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL,
    model_id TEXT NOT NULL,
    model_label TEXT DEFAULT '',
    backend TEXT DEFAULT '',
    backend_family TEXT NOT NULL,
    embedding_dim INTEGER NOT NULL,
    sample_count INTEGER NOT NULL,
    threshold REAL NOT NULL,
    mu REAL NOT NULL,
    sigma REAL NOT NULL,
    calibration_mode TEXT DEFAULT 'self',
    calibration_json TEXT DEFAULT '{}',
    metadata_json TEXT DEFAULT '{}'
  );
  CREATE TABLE IF NOT EXISTS voice_gallery (
    profile_id INTEGER NOT NULL,
    idx INTEGER NOT NULL,
    embedding BLOB NOT NULL,
    duration_ms REAL DEFAULT 0,
    created_at TEXT NOT NULL,
    PRIMARY KEY (profile_id, idx)
  );
`);

// Additive migration preserves older recordings and voice-profile tables.
const columns = new Set(db.prepare("PRAGMA table_info(recordings)").all().map(row => row.name));
for (const [name, definition] of Object.entries({
  error_category: "TEXT DEFAULT ''", updated_at: "TEXT DEFAULT ''",
  attempt_generation: "INTEGER DEFAULT 0", fingerprint: "TEXT DEFAULT ''",
  audio_available: "INTEGER DEFAULT 1", outcome_json: "TEXT DEFAULT ''",
})) {
  if (!columns.has(name)) db.exec(`ALTER TABLE recordings ADD COLUMN ${name} ${definition}`);
}

const stmtGet = db.prepare("SELECT * FROM recordings WHERE id = ?");
export function saveRecording(rec) {
  db.prepare(`INSERT INTO recordings (id, created_at, duration_ms, client_device,
    raw_filename, wav_filename, transcript, status, transcribe_ms, error_category,
    updated_at, fingerprint, audio_available, outcome_json)
    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`).run(
      rec.id, rec.createdAt || new Date().toISOString(), rec.durationMs || 0,
      rec.clientDevice || "", rec.rawFilename, rec.wavFilename || rec.rawFilename,
      rec.transcript || "", rec.status || "transcribed", rec.transcribeMs || 0,
      rec.errorCategory || "", new Date().toISOString(), rec.fingerprint || "",
      rec.audioAvailable === false ? 0 : 1, rec.outcome ? JSON.stringify(rec.outcome) : "");
  return getRecording(rec.id);
}
export function getRecording(id) { return stmtGet.get(id); }
export function listRecordings(limit = 50, offset = 0) {
  return db.prepare("SELECT * FROM recordings ORDER BY created_at DESC LIMIT ? OFFSET ?")
    .all(Math.min(500, Math.max(1, limit || 50)), Math.max(0, offset));
}
export function deleteRecording(id) { return db.prepare("DELETE FROM recordings WHERE id = ?").run(id); }
export function updateTranscript(id, text, status = "transcribed", transcribeMs = 0) {
  db.prepare("UPDATE recordings SET transcript=?, status=?, transcribe_ms=? WHERE id=?")
    .run(text, status, transcribeMs, id);
  return getRecording(id);
}
export function updateRecording(id, status, errorCategory = "", audioAvailable = true) {
  db.prepare(`UPDATE recordings SET status=?, error_category=?, audio_available=?, updated_at=? WHERE id=?`)
    .run(status, errorCategory, audioAvailable ? 1 : 0, new Date().toISOString(), id);
  return getRecording(id);
}
export function claimRecording(id) {
  const result = db.prepare(`UPDATE recordings SET status='processing', error_category='',
    attempt_generation=attempt_generation+1, updated_at=?
    WHERE id=? AND audio_available=1 AND status IN ('pending','failed','interrupted')`)
    .run(new Date().toISOString(), id);
  return result.changes ? getRecording(id) : null;
}
export function finishRecording(id, generation, status, outcome, errorCategory = "") {
  const result = db.prepare(`UPDATE recordings SET status=?, error_category=?, transcript=?,
    transcribe_ms=?, outcome_json=?, updated_at=?
    WHERE id=? AND status='processing' AND attempt_generation=?`).run(
      status, errorCategory, outcome?.text || "", outcome?.transcribeMs || 0,
      outcome ? JSON.stringify(outcome) : "", new Date().toISOString(), id, generation);
  return result.changes ? getRecording(id) : null;
}
export function reconcileRecordings() {
  return db.prepare(`UPDATE recordings SET status='interrupted', error_category='interrupted',
    updated_at=? WHERE status IN ('intake','pending','processing')`).run(new Date().toISOString());
}

// ---------------------------------------------------------------------------
// Voice profile (speaker gate) persistence
// ---------------------------------------------------------------------------

const stmtUpsertProfile = db.prepare(`
  INSERT INTO voice_profile (
    id, created_at, updated_at, model_id, model_label, backend, backend_family,
    embedding_dim, sample_count, threshold, mu, sigma, calibration_mode,
    calibration_json, metadata_json
  ) VALUES (1, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
  ON CONFLICT(id) DO UPDATE SET
    updated_at = excluded.updated_at,
    model_id = excluded.model_id,
    model_label = excluded.model_label,
    backend = excluded.backend,
    backend_family = excluded.backend_family,
    embedding_dim = excluded.embedding_dim,
    sample_count = excluded.sample_count,
    threshold = excluded.threshold,
    mu = excluded.mu,
    sigma = excluded.sigma,
    calibration_mode = excluded.calibration_mode,
    calibration_json = excluded.calibration_json,
    metadata_json = excluded.metadata_json
`);

const stmtInsertGalleryVector = db.prepare(`
  INSERT INTO voice_gallery (profile_id, idx, embedding, duration_ms, created_at)
  VALUES (1, ?, ?, ?, ?)
`);

const stmtGetProfile = db.prepare(`SELECT * FROM voice_profile WHERE id = 1`);
const stmtGetGallery = db.prepare(
  `SELECT idx, embedding, duration_ms, created_at FROM voice_gallery WHERE profile_id = 1 ORDER BY idx ASC`,
);
const stmtDeleteProfile = db.prepare(`DELETE FROM voice_profile WHERE id = 1`);
const stmtDeleteGallery = db.prepare(`DELETE FROM voice_gallery WHERE profile_id = 1`);

/**
 * Persist the enrolled voiceprint and its gallery atomically.
 *
 * @param {object} profile calibration + model metadata
 * @param {Array<{embedding: Float32Array, durationMs?: number}>} gallery
 */
export function saveVoiceProfile(profile, gallery = []) {
  const now = profile.updatedAt || new Date().toISOString();
  const createdAt = profile.createdAt || now;
  db.exec("BEGIN IMMEDIATE");
  try {
    stmtUpsertProfile.run(
      createdAt,
      now,
      profile.modelId || "unknown",
      profile.modelLabel || "",
      profile.backend || "",
      profile.backendFamily || "onnx",
      profile.dim || 0,
      gallery.length,
      profile.threshold ?? 0,
      profile.mu ?? 0,
      profile.sigma ?? 0,
      profile.calibrationMode || "self",
      JSON.stringify(profile.calibration || {}),
      JSON.stringify(profile.metadata || {}),
    );
    stmtDeleteGallery.run();
    gallery.forEach((item, index) => {
      stmtInsertGalleryVector.run(
        index,
        float32ToBlob(item.embedding),
        item.durationMs || 0,
        now,
      );
    });
    db.exec("COMMIT");
  } catch (error) {
    try {
      db.exec("ROLLBACK");
    } catch {}
    throw error;
  }
  return getVoiceProfileRecord();
}

export function getVoiceProfileRecord() {
  return stmtGetProfile.get();
}

/** Gallery vectors decoded back into Float32Array, in enrollment order. */
export function getVoiceGallery() {
  return stmtGetGallery.all().map((row) => ({
    index: row.idx,
    embedding: blobToFloat32(row.embedding),
    durationMs: row.duration_ms || 0,
    createdAt: row.created_at,
  }));
}

export function deleteVoiceProfile() {
  db.exec("BEGIN IMMEDIATE");
  try {
    stmtDeleteGallery.run();
    stmtDeleteProfile.run();
    db.exec("COMMIT");
  } catch (error) {
    try {
      db.exec("ROLLBACK");
    } catch {}
    throw error;
  }
  return { ok: true };
}
