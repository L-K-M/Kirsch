from __future__ import annotations

import copy
import hashlib
import json
import shutil
import tempfile
import unittest
from pathlib import Path

from benchmark.tools.kirsch_benchmark import (
    load_json,
    validate_benchmark,
    validate_capture_package,
)


FIXTURE = Path(__file__).parent / "fixtures" / "development"


class BenchmarkValidatorTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary.name) / "development"
        shutil.copytree(FIXTURE, self.root)
        self.manifest_path = self.root / "manifest.json"

    def tearDown(self) -> None:
        self.temporary.cleanup()

    def manifest(self) -> dict:
        return load_json(self.manifest_path)

    def write_manifest(self, manifest: dict) -> None:
        self.manifest_path.write_text(
            json.dumps(manifest, indent=2) + "\n", encoding="utf-8"
        )

    def codes(self) -> set[str]:
        return {issue.code for issue in validate_benchmark(self.manifest_path)}

    def accepted_annotation(self) -> dict:
        return {
            "annotation_set_id": "annotation-one",
            "physical_print_id": "print-one",
            "target_asset_id": "asset-frame-zero",
            "protocol_asset_id": "asset-annotation-protocol",
            "status": "accepted",
            "blinded": True,
            "reviewed": True,
            "annotator_ids": ["annotator-one"],
            "coordinate_space": {
                "width": 4,
                "height": 4,
                "origin": "top-left",
                "x_axis": "right",
                "y_axis": "down",
                "unit": "pixel",
                "orientation": "encoded-before-exif",
            },
            "layers": [
                {"kind": kind, "asset_id": "asset-frame-zero"}
                for kind in (
                    "surface-glare-hotspot",
                    "surface-glare-sheen",
                    "glare-ambiguous",
                    "saturation-any-channel",
                    "saturation-all-channels",
                    "legitimate-highlight",
                    "geometry",
                )
            ],
        }

    def test_checked_fixture_is_valid(self) -> None:
        self.assertEqual([], validate_benchmark(self.manifest_path))

    def test_hash_mismatch_is_rejected(self) -> None:
        (self.root / "assets" / "frame-0.dng").write_bytes(b"modified")
        self.assertIn("ASSET_HASH_MISMATCH", self.codes())

    def test_path_traversal_is_rejected(self) -> None:
        manifest = self.manifest()
        manifest["assets"][0]["path"] = "../outside.txt"
        self.write_manifest(manifest)
        self.assertIn("INVALID_PATH", self.codes())

    def test_duplicate_print_across_groups_is_rejected(self) -> None:
        manifest = self.manifest()
        second_group = copy.deepcopy(manifest["source_groups"][0])
        second_group["source_group_id"] = "source-group-two"
        second_group["split"] = "dev-train"
        manifest["source_groups"].append(second_group)
        self.write_manifest(manifest)
        self.assertIn("PRINT_SPLIT_LEAKAGE", self.codes())

    def test_frame_gap_is_rejected(self) -> None:
        manifest = self.manifest()
        frame = copy.deepcopy(manifest["captures"][1]["frames"][0])
        frame["frame_id"] = "frame-one-two"
        frame["frame_index"] = 2
        frame["sensor_timestamp_ns"] = 2000
        manifest["captures"][1]["frames"].append(frame)
        self.write_manifest(manifest)
        self.assertIn("FRAME_INDEX_GAP", self.codes())

    def test_confirmatory_manifest_requires_frozen_controls(self) -> None:
        manifest = self.manifest()
        manifest["study_phase"] = "confirmatory"
        manifest["source_groups"][0]["split"] = "confirmatory-test"
        self.write_manifest(manifest)
        codes = self.codes()
        self.assertIn("CONFIRMATORY_NOT_FROZEN", codes)
        self.assertIn("TYPE_OBJECT", codes)

    def test_unknown_annotation_layer_is_rejected(self) -> None:
        manifest = self.manifest()
        annotation = self.accepted_annotation()
        annotation["layers"] = [{"kind": "not-a-layer", "asset_id": "asset-frame-zero"}]
        manifest["annotations"] = [annotation]
        self.write_manifest(manifest)
        self.assertIn("ANNOTATION_LAYER_KIND", self.codes())

    def test_confirmatory_analysis_values_must_be_meaningful(self) -> None:
        manifest = self.manifest()
        manifest["study_phase"] = "confirmatory"
        manifest["source_groups"][0]["split"] = "confirmatory-test"
        manifest["protocol"]["status"] = "frozen"
        manifest["protocol"]["git_dirty"] = False
        manifest["preregistration"] = {
            "registered_utc": "2026-07-13T11:00:00Z",
            "uri": "https://example.invalid/registration",
            "snapshot_asset_id": "asset-metric-spec",
        }
        manifest["confirmatory_analysis"] = {
            "analysis_unit": "physical-print",
            "dependence_cluster": "source-group",
            "primary_endpoint": None,
            "practical_margin": None,
            "confidence_interval": None,
            "decision_rule": None,
            "repeat_aggregation": None,
            "exclusion_rule": None,
            "failure_rule": None,
            "no_regression_endpoints": [],
            "multiplicity_rule": None,
            "sample_size_justification": None,
        }
        manifest["target_instances"] = [
            {
                "target_instance_id": "target-verification-one",
                "role": "verification",
                "reference_asset_id": "asset-frame-zero",
                "condition_checked_utc": "2026-07-13T11:00:00Z",
            },
        ]
        manifest["annotations"] = [self.accepted_annotation()]
        self.write_manifest(manifest)
        codes = self.codes()
        self.assertIn("ANALYSIS_FIELD_EMPTY", codes)
        self.assertIn("PRACTICAL_MARGIN", codes)
        self.assertIn("NO_REGRESSION_ENDPOINTS", codes)
        self.assertIn("TARGET_REFERENCE_ROLE", codes)
        self.assertIn("ANNOTATION_ASSET_TYPE", codes)

    def test_acceptance_boolean_must_match_status(self) -> None:
        manifest = self.manifest()
        manifest["captures"][0]["status"] = "failed"
        self.write_manifest(manifest)
        self.assertIn("ACCEPTANCE_MISMATCH", self.codes())

    def test_accepted_capture_requires_payload_assets(self) -> None:
        manifest = self.manifest()
        manifest["captures"][0]["asset_ids"] = []
        self.write_manifest(manifest)
        codes = self.codes()
        self.assertIn("CAPTURE_ASSETS_REQUIRED", codes)
        self.assertIn("FLATBED_REQUIRED", codes)

    def test_each_phone_frame_requires_payload_and_result_roles(self) -> None:
        manifest = self.manifest()
        manifest["captures"][1]["frames"][0]["asset_ids"] = ["asset-frame-zero"]
        self.write_manifest(manifest)
        self.assertIn("FRAME_ASSET_ROLE", self.codes())

    def test_malformed_frame_assets_return_issue_instead_of_crashing(self) -> None:
        manifest = self.manifest()
        manifest["captures"][1]["frames"][0]["asset_ids"] = 42
        self.write_manifest(manifest)
        self.assertIn("FRAME_ASSETS", self.codes())

    def test_malformed_frame_asset_element_returns_issue(self) -> None:
        manifest = self.manifest()
        manifest["captures"][1]["frames"][0]["asset_ids"] = [[]]
        self.write_manifest(manifest)
        self.assertIn("INVALID_ID", self.codes())

    def test_malformed_references_return_issues_instead_of_crashing(self) -> None:
        manifest = self.manifest()
        manifest["protocol"]["capture_protocol_asset_id"] = []
        manifest["captures"][0]["physical_print_id"] = []
        self.write_manifest(manifest)
        self.assertIn("INVALID_ID", self.codes())

    def test_duplicate_json_key_is_rejected(self) -> None:
        self.manifest_path.write_text('{"schema_version":"0.1.0","schema_version":"0.1.0"}')
        self.assertEqual("JSON_INVALID", validate_benchmark(self.manifest_path)[0].code)


class CapturePackageValidatorTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary.name)

    def tearDown(self) -> None:
        self.temporary.cleanup()

    def _file(self, path: str, data: bytes, role: str, media_type: str) -> dict:
        target = self.root / path
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(data)
        return {
            "path": path,
            "role": role,
            "media_type": media_type,
            "bytes": len(data),
            "sha256": hashlib.sha256(data).hexdigest(),
        }

    def _manifest(self) -> dict:
        metadata = json.dumps(
            {
                "capture_id": "capture-one",
                "frame_index": 0,
                "sensor_timestamp_ns": 1000,
                "image_timestamp_ns": 1000,
            },
            separators=(",", ":"),
        ).encode()
        return {
            "schema_version": "0.1.0",
            "capture_id": "capture-one",
            "print_id": "print-one",
            "created_utc": "2026-07-13T12:00:00Z",
            "completed_utc": "2026-07-13T12:00:01Z",
            "status": "accepted",
            "mode": "raw-sensor",
            "requested_frame_count": 1,
            "received_frame_count": 1,
            "persisted_frame_count": 1,
            "camera": {"camera_id": "0"},
            "device": {"model": "fixture"},
            "frames": [
                {
                    "frame_index": 0,
                    "sensor_timestamp_ns": 1000,
                    "width": 4,
                    "height": 4,
                    "format": "RAW_SENSOR",
                    "files": [
                        self._file("frames/frame-00.dng", b"dng", "dng", "image/x-adobe-dng"),
                        self._file(
                            "frames/frame-00.json",
                            metadata,
                            "capture-metadata",
                            "application/json",
                        ),
                    ],
                }
            ],
            "errors": [],
        }

    def test_valid_capture_package(self) -> None:
        manifest = self._manifest()
        path = self.root / "capture.json"
        path.write_text(json.dumps(manifest), encoding="utf-8")
        self.assertEqual([], validate_capture_package(path))

    def test_incomplete_accepted_burst_is_rejected(self) -> None:
        manifest = self._manifest()
        manifest["requested_frame_count"] = 2
        path = self.root / "capture.json"
        path.write_text(json.dumps(manifest), encoding="utf-8")
        codes = {issue.code for issue in validate_capture_package(path)}
        self.assertIn("INCOMPLETE_ACCEPTED_BURST", codes)

    def test_empty_frames_cannot_be_accepted(self) -> None:
        manifest = self._manifest()
        manifest["frames"] = []
        path = self.root / "capture.json"
        path.write_text(json.dumps(manifest), encoding="utf-8")
        codes = {issue.code for issue in validate_capture_package(path)}
        self.assertIn("INCOMPLETE_ACCEPTED_BURST", codes)

    def test_timestamp_pair_mismatch_is_rejected(self) -> None:
        manifest = self._manifest()
        metadata_path = self.root / "frames" / "frame-00.json"
        bad_metadata = {
            "capture_id": "capture-one",
            "frame_index": 0,
            "sensor_timestamp_ns": 1000,
            "image_timestamp_ns": 999,
        }
        metadata_path.write_text(json.dumps(bad_metadata), encoding="utf-8")
        data = metadata_path.read_bytes()
        record = manifest["frames"][0]["files"][1]
        record["bytes"] = len(data)
        record["sha256"] = hashlib.sha256(data).hexdigest()
        path = self.root / "capture.json"
        path.write_text(json.dumps(manifest), encoding="utf-8")
        codes = {issue.code for issue in validate_capture_package(path)}
        self.assertIn("TIMESTAMP_PAIR_MISMATCH", codes)

    def test_wrong_raw_roles_are_rejected(self) -> None:
        manifest = self._manifest()
        manifest["frames"][0]["files"][0]["role"] = "i420"
        path = self.root / "capture.json"
        path.write_text(json.dumps(manifest), encoding="utf-8")
        codes = {issue.code for issue in validate_capture_package(path)}
        self.assertIn("FRAME_FILE_ROLES", codes)

    def test_camera_characteristics_hash_is_checked(self) -> None:
        manifest = self._manifest()
        manifest["camera"]["characteristics_file"] = self._file(
            "camera-characteristics.json",
            b"{}",
            "camera-characteristics",
            "application/json",
        )
        manifest["camera"]["characteristics_file"]["sha256"] = "0" * 64
        path = self.root / "capture.json"
        path.write_text(json.dumps(manifest), encoding="utf-8")
        codes = {issue.code for issue in validate_capture_package(path)}
        self.assertIn("ASSET_HASH_MISMATCH", codes)


if __name__ == "__main__":
    unittest.main()
