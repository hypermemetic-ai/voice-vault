/**
 * Deterministic post-processing for dictation transcripts.
 *
 * Parakeet-family models spell numbers out and pronounce acronyms letter by
 * letter. This turns the unambiguous spoken forms back into written ones:
 *
 *   "one point two seconds"        -> "1.2 seconds"
 *   "one hundred and two percent"  -> "102%"
 *   "fifty-five"                   -> "55"
 *   "S. S. H. access"              -> "SSH access"
 *   "s. v. g."                     -> "SVG"
 *
 * It is rule based and local: no model, no network, no guessing. Ambiguous
 * prose is left exactly as spoken — bare small numbers ("three children"),
 * idioms ("one of the things"), and ordinary words such as "pass out" are
 * never rewritten.
 *
 * User vocabulary that is not a general language rule lives in a JSON lexicon
 * (default: ~/.config/voice-vault/transcript-lexicon.json, override with
 * TRANSCRIPT_LEXICON), so it can be edited without touching code.
 */

import fs from "node:fs";
import os from "node:os";
import path from "node:path";

const SMALL = {
  zero: 0, one: 1, two: 2, three: 3, four: 4, five: 5, six: 6, seven: 7,
  eight: 8, nine: 9, ten: 10, eleven: 11, twelve: 12, thirteen: 13,
  fourteen: 14, fifteen: 15, sixteen: 16, seventeen: 17, eighteen: 18,
  nineteen: 19,
};

const TENS = {
  twenty: 20, thirty: 30, forty: 40, fifty: 50, sixty: 60, seventy: 70,
  eighty: 80, ninety: 90,
};

const SCALES = {
  hundred: 100, thousand: 1000, million: 1e6, billion: 1e9, trillion: 1e12,
};

const DIGIT_WORDS = ["zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine"];
const NUMBER_WORDS = [...Object.keys(SMALL), ...Object.keys(TENS), ...Object.keys(SCALES)];

const WORD_ALT = NUMBER_WORDS.sort((a, b) => b.length - a.length).join("|");
const DIGIT_ALT = DIGIT_WORDS.join("|");
const SEQ = `(?:${WORD_ALT})(?:(?:[\\s-]+|\\s+and\\s+)(?:${WORD_ALT}))*`;

const DECIMAL_RE = new RegExp(`\\b(?:(?<int>${SEQ})\\s+)?point\\s+(?<frac>${DIGIT_ALT}(?:[\\s-]+${DIGIT_ALT})*)\\b`, "gi");
const RANGE_RE = new RegExp(`\\b(?<lo>${SEQ})\\s+to\\s+(?<hi>${SEQ})\\b`, "gi");
const VERSION_RE = new RegExp(`\\b(?<kind>version|Version)\\s+(?<num>${SEQ})\\b`, "g");
// "V two" is only a version number when the clause ends or a version noun
// follows; "the letter V two times" must not become "V2 times".
const SHORT_VERSION_RE = new RegExp(`\\b(?<kind>[vV])\\s+(?<num>${SEQ})\\b(?=\\s*(?:[.,!?;:]|$|\\b(?:turbo|pro|max|mini|ultra|api|sdk|model|release|beta|alpha|stable|build)\\b))`, "gi");
const PERCENT_RE = new RegExp(`\\b(?:a|an)\\s+(?<num>${SEQ})\\s+(?:percent|per\\s+cent)\\b`, "gi");
const NUMBER_PERCENT_RE = new RegExp(`\\b(?<num>${SEQ})\\s+(?:percent|per\\s+cent)\\b`, "gi");
const ARTICLE_SCALE_RE = new RegExp(`\\b(?:a|an)\\s+(?<num>${SEQ})\\b`, "gi");
const NUMBER_RE = new RegExp(`\\b${SEQ}\\b`, "gi");

/** Dotted forms that are already correct and must not be joined. */
const PROTECTED = ["e.g.", "i.e.", "etc.", "vs.", "a.m.", "p.m."];

/** Two-letter joins whose spoken form is a different written acronym. */
const ACRONYM_ALIASES = { PG: "PNG" };

/** Spaced capitals are only joined when they form one of these. */
const KNOWN_ACRONYMS = new Set([
  "AI", "API", "ASR", "AWS", "CLI", "CPU", "CSS", "CSV", "DB", "DNS",
  "GPU", "GUI", "HTML", "HTTP", "HTTPS", "IAM", "ID", "IDE", "IP",
  "JSON", "JS", "LLM", "OCR", "OS", "PDF", "PNG", "RAM", "SDK", "SQL",
  "SSD", "SSH", "SSL", "STT", "SVG", "TCP", "TLS", "TTS", "UDP", "UI",
  "URI", "URL", "USB", "UX", "VM", "VPN", "XML", "YAML",
]);

const DEFAULT_LEXICON = {
  acronymAliases: ACRONYM_ALIASES,
  phrases: [
    { from: "crispr wispre twe", to: "CrisperWhisper 2.0", ignoreCase: true },
    { from: "crispr whispre twe", to: "CrisperWhisper 2.0", ignoreCase: true },
    { from: "crispr whisper twe", to: "CrisperWhisper 2.0", ignoreCase: true },
    { from: "crispr wisp re twe", to: "CrisperWhisper 2.0", ignoreCase: true },
    { from: "crispr whisper 2.0", to: "CrisperWhisper 2.0", ignoreCase: true },
    { from: "crispr-whisper 2.0", to: "CrisperWhisper 2.0", ignoreCase: true },
    { from: "wispr v three turbo", to: "Whisper V3 Turbo", ignoreCase: true },
    { from: "wisper v three turbo", to: "Whisper V3 Turbo", ignoreCase: true },
    { from: "bosendorfer", to: "Bösendorfer", ignoreCase: true },
  ],
  customWords: ["CrisperWhisper", "VoiceVault", "Bösendorfer", "Fazioli"],
};

function splitNumberWords(text) {
  return text.toLowerCase().split(/[\s-]+/).filter((t) => t && t !== "and");
}

/** Parse a sequence of English cardinal words, or null if it is not valid. */
function parseCardinal(words) {
  let total = 0;
  let current = 0;
  let last = null;
  for (const w of words) {
    if (w === "and") continue;
    if (Object.hasOwn(SMALL, w)) {
      if (last === "tens") {
        current += SMALL[w];
        last = "unit";
      } else if (last === "small" || last === "unit") {
        return null;
      } else {
        current += SMALL[w];
        last = "small";
      }
    } else if (Object.hasOwn(TENS, w)) {
      if (last === "tens" || last === "small" || last === "unit") return null;
      current += TENS[w];
      last = "tens";
    } else if (w === "hundred") {
      current = (current || 1) * 100;
      last = "hundred";
    } else if (Object.hasOwn(SCALES, w)) {
      total += (current || 1) * SCALES[w];
      current = 0;
      last = "scale";
    } else {
      return null;
    }
  }
  return total + current;
}

function digits(value) {
  return Number.isFinite(value) && value >= 0 && value < 1e15 ? String(value) : null;
}

function tightenAbbreviations(text) {
  return text
    .replace(/\b([eE])\.\s*([gG])\./g, "$1.$2.")
    .replace(/\b([iI])\.\s*([eE])\./g, "$1.$2.")
    .replace(/\b([apAP])\.\s*([mM])\./g, "$1.$2.");
}

function replacePhrases(text, phrases) {
  for (const { from, to, ignoreCase } of phrases) {
    const literal = from.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
    text = text.replace(new RegExp(`\\b${literal}\\b`, ignoreCase === false ? "g" : "gi"), to);
  }
  return text;
}

function replaceAcronyms(text, aliases) {
  for (const literal of PROTECTED) {
    const safe = literal.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
    text = text.replace(new RegExp(safe, "gi"), (m) => `\u0000${m.replace(/\./g, "\u0001")}\u0000`);
  }

  text = text.replace(/\b(?:[A-Za-z]\.\s*)+[A-Za-z]\./g, (match, offset) => {
    const joined = match.replace(/[^A-Za-z]/g, "").toUpperCase();
    if (joined.length < 2) return match;
    // Only curated technical acronyms are joined. A literal spelling such as
    // "X. Y. Z." stays as spoken.
    const written = aliases[joined] || (KNOWN_ACRONYMS.has(joined) ? joined : null);
    if (!written) return match;
    const rest = text.slice(offset + match.length);
    // The last "." may double as the sentence period; keep it when a new
    // sentence (or the end of the transcript) follows.
    const keepDot = /^\s*$/.test(rest) || /^\s+["'“(]?[A-Z]/.test(rest);
    return written + (keepDot ? "." : "");
  });

  text = text.replace(/\b(?:[A-Z]\s+){2,}[A-Z]\b/g, (match) => {
    const joined = match.replace(/\s+/g, "");
    return KNOWN_ACRONYMS.has(joined) ? joined : match;
  });

  return text.replace(/\u0000([^\u0000]+)\u0000/g, (_, m) => m.replace(/\u0001/g, "."));
}

function replaceDecimals(text) {
  return text.replace(DECIMAL_RE, (match, intPart, fracPart) => {
    const whole = intPart ? digits(parseCardinal(splitNumberWords(intPart))) : "0";
    if (whole === null) return match;
    const frac = splitNumberWords(fracPart).map((w) => String(SMALL[w]));
    return `${whole}.${frac.join("")}`;
  });
}

function replaceRanges(text) {
  return text.replace(RANGE_RE, (match, lo, hi) => {
    const loValue = digits(parseCardinal(splitNumberWords(lo)));
    const hiValue = digits(parseCardinal(splitNumberWords(hi)));
    return loValue === null || hiValue === null ? match : `${loValue} to ${hiValue}`;
  });
}

function replaceVersions(text) {
  const replace = (match, kind, num) => {
    const value = digits(parseCardinal(splitNumberWords(num)));
    if (value === null) return match;
    return kind.toLowerCase() === "version" ? `${kind} ${value}` : `${kind}${value}`;
  };
  text = text.replace(VERSION_RE, replace);
  return text.replace(SHORT_VERSION_RE, replace);
}

function replacePercents(text) {
  // The article form goes first so "a hundred percent" loses its "a".
  text = text.replace(PERCENT_RE, (match, num) => {
    const value = digits(parseCardinal(splitNumberWords(num)));
    return value === null ? match : `${value}%`;
  });
  return text.replace(NUMBER_PERCENT_RE, (match, num) => {
    const value = digits(parseCardinal(splitNumberWords(num)));
    return value === null ? match : `${value}%`;
  });
}

function replaceArticleScales(text) {
  // "a hundred and two" reads as a quantity, not an article plus a scale word.
  // Run after percentages, where the article is already handled.
  return text.replace(ARTICLE_SCALE_RE, (match, num) => {
    const words = splitNumberWords(num);
    if (!/^(hundred|thousand|million|billion|trillion)$/.test(words[0] || "")) return match;
    const value = digits(parseCardinal(words));
    return value === null ? match : value;
  });
}

function replaceCardinals(text) {
  return text.replace(NUMBER_RE, (match) => {
    const words = splitNumberWords(match);
    const value = parseCardinal(words);
    if (value === null) return match;
    // Convert only when the spoken form is unmistakably a number: a compound
    // or a value that is normally written in digits. Bare one..ten stay words.
    const compound = words.length >= 2 || /[-\s]/.test(match);
    if (!compound && value < 20) return match;
    return digits(value) ?? match;
  });
}

function replaceCustomWords(text, words) {
  for (const word of words) {
    const literal = word.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
    text = text.replace(new RegExp(`\\b${literal}\\b`, "gi"), word);
  }
  return text;
}

function tidy(text) {
  return text
    .replace(/\s+([.,!?;:])/g, "$1")
    .replace(/[ \t]{2,}/g, " ")
    .trim();
}

function loadLexicon() {
  const file = process.env.TRANSCRIPT_LEXICON
    || path.join(os.homedir(), ".config", "voice-vault", "transcript-lexicon.json");
  try {
    const parsed = JSON.parse(fs.readFileSync(file, "utf8"));
    return {
      acronymAliases: { ...DEFAULT_LEXICON.acronymAliases, ...(parsed.acronymAliases || {}) },
      phrases: [...DEFAULT_LEXICON.phrases, ...(parsed.phrases || [])],
      customWords: [...DEFAULT_LEXICON.customWords, ...(parsed.customWords || [])],
    };
  } catch {
    return DEFAULT_LEXICON;
  }
}

let cachedLexicon = null;

function lexicon() {
  cachedLexicon ??= loadLexicon();
  return cachedLexicon;
}

/**
 * Normalize one transcript.
 * @param {string} text
 * @param {{acronymAliases?: object, phrases?: Array, customWords?: string[]}} [options]
 * @returns {string}
 */
export function normalizeTranscript(text, options = {}) {
  if (!text || typeof text !== "string") return "";
  const base = lexicon();
  const lex = {
    acronymAliases: { ...base.acronymAliases, ...(options.acronymAliases || {}) },
    phrases: [...base.phrases, ...(options.phrases || [])],
    customWords: [...base.customWords, ...(options.customWords || [])],
  };

  let out = tightenAbbreviations(text);
  out = replacePhrases(out, lex.phrases);
  out = replaceAcronyms(out, lex.acronymAliases);
  out = replaceDecimals(out);
  out = replaceRanges(out);
  out = replaceVersions(out);
  out = replacePercents(out);
  out = replaceArticleScales(out);
  out = replaceCardinals(out);
  out = replaceCustomWords(out, lex.customWords);
  return tidy(out);
}

export const _internals = { parseCardinal, splitNumberWords };
