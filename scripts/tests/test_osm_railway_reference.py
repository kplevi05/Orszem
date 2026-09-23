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


if __name__ == "__main__":
    unittest.main()
