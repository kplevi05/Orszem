#!/usr/bin/env node
/**
 * Őrszem V2 — canonical reference dataset builder.
 *
 * OFFLINE preparation only. This never runs at runtime and never touches the network:
 * it reads local copies of the official sources and writes the canonical CSVs that the
 * backend importer later consumes.
 *
 * ## Inputs (not committed — see reference-data/README.md for how to obtain them)
 *   --ksh      KSH Helységnévtár workbook (.xlsx), unzipped directory
 *   --mav      text extract of the HÜSZ MÁV service-point/line annex
 *   --gysev    text extract of the HÜSZ GYSEV service-point/line annex
 *   --review   manually reviewed decisions (CSV)
 *   --out      output directory for canonical files
 *
 * ## What this does and does not decide
 * Candidate generation is deterministic and exact. A station is proposed as belonging to
 * a settlement only when its name, after stripping a **whitelisted** set of pure
 * station-type suffixes, is exactly equal to a KSH settlement name.
 *
 * There is no similarity score, no edit distance and no threshold. A fuzzy match cannot
 * promote a relation into canonical data, because "probably Tata" is not a fact about the
 * railway and the routing built on top of it would inherit the guess.
 *
 * Everything that is not an exact, unambiguous match is quarantined as NEEDS_REVIEW and
 * excluded from the canonical output unless a human decision in the review file admits it.
 */

import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';

// ---------------------------------------------------------------- arguments

const args = Object.fromEntries(
  process.argv.slice(2).reduce((acc, cur, i, arr) => {
    if (cur.startsWith('--')) acc.push([cur.slice(2), arr[i + 1]]);
    return acc;
  }, []),
);

for (const required of ['ksh', 'mav', 'gysev', 'out']) {
  if (!args[required]) {
    console.error(`missing --${required}`);
    process.exit(2);
  }
}

// ------------------------------------------------------------- normalisation

/** NFC + lower-case + collapsed whitespace. Used only for comparison, never for output. */
const fold = (value) => value.normalize('NFC').trim().toLowerCase().replace(/\s+/g, ' ');

/**
 * Pure station-type suffixes, which describe the kind of facility rather than a different
 * place. Removing them does not change which settlement is meant.
 *
 * Deliberately a short whitelist. Anything not listed here keeps the name intact and will
 * therefore fail the exact match and be quarantined, which is the safe direction.
 */
const STATION_SUFFIX = /\s+(mh\.|pvh\.|ipvk\.|oh\.|vá\.|áll\.|rendezö|rendező|rndz\.)$/iu;

/**
 * Markers that make a station name ambiguous with respect to settlements.
 *
 * Each of these is a real failure mode observed in the source:
 *   -, –, (, )   composite names such as "Budapest-Kelenföld", "Győr-GYSEV nyugati elágazás"
 *   alsó/felső   a district of a settlement, or a separate settlement entirely
 *   elágazás     a junction, which is a point on the network, not a place
 *   szelvény     a chainage marker, not a place at all
 *   ipvk/rakodó  industrial sidings and loading points
 *   oh./határ    border crossings
 *   Budapest     district and terminus naming that never matches a settlement name
 */
const AMBIGUOUS = /[-–(),]|\balsó\b|\bfelső\b|\bkülső\b|\bbelső\b|elágazás|elág\.|szelvény|ipvk|rakodó|országhatár|\boh\.|Budapest/iu;

// --------------------------------------------------------- KSH settlements

/** Reads the KSH workbook from an unzipped .xlsx directory. */
function readSettlements(unzippedXlsxDir) {
  const sharedPath = path.join(unzippedXlsxDir, 'xl', 'sharedStrings.xml');
  const sheetPath = path.join(unzippedXlsxDir, 'xl', 'worksheets', 'sheet1.xml');

  const shared = fs.readFileSync(sharedPath, 'utf8');
  const strings = [...shared.matchAll(/<si>(.*?)<\/si>/gs)].map((m) =>
    [...m[1].matchAll(/<t[^>]*>(.*?)<\/t>/gs)].map((t) => t[1]).join(''),
  );

  const sheet = fs.readFileSync(sheetPath, 'utf8');
  const settlements = [];

  for (const row of sheet.matchAll(/<row[^>]*>(.*?)<\/row>/gs)) {
    const cells = {};
    for (const cell of row[1].matchAll(/<c r="([A-Z]+)\d+"([^>]*)>(?:<v>(.*?)<\/v>)?/gs)) {
      const isShared = /t="s"/.test(cell[2]);
      cells[cell[1]] = cell[3] === undefined ? '' : isShared ? strings[+cell[3]] : cell[3];
    }
    const kshCode = cells.B;
    // The KSH code is the stable external key. Anything not exactly five digits is not a
    // settlement row (headers, notes, totals).
    if (!/^\d{5}$/.test(kshCode || '')) continue;

    settlements.push({
      kshCode,
      name: decodeXml(cells.A || '').trim(),
      countyName: decodeXml(cells.D || '').trim() || null,
    });
  }
  return settlements;
}

const decodeXml = (value) =>
  value
    .replace(/&amp;/g, '&')
    .replace(/&lt;/g, '<')
    .replace(/&gt;/g, '>')
    .replace(/&quot;/g, '"')
    .replace(/&apos;/g, "'");

// ------------------------------------------------------- HÜSZ line/stations

/**
 * Parses a HÜSZ service-point annex text extract.
 *
 * Row shape: `<HÜSZ line no> <IT line no> <5-digit service point code> <name> [metrics]`
 *
 * The line-code pattern is deliberately permissive about shape, because the real codes are
 * more varied than they first appear. All of these occur in the source:
 *
 *   1        plain
 *   1d       letter suffix
 *   102K     three digits with a letter
 *   100EK    multiple trailing letters
 *   12/1     a slash-qualified branch
 *   17 (1)   a parenthesised annotation in the line column
 *
 * An earlier, narrower pattern silently dropped 457 of 2,116 rows — about 22% of the
 * data — including all of line 17. Under-matching here does not fail loudly; it just
 * produces a quieter dataset, so the pattern accepts the full observed alphabet and the
 * count is asserted against the source instead.
 */
const LINE_CODE = '[0-9]{1,4}[A-Za-z]{0,3}(?:\\/[0-9A-Za-z]+)?';

function readStationRows(textFile, source) {
  const rows = [];
  const full = new RegExp(
    `^\\s*(${LINE_CODE})(?:\\s*\\([^)]*\\))?\\s+(${LINE_CODE})(?:\\s*\\([^)]*\\))?\\s+([0-9]{5})\\s+(\\S.*?)\\s*$`,
    'u',
  );
  // A row whose line-number columns are blank: the table prints the line number once and
  // leaves it empty for the remaining service points of that line.
  const continuation = /^\s{6,}([0-9]{5})\s+(\S.*?)\s*$/u;

  let currentLine = null;

  for (const raw of fs.readFileSync(textFile, 'utf8').split(/\r?\n/)) {
    let lineCode;
    let servicePointCode;
    let rest;

    const m = raw.match(full);
    if (m) {
      lineCode = m[1];
      servicePointCode = m[3];
      rest = m[4];
      currentLine = lineCode;
    } else {
      const c = raw.match(continuation);
      // Carry the line number forward. This reads the table's own convention rather than
      // inferring anything: an empty line-number cell means "still the line above". It is
      // only applied after a line number has actually been seen in this file.
      if (!c || !currentLine) continue;
      lineCode = currentLine;
      servicePointCode = c[1];
      rest = c[2];
    }

    // Trailing numeric and Igen/Nem columns are laid out with wide gaps; the name is the
    // first field, so cutting at the first run of two or more spaces isolates it.
    const name = rest.replace(/\s{2,}.*$/u, '').trim();
    if (!name) continue;

    rows.push({ lineCode, servicePointCode, stationName: name, source });
  }
  return rows;
}

/**
 * Counts rows that look like data, independent of the parser.
 *
 * Used as a coverage assertion: if the parser matches materially fewer rows than the
 * source contains, the extraction is defective and the dataset would be quietly
 * incomplete rather than visibly broken.
 */
function countDataRows(textFile) {
  return fs.readFileSync(textFile, 'utf8')
    .split(/\r?\n/)
    .filter((l) => /[0-9]{5}\s+\S/.test(l))
    .length;
}

// ------------------------------------------------------------- manual review

/**
 * Reviewed decisions, as transparent data rather than code.
 *
 * Columns: ksh_code,line_code,decision,source,reason
 * decision is ACCEPT (admit this relation) or REJECT (never propose it again).
 */
function readReview(file) {
  const accepted = new Map();
  const rejected = new Set();
  if (!file || !fs.existsSync(file)) return { accepted, rejected, rows: [] };

  const rows = [];
  const lines = fs.readFileSync(file, 'utf8').split(/\r?\n/).filter((l) => l.trim() && !l.startsWith('#'));
  for (const line of lines.slice(1)) {
    const [kshCode, lineCode, decision, source, reason] = splitCsv(line);
    if (!kshCode || !lineCode) continue;
    rows.push({ kshCode, lineCode, decision, source, reason });
    const key = `${kshCode}|${lineCode}`;
    if (decision === 'ACCEPT') accepted.set(key, { source, reason });
    else rejected.add(key);
  }
  return { accepted, rejected, rows };
}

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
  return out.map((v) => v.trim());
};

const csvCell = (value) => {
  const v = value === null || value === undefined ? '' : String(value);
  return /[",\n]/.test(v) ? `"${v.replace(/"/g, '""')}"` : v;
};

// ------------------------------------------------------------------- build

const settlements = readSettlements(args.ksh);
const stationRows = [
  ...readStationRows(args.mav, 'MAV'),
  ...readStationRows(args.gysev, 'GYSEV'),
];
const review = readReview(args.review);

// Extraction coverage gate. A parser that quietly matches only part of the source would
// produce a dataset that looks fine and is simply missing places, which is the failure
// mode this whole pipeline exists to avoid.
const sourceDataRows = countDataRows(args.mav) + countDataRows(args.gysev);
const extractionCoverage = stationRows.length / sourceDataRows;
if (extractionCoverage < 0.98) {
  console.error(
    `extraction coverage ${(extractionCoverage * 100).toFixed(1)}% ` +
      `(${stationRows.length}/${sourceDataRows} rows) — the annex parser is missing data, refusing to build`,
  );
  process.exit(4);
}

// Index settlements by folded name. A name shared by two settlements can never be matched
// automatically, because the station name alone cannot say which one is meant.
const byName = new Map();
for (const s of settlements) {
  const key = fold(s.name);
  if (!byName.has(key)) byName.set(key, []);
  byName.get(key).push(s);
}

const lines = new Map();
const relations = new Set();
const needsReview = [];
const stats = { exact: 0, ambiguousMarker: 0, noMatch: 0, duplicateName: 0, manualAccepted: 0, manualRejected: 0 };

for (const row of stationRows) {
  if (!lines.has(row.lineCode)) {
    lines.set(row.lineCode, { lineCode: row.lineCode, source: row.source, stations: [] });
  }
  lines.get(row.lineCode).stations.push(row.stationName);

  const stripped = row.stationName.normalize('NFC').replace(STATION_SUFFIX, '').trim();
  const candidates = byName.get(fold(stripped));

  let reason = null;
  if (AMBIGUOUS.test(row.stationName)) reason = 'AMBIGUOUS_NAME';
  else if (!candidates) reason = 'NO_SETTLEMENT_MATCH';
  else if (candidates.length > 1) reason = 'SETTLEMENT_NAME_NOT_UNIQUE';

  if (reason) {
    stats[reason === 'AMBIGUOUS_NAME' ? 'ambiguousMarker' : reason === 'NO_SETTLEMENT_MATCH' ? 'noMatch' : 'duplicateName']++;
    needsReview.push({
      stationName: row.stationName,
      lineCode: row.lineCode,
      servicePointCode: row.servicePointCode,
      source: row.source,
      reason,
    });
    continue;
  }

  const settlement = candidates[0];
  const key = `${settlement.kshCode}|${row.lineCode}`;
  if (review.rejected.has(key)) { stats.manualRejected++; continue; }

  stats.exact++;
  relations.add(key);
}

// Admit manually reviewed relations. These are decisions a person made against an
// authoritative source; they are data in the review file, never conditions in code.
for (const [key] of review.accepted) {
  const [kshCode, lineCode] = key.split('|');
  if (!settlements.some((s) => s.kshCode === kshCode)) {
    console.error(`review references unknown settlement ${kshCode}`);
    process.exit(3);
  }
  if (!lines.has(lineCode)) {
    console.error(`review references unknown line ${lineCode}`);
    process.exit(3);
  }
  if (!relations.has(key)) stats.manualAccepted++;
  relations.add(key);
}

// ------------------------------------------------------------------ output

fs.mkdirSync(args.out, { recursive: true });

// Deterministic ordering everywhere, so a regenerated file produces a reviewable Git diff
// rather than a reshuffle.
const settlementsOut = [...settlements].sort((a, b) => a.kshCode.localeCompare(b.kshCode));
writeCsv(path.join(args.out, 'settlements.csv'), ['ksh_code', 'name', 'county_name'],
  settlementsOut.map((s) => [s.kshCode, s.name, s.countyName]));

const linesOut = [...lines.values()].sort((a, b) => a.lineCode.localeCompare(b.lineCode, 'hu'));
writeCsv(path.join(args.out, 'railway-lines.csv'), ['line_code', 'display_name'],
  linesOut.map((l) => [l.lineCode, displayNameFor(l)]));

const relationsOut = [...relations].map((k) => k.split('|'))
  .sort((a, b) => a[0].localeCompare(b[0]) || a[1].localeCompare(b[1], 'hu'));
writeCsv(path.join(args.out, 'settlement-railway-lines.csv'), ['ksh_code', 'line_code'], relationsOut);

// The quarantine is written out too. It is not canonical data, but it is the evidence of
// what was deliberately excluded, and the worklist for future manual review.
needsReview.sort((a, b) => a.lineCode.localeCompare(b.lineCode, 'hu') || a.stationName.localeCompare(b.stationName, 'hu'));
writeCsv(path.join(args.out, '..', 'review', 'needs-review.csv'),
  ['line_code', 'service_point_code', 'station_name', 'source', 'reason'],
  needsReview.map((r) => [r.lineCode, r.servicePointCode, r.stationName, r.source, r.reason]));

function displayNameFor(line) {
  // First and last service point on the line, which is how the network refers to it.
  const first = line.stations[0];
  const last = line.stations[line.stations.length - 1];
  return first === last ? first : `${first} – ${last}`;
}

function writeCsv(file, header, rows) {
  fs.mkdirSync(path.dirname(file), { recursive: true });
  const body = [header.join(','), ...rows.map((r) => r.map(csvCell).join(','))].join('\n');
  fs.writeFileSync(file, body + '\n', 'utf8');
}

const covered = new Set(relationsOut.map((r) => r[0]));
const summary = {
  settlements: settlementsOut.length,
  railwayLines: linesOut.length,
  relations: relationsOut.length,
  settlementsWithRelations: covered.size,
  stationRows: stationRows.length,
  sourceDataRows,
  extractionCoverage: Number(extractionCoverage.toFixed(4)),
  needsReview: needsReview.length,
  ...stats,
};
console.log(JSON.stringify(summary, null, 2));
fs.writeFileSync(path.join(args.out, '..', 'review', 'build-summary.json'), JSON.stringify(summary, null, 2) + '\n');

// ---------------------------------------------------------------- manifest

const sha256 = (file) =>
  crypto.createHash('sha256').update(fs.readFileSync(file)).digest('hex');

const manifest = {
  datasetVersion: args.version ?? '2026.09.07-1',
  generatedAt: args.generatedAt ?? new Date().toISOString(),

  /**
   * Correctness and coverage are independent.
   *
   * VERIFIED says every row that IS present passed validation against an authoritative
   * source. It does not claim the dataset covers every settlement in Hungary.
   *
   * PARTIAL says exactly that it does not. Routing therefore treats a missing relation as
   * "no verified reference", never as "no railway exists here" — the difference between
   * an honest gap and a false statement about the country.
   */
  verificationStatus: 'VERIFIED',
  coverageStatus: 'PARTIAL',

  /**
   * Correctness and reuse rights are a THIRD independent axis, distinct from both of the
   * above. VERIFIED proves the data is factually right; it says nothing about whether we
   * are cleared to redistribute it outside the running service.
   *
   * PENDING: the reuse basis recorded under `sources` is sound for internal use and for
   * running the service, but no source has issued an explicit written reuse grant. The
   * backend importer refuses to import a PENDING dataset into a production database — see
   * ADR 0006. CLEARED is set only once that written confirmation exists, and is never
   * assumed by this tool.
   */
  reuseStatus: args.reuseStatus ?? 'PENDING',

  sources: {
    KSH: {
      source: 'Központi Statisztikai Hivatal — Magyarország helységnévtára',
      url: 'https://www.ksh.hu/docs/helysegnevtar/hnt_letoltes_2025.xlsx',
      retrievedAt: '2026-09-07',
      sourceVersionOrDate: '2025-01-01',
      sha256: 'd059a14883a27963daa234d9f3954eb68b4cbf967e796710a4bcdb4f03437991',
      licence: 'CC BY 4.0',
      attribution: 'Forrás: KSH — https://www.ksh.hu',
      used: 'settlement identity (ksh_code, name, county)',
    },
    HUSZ_MAV: {
      source: 'VPE (KTI VPE Igazgatóság) — Hálózati Üzletszabályzat 2026/2027, 5.2-4 sz. melléklet, MÁV szolgálati hely és vonalkategóriák',
      url: 'https://vpe.kti.hu/wp-content/uploads/2026/09/husz-2026-2027-ae-sz-modositas.zip',
      retrievedAt: '2026-09-07',
      sourceVersionOrDate: 'HÜSZ 2026/2027, AE. sz. módosítás',
      sha256: null,
      licenceReference: 'Mandatory network statement under Directive 2012/34/EU Art. 27; public-body data under Hungarian Act LXIII of 2012 on the reuse of public data. Only factual rows are derived; the source document is not redistributed. Owner confirmation recommended before external distribution.',
      used: 'railway line codes and their service points',
    },
    HUSZ_GYSEV: {
      source: 'VPE (KTI VPE Igazgatóság) — Hálózati Üzletszabályzat 2026/2027, 5.2-5 sz. melléklet, GYSEV szolgálati hely és vonalkategóriák',
      url: 'https://vpe.kti.hu/wp-content/uploads/2026/09/husz-2026-2027-ae-sz-modositas.zip',
      retrievedAt: '2026-09-07',
      sourceVersionOrDate: 'HÜSZ 2026/2027, D. sz. módosítás',
      sha256: null,
      licenceReference: 'As HUSZ_MAV. Note this is the VPE-published regulatory annex, not content from gysev.hu, whose own legal notice forbids placing site content in a database without written permission.',
      used: 'GYSEV railway line codes and their service points',
    },
  },

  canonicalFiles: {
    'settlements.csv': sha256(path.join(args.out, 'settlements.csv')),
    'railway-lines.csv': sha256(path.join(args.out, 'railway-lines.csv')),
    'settlement-railway-lines.csv': sha256(path.join(args.out, 'settlement-railway-lines.csv')),
  },

  counts: {
    settlements: settlementsOut.length,
    railwayLines: linesOut.length,
    settlementRailwayLineMappings: relationsOut.length,
  },

  coverage: {
    /**
     * Coverage is per-component, not one blanket flag, because absence means something
     * different in each roster - see ADR 0006 and PHASE_3B_DECISION_GATE.md SS7.
     *
     * settlements: COMPLETE - KSH publishes the full settlement registry, so this dataset
     * lists every settlement KSH recognises.
     *
     * railwayLines / settlementRailwayLines: PARTIAL - the HUSZ annexes were extracted with
     * a deterministic parser and a quarantine step that makes no claim of network-wide
     * completeness. The backend importer treats a component absent under PARTIAL as
     * "unknown", never as "removed" - it will not deactivate a railway line or delete a
     * relation just because a later snapshot omits it.
     */
    settlements: args.settlementsCoverage ?? 'COMPLETE',
    railwayLines: args.railwayLinesCoverage ?? 'PARTIAL',
    settlementRailwayLines: args.relationsCoverage ?? 'PARTIAL',

    settlementsWithVerifiedRelations: covered.size,
    settlementsWithoutVerifiedRelations: settlementsOut.length - covered.size,
    railwayLinesWithVerifiedRelations: new Set(relationsOut.map((r) => r[1])).size,
    quarantinedCandidates: needsReview.length,
    sourceExtractionCoverage: Number(extractionCoverage.toFixed(4)),
  },

  manualReviewVersion: args.review ? path.basename(args.review) : 'none',
};

const manifestPath = path.join(args.out, 'manifest.json');
fs.writeFileSync(manifestPath, JSON.stringify(manifest, null, 2) + '\n', 'utf8');
console.log(`manifest written: ${manifestPath}`);
