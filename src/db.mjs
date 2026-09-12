import { DatabaseSync } from "node:sqlite";
import { mkdirSync } from "node:fs";
import { dirname } from "node:path";

const DB_PATH = process.env.VOICE_VAULT_DB || "/home/qqp/recordings/voice-vault/history.db";

mkdirSync(dirname(DB_PATH), { recursive: true });

const db = new DatabaseSync(DB_PATH);

// Enable WAL mode for high concurrency and resilience
db.exec(`
  PRAGMA journal_mode = WAL;
  PRAGMA synchronous = NORMAL;
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
`);

const stmtInsert = db.prepare(`
  INSERT INTO recordings (
    id, created_at, duration_ms, client_device,
    raw_filename, wav_filename, transcript, status, transcribe_ms
  ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
`);

const stmtGet = db.prepare(`
  SELECT * FROM recordings WHERE id = ?
`);

const stmtList = db.prepare(`
  SELECT * FROM recordings ORDER BY created_at DESC LIMIT ? OFFSET ?
`);

const stmtDelete = db.prepare(`
  DELETE FROM recordings WHERE id = ?
`);

const stmtUpdateTranscript = db.prepare(`
  UPDATE recordings SET transcript = ?, status = ?, transcribe_ms = ? WHERE id = ?
`);

export function saveRecording(rec) {
  stmtInsert.run(
    rec.id,
    rec.createdAt || new Date().toISOString(),
    rec.durationMs || 0,
    rec.clientDevice || "",
    rec.rawFilename,
    rec.wavFilename,
    rec.transcript || "",
    rec.status || "transcribed",
    rec.transcribeMs || 0
  );
  return stmtGet.get(rec.id);
}

export function getRecording(id) {
  return stmtGet.get(id);
}

export function listRecordings(limit = 50, offset = 0) {
  return stmtList.all(limit, offset);
}

export function deleteRecording(id) {
  return stmtDelete.run(id);
}

export function updateTranscript(id, text, status = "transcribed", transcribeMs = 0) {
  stmtUpdateTranscript.run(text, status, transcribeMs, id);
  return stmtGet.get(id);
}
