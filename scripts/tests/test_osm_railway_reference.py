import csv
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location("osm_reference", ROOT / "scripts/build-osm-railway-reference.py")
builder = importlib.util.module_from_spec(spec)
spec.loader.exec_module(builder)


class OsmRailwayReferenceTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.settlements = self.root / "settlements.csv"
        self.settlements.write_text("ksh_code,name,county_name\n00001,Fixtureváros,Pest\n00002,Azonos,Pest\n00003,Azonos,Fejér\n", encoding="utf-8")

    def build(self, features):
        source = self.root / "source.geojson"
        source.write_text(json.dumps({"type": "FeatureCollection", "features": features}), encoding="utf-8")
        out = self.root / "out"
        return out, builder.build(source, self.settlements, out, "2026-09-23", "a" * 64, 250)

    @staticmethod
    def way(ref="1", latitude=47.0):
        return {"type": "Feature", "properties": {"railway": "rail", "ref": ref}, "geometry": {"type": "LineString", "coordinates": [[19.0, latitude], [19.02, latitude]]}}

    @staticmethod
    def station(name="Fixtureváros", latitude=47.0005):
        return {"type": "Feature", "properties": {"railway": "station", "name": name, "@id": "node/1"}, "geometry": {"type": "Point", "coordinates": [19.01, latitude]}}

    def test_exact_unique_station_near_referenced_way_creates_review_candidate(self):
        out, manifest = self.build([self.way(), self.station("Fixtureváros vasútállomás")])
        relations = list(csv.reader((out / "settlement-railway-lines.csv").read_text().splitlines()))
        self.assertEqual(relations[1], ["00001", "1"])
        self.assertEqual(manifest["verificationStatus"], "UNVERIFIED")
        self.assertEqual(manifest["reuseStatus"], "CLEARED")
        self.assertFalse(manifest["review"]["automaticImportAllowed"])

    def test_ambiguous_name_is_quarantined_and_never_guessed(self):
        out, manifest = self.build([self.way(), self.station("Azonos")])
        self.assertEqual(manifest["counts"]["settlementRailwayLineMappings"], 0)
        self.assertIn("NO_UNIQUE_KSH_NAME", (out / "needs-review.csv").read_text())

    def test_unreferenced_and_far_ways_never_create_relations(self):
        unreferenced = self.way(); unreferenced["properties"].pop("ref")
        out, manifest = self.build([unreferenced, self.way("2", 47.1), self.station()])
        self.assertEqual(manifest["counts"]["settlementRailwayLineMappings"], 0)
        self.assertIn("NO_REFERENCED_LINE_WITHIN_LIMIT", (out / "needs-review.csv").read_text())

    def test_multiple_nearby_referenced_lines_preserve_junction_membership(self):
        out, manifest = self.build([self.way("1"), self.way("2", 47.0008), self.station()])
        self.assertEqual(manifest["counts"]["settlementRailwayLineMappings"], 2)
        self.assertIn("00001,1", (out / "settlement-railway-lines.csv").read_text())
        self.assertIn("00001,2", (out / "settlement-railway-lines.csv").read_text())

    def test_invalid_line_ref_is_ignored(self):
        out, manifest = self.build([self.way("not a valid railway ref"), self.way("2", 47.1), self.station()])
        self.assertEqual(manifest["counts"]["railwayLines"], 0)
        self.assertEqual(manifest["counts"]["settlementRailwayLineMappings"], 0)

    def test_siding_only_evidence_is_quarantined_not_accepted(self):
        siding = self.way(); siding["properties"]["service"] = "siding"
        out, manifest = self.build([siding, self.station()])
        self.assertEqual(manifest["counts"]["settlementRailwayLineMappings"], 0)
        self.assertEqual(manifest["review"]["nonOperationalInfrastructureOnly"], 1)
        review = (out / "needs-review.csv").read_text()
        self.assertIn("NON_OPERATIONAL_INFRASTRUCTURE_ONLY", review)
        non_op = (out / "non-operational-evidence.csv").read_text()
        self.assertIn("siding", non_op)

    def test_industrial_usage_only_evidence_is_quarantined(self):
        industrial = self.way(); industrial["properties"]["usage"] = "industrial"
        out, manifest = self.build([industrial, self.station()])
        self.assertEqual(manifest["counts"]["settlementRailwayLineMappings"], 0)
        self.assertIn("NON_OPERATIONAL_INFRASTRUCTURE_ONLY", (out / "needs-review.csv").read_text())

    def test_station_with_both_ordinary_and_non_operational_evidence_keeps_ordinary_and_still_records_non_operational(self):
        ordinary = self.way("1")
        siding = self.way("2", 47.0008); siding["properties"]["service"] = "yard"
        out, manifest = self.build([ordinary, siding, self.station()])
        self.assertEqual(manifest["counts"]["settlementRailwayLineMappings"], 1)
        self.assertIn("00001,1", (out / "settlement-railway-lines.csv").read_text())
        self.assertNotIn("00001,2", (out / "settlement-railway-lines.csv").read_text())
        self.assertEqual(manifest["review"]["nonOperationalInfrastructureOnly"], 0, "station accepted via code 1; code 2's yard evidence must not trigger station-level quarantine")
        self.assertIn("yard", (out / "non-operational-evidence.csv").read_text())

    def test_abandoned_and_disused_ways_never_contribute_evidence_even_if_present_in_input(self):
        # A real distant way keeps the input non-empty; the near abandoned/disused ways
        # must not be what saves the station from quarantine.
        abandoned = self.way(); abandoned["properties"]["railway"] = "abandoned"
        disused = self.way("2", 47.0005); disused["properties"]["railway"] = "disused"
        far_ordinary = self.way("3", 47.5)
        out, manifest = self.build([abandoned, disused, far_ordinary, self.station()])
        self.assertEqual(manifest["counts"]["settlementRailwayLineMappings"], 0)
        self.assertEqual(manifest["review"]["nonOperationalInfrastructureOnly"], 0)
        self.assertIn("NO_REFERENCED_LINE_WITHIN_LIMIT", (out / "needs-review.csv").read_text())

    def test_accepted_candidate_has_stable_pair_candidate_id_and_evidence_hash(self):
        out, manifest = self.build([self.way(), self.station()])
        candidates = json.loads((out / "candidates.json").read_text())["candidates"]
        pair = next(c for c in candidates if c["category"] == "ORDINARY_ACCEPTED_BY_RULE")
        self.assertEqual(pair["candidateId"], "pair:00001:1")
        self.assertEqual(pair["kshCode"], "00001")
        self.assertEqual(pair["lineCode"], "1")
        self.assertTrue(pair["evidenceHash"])

    def test_deterministic_rebuild_produces_byte_identical_output(self):
        out1, _ = self.build([self.way(), self.station()])
        first = (out1 / "candidates.json").read_text()
        out2, _ = self.build([self.way(), self.station()])
        second = (out2 / "candidates.json").read_text()
        self.assertEqual(first, second)

    def test_changed_source_geometry_changes_evidence_hash(self):
        out1, _ = self.build([self.way(), self.station(latitude=47.0005)])
        hash1 = self._pair_hash(out1)
        out2, _ = self.build([self.way(), self.station(latitude=47.0007)])
        hash2 = self._pair_hash(out2)
        self.assertNotEqual(hash1, hash2, "moving the station must change the evidence hash so a stale decision is detectable")

    def _pair_hash(self, out):
        candidates = json.loads((out / "candidates.json").read_text())["candidates"]
        return next(c for c in candidates if c["category"] == "ORDINARY_ACCEPTED_BY_RULE")["evidenceHash"]


if __name__ == "__main__":
    unittest.main()
