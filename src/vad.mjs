/**
 * Multi-segment voice-activity detection over 16 kHz mono PCM16.
 *
 * The existing whole-file VAD (`prepareAudioForTranscription` from qq-dictation)
 * answers "does this recording contain speech at all" and trims silence from
 * the edges. Target-speaker gating needs more than that: it has to know *where*
 * each speech region is, so that a segment spoken by somebody else can be
 * dropped without discarding the user's own words. This module therefore
 * returns a list of speech segments with sample-accurate bounds, plus the noise
 * floor / threshold it derived, which the gate reports for observability.
 *
 * Design notes:
 *  - Frame RMS levels are computed over a 25 ms window every 10 ms.
 *  - The noise floor is the 20th percentile of frame levels. A seed threshold of
 *    `noiseFloor + 9 dB` (or an absolute -50 dB floor) decides what counts as
 *    speech; boundaries are then expanded with a 3 dB hysteresis threshold so
 *    quiet word endings are not clipped.
 *  - A minimum amount of sustained energy (`minSpeechMs`) is required, so single
 *    clicks/taps cannot promote room tone to speech.
 *  - Speech-like recordings with no quiet part at all (music, a continuous
 *    monologue recorded with AGC) collapse to one full-span segment instead of
 *    being sliced by an over-estimated noise floor.
 */

export const DEFAULT_VAD_OPTIONS = Object.freeze({
  frameMs: 25,
  hopMs: 10,
  minSpeechMs: 250,
  maxGapMs: 250,
  paddingMs: 120,
  absoluteThresholdDb: -50,
  noiseMarginDb: 9,
  hysteresisDb: 3,
  noisePercentile: 0.2,
  minActivityRangeDb: 6,
  floorDb: -100,
});

function resolveOptions(options = {}) {
  const merged = { ...DEFAULT_VAD_OPTIONS };
  for (const [key, fallback] of Object.entries(DEFAULT_VAD_OPTIONS)) {
    const value = Number(options[key]);
    merged[key] = Number.isFinite(value) ? value : fallback;
  }
  return merged;
}

/** Frame RMS levels in dBFS (0 dB == full scale int16). */
export function frameLevelsDb(pcm, sampleRate = 16000, options = {}) {
  const settings = resolveOptions(options);
  const samples = pcm instanceof Int16Array ? pcm : Int16Array.from(pcm ?? []);
  const frameSamples = Math.max(1, Math.round((sampleRate * settings.frameMs) / 1000));
  const hopSamples = Math.max(1, Math.round((sampleRate * settings.hopMs) / 1000));

  const levels = [];
  for (let start = 0; start < samples.length; start += hopSamples) {
    const end = Math.min(samples.length, start + frameSamples);
    if (end <= start) break;
    let squareSum = 0;
    for (let i = start; i < end; i += 1) {
      const value = samples[i];
      squareSum += value * value;
    }
    const rms = Math.sqrt(squareSum / (end - start));
    levels.push(rms > 0 ? Math.max(settings.floorDb, 20 * Math.log10(rms / 32768)) : settings.floorDb);
  }
  return { levels, frameSamples, hopSamples };
}

function percentile(values, proportion) {
  if (!values.length) return 0;
  const sorted = [...values].sort((a, b) => a - b);
  const index = Math.min(sorted.length - 1, Math.max(0, Math.floor((sorted.length - 1) * proportion)));
  return sorted[index];
}

function rangeDb(lower, upper) {
  if (!(lower > 0) || !(upper > 0)) return 0;
  return 20 * Math.log10(upper / lower);
}

function sustained(active, minimumFrames) {
  const supported = new Array(active.length).fill(false);
  if (minimumFrames <= 1) return active.slice();
  const required = Math.max(1, Math.ceil(minimumFrames * 0.6));
  for (let start = 0; start + minimumFrames <= active.length; start += 1) {
    let count = 0;
    for (let i = start; i < start + minimumFrames; i += 1) if (active[i]) count += 1;
    if (count < required) continue;
    for (let i = start; i < start + minimumFrames; i += 1) if (active[i]) supported[i] = true;
  }
  return supported;
}

/**
 * Segment PCM16 audio into speech regions.
 *
 * @returns {{
 *   segments: Array<{startSample:number,endSample:number,startMs:number,endMs:number,durationMs:number,meanDb:number,peakDb:number}>,
 *   noiseFloorDb:number, thresholdDb:number, frameCount:number, speechRatio:number,
 *   fullSpan:boolean
 * }}
 */
export function segmentSpeech(pcm, sampleRate = 16000, options = {}) {
  const settings = resolveOptions(options);
  const samples = pcm instanceof Int16Array ? pcm : Int16Array.from(pcm ?? []);
  const durationMs = (samples.length / sampleRate) * 1000;
  const empty = {
    segments: [],
    noiseFloorDb: settings.floorDb,
    thresholdDb: settings.absoluteThresholdDb,
    frameCount: 0,
    speechRatio: 0,
    fullSpan: false,
  };
  if (samples.length === 0) return empty;

  const { levels, frameSamples, hopSamples } = frameLevelsDb(samples, sampleRate, settings);
  const minimumFrames = Math.max(1, Math.ceil(settings.minSpeechMs / settings.hopMs));
  const noiseFloorDb = percentile(levels, settings.noisePercentile);
  const thresholdDb = Math.max(settings.absoluteThresholdDb, noiseFloorDb + settings.noiseMarginDb);

  const lower = percentile(levels, settings.noisePercentile);
  const upper = percentile(levels, 1 - settings.noisePercentile);
  const hasSpeechContrast = rangeDb(lower, upper) >= settings.minActivityRangeDb;
  const loudEnough = upper >= settings.absoluteThresholdDb;
  // A digital-silence floor (or any floor under the absolute threshold) means
  // the recording does contain quiet regions, so normal segmentation applies.
  const hasQuietRegion = lower <= settings.absoluteThresholdDb;
  if (!hasQuietRegion && !hasSpeechContrast && loudEnough && levels.length >= minimumFrames) {
    return {
      ...empty,
      segments: [makeSegment(0, samples.length, samples, sampleRate, levels, frameSamples, hopSamples)],
      noiseFloorDb,
      thresholdDb,
      frameCount: levels.length,
      speechRatio: 1,
      fullSpan: true,
    };
  }

  const seeds = sustained(
    levels.map((level) => level >= thresholdDb),
    minimumFrames,
  );
  const firstSeed = seeds.indexOf(true);
  if (firstSeed === -1) {
    return { ...empty, noiseFloorDb, thresholdDb, frameCount: levels.length };
  }
  const lastSeed = seeds.lastIndexOf(true);

  const boundaryThresholdDb = Math.max(
    settings.absoluteThresholdDb,
    noiseFloorDb + settings.hysteresisDb,
  );
  const maxGapFrames = Math.max(1, Math.ceil(settings.maxGapMs / settings.hopMs));

  let startFrame = firstSeed;
  let gap = 0;
  for (let i = firstSeed - 1; i >= 0; i -= 1) {
    if (levels[i] >= boundaryThresholdDb) {
      startFrame = i;
      gap = 0;
    } else {
      gap += 1;
      if (gap > maxGapFrames) break;
    }
  }

  let endFrame = lastSeed;
  gap = 0;
  for (let i = lastSeed + 1; i < levels.length; i += 1) {
    if (levels[i] >= boundaryThresholdDb) {
      endFrame = i;
      gap = 0;
    } else {
      gap += 1;
      if (gap > maxGapFrames) break;
    }
  }

  const paddingSamples = Math.round((sampleRate * settings.paddingMs) / 1000);
  const minSamples = Math.max(1, Math.round((sampleRate * settings.minSpeechMs) / 1000));

  // Walk the seeded span, closing a region only once the quiet run exceeds
  // maxGapMs so short unvoiced gaps stay inside the surrounding utterance.
  const regions = [];
  let regionStart = -1;
  let quietRun = 0;
  for (let frame = startFrame; frame <= endFrame; frame += 1) {
    if (levels[frame] >= boundaryThresholdDb) {
      if (regionStart === -1) regionStart = frame;
      quietRun = 0;
    } else if (regionStart !== -1) {
      quietRun += 1;
      if (quietRun > maxGapFrames) {
        regions.push({ firstFrame: regionStart, lastFrame: frame - quietRun });
        regionStart = -1;
        quietRun = 0;
      }
    }
  }
  if (regionStart !== -1) regions.push({ firstFrame: regionStart, lastFrame: endFrame });

  const segments = [];
  for (const region of regions) {
    const startSample = Math.max(0, region.firstFrame * hopSamples - paddingSamples);
    const endSample = Math.min(
      samples.length,
      (region.lastFrame * hopSamples + frameSamples) + paddingSamples,
    );
    if (endSample - startSample < minSamples) continue;
    segments.push(
      makeSegment(startSample, endSample, samples, sampleRate, levels, frameSamples, hopSamples),
    );
  }

  // Deliberately no post-hoc merging of padded segments: two regions are only
  // ever split when the unpadded quiet run exceeded maxGapMs, and merging them
  // again because padding shrank the visible gap would fuse different speakers
  // (or a speaker and a nearby interjection) into one unverifiable segment.
  const speechSamples = segments.reduce((sum, segment) => sum + segment.durationMs, 0);
  return {
    segments,
    noiseFloorDb,
    thresholdDb,
    frameCount: levels.length,
    speechRatio: durationMs > 0 ? Math.min(1, speechSamples / durationMs) : 0,
    fullSpan: false,
  };
}

function makeSegment(startSample, endSample, samples, sampleRate, levels, frameSamples, hopSamples) {
  let peakDb = -100;
  let squareSum = 0;
  for (let i = startSample; i < endSample; i += 1) {
    const value = samples[i];
    squareSum += value * value;
    const level = value === 0 ? -100 : 20 * Math.log10(Math.abs(value) / 32768);
    if (level > peakDb) peakDb = level;
  }
  const rms = Math.sqrt(squareSum / Math.max(1, endSample - startSample));
  const meanDb = rms > 0 ? 20 * Math.log10(rms / 32768) : -100;
  const firstFrame = Math.floor(startSample / hopSamples);
  const lastFrame = Math.min(levels.length - 1, Math.floor((endSample - 1) / hopSamples));
  const frameAvg = lastFrame >= firstFrame
    ? levels.slice(firstFrame, lastFrame + 1).reduce((sum, value) => sum + value, 0) / (lastFrame - firstFrame + 1)
    : meanDb;
  return {
    startSample,
    endSample,
    startMs: (startSample / sampleRate) * 1000,
    endMs: (endSample / sampleRate) * 1000,
    durationMs: ((endSample - startSample) / sampleRate) * 1000,
    meanDb: Number.isFinite(frameAvg) ? frameAvg : meanDb,
    peakDb,
  };
}

/** Concatenate the selected segments into one contiguous PCM16 buffer. */
export function concatenateSegments(pcm, segments) {
  const total = segments.reduce((sum, segment) => sum + (segment.endSample - segment.startSample), 0);
  const out = new Int16Array(total);
  let offset = 0;
  for (const segment of segments) {
    out.set(pcm.subarray(segment.startSample, segment.endSample), offset);
    offset += segment.endSample - segment.startSample;
  }
  return out;
}
