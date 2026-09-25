import csv
import hashlib
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location("preview", ROOT / "scripts/build-county-territory-preview.py")
preview = importlib.util.module_from_spec(spec)
spec.loader.exec_module(preview)
POLICY = ROOT / "operational-data/county-v2/policy.json"
AREA_IDS = {a["id"] for a in json.loads(POLICY.read_text(encoding="utf-8"))["serviceAreas"].values()}


class CountyTerritoryPreviewTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)

    def dataset(self, settlements, lines, relations, verification="VERIFIED", reuse="CLEARED", tamper=False):
        d = self.root / "ds"
        d.mkdir(exist_ok=True)
        files = {
            "settlements.csv": "ksh_code,name,county_name\n" + "".join(f"{k},{n},{c}\n" for k, n, c in settlements),
            "railway-lines.csv": "line_code,display_name\n" + "".join(f"{k},{n}\n" for k, n in lines),
            "settlement-railway-lines.csv": "ksh_code,line_code\n" + "".join(f"{k},{c}\n" for k, c in relations),
        }
        hashes = {}
        for name, text in files.items():
            (d / name).write_bytes(text.encode("utf-8"))
            hashes[name] = hashlib.sha256(text.encode("utf-8")).hexdigest()
        if tamper:
            hashes["settlements.csv"] = "0" * 64
        manifest = {"datasetVersion": "TEST-1", "verificationStatus": verification, "reuseStatus": reuse, "canonicalFiles": hashes}
        (d / "manifest.json").write_text(json.dumps(manifest), encoding="utf-8")
        return d

    def build(self, ds, gysev=None):
        path = self.root / "gysev.json"
        path.write_text(json.dumps({"candidates": gysev or []}), encoding="utf-8")
        files = preview.build(ds, POLICY, path)
        rows = list(csv.DictReader(files["pair-area-preview.csv"].decode("utf-8").splitlines()))
        return files, rows

    def test_one_line_serves_different_areas_per_settlement(self):
        ds = self.dataset(
            [("00001", "A", "Vas"), ("00002", "B", "Fejér"), ("00003", "C", "Hajdú-Bihar")],
            [("1", "1. vonal")], [("00001", "1"), ("00002", "1"), ("00003", "1")])
        files, rows = self.build(ds)
        self.assertEqual({r["ksh_code"]: r["service_area"] for r in rows},
                         {"00001": "Szombathely", "00002": "Székesfehérvár", "00003": "Debrecen"})
        self.assertIn("1,", files["cross-territory-lines.csv"].decode("utf-8"))

    def test_unknown_or_missing_county_never_gets_an_automatic_area(self):
        ds = self.dataset([("00001", "A", "Ismeretlen"), ("00002", "B", "")], [("1", "x")], [("00001", "1"), ("00002", "1")])
        files, rows = self.build(ds)
        self.assertTrue(all(r["status"] == "UNASSIGNED" and r["service_area_id"] == "" for r in rows))
        self.assertEqual({r["reason"] for r in rows}, {"COUNTY_NOT_IN_POLICY", "MISSING_COUNTY"})
        self.assertEqual(json.loads(files["summary.json"])["unassigned"], 2)

    def test_budapest_and_pest_map_to_budapest_and_the_kshs_capital_value_is_used(self):
        ds = self.dataset([("00001", "Bp", "főváros"), ("00002", "P", "Pest")], [("1", "x")], [("00001", "1"), ("00002", "1")])
        _, rows = self.build(ds)
        self.assertEqual({r["service_area"] for r in rows}, {"Budapest"})

    def test_gysev_is_never_assigned_automatically_and_candidates_are_never_applied(self):
        ds = self.dataset([("00001", "A", "Győr-Moson-Sopron")], [("1", "x")], [("00001", "1")])
        candidate = {"kshCode": "00001", "lineCode": "1", "lineName": "x", "sourceUrl": "https://example.org/p",
                     "retrievedAt": "2026-09-24", "evidence": "test", "confidence": "LOW", "status": "CANDIDATE_NOT_APPROVED"}
        files, rows = self.build(ds, [candidate])
        self.assertEqual(rows[0]["service_area"], "Szombathely")
        summary = json.loads(files["summary.json"])
        self.assertEqual(summary["gysevCheck"], {"automaticallyAssignedPairs": 0, "overrideCandidatesApplied": 0, "overrideCandidatesListed": 1})

    def test_gysev_candidate_must_be_a_verified_pair_with_sources_and_never_cite_pending_material(self):
        ds = self.dataset([("00001", "A", "Vas")], [("1", "x")], [("00001", "1")])
        base = {"kshCode": "00001", "lineCode": "1", "lineName": "x", "sourceUrl": "https://example.org/p",
                "retrievedAt": "2026-09-24", "evidence": "e", "confidence": "LOW", "status": "CANDIDATE_NOT_APPROVED"}
        for bad in ({**base, "kshCode": "99999"}, {**base, "status": "APPROVED"}, {**base, "evidence": "from local-research"}, {**base, "sourceUrl": "http://x"}):
            with self.assertRaises(preview.PreviewError):
                self.build(ds, [bad])

    def test_unverified_or_uncleared_dataset_is_rejected_before_rows_are_read(self):
        for kwargs in ({"verification": "UNVERIFIED"}, {"reuse": "PENDING"}):
            ds = self.dataset([("00001", "A", "Vas")], [("1", "x")], [("00001", "1")], **kwargs)
            (ds / "settlements.csv").write_bytes(b"\xff\xfe not even csv")
            with self.assertRaises(preview.PreviewError):
                self.build(ds)

    def test_checksum_mismatch_and_dangling_relation_are_rejected(self):
        with self.assertRaises(preview.PreviewError):
            self.build(self.dataset([("00001", "A", "Vas")], [("1", "x")], [("00001", "1")], tamper=True))
        with self.assertRaises(preview.PreviewError):
            self.build(self.dataset([("00001", "A", "Vas")], [("1", "x")], [("00009", "1")]))

    def test_apply_payload_contains_only_assigned_pairs_with_null_expected_current_and_the_dataset_version(self):
        ds = self.dataset([("00001", "A", "Vas"), ("00002", "B", "Ismeretlen")], [("1", "x")], [("00001", "1"), ("00002", "1")])
        files, _ = self.build(ds)
        payload = json.loads(files["apply-batch-payload.json"])
        self.assertEqual(payload["expectedReferenceVersion"], "TEST-1")
        self.assertEqual(len(payload["changes"]), 1)
        change = payload["changes"][0]
        self.assertEqual((change["kshCode"], change["lineCode"], change["expectedCurrentServiceAreaId"]), ("00001", "1", None))
        self.assertIn(change["targetServiceAreaId"], AREA_IDS)

    def test_ksh_13578_budapest_aggregate_row_is_assigned_to_budapest_by_id_exception(self):
        ds = self.dataset([("13578", "Budapest", "")], [("1", "x")], [("13578", "1")])
        files, rows = self.build(ds)
        self.assertEqual((rows[0]["service_area"], rows[0]["status"], rows[0]["reason"]), ("Budapest", "ASSIGNED_GEOGRAPHIC", "KSH_ID_EXCEPTION"))
        self.assertEqual(json.loads(files["summary.json"])["kshIdExceptionPairs"], 1)

    def test_another_settlement_with_an_empty_county_stays_unassigned_even_if_named_budapest(self):
        ds = self.dataset([("00001", "Budapest", ""), ("00002", "Másik", "")], [("1", "x")], [("00001", "1"), ("00002", "1")])
        _, rows = self.build(ds)
        self.assertEqual({r["status"] for r in rows}, {"UNASSIGNED"})
        self.assertEqual({r["reason"] for r in rows}, {"MISSING_COUNTY"})

    def test_the_exception_does_not_survive_a_changed_canonical_record_for_the_same_id(self):
        for name, county in (("Nem Budapest", ""), ("Budapest", "Pest2")):
            ds = self.dataset([("13578", name, county)], [("1", "x")], [("13578", "1")])
            _, rows = self.build(ds)
            self.assertEqual(rows[0]["status"], "UNASSIGNED", (name, county))
            self.assertEqual(rows[0]["service_area_id"], "")

    def test_policy_exceptions_must_be_ksh_ids_targeting_an_existing_non_gysev_area(self):
        for mutate in (
            lambda p: p["settlementIdExceptions"].update({"Budapest": p["settlementIdExceptions"]["13578"]}),
            lambda p: p["settlementIdExceptions"]["13578"].update({"serviceArea": "GYSEV"}),
            lambda p: p["settlementIdExceptions"]["13578"].update({"serviceArea": "Nemletezo"}),
            lambda p: p["settlementIdExceptions"]["13578"].pop("reason"),
        ):
            policy = json.loads(POLICY.read_text(encoding="utf-8"))
            mutate(policy)
            bad = self.root / "bad_policy.json"; bad.write_text(json.dumps(policy), encoding="utf-8")
            with self.assertRaises((preview.PreviewError, KeyError)):
                preview.load_policy(bad)

    def test_output_is_deterministic(self):
        ds = self.dataset([("00002", "B", "Fejér"), ("00001", "A", "Vas")], [("1", "x")], [("00002", "1"), ("00001", "1")])
        self.assertEqual(self.build(ds)[0], self.build(ds)[0])

    def test_real_policy_is_valid_covers_every_ksh_county_and_only_targets_the_eight_active_areas(self):
        policy, owner = preview.load_policy(POLICY)
        counties = {r["county_name"] for r in csv.DictReader((ROOT / "reference-data/cleared/settlements.csv").read_text(encoding="utf-8").splitlines())}
        self.assertEqual(counties - {""}, set(owner))
        self.assertEqual(len(AREA_IDS), 8)
        for inactive in policy["untouchedInactiveAreas"]:
            self.assertNotIn(inactive["id"], AREA_IDS)

    def test_policy_with_a_county_in_two_areas_or_a_gysev_county_is_rejected(self):
        policy = json.loads(POLICY.read_text(encoding="utf-8"))
        policy["serviceAreas"]["Pécs"]["counties"].append("Vas")
        bad = self.root / "p1.json"; bad.write_text(json.dumps(policy), encoding="utf-8")
        with self.assertRaises(preview.PreviewError):
            preview.load_policy(bad)
        policy = json.loads(POLICY.read_text(encoding="utf-8"))
        policy["serviceAreas"]["GYSEV"]["counties"] = ["Vas"]
        policy["serviceAreas"]["Szombathely"]["counties"].remove("Vas")
        bad2 = self.root / "p2.json"; bad2.write_text(json.dumps(policy), encoding="utf-8")
        with self.assertRaises(preview.PreviewError):
            preview.load_policy(bad2)


if __name__ == "__main__":
    unittest.main()
