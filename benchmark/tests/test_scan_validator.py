import hashlib
import json
import tempfile
import unittest
from pathlib import Path

from benchmark.tools.kirsch_benchmark import validate_scan_package


class ScanPackageValidatorTest(unittest.TestCase):
    def _write_capture(self, directory: Path, capture_id: str) -> Path:
        frames = directory / "frames"
        frames.mkdir(parents=True)
        payload = frames / "frame-00.i420"
        payload.write_bytes(b"i420")
        metadata = frames / "frame-00.json"
        metadata.write_text(
            json.dumps(
                {
                    "capture_id": capture_id,
                    "frame_index": 0,
                    "sensor_timestamp_ns": 1000,
                    "image_timestamp_ns": 1000,
                }
            )
        )

        def record(file: Path, role: str, media_type: str) -> dict:
            return {
                "path": file.relative_to(directory).as_posix(),
                "role": role,
                "media_type": media_type,
                "bytes": file.stat().st_size,
                "sha256": hashlib.sha256(file.read_bytes()).hexdigest(),
            }

        manifest = {
            "schema_version": "0.1.0",
            "capture_id": capture_id,
            "print_id": "print-test",
            "created_utc": "2026-07-13T12:00:00Z",
            "completed_utc": "2026-07-13T12:00:01Z",
            "status": "accepted",
            "mode": "yuv-420-888",
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
                    "format": "I420",
                    "files": [
                        record(payload, "i420", "application/octet-stream"),
                        record(metadata, "capture-metadata", "application/json"),
                    ],
                }
            ],
            "errors": [],
        }
        path = directory / "capture.json"
        path.write_text(json.dumps(manifest))
        return path

    def test_valid_review_package_and_parent_hashes(self):
        with tempfile.TemporaryDirectory() as directory:
            export = Path(directory)
            root = export / "scans" / "capture-test-1"
            root.mkdir(parents=True)
            acquisition = export / "acquisitions" / "capture-test-1"
            acquisition.mkdir(parents=True)
            capture_manifest = self._write_capture(acquisition, "capture-test-1")
            derivatives = root / "derivatives"
            working = root / "working"
            derivatives.mkdir()
            working.mkdir()
            preview = derivatives / "acquisition-master.jpg"
            preview.write_bytes(b"preview")
            fused = working / "fused.png"
            fused.write_bytes(b"fused")
            report = root / "processing-report.json"
            report.write_text("{}")
            digest = hashlib.sha256(preview.read_bytes()).hexdigest()
            manifest = {
                "schema_version": "1.0.0",
                "scan_id": "capture-test-1",
                "created_utc": "2026-07-13T12:00:00Z",
                "state": "review",
                "acquisition_manifest": "capture-package:capture-test-1",
                "acquisition_sha256": hashlib.sha256(capture_manifest.read_bytes()).hexdigest(),
                "source_retained": True,
                "used_fusion": True,
                "preview_path": "derivatives/acquisition-master.jpg",
                "working_image_path": "working/fused.png",
                "processing_report": "processing-report.json",
                "selected_quad": {"normalized_points": [[0, 0], [1, 0], [1, 1], [0, 1]]},
                "derivatives": [
                    {
                        "path": "derivatives/acquisition-master.jpg",
                        "kind": "acquisition-master",
                        "bytes": preview.stat().st_size,
                        "sha256": digest,
                    }
                ],
                "extensions": {"gallery_uri": "content://media/external_primary/images/media/1"},
            }
            path = root / "scan.json"
            path.write_text(json.dumps(manifest))
            self.assertEqual([], validate_scan_package(path))
            path.write_text(json.dumps(dict(manifest, extensions="not-an-object")))
            self.assertIn(
                "TYPE_OBJECT",
                [issue.code for issue in validate_scan_package(path)],
            )

    def test_rejects_mutated_derivative(self):
        with tempfile.TemporaryDirectory() as directory:
            export = Path(directory)
            root = export / "scans" / "capture-test-2"
            root.mkdir(parents=True)
            acquisition = export / "acquisitions" / "capture-test-2"
            acquisition.mkdir(parents=True)
            capture_manifest = self._write_capture(acquisition, "capture-test-2")
            asset = root / "master.jpg"
            asset.write_bytes(b"changed")
            path = root / "scan.json"
            path.write_text(
                json.dumps(
                    {
                        "schema_version": "1.0.0",
                        "scan_id": "capture-test-2",
                        "created_utc": "2026-07-13T12:00:00Z",
                        "state": "review",
                        "acquisition_manifest": "capture-package:capture-test-2",
                        "acquisition_sha256": hashlib.sha256(capture_manifest.read_bytes()).hexdigest(),
                        "source_retained": True,
                        "used_fusion": False,
                        "preview_path": "master.jpg",
                        "working_image_path": "master.jpg",
                        "processing_report": "master.jpg",
                        "selected_quad": {"normalized_points": [[0, 0], [1, 0], [1, 1], [0, 1]]},
                        "derivatives": [
                            {
                                "path": "master.jpg",
                                "kind": "acquisition-master",
                                "bytes": 1,
                                "sha256": "0" * 64,
                            }
                        ],
                    }
                )
            )
            codes = {issue.code for issue in validate_scan_package(path)}
            self.assertIn("ASSET_SIZE_MISMATCH", codes)
            self.assertIn("ASSET_HASH_MISMATCH", codes)

    def test_rejects_invalid_paired_acquisition(self):
        with tempfile.TemporaryDirectory() as directory:
            export = Path(directory)
            root = export / "scans" / "capture-test-3"
            root.mkdir(parents=True)
            acquisition = export / "acquisitions" / "capture-test-3"
            acquisition.mkdir(parents=True)
            capture_manifest = acquisition / "capture.json"
            capture_manifest.write_text("{}")
            asset = root / "master.jpg"
            asset.write_bytes(b"master")
            path = root / "scan.json"
            path.write_text(
                json.dumps(
                    {
                        "schema_version": "1.0.0",
                        "scan_id": "capture-test-3",
                        "created_utc": "2026-07-13T12:00:00Z",
                        "state": "review",
                        "acquisition_manifest": "capture-package:capture-test-3",
                        "acquisition_sha256": hashlib.sha256(capture_manifest.read_bytes()).hexdigest(),
                        "source_retained": True,
                        "used_fusion": False,
                        "preview_path": "master.jpg",
                        "working_image_path": "master.jpg",
                        "processing_report": "master.jpg",
                        "selected_quad": {"normalized_points": [[0, 0], [1, 0], [1, 1], [0, 1]]},
                        "derivatives": [
                            {
                                "path": "master.jpg",
                                "kind": "acquisition-master",
                                "bytes": asset.stat().st_size,
                                "sha256": hashlib.sha256(asset.read_bytes()).hexdigest(),
                            }
                        ],
                    }
                )
            )
            codes = {issue.code for issue in validate_scan_package(path)}
            self.assertIn("ACQUISITION_REQUIRED", codes)


if __name__ == "__main__":
    unittest.main()
