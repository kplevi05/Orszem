#!/usr/bin/env python3
"""Build an offline operational proposal; never imports data or changes routing.

Uses only VERIFIED/CLEARED canonical snapshots. Metadata gates are checked before
reading any source CSV. Geographic reference facts and first-party responsibility
configuration remain separate. Run with --check to verify committed output in CI.
"""
import argparse
import csv
import hashlib
import io
import json
from pathlib import Path
import re
import sys

ROOT = Path(__file__).resolve().parents[1]
HEADERS = {
    "settlements.csv": ["ksh_code", "name", "county_name"],
    "railway-lines.csv": ["line_code", "display_name"],
    "settlement-railway-lines.csv": ["ksh_code", "line_code"],
}


class PlanError(ValueError):
    pass


def require(condition, message):
    if not condition:
        raise PlanError(message)


def digest(data):
    return hashlib.sha256(data).hexdigest()


def json_bytes(value):
    return (json.dumps(value, ensure_ascii=False, indent=2, sort_keys=True) + "\n").encode()


def csv_bytes(header, rows):
    out = io.StringIO(newline="")
    writer = csv.writer(out, lineterminator="\n")
    writer.writerow(header)
    writer.writerows(rows)
    return out.getvalue().encode()


def load_dataset(directory, allow_unverified_review=False):
    """Reject unclear metadata before opening CSVs; validate exact source bytes."""
    manifest_bytes = (directory / "manifest.json").read_bytes()
    manifest = json.loads(manifest_bytes)
    require(manifest.get("reuseStatus") == "CLEARED", "Dataset is not CLEARED; source rows were not read")
    verification = manifest.get("verificationStatus")
    require(
        verification == "VERIFIED" or (allow_unverified_review and verification == "UNVERIFIED"),
        "Dataset is not VERIFIED; source rows were not read",
    )
    require(bool(manifest.get("datasetVersion")), "Missing datasetVersion")
    for name in ["settlements", "railwayLines", "settlementRailwayLines"]:
        require(manifest.get("coverage", {}).get(name) in ("COMPLETE", "PARTIAL"), f"Missing coverage: {name}")
    require(manifest["coverage"]["settlements"] == "COMPLETE", "Nationwide plan needs COMPLETE settlement coverage")
    require(manifest.get("coverageStatus") in ("COMPLETE", "PARTIAL"), "Invalid overall coverage")
    if manifest["coverageStatus"] == "COMPLETE":
        require(all(manifest["coverage"][k] == "COMPLETE" for k in ["settlements", "railwayLines", "settlementRailwayLines"]), "Overall COMPLETE contradicts component coverage")
    require(bool(manifest.get("sources")), "Missing source provenance")
    for source in manifest["sources"].values():
        require(all(source.get(k) for k in ["source", "retrievedAt", "sourceVersionOrDate", "used"]), "Incomplete source provenance")
        require(bool(source.get("licence") or source.get("licenceReference")), "Missing source licence")
    tables = {}
    for filename, header in HEADERS.items():
        expected = manifest.get("canonicalFiles", {}).get(filename)
        require(isinstance(expected, str) and re.fullmatch(r"[0-9a-f]{64}", expected), f"Missing checksum: {filename}")
        raw = (directory / filename).read_bytes()
        require(digest(raw) == expected, f"Checksum mismatch: {filename}")
        decoded = raw.decode("utf-8", errors="strict")
        require(not decoded.startswith("\ufeff"), f"BOM not allowed: {filename}")
        parsed = list(csv.reader(io.StringIO(decoded, newline=""), strict=True))
        require(bool(parsed) and parsed[0] == header, f"Incorrect header: {filename}")
        rows = parsed[1:]
        require(all(len(r) == len(header) for r in rows), f"Incorrect row width: {filename}")
        tables[filename] = rows
    settlements, lines, relations = (tables[n] for n in HEADERS)
    for field, rows in [("settlements", settlements), ("railwayLines", lines), ("settlementRailwayLineMappings", relations)]:
        require(manifest.get("counts", {}).get(field) == len(rows), f"Count mismatch: {field}")
    settlement_map = {}
    for code, name, county in settlements:
        require(re.fullmatch(r"[0-9]{5}", code) and name.strip(), "Invalid settlement identity")
        require(code not in settlement_map, f"Duplicate settlement: {code}")
        settlement_map[code] = (name, county)
    line_map = {}
    for code, name in lines:
        require(re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9._-]{0,15}", code) and name.strip(), "Invalid railway line identity")
        require(code not in line_map, f"Duplicate line: {code}")
        line_map[code] = name
    seen = set()
    for code, line in relations:
        require(code in settlement_map and line in line_map, "Dangling settlement-line relation")
        require((code, line) not in seen, "Duplicate settlement-line relation")
        seen.add((code, line))
    require(manifest["coverage"].get("settlementsWithVerifiedRelations") == len({r[0] for r in relations}), "Relation coverage count mismatch")
    return manifest, manifest_bytes, settlement_map, line_map, seen


def build_plan(directory, policy_path, allow_unverified_review=False):
    manifest, manifest_raw, settlements, lines, relations = load_dataset(directory, allow_unverified_review)
    policy_raw = policy_path.read_bytes()
    policy = json.loads(policy_raw)
    require(policy.get("schemaVersion") == 1 and policy.get("kind") == "CUSTOM_OPERATIONAL_TERRITORIES", "Unknown policy schema")
    require(bool(policy.get("policyVersion")), "Missing policyVersion")
    counties = policy.get("counties", {})
    require(len(counties) == 20 and len(set(counties.values())) == 20, "Expected 20 distinct custom areas")
    require(all(re.fullmatch(r"ORSZEM-[A-Z0-9-]+", code) for code in counties.values()), "Invalid area code")
    exceptions = policy.get("settlementExceptions", {})
    require(set(exceptions) <= set(settlements), "Policy exception references an unknown settlement")
    assignments = {}
    reasons = {}
    for code, (name, county) in sorted(settlements.items()):
        if code in exceptions:
            exception = exceptions[code]
            require(name == exception.get("expectedName") and county == exception.get("expectedCounty"), f"Stale policy exception: {code}")
            area = exception.get("areaCode")
            require(area in counties.values() and exception.get("reason"), f"Invalid policy exception: {code}")
            reason = "EXPLICIT_POLICY_EXCEPTION"
        else:
            require(county in counties, f"Unknown county for settlement {code}; no guessed assignment")
            area, reason = counties[county], "KSH_COUNTY"
        assignments[code], reasons[code] = area, reason
    covered = {code for code, _ in relations}
    area_names = {area: "Őrszem – " + ("Budapest" if county == "főváros" else county) for county, area in counties.items()}
    pairs = [(code, line, assignments[code]) for code, line in sorted(relations)]
    line_areas = {line: sorted({assignments[code] for code, related_line in relations if related_line == line}) for line in sorted(lines)}
    assessments = []
    for line, areas in line_areas.items():
        if not areas:
            state = "NO_VERIFIED_SETTLEMENT_RELATION"
        elif len(areas) > 1:
            state = "REQUIRES_SETTLEMENT_LINE_ROUTING"
        elif manifest["coverage"]["settlementRailwayLines"] != "COMPLETE":
            state = "PARTIAL_COVERAGE_NO_WHOLE_LINE_ASSIGNMENT"
        else:
            state = "SINGLE_AREA_IN_COMPLETE_REFERENCE"
        assessments.append((line, state, "|".join(areas)))
    output = {
        "service-areas.csv": csv_bytes(["area_code", "name", "kind"], [(a, area_names[a], "CUSTOM_ORSZEM") for a in sorted(area_names)]),
        "settlement-service-areas.csv": csv_bytes(["ksh_code", "settlement_name", "county_name", "area_code", "basis"], [(k, *settlements[k], assignments[k], reasons[k]) for k in sorted(assignments)]),
        "settlement-line-service-areas.csv": csv_bytes(["ksh_code", "line_code", "area_code"], pairs),
        "line-routing-assessment.csv": csv_bytes(["line_code", "assessment", "area_codes"], assessments),
        "unresolved-settlements.csv": csv_bytes(["ksh_code", "reason"], [(k, "NO_VERIFIED_RAILWAY_LINE_REFERENCE") for k in sorted(set(settlements) - covered)]),
    }
    summary = {
        "schemaVersion": 1,
        "policyVersion": policy["policyVersion"],
        "status": "OFFLINE_PROPOSAL_NOT_IMPORTED" if manifest["verificationStatus"] == "VERIFIED" else "OFFLINE_UNVERIFIED_SOURCE_REVIEW",
        "officialRailwayJurisdiction": False,
        "sourceDatasetVersion": manifest["datasetVersion"],
        "sourceVerificationStatus": manifest["verificationStatus"],
        "sourceManifestSha256": digest(manifest_raw),
        "sourceFiles": manifest["canonicalFiles"],
        "sourceCoverage": manifest["coverage"],
        "policySha256": digest(policy_raw),
        "attribution": {"text": "Forrás: KSH Helységnévtár; saját Őrszem területi besorolással kiegészítve.", "url": "https://www.ksh.hu", "licence": "CC BY 4.0", "licenceUrl": "https://creativecommons.org/licenses/by/4.0/"},
        "sourceProvenance": manifest["sources"],
        "counts": {"areas": len(area_names), "settlements": len(assignments), "railwayLines": len(lines), "settlementLineAreaMappings": len(pairs), "settlementsWithoutVerifiedRailwayRelation": len(settlements) - len(covered), "linesCrossingAreas": sum(len(a) > 1 for a in line_areas.values())},
        "areas": [{"code": a, "name": area_names[a], "settlementCount": sum(v == a for v in assignments.values())} for a in sorted(area_names)],
        "runtimeCompatible": False,
        "runtimeBlocker": (
            "Source dataset is UNVERIFIED; this output is review-only and must not be imported."
            if manifest["verificationStatus"] != "VERIFIED"
            else "No verified railway relations are present in the selected source snapshot."
        ),
        "files": {name: digest(data) for name, data in output.items()},
    }
    output["summary.json"] = json_bytes(summary)
    return output


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--dataset", type=Path, default=ROOT / "reference-data/cleared")
    parser.add_argument("--policy", type=Path, default=ROOT / "operational-data/county-v1/policy.json")
    parser.add_argument("--out", type=Path, default=ROOT / "operational-data/county-v1/generated")
    parser.add_argument("--check", action="store_true", help="Verify byte-for-byte; write nothing")
    parser.add_argument(
        "--allow-unverified-review",
        action="store_true",
        help="Generate an offline review only; output stays runtime-incompatible and never promotes the source",
    )
    args = parser.parse_args()
    try:
        output = build_plan(args.dataset, args.policy, args.allow_unverified_review)
        if args.check:
            for name, data in output.items():
                target = args.out / name
                require(target.is_file() and target.read_bytes() == data, f"Missing or stale output: {name}")
            actual = {p.name for p in args.out.iterdir()}
            require(actual == set(output), "Unexpected generated files; review stale output")
        else:
            require(args.out.resolve() != args.dataset.resolve(), "Output must not replace source data")
            if args.out.exists():
                require({p.name for p in args.out.iterdir()} <= set(output), "Output directory contains unrelated files")
            args.out.mkdir(parents=True, exist_ok=True)
            for name, data in output.items():
                (args.out / name).write_bytes(data)
        summary = json.loads(output["summary.json"])
        print(json.dumps({"status": summary["status"], **summary["counts"]}, ensure_ascii=False))
        print("Offline proposal only. No database or running service was changed.")
    except (PlanError, OSError, ValueError, csv.Error, KeyError, TypeError) as exc:
        print(f"ERROR: {exc}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
