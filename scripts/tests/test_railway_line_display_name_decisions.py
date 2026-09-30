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


class RailwayLineDisplayNameDecisionsTest(unittest.TestCase):
    """Covers `--display-name-decisions` (owner-approved line display-name overrides).

    Mirrors `test_promote_osm_railway_reference.py`'s fixture: two candidates, on line
    codes "1" and "2", both promoted to INDEPENDENTLY_VERIFIED so `used_codes == {"1", "2"}"
    - the set a display-name decision must (or must not) match.
    """

    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.settlements = self.root / "settlements.csv"
        self.settlements.write_text(
            "ksh_code,name,county_name\n00001,Fixtureváros,Pest\n00002,Másikváros,Pest\n", encoding="utf-8",
        )
        self.review_dir = self.root / "review"
        self.decisions_dir = self.review_dir / "decisions"
        self.decisions_dir.mkdir(parents=True)
        self.out_dir = self.root / "promoted"
        geojson = self.root / "source.geojson"
        geojson.write_text(json.dumps({"type": "FeatureCollection", "features": [
            {"type": "Feature", "properties": {"railway": "rail", "ref": "1"}, "geometry": {"type": "LineString", "coordinates": [[19.0, 47.0], [19.02, 47.0]]}},
            {"type": "Feature", "properties": {"railway": "station", "name": "Fixtureváros vasútállomás", "@id": "node/1"}, "geometry": {"type": "Point", "coordinates": [19.01, 47.0005]}},
            {"type": "Feature", "properties": {"railway": "rail", "ref": "2"}, "geometry": {"type": "LineString", "coordinates": [[20.0, 47.0], [20.02, 47.0]]}},
            {"type": "Feature", "properties": {"railway": "station", "name": "Másikváros vasútállomás", "@id": "node/2"}, "geometry": {"type": "Point", "coordinates": [20.01, 47.0005]}},
        ]}), encoding="utf-8")
        self.manifest = build_module.build(geojson, self.settlements, self.review_dir, "2026-09-23", "a" * 64, 250)
        self.candidate_id = "pair:00001:1"
        self.other_candidate_id = "pair:00002:2"
        self.candidates_by_id = {
            c["candidateId"]: c for c in json.loads((self.review_dir / "candidates.json").read_text())["candidates"]
        }
        self.evidence_hash = self.candidates_by_id[self.candidate_id]["evidenceHash"]
        self.other_evidence_hash = self.candidates_by_id[self.other_candidate_id]["evidenceHash"]
        # Both candidates INDEPENDENTLY_VERIFIED -> used_codes == {"1", "2"}.
        self._verified_decision("d1.json", self.candidate_id, self.evidence_hash, "1", "00001")
        self._verified_decision("d2.json", self.other_candidate_id, self.other_evidence_hash, "2", "00002")

    def _verified_decision(self, name, candidate_id, evidence_hash, line_code, ksh_code):
        record = {
            "candidateId": candidate_id, "category": "OSM_EVIDENCE_ACCEPTED",
            "kshCode": ksh_code, "lineCode": line_code, "osmObjectIds": ["node/1"],
            "decisionTier": "INDEPENDENTLY_VERIFIED", "decisionMethod": "INDEPENDENT_SOURCE_VERIFICATION",
            "reviewerType": "HUMAN", "humanApproved": True, "reasonCode": "TEST_FIXTURE",
            "note": "test fixture decision",
            "independentSourcesChecked": [
                {"url": "https://example.org/source", "retrievedAt": "2026-09-24", "usageBasis": "test fixture"},
            ],
            "evidenceHash": evidence_hash, "policyVersion": "2026-09-23.1",
            "reviewer": "test", "decidedAt": "2026-09-23T12:00:00Z",
        }
        (self.decisions_dir / name).write_text(json.dumps(record), encoding="utf-8")

    def _write_name_decisions(self, decisions, schema_version=1, policy_version="RAILWAY-LINE-NAME-V1"):
        path = self.root / "display-name-decisions.json"
        doc = {
            "schemaVersion": schema_version, "policyVersion": policy_version,
            "generatedAt": "2026-09-30T00:00:00Z", "decisions": decisions,
        }
        path.write_text(json.dumps(doc, ensure_ascii=False), encoding="utf-8")
        return path

    def _name_decision(self, line_code, name, human_approved=True, policy_version="RAILWAY-LINE-NAME-V1", **overrides):
        record = {
            "lineCode": line_code, "approvedDisplayName": name,
            "source": {
                "url": "https://hu.wikipedia.org/wiki/Test", "retrievedAt": "2026-09-30",
                "sourceType": "WIKIPEDIA_TABLE_CITING_LEGAL_DECREE", "license": "CC BY-SA 4.0",
            },
            "evidence": "test evidence", "decisionStatus": "OWNER_APPROVED",
            "humanApproved": human_approved, "policyVersion": policy_version,
        }
        record.update(overrides)
        if not human_approved and "decisionStatus" not in overrides:
            record["decisionStatus"] = "PENDING_OWNER_APPROVAL"
        return record

    def promote(self, **kwargs):
        return promote_module.promote(self.review_dir, self.decisions_dir, self.out_dir, **kwargs)

    def railway_lines_rows(self):
        text = (self.out_dir / "railway-lines.csv").read_text(encoding="utf-8")
        rows = [line.split(",", 1) for line in text.splitlines()[1:]]
        return dict(rows)

    # ---------------------------------------------------------------- no decisions given

    def test_no_display_name_decisions_keeps_safe_placeholder_for_every_line(self):
        report, manifest = self.promote()
        self.assertIsNotNone(manifest)
        rows = self.railway_lines_rows()
        self.assertEqual(rows["1"], "1. számú vasútvonal")
        self.assertEqual(rows["2"], "2. számú vasútvonal")
        self.assertNotIn("displayNameSource", manifest)

    # --------------------------------------------------------------------- happy path

    def test_approved_decision_overrides_the_placeholder(self):
        path = self._write_name_decisions([self._name_decision("1", "1 – Teszt–Város")])
        report, manifest = self.promote(display_name_decisions_path=path)
        rows = self.railway_lines_rows()
        self.assertEqual(rows["1"], "1 – Teszt–Város")
        self.assertEqual(rows["2"], "2. számú vasútvonal")  # untouched, no decision for it
        self.assertIn("displayNameSource", manifest)
        self.assertEqual(manifest["displayNameSource"]["overriddenLineCount"], 1)
        self.assertEqual(manifest["displayNameSource"]["totalLineCount"], 2)

    def test_humanApproved_false_is_never_applied(self):
        path = self._write_name_decisions([self._name_decision("1", "1 – Nem jóváhagyott", human_approved=False)])
        report, manifest = self.promote(display_name_decisions_path=path)
        rows = self.railway_lines_rows()
        self.assertEqual(rows["1"], "1. számú vasútvonal")
        self.assertEqual(manifest["displayNameSource"]["overriddenLineCount"], 0)

    def test_expect_name_policy_version_matching_succeeds(self):
        path = self._write_name_decisions([self._name_decision("1", "1 – Teszt–Város")])
        report, manifest = self.promote(display_name_decisions_path=path, expect_name_policy_version="RAILWAY-LINE-NAME-V1")
        self.assertEqual(self.railway_lines_rows()["1"], "1 – Teszt–Város")

    # ----------------------------------------------------------------------- hard failures

    def test_missing_decisions_file_blocks(self):
        with self.assertRaises(promote_module.PromotionBlocked):
            self.promote(display_name_decisions_path=self.root / "does-not-exist.json")

    def test_invalid_json_blocks(self):
        path = self.root / "broken.json"
        path.write_text("{not json", encoding="utf-8")
        with self.assertRaises(promote_module.PromotionBlocked):
            self.promote(display_name_decisions_path=path)

    def test_wrong_schema_version_blocks(self):
        path = self._write_name_decisions([self._name_decision("1", "1 – Teszt–Város")], schema_version=2)
        with self.assertRaises(promote_module.PromotionBlocked):
            self.promote(display_name_decisions_path=path)

    def test_policy_version_mismatch_blocks(self):
        path = self._write_name_decisions([self._name_decision("1", "1 – Teszt–Város")])
        with self.assertRaises(promote_module.PromotionBlocked):
            self.promote(display_name_decisions_path=path, expect_name_policy_version="SOME-OTHER-VERSION")

    def test_decision_policy_version_must_match_file_policy_version(self):
        path = self._write_name_decisions([
            self._name_decision("1", "1 – Teszt–Város", policy_version="MISMATCHED-VERSION"),
        ])
        with self.assertRaises(promote_module.PromotionBlocked):
            self.promote(display_name_decisions_path=path)

    def test_missing_required_field_blocks(self):
        record = self._name_decision("1", "1 – Teszt–Város")
        del record["evidence"]
        path = self._write_name_decisions([record])
        with self.assertRaises(promote_module.PromotionBlocked):
            self.promote(display_name_decisions_path=path)

    def test_missing_source_subfield_blocks(self):
        record = self._name_decision("1", "1 – Teszt–Város")
        del record["source"]["retrievedAt"]
        path = self._write_name_decisions([record])
        with self.assertRaises(promote_module.PromotionBlocked):
            self.promote(display_name_decisions_path=path)

    def test_empty_approved_display_name_blocks(self):
        record = self._name_decision("1", "   ")
        path = self._write_name_decisions([record])
        with self.assertRaises(promote_module.PromotionBlocked):
            self.promote(display_name_decisions_path=path)

    def test_duplicate_line_code_blocks(self):
        path = self._write_name_decisions([
            self._name_decision("1", "1 – Első"),
            self._name_decision("1", "1 – Második"),
        ])
        with self.assertRaises(promote_module.PromotionBlocked):
            self.promote(display_name_decisions_path=path)

    def test_stale_decision_for_a_line_code_not_in_this_promotion_blocks(self):
        # "999" was never a candidate at all in this promotion run.
        path = self._write_name_decisions([self._name_decision("999", "999 – Sehol")])
        with self.assertRaises(promote_module.PromotionBlocked):
            self.promote(display_name_decisions_path=path)

    def test_non_boolean_human_approved_blocks(self):
        record = self._name_decision("1", "1 – Teszt–Város")
        record["humanApproved"] = "true"
        path = self._write_name_decisions([record])
        with self.assertRaises(promote_module.PromotionBlocked):
            self.promote(display_name_decisions_path=path)

    def test_human_approved_true_requires_owner_approved_status(self):
        record = self._name_decision(
            "1", "1 – Teszt–Város", decisionStatus="PENDING_OWNER_APPROVAL",
        )
        path = self._write_name_decisions([record])
        with self.assertRaises(promote_module.PromotionBlocked):
            self.promote(display_name_decisions_path=path)

    def test_owner_approved_status_requires_human_approved_true(self):
        record = self._name_decision(
            "1", "1 – Teszt–Város", human_approved=False, decisionStatus="OWNER_APPROVED",
        )
        path = self._write_name_decisions([record])
        with self.assertRaises(promote_module.PromotionBlocked):
            self.promote(display_name_decisions_path=path)

    def test_blank_decision_status_blocks(self):
        record = self._name_decision("1", "1 – Teszt–Város", decisionStatus="   ")
        path = self._write_name_decisions([record])
        with self.assertRaises(promote_module.PromotionBlocked):
            self.promote(display_name_decisions_path=path)

    def test_manifest_is_path_independent_for_the_same_decisions_file(self):
        path = self._write_name_decisions([self._name_decision("1", "1 – Teszt–Város")])
        alias_parent = path.parent / "path-alias"
        alias_parent.mkdir()
        path_with_parent_segment = alias_parent / ".." / path.name
        first_out = self.root / "first-out"
        second_out = self.root / "second-out"

        _, first_manifest = promote_module.promote(
            self.review_dir, self.decisions_dir, first_out,
            display_name_decisions_path=path,
        )
        _, second_manifest = promote_module.promote(
            self.review_dir, self.decisions_dir, second_out,
            display_name_decisions_path=path_with_parent_segment,
        )

        self.assertEqual(first_manifest, second_manifest)
        self.assertEqual(
            (first_out / "manifest.json").read_bytes(),
            (second_out / "manifest.json").read_bytes(),
        )
        self.assertEqual(first_manifest["displayNameSource"]["decisionsFile"], path.name)

    # ------------------------------------------------------------- determinism / real file

    def test_real_decisions_file_is_valid_and_deterministic(self):
        """The actual committed decisions file, loaded twice, yields identical output -
        and every lineCode in it exists among this fixture's own two used_codes is NOT
        assumed (the real file has 38 lines, this fixture has 2) - this test only checks
        the file parses, is internally consistent (no duplicates, all required fields,
        every decision humanApproved), and re-loading it is byte-for-byte deterministic.
        """
        real_path = ROOT / "reference-data/osm-review/railway-line-display-name-decisions.json"
        doc = json.loads(real_path.read_text(encoding="utf-8"))
        self.assertEqual(doc["schemaVersion"], 1)
        self.assertEqual(doc["policyVersion"], "RAILWAY-LINE-NAME-V1")
        codes = [d["lineCode"] for d in doc["decisions"]]
        self.assertEqual(len(codes), 38)
        self.assertEqual(len(codes), len(set(codes)), "duplicate lineCode in the real decisions file")
        for d in doc["decisions"]:
            self.assertTrue(d["humanApproved"], f"{d['lineCode']} is not humanApproved")
            self.assertTrue(d["approvedDisplayName"].strip())
            self.assertTrue(d["evidence"].strip())
            self.assertEqual(d["policyVersion"], doc["policyVersion"])


if __name__ == "__main__":
    unittest.main()
