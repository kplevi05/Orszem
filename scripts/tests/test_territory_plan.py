"""No network, database, PENDING rows, or real railway fixtures."""
import importlib.util
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location("territory_plan", ROOT / "scripts/build-territory-plan.py")
plan = importlib.util.module_from_spec(spec)
spec.loader.exec_module(plan)
POLICY = ROOT / "operational-data/county-v1/policy.json"


class TerritoryPlanTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.dataset = Path(self.tmp.name) / "source"
        self.dataset.mkdir()

    def fixture(self, settlements=None, lines=None, relations=None, coverage="PARTIAL"):
        # Deliberately invented settlements and lines; never a source of production rows.
        settlements = settlements if settlements is not None else [("00001", "Fixture A", "Komárom-Esztergom"), ("00002", "Fixture B", "Pest")]
        lines = lines if lines is not None else [("TEST-A", "Fictional line A")]
        relations = relations if relations is not None else [("00001", "TEST-A"), ("00002", "TEST-A")]
        tables = dict(zip(plan.HEADERS, [settlements, lines, relations]))
        manifest = {
            "datasetVersion": "TEST-ONLY", "verificationStatus": "VERIFIED", "reuseStatus": "CLEARED", "coverageStatus": "PARTIAL",
            "sources": {"TEST": {"source": "Invented test fixture", "retrievedAt": "2026-09-22", "sourceVersionOrDate": "1", "used": "test", "licence": "first-party"}},
            "coverage": {"settlements": "COMPLETE", "railwayLines": "PARTIAL", "settlementRailwayLines": coverage, "settlementsWithVerifiedRelations": len({r[0] for r in relations})},
            "counts": {"settlements": len(settlements), "railwayLines": len(lines), "settlementRailwayLineMappings": len(relations)}, "canonicalFiles": {},
        }
        for name, rows in tables.items():
            data = plan.csv_bytes(plan.HEADERS[name], rows)
            (self.dataset / name).write_bytes(data)
            manifest["canonicalFiles"][name] = plan.digest(data)
        self.manifest(manifest)
        # This fixture has no Budapest row, so remove the production-only exception.
        policy = json.loads(POLICY.read_text())
        policy["settlementExceptions"] = {}
        self.policy = Path(self.tmp.name) / "policy.json"
        self.policy.write_bytes(plan.json_bytes(policy))
        return manifest

    def manifest(self, manifest):
        (self.dataset / "manifest.json").write_bytes(plan.json_bytes(manifest))

    def build(self):
        return plan.build_plan(self.dataset, self.policy)

    def test_real_catalogue_all_rows_once_tata_and_budapest(self):
        output = plan.build_plan(ROOT / "reference-data/cleared", POLICY)
        rows = list(plan.csv.DictReader(plan.io.StringIO(output["settlement-service-areas.csv"].decode())))
        self.assertEqual(len(rows), 3178)
        self.assertEqual(len({r["ksh_code"] for r in rows}), 3178)
        self.assertEqual(next(r["area_code"] for r in rows if r["ksh_code"] == "20127"), "ORSZEM-KOMAROM-ESZTERGOM")
        budapest = [r for r in rows if r["area_code"] == "ORSZEM-BUDAPEST"]
        self.assertEqual(len(budapest), 24)
        self.assertEqual(next(r["settlement_name"] for r in budapest if r["ksh_code"] == "13578"), "Budapest")
        summary = json.loads(output["summary.json"])
        self.assertEqual(summary["counts"]["areas"], 20)
        self.assertEqual(summary["counts"]["settlementLineAreaMappings"], 0)
        self.assertEqual(summary["counts"]["settlementsWithoutVerifiedRailwayRelation"], 3178)

    def test_cross_area_line_keeps_both_pair_assignments(self):
        self.fixture()
        output = self.build()
        self.assertIn(b"00001,TEST-A,ORSZEM-KOMAROM-ESZTERGOM", output["settlement-line-service-areas.csv"])
        self.assertIn(b"00002,TEST-A,ORSZEM-PEST", output["settlement-line-service-areas.csv"])
        self.assertIn(b"REQUIRES_SETTLEMENT_LINE_ROUTING", output["line-routing-assessment.csv"])
        self.assertEqual(json.loads(output["summary.json"])["counts"]["linesCrossingAreas"], 1)

    def test_partial_single_area_never_implies_entire_line(self):
        self.fixture(relations=[("00001", "TEST-A")])
        self.assertIn(b"PARTIAL_COVERAGE_NO_WHOLE_LINE_ASSIGNMENT", self.build()["line-routing-assessment.csv"])

    def test_complete_single_area_is_distinguished(self):
        self.fixture(relations=[("00001", "TEST-A")], coverage="COMPLETE")
        self.assertIn(b"SINGLE_AREA_IN_COMPLETE_REFERENCE", self.build()["line-routing-assessment.csv"])

    def test_pending_or_unverified_rejected_before_csv_read(self):
        for key, value in [("reuseStatus", "PENDING"), ("verificationStatus", "UNVERIFIED")]:
            with self.subTest(key=key):
                manifest = self.fixture()
                manifest[key] = value
                self.manifest(manifest)
                original = Path.read_bytes
                def guard(p):
                    self.assertNotEqual(p.suffix, ".csv", "CSV opened before clearance check")
                    return original(p)
                with patch.object(Path, "read_bytes", guard):
                    with self.assertRaises(plan.PlanError):
                        self.build()

    def test_unverified_source_can_only_generate_explicit_offline_review(self):
        manifest = self.fixture()
        manifest["verificationStatus"] = "UNVERIFIED"
        self.manifest(manifest)
        with self.assertRaises(plan.PlanError):
            self.build()
        output = plan.build_plan(self.dataset, self.policy, allow_unverified_review=True)
        summary = json.loads(output["summary.json"])
        self.assertEqual(summary["status"], "OFFLINE_UNVERIFIED_SOURCE_REVIEW")
        self.assertEqual(summary["sourceVerificationStatus"], "UNVERIFIED")
        self.assertFalse(summary["runtimeCompatible"])
        self.assertIn("must not be imported", summary["runtimeBlocker"])

    def test_bad_checksum_rejected(self):
        self.fixture()
        with (self.dataset / "settlements.csv").open("a") as f:
            f.write("99999,Fake,Pest\n")
        with self.assertRaisesRegex(plan.PlanError, "Checksum"):
            self.build()

    def test_unknown_county_never_gets_default_area(self):
        self.fixture(settlements=[("00001", "Fixture", "Unknown")], relations=[("00001", "TEST-A")])
        with self.assertRaisesRegex(plan.PlanError, "Unknown county"):
            self.build()

    def test_duplicate_relation_rejected(self):
        self.fixture(relations=[("00001", "TEST-A"), ("00001", "TEST-A")])
        with self.assertRaisesRegex(plan.PlanError, "Duplicate"):
            self.build()

    def test_dangling_relation_rejected(self):
        self.fixture(relations=[("99999", "TEST-A")])
        with self.assertRaisesRegex(plan.PlanError, "Dangling"):
            self.build()

    def test_no_relations_never_fabricates_pairs(self):
        self.fixture(relations=[])
        output = self.build()
        self.assertEqual(output["settlement-line-service-areas.csv"], b"ksh_code,line_code,area_code\n")
        self.assertIn(b"NO_VERIFIED_SETTLEMENT_RELATION", output["line-routing-assessment.csv"])

    def test_leading_zeros_and_csv_quotes_preserved(self):
        self.fixture(settlements=[("00001", 'Fixture, "quoted"', "Pest")], relations=[("00001", "TEST-A")])
        output = self.build()["settlement-service-areas.csv"].decode()
        row = next(plan.csv.DictReader(plan.io.StringIO(output)))
        self.assertEqual(row["ksh_code"], "00001")
        self.assertEqual(row["settlement_name"], 'Fixture, "quoted"')

    def test_cli_deterministic_and_check_detects_tampering(self):
        self.fixture()
        out = Path(self.tmp.name) / "out"
        args = [sys.executable, str(ROOT / "scripts/build-territory-plan.py"), "--dataset", str(self.dataset), "--policy", str(self.policy), "--out", str(out)]
        first = subprocess.run(args, capture_output=True, text=True)
        self.assertEqual(first.returncode, 0, first.stderr)
        before = {f.name: f.read_bytes() for f in out.iterdir()}
        self.assertEqual(subprocess.run(args, capture_output=True).returncode, 0)
        self.assertEqual(before, {f.name: f.read_bytes() for f in out.iterdir()})
        self.assertEqual(subprocess.run(args + ["--check"], capture_output=True).returncode, 0)
        (out / "service-areas.csv").write_text("tampered\n")
        self.assertNotEqual(subprocess.run(args + ["--check"], capture_output=True).returncode, 0)


if __name__ == "__main__":
    unittest.main()
