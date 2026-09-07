#!/usr/bin/env node
/**
 * Őrszem V2 — canonical reference dataset validator.
 *
 * Deterministic and entirely offline. It reads only the committed canonical files, so CI
 * can run it without network access and without depending on KSH, VPE, MÁV or GYSEV being
 * reachable. The committed snapshot is what gets validated.
 *
 * Usage: node validate-canonical.mjs reference-data/local-research/manifest.json
 */

import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';

const manifestPath = process.argv[2];
if (!manifestPath) {
  console.error('usage: validate-canonical.mjs <manifest.json>');
  process.exit(2);
}

const dir = path.dirname(manifestPath);
const failures = [];
const fail = (message) => failures.push(message);

// ------------------------------------------------------------------ manifest

let manifest;
try {
  manifest = JSON.parse(fs.readFileSync(manifestPath, 'utf8'));
} catch (e) {
  console.error(`manifest is not readable JSON: ${e.message}`);
  process.exit(1);
}

if (!['VERIFIED', 'UNVERIFIED'].includes(manifest.verificationStatus)) {
  fail(`verificationStatus must be VERIFIED or UNVERIFIED, got ${manifest.verificationStatus}`);
}
if (!['COMPLETE', 'PARTIAL'].includes(manifest.coverageStatus)) {
  fail(`coverageStatus must be COMPLETE or PARTIAL, got ${manifest.coverageStatus}`);
}
// A third, independent axis: whether reuse rights are cleared. VERIFIED proves the data is
// factually right and says nothing about redistribution rights, so this cannot be folded
// into verificationStatus without losing information a downstream importer needs.
if (!['PENDING', 'CLEARED'].includes(manifest.reuseStatus)) {
  fail(`reuseStatus must be PENDING or CLEARED, got ${manifest.reuseStatus}`);
}
if (!manifest.datasetVersion) fail('datasetVersion is missing');

// Coverage is tracked per component (ADR 0006): absence means something different in each
// roster, so a single blanket flag cannot drive the importer's deactivation decisions.
const coverageComponents = ['settlements', 'railwayLines', 'settlementRailwayLines'];
for (const component of coverageComponents) {
  const value = manifest.coverage?.[component];
  if (!['COMPLETE', 'PARTIAL'].includes(value)) {
    fail(`coverage.${component} must be COMPLETE or PARTIAL, got ${value}`);
  }
}
// The blanket status must not overclaim relative to its own components.
if (
  manifest.coverageStatus === 'COMPLETE' &&
  coverageComponents.some((c) => manifest.coverage?.[c] === 'PARTIAL')
) {
  fail('coverageStatus is COMPLETE but at least one coverage component is PARTIAL');
}

// Every source must carry enough provenance to be re-checked by a person later.
for (const [name, source] of Object.entries(manifest.sources ?? {})) {
  for (const field of ['source', 'retrievedAt', 'sourceVersionOrDate', 'used']) {
    if (!source[field]) fail(`source ${name} is missing ${field}`);
  }
  if (!source.licence && !source.licenceReference) {
    fail(`source ${name} states neither licence nor licenceReference`);
  }
}

// ------------------------------------------------------------- csv utilities

const splitCsv = (line) => {
  const out = [];
  let cur = '';
  let quoted = false;
  for (let i = 0; i < line.length; i++) {
    const ch = line[i];
    if (quoted) {
      if (ch === '"' && line[i + 1] === '"') { cur += '"'; i++; }
      else if (ch === '"') quoted = false;
      else cur += ch;
    } else if (ch === '"') quoted = true;
    else if (ch === ',') { out.push(cur); cur = ''; }
    else cur += ch;
  }
  out.push(cur);
  return out;
};

function readCsv(file, expectedHeader) {
  const raw = fs.readFileSync(file);

  // Valid UTF-8 with no BOM: the importer and the database both assume it.
  const text = raw.toString('utf8');
  if (Buffer.compare(Buffer.from(text, 'utf8'), raw) !== 0) fail(`${path.basename(file)} is not valid UTF-8`);
  if (text.charCodeAt(0) === 0xfeff) fail(`${path.basename(file)} starts with a BOM`);

  const lines = text.split('\n').filter((l, i, arr) => l.length > 0 || i < arr.length - 1);
  const header = splitCsv(lines[0]);
  if (header.join(',') !== expectedHeader.join(',')) {
    fail(`${path.basename(file)} header is ${header.join(',')}, expected ${expectedHeader.join(',')}`);
  }
  return lines.slice(1).filter((l) => l.length > 0).map(splitCsv);
}

const sha256 = (file) => crypto.createHash('sha256').update(fs.readFileSync(file)).digest('hex');

// ------------------------------------------------------------------ checksums

for (const [file, expected] of Object.entries(manifest.canonicalFiles ?? {})) {
  const full = path.join(dir, file);
  if (!fs.existsSync(full)) { fail(`canonical file missing: ${file}`); continue; }
  const actual = sha256(full);
  if (actual !== expected) fail(`${file} checksum mismatch\n    manifest: ${expected}\n    actual:   ${actual}`);
}

// ------------------------------------------------------------------ contents

const settlements = readCsv(path.join(dir, 'settlements.csv'), ['ksh_code', 'name', 'county_name']);
const lines = readCsv(path.join(dir, 'railway-lines.csv'), ['line_code', 'display_name']);
const relations = readCsv(path.join(dir, 'settlement-railway-lines.csv'), ['ksh_code', 'line_code']);

// --- settlements ---
const kshCodes = new Set();
for (const [code, name] of settlements) {
  if (!/^\d{5}$/.test(code)) fail(`settlement code is not five digits: '${code}'`);
  if (!name) fail(`settlement ${code} has no name`);
  if (kshCodes.has(code)) fail(`duplicate settlement code: ${code}`);
  kshCodes.add(code);
}

// --- railway lines ---
const lineCodes = new Set();
for (const [code, displayName] of lines) {
  if (!code) fail('railway line with empty code');
  if (!displayName) fail(`railway line ${code} has no display name`);
  if (lineCodes.has(code)) fail(`duplicate line code: ${code}`);
  lineCodes.add(code);
}

// --- relations: no duplicates, no dangling references ---
const seenRelations = new Set();
for (const [kshCode, lineCode] of relations) {
  const key = `${kshCode}|${lineCode}`;
  if (seenRelations.has(key)) fail(`duplicate relation: ${key}`);
  seenRelations.add(key);
  if (!kshCodes.has(kshCode)) fail(`relation references unknown settlement: ${kshCode}`);
  if (!lineCodes.has(lineCode)) fail(`relation references unknown line: ${lineCode}`);
}

// ------------------------------------------------------- deterministic order

const isSorted = (rows, compare, label) => {
  for (let i = 1; i < rows.length; i++) {
    if (compare(rows[i - 1], rows[i]) > 0) {
      fail(`${label} is not in deterministic order at row ${i + 1}`);
      return;
    }
  }
};
isSorted(settlements, (a, b) => a[0].localeCompare(b[0]), 'settlements.csv');
isSorted(lines, (a, b) => a[0].localeCompare(b[0], 'hu'), 'railway-lines.csv');
isSorted(relations, (a, b) => a[0].localeCompare(b[0]) || a[1].localeCompare(b[1], 'hu'), 'settlement-railway-lines.csv');

// ------------------------------------------------------------------ counts

const counts = manifest.counts ?? {};
if (counts.settlements !== settlements.length) {
  fail(`manifest says ${counts.settlements} settlements, file has ${settlements.length}`);
}
if (counts.railwayLines !== lines.length) {
  fail(`manifest says ${counts.railwayLines} railway lines, file has ${lines.length}`);
}
if (counts.settlementRailwayLineMappings !== relations.length) {
  fail(`manifest says ${counts.settlementRailwayLineMappings} mappings, file has ${relations.length}`);
}

const covered = new Set(relations.map((r) => r[0]));
if (manifest.coverage?.settlementsWithVerifiedRelations !== covered.size) {
  fail(
    `manifest coverage says ${manifest.coverage?.settlementsWithVerifiedRelations} settlements with ` +
      `relations, file has ${covered.size}`,
  );
}

// There is deliberately no "covered < total settlements => reject COMPLETE" check here.
// That would assume nearly every settlement has a railway line, which is false - most
// genuinely do not, no matter how complete the mapping is. The real structural guard is
// the component/overall consistency check above: an overall COMPLETE claim may not
// contradict a PARTIAL component (ADR 0006).

// ------------------------------------------------------------------- report

console.log(
  `dataset ${manifest.datasetVersion}  ${manifest.verificationStatus} / ${manifest.coverageStatus} / reuse:${manifest.reuseStatus}`,
);
console.log(`  settlements                    ${settlements.length}`);
console.log(`  railway lines                  ${lines.length}`);
console.log(`  settlement-line relations      ${relations.length}`);
console.log(`  settlements with relations     ${covered.size} (${((covered.size / settlements.length) * 100).toFixed(1)}%)`);
console.log(`  lines with relations           ${new Set(relations.map((r) => r[1])).size}`);

if (failures.length > 0) {
  console.error(`\n${failures.length} validation failure(s):`);
  failures.forEach((f) => console.error(`  - ${f}`));
  process.exit(1);
}
console.log('\ncanonical reference dataset is valid.');
