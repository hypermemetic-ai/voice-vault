import test from "node:test";
import assert from "node:assert/strict";
import { normalizeTranscript } from "../src/transcript-normalize.mjs";

const n = (s) => normalizeTranscript(s, { customWords: [] });

test("decimals, percents, compounds, ranges and versions become written forms", () => {
  assert.equal(n("thresholds one point two seconds and two point two seconds"),
    "thresholds 1.2 seconds and 2.2 seconds");
  assert.equal(n("one hundred and two percent"), "102%");
  assert.equal(n("that is still a hundred percent"), "that is still 100%");
  assert.equal(n("whether fifty-five is useful"), "whether 55 is useful");
  assert.equal(n("one to six is five steps"), "1 to 6 is five steps");
  assert.equal(n("intended mode in V two"), "intended mode in V2");
  assert.equal(n("Version three is out"), "Version 3 is out");
});

test("dotted acronyms are joined and aliased", () => {
  assert.equal(n("I cannot open s. v. g. right now"), "I cannot open SVG right now");
  assert.equal(n("serve a p. g. instead"), "serve a PNG instead");
  assert.equal(n("S. S. H. access to the host"), "SSH access to the host");
  assert.equal(n("the manager I. d. I will explain"), "the manager ID. I will explain");
});

test("spaced capitals only join when they form a known acronym", () => {
  assert.equal(n("see the S S H turn green"), "see the SSH turn green");
  assert.equal(n("then D O U G H appeared"), "then D O U G H appeared");
  assert.equal(n("the code is X. Y. Z."), "the code is X. Y. Z.");
});

test("a bare V only becomes a version when the context is a version", () => {
  assert.equal(n("intended mode in V two."), "intended mode in V2.");
  assert.equal(n("V Two Turbo"), "V2 Turbo");
  assert.equal(n("the letter V two times"), "the letter V two times");
});

test("a comma breaks the phrase, so a number is never merged across it", () => {
  assert.equal(n("one hundred, and two percent"), "100, and 2%");
});

test("abbreviations and ordinary prose are left alone", () => {
  assert.equal(n("This is e. g. a test, i. e. a sample."), "This is e.g. a test, i.e. a sample.");
  assert.equal(n("We met at 3 p. m. yesterday"), "We met at 3 p.m. yesterday");
  assert.equal(n("one of the things I want to do"), "one of the things I want to do");
  assert.equal(n("I am going to pass out"), "I am going to pass out");
  assert.equal(n("three children and five steps"), "three children and five steps");
  assert.equal(n("they are tenths, not octaves"), "they are tenths, not octaves");
});

test("known model spellings and product names are repaired", () => {
  assert.equal(n("from WISPR V Three Turbo to CRISPR WISPRE TWE ."),
    "from Whisper V3 Turbo to CrisperWhisper 2.0.");
  assert.equal(n("the bosendorfer piano"), "the Bösendorfer piano");
  assert.equal(normalizeTranscript("the crisperwhisper and voicevault repos"),
    "the CrisperWhisper and VoiceVault repos");
});
