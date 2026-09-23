import importlib.util
import json
from pathlib import Path
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]

build_spec = importlib.util.spec_from_file_location("osm_build", ROOT / "scripts/build-osm-railway-reference.py")
build_module = importlib.util.module_from_spec(build_spec)
build_spec.loader.exec_module(build_module)

promote_spec = importlib.util.spec_from_file_location("osm_promote", ROOT / "scripts/promote-osm-railway-reference.py")
promote_module = importlib.util.module_from_spec(promote_spec)
promote_spec.loader.exec_module(promote_module)


class PromoteOsmRailwayReferenceTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.settlements = self.root / "settlements.csv"
        self.settlements.write_text("ksh_code,name,county_name\n00001,Fixtureváros,Pest\n", encoding="utf-8")
        self.review_dir = self.root / "review"
        self.decisions_dir = self.review_dir / "decisions"
        self.decisions_dir.mkdir(parents=True)
        self.out_dir = self.root / "promoted"
        geojson = self.root / "source.geojson"
        geojson.write_text(json.dumps({"type": "FeatureCollection", "features": [
            {"type": "Feature", "properties": {"railway": "rail", "ref": "1"}, "geometry": {"type": "LineString", "coordinates": [[19.0, 47.0], [19.02, 47.0]]}},
            {"type": "Feature", "properties": {"railway": "station", "name": "Fixtureváros vasútállomás", "@id": "node/1"}, "geometry": {"type": "Point", "coordinates": [19.01, 47.0005]}},
        ]}), encoding="utf-8")
        self.manifest = build_module.build(geojson, self.settlements, self.review_dir, "2026-09-23", "a" * 64, 250)
        self.candidate_id = "pair:00001:1"
        self.evidence_hash = next(
            c for c in json.loads((self.review_dir / "candidates.json").read_text())["candidates"]
            if c["candidateId"] == self.candidate_id
        )["evidenceHash"]

    def decision(self, name, **overrides):
        record = {
            "candidateId": self.candidate_id, "category": "ORDINARY_ACCEPTED_BY_RULE",
            "kshCode": "00001", "lineCode": "1", "osmObjectIds": ["node/1"],
            "decision": "ACCEPTED", "reasonCode": "TEST_FIXTURE", "note": "test fixture decision",
            "sourceUrl": "https://www.openstreetmap.org/node/1", "evidenceHash": self.evidence_hash,
            "policyVersion": "2026-09-23.1", "reviewer": "test", "decidedAt": "2026-09-23T12:00:00Z",
        }
        record.update(overrides)
        (self.decisions_dir / name).write_text(json.dumps(record), encoding="utf-8")

    def promote(self):
        return promote_module.promote(self.review_dir, self.decisions_dir, self.out_dir)

    def test_unreviewed_candidate_cannot_be_promoted(self):
        report, manifest = self.promote()
        self.assertIsNone(manifest)
        self.assertFalse(report["readyForPromotion"])
        self.assertIn(self.candidate_id, report["missingDecision"])
        self.assertFalse((self.out_dir / "manifest.json").exists())

    def test_accepted_current_decision_promotes_successfully(self):
        self.decision("pair_00001_1.json")
        report, manifest = self.promote()
        self.assertTrue(report["readyForPromotion"])
        self.assertEqual(manifest["verificationStatus"], "VERIFIED")
        self.assertTrue(manifest["review"]["automaticImportAllowed"])
        self.assertTrue(manifest["review"]["runtimeCompatible"])
        self.assertIn("00001,1", (self.out_dir / "settlement-railway-lines.csv").read_text())

    def test_rejected_candidate_never_appears_in_promoted_canonical_csv(self):
        self.decision("pair_00001_1.json", decision="REJECTED")
        report, manifest = self.promote()
        self.assertIsNone(manifest)
        self.assertIn(self.candidate_id, report["rejected"])

    def test_stale_decision_blocks_promotion(self):
        self.decision("pair_00001_1.json", evidenceHash="0" * 64)
        report, manifest = self.promote()
        self.assertIsNone(manifest)
        self.assertIn(self.candidate_id, report["staleDecision"])

    def test_decision_for_unknown_candidate_id_blocks_promotion(self):
        self.decision("pair_00001_1.json")
        self.decision("pair_99999_1.json", candidateId="pair:99999:1", kshCode="99999")
        report, manifest = self.promote()
        self.assertIsNone(manifest)
        self.assertIn("pair:99999:1", report.get("unknownDecisions", []))

    def test_blank_approval_file_is_rejected_not_treated_as_a_decision(self):
        (self.decisions_dir / "blank.json").write_text(json.dumps({"approved": True}), encoding="utf-8")
        with self.assertRaises(promote_module.PromotionBlocked):
            self.promote()

    def test_wrong_policy_version_decision_blocks_promotion(self):
        self.decision("pair_00001_1.json", policyVersion="2020-01-01.0")
        report, manifest = self.promote()
        self.assertIsNone(manifest)
        self.assertIn(self.candidate_id, report["wrongPolicyVersion"])

    def test_expect_policy_version_mismatch_blocks_before_reading_decisions(self):
        with self.assertRaises(promote_module.PromotionBlocked):
            promote_module.promote(self.review_dir, self.decisions_dir, self.out_dir, expect_policy_version="not-the-real-version")

    def test_promoted_output_is_deterministic(self):
        self.decision("pair_00001_1.json")
        _, manifest1 = self.promote()
        out2 = self.root / "promoted2"
        _, manifest2 = promote_module.promote(self.review_dir, self.decisions_dir, out2)
        self.assertEqual(manifest1["canonicalFiles"], manifest2["canonicalFiles"])


if __name__ == "__main__":
    unittest.main()
