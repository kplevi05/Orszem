#!/usr/bin/env python3
"""Build a review-only Hungarian railway reference snapshot from OSM GeoJSON.

The input is expected to be an ``osmium export`` of railway ways and station/halt
objects.  No network access happens here.  Only stations whose normalized OSM name
matches exactly one KSH settlement and which are within the configured distance of an
**ordinary** referenced railway way become deterministic candidate relations. Everything
else is quarantined — including a station whose only nearby evidence is a siding, yard,
spur, industrial or military way (see ``review-policy.json`` §2), and any station whose
name does not match exactly one KSH settlement.

The resulting manifest is deliberately UNVERIFIED, even though OSM reuse is CLEARED
under ODbL. A human review, recorded as decision files under ``decisions/`` (see
``decisions/README.md``) and applied by ``promote-osm-railway-reference.py``, is required
before any candidate relation may become part of an importable VERIFIED snapshot. This
script alone never promotes anything - see ``review-policy.json`` for the full policy
this build enforces.
"""

import argparse
import csv
import hashlib
import json
import math
from collections import defaultdict
from datetime import datetime, timezone
from pathlib import Path
import re
import sys

# Hungarian infrastructure line references are one to three non-zero-leading digits,
# optionally followed by at most two letters (for example 1, 30, 100c, 70AX). A broad
# free-form OSM ref is evidence to review, not permission to create a canonical line -
# syntactic validity alone never implies acceptance (review-policy.json §1).
LINE_CODE = re.compile(r"[1-9][0-9]{0,2}[A-Za-z]{0,2}")
STATION_SUFFIX = re.compile(r"\s+(?:vasútállomás|állomás|megállóhely|mh\.)$", re.IGNORECASE)
GRID = 0.02

# review-policy.json §2: a way tagged with any of these is never ordinary user-selectable
# infrastructure, regardless of its `railway` value. A station whose *only* nearby
# evidence comes from such a way is quarantined, never silently accepted or dropped.
NON_OPERATIONAL_SERVICE = {"siding", "yard", "spur", "crossover"}
NON_OPERATIONAL_USAGE = {"industrial", "military"}

# Defense in depth: the osmium `tags-filter` step already excludes these `railway`
# values upstream (only `rail`/`narrow_gauge` are exported), but a build must never rely
# solely on an upstream filter for a safety property it can check itself.
EXCLUDED_RAILWAY_VALUES = {"construction", "abandoned", "disused", "proposed"}
ORDINARY_RAILWAY_VALUES = {"rail", "narrow_gauge"}


class BuildError(ValueError):
    pass


def require(condition, message):
    if not condition:
        raise BuildError(message)


def sha256_bytes(value):
    return hashlib.sha256(value).hexdigest()


def sha256_text(value):
    return sha256_bytes(value.encode("utf-8"))


def write_csv(path, header, rows):
    with path.open("w", encoding="utf-8", newline="") as handle:
        writer = csv.writer(handle, lineterminator="\n")
        writer.writerow(header)
        writer.writerows(rows)


def normalize_station_name(value):
    return STATION_SUFFIX.sub("", " ".join(value.strip().split())).strip()


def line_codes(properties):
    raw = properties.get("ref") or properties.get("railway:ref") or properties.get("ref:hu") or ""
    result = set()
    for part in re.split(r"[;,/]", str(raw)):
        code = part.strip()
        if LINE_CODE.fullmatch(code):
            result.add(code)
    return result


def way_disposition(properties):
    """Classifies one way's tags per review-policy.json §2.

    Returns ``"excluded"`` (never contributes evidence of any kind - construction,
    abandoned, disused or proposed infrastructure), ``"non_operational"`` (siding, yard,
    spur, industrial or military - contributes quarantine evidence only, never an
    accepted candidate), or ``"ordinary"`` (may contribute an accepted candidate).
    """
    railway = properties.get("railway")
    if railway in EXCLUDED_RAILWAY_VALUES:
        return "excluded"
    if railway not in ORDINARY_RAILWAY_VALUES:
        return "excluded"
    service = (properties.get("service") or "").strip().lower()
    usage = (properties.get("usage") or "").strip().lower()
    if service in NON_OPERATIONAL_SERVICE or usage in NON_OPERATIONAL_USAGE:
        return "non_operational"
    return "ordinary"


def way_tags_summary(properties):
    """The small, fixed set of tags a reviewer needs to see - never the whole tag bag."""
    return {
        "railway": properties.get("railway") or "",
        "service": properties.get("service") or "",
        "usage": properties.get("usage") or "",
        "operator": properties.get("operator") or "",
    }


def geometry_lines(geometry):
    if not geometry:
        return []
    kind, coordinates = geometry.get("type"), geometry.get("coordinates")
    if kind == "LineString":
        return [coordinates]
    if kind == "MultiLineString":
        return coordinates
    return []


def station_point(geometry):
    if not geometry:
        return None
    kind, coordinates = geometry.get("type"), geometry.get("coordinates")
    if kind == "Point":
        return tuple(coordinates[:2])
    rings = coordinates[0] if kind == "Polygon" and coordinates else None
    if rings:
        points = rings[:-1] if len(rings) > 1 and rings[0] == rings[-1] else rings
        return (sum(p[0] for p in points) / len(points), sum(p[1] for p in points) / len(points))
    return None


def distance_to_segment_m(point, start, end):
    lon, lat = point
    scale_x = 111_320.0 * math.cos(math.radians(lat))
    scale_y = 110_540.0
    ax, ay = (start[0] - lon) * scale_x, (start[1] - lat) * scale_y
    bx, by = (end[0] - lon) * scale_x, (end[1] - lat) * scale_y
    dx, dy = bx - ax, by - ay
    if dx == 0 and dy == 0:
        return math.hypot(ax, ay)
    t = max(0.0, min(1.0, -(ax * dx + ay * dy) / (dx * dx + dy * dy)))
    return math.hypot(ax + t * dx, ay + t * dy)


def distance_to_line_m(point, line):
    return min(distance_to_segment_m(point, a, b) for a, b in zip(line, line[1:]))


def cells_for_line(line):
    min_lon, max_lon = min(p[0] for p in line), max(p[0] for p in line)
    min_lat, max_lat = min(p[1] for p in line), max(p[1] for p in line)
    for x in range(math.floor(min_lon / GRID), math.floor(max_lon / GRID) + 1):
        for y in range(math.floor(min_lat / GRID), math.floor(max_lat / GRID) + 1):
            yield x, y


def load_settlements(path):
    raw = path.read_bytes()
    rows = list(csv.DictReader(raw.decode("utf-8").splitlines()))
    require(rows and list(rows[0]) == ["ksh_code", "name", "county_name"], "unexpected settlements header")
    require(all(re.fullmatch(r"[0-9]{5}", row["ksh_code"]) for row in rows), "invalid KSH code")
    by_name = defaultdict(list)
    for row in rows:
        by_name[row["name"]].append(row)
    return raw, rows, by_name


def pair_candidate_id(ksh_code, line_code):
    return f"pair:{ksh_code}:{line_code}"


def osm_candidate_id(osm_id):
    return f"osm:{osm_id}"


def evidence_hash(rows):
    """Deterministic hash of the evidence supporting one candidate.

    A decision recorded against one evidence state must never silently keep applying
    once the underlying OSM/KSH facts change (review-policy.json §7 - stale-decision
    protection). The hash covers every field a reviewer actually saw.
    """
    def distance_str(value):
        return value if isinstance(value, str) else f"{value:.1f}"

    lines = sorted(
        "|".join([r["osm_id"], r["osm_name"], r.get("candidate_line_code", ""), distance_str(r["distance_m"]),
                   r["railway_tag"], r["service_tag"], r["usage_tag"]])
        for r in rows
    )
    return sha256_text("\n".join(lines))


def build(geojson_path, settlements_path, output, source_date, source_sha256, maximum_distance):
    settlement_raw, settlements, settlements_by_name = load_settlements(settlements_path)
    document = json.loads(geojson_path.read_text(encoding="utf-8"))
    require(document.get("type") == "FeatureCollection", "OSM input must be a GeoJSON FeatureCollection")

    ordinary_ways, non_operational_ways, stations = [], [], []
    ordinary_grid, non_operational_grid = defaultdict(set), defaultdict(set)
    for feature in document.get("features", []):
        properties = feature.get("properties") or {}
        lines = geometry_lines(feature.get("geometry"))
        codes = line_codes(properties)
        disposition = way_disposition(properties)
        if disposition != "excluded" and lines and codes:
            bucket, grid = (ordinary_ways, ordinary_grid) if disposition == "ordinary" else (non_operational_ways, non_operational_grid)
            tags = way_tags_summary(properties)
            for line in lines:
                if len(line) < 2:
                    continue
                index = len(bucket)
                bucket.append((line, codes, tags))
                for cell in cells_for_line(line):
                    grid[cell].add(index)
        if properties.get("railway") in {"station", "halt"} and properties.get("name"):
            point = station_point(feature.get("geometry"))
            if point:
                stations.append((
                    str(properties["name"]),
                    point,
                    properties.get("@id") or properties.get("id") or feature.get("id") or "",
                ))

    require(ordinary_ways or non_operational_ways, "no referenced railway ways found")
    require(stations, "no named station/halt objects found")

    def nearby(grid, bucket, point):
        cell = math.floor(point[0] / GRID), math.floor(point[1] / GRID)
        indices = set()
        for dx in (-1, 0, 1):
            for dy in (-1, 0, 1):
                indices.update(grid.get((cell[0] + dx, cell[1] + dy), ()))
        best = {}
        for index in indices:
            line, codes, tags = bucket[index]
            distance = distance_to_line_m(point, line)
            if distance <= maximum_distance:
                for code in codes:
                    if code not in best or distance < best[code][0]:
                        best[code] = (distance, tags)
        return best

    accepted = set()
    evidence = []
    non_operational_evidence = []
    review = []
    used_codes = set()
    for osm_name, point, osm_id in sorted(stations):
        normalized = normalize_station_name(osm_name)
        matches = settlements_by_name.get(normalized, [])
        if len(matches) != 1:
            review.append((osm_id, osm_name, normalized, "NO_UNIQUE_KSH_NAME", "", "", "", "", ""))
            continue
        ksh = matches[0]["ksh_code"]
        # Both bucket's nearby evidence is always computed, independently of one another:
        # a station may have an accepted code via one ordinary way while *also* sitting
        # near a siding/yard on a different code - that second fact is never allowed to
        # go unrecorded just because the station "succeeded" through its other code.
        ordinary = nearby(ordinary_grid, ordinary_ways, point)
        non_operational = nearby(non_operational_grid, non_operational_ways, point)
        for code, (distance, tags) in sorted(ordinary.items()):
            accepted.add((ksh, code))
            used_codes.add(code)
            evidence.append({
                "ksh_code": ksh, "line_code": code, "osm_id": osm_id, "osm_name": osm_name,
                "distance_m": distance, "railway_tag": tags["railway"], "service_tag": tags["service"],
                "usage_tag": tags["usage"], "operator_tag": tags["operator"],
            })
        for code, (distance, tags) in sorted(non_operational.items()):
            non_operational_evidence.append({
                "ksh_code": ksh, "line_code": code, "osm_id": osm_id, "osm_name": osm_name,
                "distance_m": distance, "railway_tag": tags["railway"], "service_tag": tags["service"],
                "usage_tag": tags["usage"], "operator_tag": tags["operator"],
            })
        if ordinary:
            continue
        if non_operational:
            review.append((
                osm_id, osm_name, normalized, "NON_OPERATIONAL_INFRASTRUCTURE_ONLY", ksh,
                ",".join(sorted(non_operational)),
                ",".join(sorted({t["service"] for _, t in non_operational.values() if t["service"]})),
                ",".join(sorted({t["usage"] for _, t in non_operational.values() if t["usage"]})),
                f"{min(d for d, _ in non_operational.values()):.1f}",
            ))
            continue
        review.append((osm_id, osm_name, normalized, "NO_REFERENCED_LINE_WITHIN_LIMIT", ksh, "", "", "", ""))

    output.mkdir(parents=True, exist_ok=True)
    (output / "settlements.csv").write_bytes(settlement_raw)
    write_csv(output / "railway-lines.csv", ["line_code", "display_name"],
              [(code, f"{code}. számú vasútvonal") for code in sorted(used_codes)])
    write_csv(output / "settlement-railway-lines.csv", ["ksh_code", "line_code"], sorted(accepted))

    evidence_header = ["candidate_id", "ksh_code", "line_code", "osm_id", "osm_name", "distance_m",
                        "railway_tag", "service_tag", "usage_tag", "operator_tag"]
    evidence_rows = sorted(
        (pair_candidate_id(r["ksh_code"], r["line_code"]), r["ksh_code"], r["line_code"], r["osm_id"], r["osm_name"],
         f"{r['distance_m']:.1f}", r["railway_tag"], r["service_tag"], r["usage_tag"], r["operator_tag"])
        for r in evidence
    )
    write_csv(output / "evidence.csv", evidence_header, evidence_rows)

    non_op_rows = sorted(
        (osm_candidate_id(r["osm_id"]), r["ksh_code"], r["line_code"], r["osm_id"], r["osm_name"],
         f"{r['distance_m']:.1f}", r["railway_tag"], r["service_tag"], r["usage_tag"], r["operator_tag"])
        for r in non_operational_evidence
    )
    write_csv(output / "non-operational-evidence.csv", evidence_header, non_op_rows)

    write_csv(
        output / "needs-review.csv",
        ["candidate_id", "osm_id", "osm_name", "normalized_name", "reason", "candidate_ksh_code",
         "candidate_line_codes", "service_tags", "usage_tags", "distance_m"],
        sorted((osm_candidate_id(row[0]),) + row for row in review),
    )

    # Machine-readable review queue: one entry per candidate the policy recognises,
    # each carrying the exact evidence hash a decision file must match (staleness gate).
    candidates = []
    by_pair = defaultdict(list)
    for row in evidence_rows:
        by_pair[(row[1], row[2])].append(dict(zip(evidence_header, row)))
    for (ksh, code), rows in sorted(by_pair.items()):
        candidates.append({
            "candidateId": pair_candidate_id(ksh, code),
            "category": "OSM_EVIDENCE_ACCEPTED",
            "kshCode": ksh,
            "lineCode": code,
            "osmObjectIds": sorted({r["osm_id"] for r in rows}),
            "evidenceCount": len(rows),
            "minimumDistanceMetres": min(float(r["distance_m"]) for r in rows),
            "evidenceHash": evidence_hash(rows),
        })
    for row in sorted(review):
        osm_id, osm_name, normalized, reason = row[0], row[1], row[2], row[3]
        is_non_op = reason == "NON_OPERATIONAL_INFRASTRUCTURE_ONLY"
        rows = [{
            "osm_id": osm_id, "osm_name": osm_name, "candidate_line_code": row[5] if is_non_op else "",
            "distance_m": float(row[8]) if is_non_op and row[8] else 0.0,
            "railway_tag": "", "service_tag": row[6] if is_non_op else "", "usage_tag": row[7] if is_non_op else "",
        }]
        candidates.append({
            "candidateId": osm_candidate_id(osm_id),
            "category": reason,
            "kshCode": row[4] or None,
            "lineCode": None,
            "osmObjectIds": [osm_id],
            "osmName": osm_name,
            "normalizedName": normalized,
            "evidenceCount": 1,
            "evidenceHash": evidence_hash(rows),
        })
    (output / "candidates.json").write_text(
        json.dumps({"policyVersion": "2026-09-23.1", "candidates": candidates}, ensure_ascii=False, indent=2, sort_keys=True) + "\n",
        encoding="utf-8",
    )

    canonical = {}
    for name in ("settlements.csv", "railway-lines.csv", "settlement-railway-lines.csv"):
        canonical[name] = sha256_bytes((output / name).read_bytes())
    manifest = {
        "datasetVersion": f"OSM-HU-RAIL-REVIEW-{source_date}",
        "generatedAt": datetime.now(timezone.utc).replace(microsecond=0).isoformat().replace("+00:00", "Z"),
        "verificationStatus": "UNVERIFIED",
        "coverageStatus": "PARTIAL",
        "reuseStatus": "CLEARED",
        "sources": {
            "KSH": {"source": "KSH Helységnévtár", "retrievedAt": "2026-09-07", "sourceVersionOrDate": "state 2025-01-01", "licence": "CC BY 4.0", "used": "settlement identity and county"},
            "OSM": {"source": "Geofabrik Hungary extract of OpenStreetMap", "retrievedAt": source_date, "sourceVersionOrDate": source_date, "licence": "Open Database License (ODbL) 1.0", "used": "railway line references, station/halt names and proximity evidence", "sourceSha256": source_sha256},
        },
        "canonicalFiles": canonical,
        "counts": {"settlements": len(settlements), "railwayLines": len(used_codes), "settlementRailwayLineMappings": len(accepted)},
        "coverage": {"settlements": "COMPLETE", "railwayLines": "PARTIAL", "settlementRailwayLines": "PARTIAL", "settlementsWithVerifiedRelations": len({k for k, _ in accepted})},
        "review": {
            "acceptedByDeterministicRule": len(accepted),
            "quarantinedStations": len(review),
            "nonOperationalInfrastructureOnly": sum(1 for r in review if r[3] == "NON_OPERATIONAL_INFRASTRUCTURE_ONLY"),
            "maximumDistanceMetres": maximum_distance,
            "automaticImportAllowed": False,
            "runtimeCompatible": False,
            "status": "OFFLINE_UNVERIFIED_SOURCE_REVIEW",
            "policyVersion": "2026-09-23.1",
        },
    }
    (output / "manifest.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    return manifest


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--geojson", type=Path, required=True)
    parser.add_argument("--settlements", type=Path, default=Path("reference-data/cleared/settlements.csv"))
    parser.add_argument("--out", type=Path, required=True)
    parser.add_argument("--source-date", required=True)
    parser.add_argument("--source-sha256", required=True)
    parser.add_argument("--maximum-distance-metres", type=float, default=100.0)
    args = parser.parse_args()
    try:
        require(re.fullmatch(r"[0-9]{4}-[0-9]{2}-[0-9]{2}", args.source_date), "source date must be YYYY-MM-DD")
        require(re.fullmatch(r"[0-9a-f]{64}", args.source_sha256), "invalid source SHA-256")
        require(0 < args.maximum_distance_metres <= 500, "distance limit must be in (0, 500]")
        result = build(args.geojson, args.settlements, args.out, args.source_date, args.source_sha256, args.maximum_distance_metres)
        print(json.dumps({"datasetVersion": result["datasetVersion"], **result["counts"], **result["review"]}, ensure_ascii=False))
    except (BuildError, OSError, ValueError, KeyError, TypeError, json.JSONDecodeError) as exc:
        print(f"ERROR: {exc}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
