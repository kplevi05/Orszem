#!/usr/bin/env python3
"""Build a review-only Hungarian railway reference snapshot from OSM GeoJSON.

The input is expected to be an ``osmium export`` of railway ways and station/halt
objects.  No network access happens here.  Only stations whose normalized OSM name
matches exactly one KSH settlement and which are within the configured distance of a
referenced railway way become candidate relations.  Everything else is quarantined.

The resulting manifest is deliberately UNVERIFIED, even though OSM reuse is CLEARED
under ODbL.  A human review is required before any separate promotion step may create
an importable VERIFIED snapshot.
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

LINE_CODE = re.compile(r"[A-Za-z0-9][A-Za-z0-9._-]{0,15}")
STATION_SUFFIX = re.compile(r"\s+(?:vasútállomás|állomás|megállóhely|mh\.)$", re.IGNORECASE)
GRID = 0.02


class BuildError(ValueError):
    pass


def require(condition, message):
    if not condition:
        raise BuildError(message)


def sha256_bytes(value):
    return hashlib.sha256(value).hexdigest()


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


def build(geojson_path, settlements_path, output, source_date, source_sha256, maximum_distance):
    settlement_raw, settlements, settlements_by_name = load_settlements(settlements_path)
    document = json.loads(geojson_path.read_text(encoding="utf-8"))
    require(document.get("type") == "FeatureCollection", "OSM input must be a GeoJSON FeatureCollection")

    ways, stations = [], []
    grid = defaultdict(set)
    for feature in document.get("features", []):
        properties = feature.get("properties") or {}
        railway = properties.get("railway")
        lines = geometry_lines(feature.get("geometry"))
        codes = line_codes(properties)
        if railway in {"rail", "narrow_gauge"} and lines and codes:
            for line in lines:
                if len(line) < 2:
                    continue
                index = len(ways)
                ways.append((line, codes))
                for cell in cells_for_line(line):
                    grid[cell].add(index)
        if railway in {"station", "halt"} and properties.get("name"):
            point = station_point(feature.get("geometry"))
            if point:
                stations.append((str(properties["name"]), point, properties.get("@id") or properties.get("id") or ""))

    require(ways, "no referenced railway ways found")
    require(stations, "no named station/halt objects found")
    accepted = set()
    evidence = []
    review = []
    used_codes = set()
    for osm_name, point, osm_id in sorted(stations):
        normalized = normalize_station_name(osm_name)
        matches = settlements_by_name.get(normalized, [])
        if len(matches) != 1:
            review.append((osm_id, osm_name, normalized, "NO_UNIQUE_KSH_NAME", "", ""))
            continue
        cell = math.floor(point[0] / GRID), math.floor(point[1] / GRID)
        candidate_indices = set()
        for dx in (-1, 0, 1):
            for dy in (-1, 0, 1):
                candidate_indices.update(grid.get((cell[0] + dx, cell[1] + dy), ()))
        distances = {}
        for index in candidate_indices:
            line, codes = ways[index]
            distance = distance_to_line_m(point, line)
            if distance <= maximum_distance:
                for code in codes:
                    distances[code] = min(distance, distances.get(code, float("inf")))
        if not distances:
            review.append((osm_id, osm_name, normalized, "NO_REFERENCED_LINE_WITHIN_LIMIT", "", ""))
            continue
        ksh = matches[0]["ksh_code"]
        for code, distance in sorted(distances.items()):
            accepted.add((ksh, code))
            used_codes.add(code)
            evidence.append((ksh, code, osm_id, osm_name, f"{distance:.1f}"))

    output.mkdir(parents=True, exist_ok=True)
    (output / "settlements.csv").write_bytes(settlement_raw)
    write_csv(output / "railway-lines.csv", ["line_code", "display_name"],
              [(code, f"{code}. számú vasútvonal") for code in sorted(used_codes)])
    write_csv(output / "settlement-railway-lines.csv", ["ksh_code", "line_code"], sorted(accepted))
    write_csv(output / "evidence.csv", ["ksh_code", "line_code", "osm_id", "osm_name", "distance_m"], sorted(evidence))
    write_csv(output / "needs-review.csv", ["osm_id", "osm_name", "normalized_name", "reason", "candidate_ksh_code", "candidate_line_code"], review)

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
        "review": {"acceptedByDeterministicRule": len(accepted), "quarantinedStations": len(review), "maximumDistanceMetres": maximum_distance, "automaticImportAllowed": False},
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
    parser.add_argument("--maximum-distance-metres", type=float, default=250.0)
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
