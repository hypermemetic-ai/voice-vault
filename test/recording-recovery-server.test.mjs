import test, { after } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { DatabaseSync } from 'node:sqlite';

const root = fs.mkdtempSync(path.join(os.tmpdir(), 'vv-recovery-server-'));
process.env.VOICE_VAULT_DB = path.join(root, 'history.db');
process.env.VOICE_VAULT_SPEAKER_AUTODOWNLOAD = 'off';
// Start with the prior schema to exercise the additive migration.
const legacy = new DatabaseSync(process.env.VOICE_VAULT_DB);
legacy.exec(`CREATE TABLE recordings (id TEXT PRIMARY KEY, created_at TEXT NOT NULL,
 duration_ms INTEGER DEFAULT 0, client_device TEXT DEFAULT '', raw_filename TEXT NOT NULL,
 wav_filename TEXT NOT NULL, transcript TEXT DEFAULT '', status TEXT DEFAULT 'pending', transcribe_ms INTEGER DEFAULT 0);
 INSERT INTO recordings VALUES ('legacy', '2020-01-01', 7, '', 'legacy.wav', 'legacy.wav', 'old synthetic text', 'transcribed', 1);`);
legacy.close();
const db = await import('../src/db.mjs');
const { createAppServer } = await import('../src/server.mjs');
const servers = [];
async function app(options = {}) {
  const rawDir = path.join(root, `raw-${servers.length}`);
  const server = createAppServer({ rawDir, transcribeAudioFile: async () => ({
    text: 'synthetic text', hasSpeech: true, transcribeMs: 1, audioSentMs: 7, backend: 'stub',
    backendAttempts: [], gate: { enabled: false },
  }), ...options });
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  servers.push(server);
  const base = `http://127.0.0.1:${server.address().port}`;
  const request = async (route, options) => {
    const response = await fetch(base + route, options);
    return { status: response.status, body: await response.json() };
  };
  return { base, rawDir, request, upload: (id, body = Buffer.from('synthetic audio')) => request('/api/transcribe', {
    method: 'POST', headers: { 'Content-Type': 'audio/wav', ...(id ? { 'X-Recording-Id': id } : {}) }, body,
  }) };
}
after(async () => {
  for (const server of servers) { server.closeAllConnections(); await new Promise(resolve => server.close(resolve)); }
  fs.rmSync(root, { recursive: true, force: true });
});

test('migration preserves existing text and legacy raw/multipart success contract', async () => {
  assert.equal(db.getRecording('legacy').transcript, 'old synthetic text');
  const a = await app();
  const result = await a.upload();
  assert.equal(result.status, 200);
  for (const field of ['ok', 'id', 'text', 'hasSpeech', 'rejected', 'durationMs', 'audioSentMs', 'transcribeMs', 'audioUrl', 'backend', 'backendAttempts', 'rescue', 'gate']) assert.ok(field in result.body, field);
  const form = new FormData(); form.append('audio', new Blob(['synthetic multipart']), 'clip.wav'); form.append('durationMs', '123');
  const multi = await a.request('/api/transcribe', { method: 'POST', body: form });
  assert.equal(multi.status, 200); assert.equal(multi.body.durationMs, 123);
});
for (const code of ['ENOSPC', 'BACKEND']) test(`${code} after raw save retains one indexed original with a safe category`, async () => {
  const a = await app({ transcribeAudioFile: async () => { throw Object.assign(new Error('/private/audio/path secret body'), { code }); } });
  const id = `failure-${code}`;
  const result = await a.upload(id);
  assert.equal(result.status, 500); assert.equal(result.body.id, id); assert.equal(result.body.saved, true);
  assert.equal(result.body.errorCategory, code === 'ENOSPC' ? 'storage_full' : 'processing_failed');
  assert.ok(!JSON.stringify(result).includes('/private'));
  const lookup = await a.request(`/api/recording/${id}`);
  assert.equal(lookup.body.recording.status, 'failed'); assert.equal(lookup.body.recording.audio_available, true);
  const history = await a.request('/api/history'); assert.equal(history.body.recordings.filter(r => r.id === id).length, 1);
  assert.equal(await (await fetch(a.base + `/api/audio/${id}`)).text(), 'synthetic audio');
});
test('raw write and metadata commit failures never claim saved success and retain surviving bytes', async () => {
  const a = await app({ recordingFiles: { ...fs, writeFileSync() { throw Object.assign(new Error('private'), { code: 'ENOSPC' }); } } });
  const failed = await a.upload('raw-write'); assert.equal(failed.body.saved, false); assert.equal(failed.body.audioAvailable, false);
  assert.equal(db.getRecording('raw-write').status, 'failed');
  const b = await app({ recordingStore: { ...db, saveRecording() { throw Object.assign(new Error('private'), { code: 'SQLITE_FULL' }); } } });
  const noDb = await b.upload('no-db'); assert.equal(noDb.body.saved, false); assert.equal(db.getRecording('no-db'), undefined);
  assert.equal(fs.readdirSync(b.rawDir).length, 0);
  const c = await app({ recordingStore: { ...db, updateRecording() { throw Object.assign(new Error('private'), { code: 'SQLITE_FULL' }); } } });
  const pending = await c.upload('pending-db'); assert.equal(pending.body.saved, false); assert.equal(pending.body.audioAvailable, true);
  assert.equal(fs.readFileSync(path.join(c.rawDir, 'pending-db.wav'), 'utf8'), 'synthetic audio');
  const d = await app({ recordingStore: { ...db, finishRecording() { throw Object.assign(new Error('private'), { code: 'SQLITE_FULL' }); } } });
  const commit = await d.upload('finish-db'); assert.equal(commit.body.ok, false); assert.equal(commit.body.saved, false);
  assert.equal(commit.body.audioAvailable, true);
});
test('startup interrupts ownerless attempts without inference', async () => {
  db.saveRecording({ id: 'restart', rawFilename: 'restart.wav', status: 'processing' });
  let calls = 0;
  await app({ transcribeAudioFile: async () => { calls++; } });
  assert.equal(db.getRecording('restart').status, 'interrupted'); assert.equal(calls, 0);
});
test('lost response replay, concurrent claims and content conflicts keep one identity and one inference', async () => {
  let calls = 0, release;
  const wait = new Promise(resolve => { release = resolve; });
  const a = await app({ transcribeAudioFile: async () => { calls++; await wait; return { text: 'synthetic', hasSpeech: true }; } });
  const first = a.upload('replay');
  while (calls === 0) await new Promise(resolve => setTimeout(resolve, 5));
  const busy = await a.upload('replay'); assert.equal(busy.status, 409);
  const retryBusy = await a.request('/api/recording/replay/transcribe', { method: 'POST' }); assert.equal(retryBusy.status, 409);
  const conflict = await a.upload('replay', Buffer.from('different')); assert.equal(conflict.body.errorCategory, 'identity_conflict');
  const deletion = await a.request('/api/recording/replay', { method: 'DELETE' }); assert.equal(deletion.status, 409);
  release(); const completed = await first;
  const replay = await a.upload('replay'); assert.deepEqual(replay.body, completed.body);
  const lookup = await a.request('/api/recording/replay'); assert.deepEqual(lookup.body.recording.outcome, completed.body);
  const retry = await a.request('/api/recording/replay/transcribe', { method: 'POST' }); assert.deepEqual(retry.body, completed.body);
  assert.equal(calls, 1); assert.equal(fs.readFileSync(path.join(a.rawDir, 'replay.wav'), 'utf8'), 'synthetic audio');
});
test('explicit retry updates the same failed row and preserves original capture time/audio', async () => {
  let calls = 0;
  const a = await app({ transcribeAudioFile: async () => { if (++calls < 3) throw new Error('backend failure'); return { text: '', hasSpeech: false, rejected: true }; } });
  await a.upload('retry'); const created = db.getRecording('retry').created_at;
  const again = await a.request('/api/recording/retry/transcribe', { method: 'POST' }); assert.equal(again.status, 500);
  assert.equal(db.getRecording('retry').status, 'failed');
  const done = await a.request('/api/recording/retry/transcribe', { method: 'POST' }); assert.equal(done.body.rejected, true);
  assert.equal(db.getRecording('retry').status, 'speaker_rejected'); assert.equal(db.getRecording('retry').created_at, created);
  assert.equal(db.getRecording('retry').attempt_generation, 3); assert.equal(fs.readFileSync(path.join(a.rawDir, 'retry.wav'), 'utf8'), 'synthetic audio');
});
test('deletion affects only selected owned audio and honestly reports incomplete cleanup', async () => {
  const a = await app(); await a.upload('delete-one'); await a.upload('keep-one');
  assert.equal((await a.request('/api/recording/delete-one', { method: 'DELETE' })).body.ok, true);
  assert.equal(db.getRecording('delete-one'), undefined); assert.equal(fs.existsSync(path.join(a.rawDir, 'delete-one.wav')), false);
  assert.equal(fs.existsSync(path.join(a.rawDir, 'keep-one.wav')), true);
  const malformed = await a.upload('../escape'); assert.equal(malformed.status, 400);
  const b = await app({ recordingFiles: { ...fs, unlinkSync() { throw new Error('denied'); } } }); await b.upload('delete-fail');
  const fail = await b.request('/api/recording/delete-fail', { method: 'DELETE' }); assert.equal(fail.body.errorCategory, 'cleanup_incomplete'); assert.ok(db.getRecording('delete-fail'));
});


test('same-identity local re-upload repairs raw-write failure without a second row', async () => {
  let full=true, calls=0;
  const a=await app({ recordingFiles: { ...fs, writeFileSync(...args) {
    if(full)throw Object.assign(new Error('full'), {code:'ENOSPC'}); return fs.writeFileSync(...args);
  } }, transcribeAudioFile:async()=>{calls++;return {text:'repaired',hasSpeech:true};} });
  await a.upload('repair-intake'); const captured=db.getRecording('repair-intake').created_at;
  full=false; const result=await a.request('/api/transcribe',{method:'POST',headers:{'X-Recording-Id':'repair-intake','Content-Type':'audio/mp4'},body:Buffer.from('synthetic audio')}); assert.equal(result.body.text,'repaired');
  assert.equal(db.getRecording('repair-intake').created_at,captured); assert.equal(calls,1);
  assert.equal(db.listRecordings(500).filter(r=>r.id==='repair-intake').length,1);
});
test('terminal metadata failure releases live ownership and becomes manually retryable', async () => {
  let failing=true,calls=0;
  const a=await app({recordingStore:{...db,finishRecording(...args){
    if(failing)throw Object.assign(new Error('full'),{code:'SQLITE_FULL'});return db.finishRecording(...args);
  }},transcribeAudioFile:async()=>{calls++;return {text:'stored',hasSpeech:true};}});
  await a.upload('repair-metadata'); failing=false;
  const lookup=await a.request('/api/recording/repair-metadata'); assert.equal(lookup.body.recording.status,'interrupted');
  const retry=await a.request('/api/recording/repair-metadata/transcribe',{method:'POST'});
  assert.equal(retry.body.text,'stored');assert.equal(calls,2);
});

test('a failed attempt claim leaves pending intake manually recoverable',async()=>{
 let broken=true;const a=await app({recordingStore:{...db,claimRecording(id){if(broken)throw Object.assign(new Error('full'),{code:'SQLITE_FULL'});return db.claimRecording(id);}}});
 const failure=await a.upload('claim-failure');assert.equal(failure.body.id,'claim-failure');assert.equal(failure.body.saved,false);assert.equal(failure.body.audioAvailable,true);assert.equal(failure.body.errorCategory,'storage_full');broken=false;
 const lookup=await a.request('/api/recording/claim-failure');assert.equal(lookup.body.recording.status,'interrupted');
 assert.equal((await a.request('/api/recording/claim-failure/transcribe',{method:'POST'})).body.ok,true);
});


test('ownerless crash staging is repaired only for its ordinary owned part file',async()=>{
 const a=await app();const bytes=Buffer.from('synthetic audio');
 const crypto=await import('node:crypto');
 db.saveRecording({id:'stale-part',rawFilename:'stale-part.wav',status:'intake',audioAvailable:false,fingerprint:crypto.createHash('sha256').update(bytes).digest('hex')});
 fs.writeFileSync(path.join(a.rawDir,'stale-part.wav.part'),'partial');
 const repaired=await a.upload('stale-part',bytes);assert.equal(repaired.body.ok,true);
 assert.equal(fs.existsSync(path.join(a.rawDir,'stale-part.wav.part')),false);
 db.saveRecording({id:'symlink-part',rawFilename:'symlink-part.wav',status:'intake',audioAvailable:false,fingerprint:crypto.createHash('sha256').update(bytes).digest('hex')});
 const outside=path.join(root,'keep-outside');fs.writeFileSync(outside,'keep');fs.symlinkSync(outside,path.join(a.rawDir,'symlink-part.wav.part'));
 const blocked=await a.upload('symlink-part',bytes);assert.equal(blocked.body.ok,false);assert.equal(fs.readFileSync(outside,'utf8'),'keep');
});


test('legacy identity validates original bytes or rejects unverifiable delivery',async()=>{
 const a=await app();fs.writeFileSync(path.join(a.rawDir,'legacy-copy.wav'),'original');
 db.saveRecording({id:'legacy-copy',rawFilename:'legacy-copy.wav',transcript:'previous',status:'transcribed'});
 const conflict=await a.upload('legacy-copy',Buffer.from('different'));assert.equal(conflict.body.errorCategory,'identity_conflict');
 const replay=await a.upload('legacy-copy',Buffer.from('original'));assert.equal(replay.body.text,'previous');
 const missing=await a.upload('legacy',Buffer.from('anything'));assert.equal(missing.body.errorCategory,'identity_conflict');
});
