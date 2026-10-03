import test, { before, after } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
const root=path.resolve(path.dirname(fileURLToPath(import.meta.url)),'..');
let classes;
before(()=>{
 classes=fs.mkdtempSync(path.join(os.tmpdir(),'vv-recovery-classes-'));
 const source=path.join(root,'android/src/ai/hypermemetic/voicevault');
 const javaFiles=[];const walk=dir=>{for(const f of fs.readdirSync(dir,{withFileTypes:true})){const p=path.join(dir,f.name);if(f.isDirectory())walk(p);else if(p.endsWith('.java'))javaFiles.push(p);}};
 walk(path.join(root,'test/fixtures/recovery'));
 const result=spawnSync('javac',['--release','11','-d',classes,...['RecordingIndex','RecordingStore','HistoryManager','DictationUpload','RecoveryTranscription','RecordingAudio','RecordingAudioProvider'].map(n=>path.join(source,n+'.java')),...javaFiles],{encoding:'utf8'});
 assert.equal(result.status,0,result.stderr || result.error?.message);
});
after(()=>{if(classes)fs.rmSync(classes,{recursive:true,force:true});});
for(const [scenario,title] of [
 ['state','production transitions preserve identity, commit before publishing and reject stale ownership'],
 ['storage','atomic write failure, previous valid index and restart reconcile surviving valid audio'],
 ['discovery','conservative discovery and retention protect unresolved originals beyond ordinary limits'],
 ['history','merged History preserves blank failures, limited pages, original order and latest text'],
 ['deletion','owned deletion blocks concurrent processing and suppresses stale refresh after remote outage'],
 ['export','read-only export URI resolves only selected owned audio and rejects traversal/missing/deleted files'],
 ['network','real bounded loopback recovery uses lookup, explicit retry, legacy fallback and response-loss replay'],
])test(title,()=>{
 const result=spawnSync('java',['-cp',classes,'ai.hypermemetic.voicevault.RecoveryHarness',scenario],{encoding:'utf8',timeout:15000});
 assert.equal(result.status,0,result.stderr || result.error?.message);
});

test('History actions carry stored identity and manifest grants only selected read access',()=>{
 const activity=fs.readFileSync(path.join(root,'android/src/ai/hypermemetic/voicevault/MainActivity.java'),'utf8');
 for(const action of ['Retry transcription','Play original','Export original','Retry deletion','Copy'])assert.ok(activity.includes('"'+action+'"'),action);
 assert.ok(activity.includes('putExtra(VoiceVaultService.EXTRA_RECORDING_ID,entry.localId)'));
 assert.ok(activity.includes('Intent.FLAG_GRANT_READ_URI_PERMISSION'));
 assert.ok(activity.includes('ClipData.newRawUri'));
 const manifest=fs.readFileSync(path.join(root,'android/AndroidManifest.xml'),'utf8');
 assert.match(manifest,/<provider android:name=".RecordingAudioProvider"[\s\S]*?android:exported="false" android:grantUriPermissions="true"/);
});
