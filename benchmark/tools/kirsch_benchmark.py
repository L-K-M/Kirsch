#!/usr/bin/env python3
"""Validate Kirsch benchmark and Camera2 capture manifests.

The validator deliberately uses only Python's standard library so a data drop can
be checked before installing any image-processing environment. JSON Schema files
document the interchange format; this module enforces the semantic and filesystem
invariants that JSON Schema cannot express.
"""

from __future__ import annotations

import argparse
import dataclasses
import datetime as dt
import hashlib
import json
import math
import re
import sys
from pathlib import Path, PurePosixPath
from typing import Any, Iterable, Sequence


SCHEMA_VERSION = "0.1.0"
VALIDATOR_VERSION = "0.1.0"
ID_PATTERN = re.compile(r"^[a-z][a-z0-9]*(?:-[a-z0-9]+)*$")
SHA256_PATTERN = re.compile(r"^[0-9a-f]{64}$")
DEVELOPMENT_SPLITS = {"dev-train", "dev-validation"}
CONFIRMATORY_SPLITS = {"confirmatory-test"}
CAPTURE_MODALITIES = {
    "flatbed-reference",
    "phone-raw-burst",
    "phone-yuv-burst",
    "photoscan-export",
    "physical-target",
}
CAPTURE_STATUSES = {"accepted", "rejected", "failed", "aborted"}
ANNOTATION_LAYERS = {
    "surface-glare-hotspot",
    "surface-glare-sheen",
    "glare-ambiguous",
    "saturation-any-channel",
    "saturation-all-channels",
    "legitimate-highlight",
    "geometry",
}


class DuplicateKeyError(ValueError):
    """Raised when strict JSON contains a duplicate object key."""


@dataclasses.dataclass(frozen=True, order=True)
class Issue:
    code: str
    pointer: str
    message: str

    def format(self) -> str:
        return f"{self.code} {self.pointer}: {self.message}"


def _strict_object(pairs: Sequence[tuple[str, Any]]) -> dict[str, Any]:
    result: dict[str, Any] = {}
    for key, value in pairs:
        if key in result:
            raise DuplicateKeyError(f"duplicate key: {key}")
        result[key] = value
    return result


def _reject_constant(value: str) -> None:
    raise ValueError(f"non-finite number is not valid JSON: {value}")


def load_json(path: Path) -> Any:
    return json.loads(
        path.read_text(encoding="utf-8"),
        object_pairs_hook=_strict_object,
        parse_constant=_reject_constant,
    )


def _pointer(parent: str, key: str | int) -> str:
    escaped = str(key).replace("~", "~0").replace("/", "~1")
    return f"{parent}/{escaped}" if parent else f"/{escaped}"


class Validator:
    def __init__(self, manifest_path: Path) -> None:
        self.manifest_path = manifest_path.resolve()
        self.root = self.manifest_path.parent
        self.issues: list[Issue] = []

    def issue(self, code: str, pointer: str, message: str) -> None:
        self.issues.append(Issue(code, pointer or "/", message))

    def require_keys(
        self, value: Any, pointer: str, required: Iterable[str]
    ) -> bool:
        if not isinstance(value, dict):
            self.issue("TYPE_OBJECT", pointer, "must be an object")
            return False
        for key in required:
            if key not in value:
                self.issue("REQUIRED", _pointer(pointer, key), "field is required")
        return True

    def reject_unknown(
        self, value: Any, pointer: str, allowed: Iterable[str]
    ) -> None:
        if not isinstance(value, dict):
            return
        allowed_set = set(allowed)
        for key in value:
            if key not in allowed_set:
                self.issue("UNKNOWN_FIELD", _pointer(pointer, key), "field is not allowed")

    def validate_id(self, value: Any, pointer: str) -> bool:
        if not isinstance(value, str) or not ID_PATTERN.fullmatch(value):
            self.issue(
                "INVALID_ID",
                pointer,
                "must match ^[a-z][a-z0-9]*(?:-[a-z0-9]+)*$",
            )
            return False
        return True

    def validate_timestamp(self, value: Any, pointer: str) -> dt.datetime | None:
        if not isinstance(value, str):
            self.issue("INVALID_TIMESTAMP", pointer, "must be an RFC 3339 UTC string")
            return None
        try:
            parsed = dt.datetime.fromisoformat(value.replace("Z", "+00:00"))
        except ValueError:
            self.issue("INVALID_TIMESTAMP", pointer, "must be an RFC 3339 UTC string")
            return None
        if parsed.tzinfo is None or parsed.utcoffset() != dt.timedelta(0):
            self.issue("INVALID_TIMESTAMP", pointer, "must include a UTC offset")
            return None
        return parsed

    def resolve_asset_path(self, raw: Any, pointer: str) -> Path | None:
        if not isinstance(raw, str) or not raw:
            self.issue("INVALID_PATH", pointer, "must be a non-empty relative POSIX path")
            return None
        if "\\" in raw:
            self.issue("INVALID_PATH", pointer, "backslashes are not allowed")
            return None
        pure = PurePosixPath(raw)
        if pure.is_absolute() or any(part in {"", ".", ".."} for part in pure.parts):
            self.issue("INVALID_PATH", pointer, "path must be normalized and relative")
            return None
        candidate = self.root.joinpath(*pure.parts)
        try:
            resolved = candidate.resolve(strict=True)
        except FileNotFoundError:
            self.issue("ASSET_MISSING", pointer, f"file does not exist: {raw}")
            return None
        if resolved != self.root and self.root not in resolved.parents:
            self.issue("PATH_ESCAPE", pointer, "path resolves outside the manifest root")
            return None
        current = self.root
        for part in pure.parts:
            current = current / part
            if current.is_symlink():
                self.issue("SYMLINK_ASSET", pointer, "symlinked assets are not allowed")
                return None
        if not resolved.is_file():
            self.issue("ASSET_NOT_FILE", pointer, "asset must be a regular file")
            return None
        return resolved

    def validate_asset(self, asset: Any, pointer: str) -> str | None:
        allowed = {
            "asset_id",
            "path",
            "role",
            "media_type",
            "bytes",
            "sha256",
            "width",
            "height",
            "bit_depth",
            "channels",
            "color_space",
            "extensions",
        }
        required = {"asset_id", "path", "role", "media_type", "bytes", "sha256"}
        if not self.require_keys(asset, pointer, required):
            return None
        self.reject_unknown(asset, pointer, allowed)
        asset_id = asset.get("asset_id")
        self.validate_id(asset_id, _pointer(pointer, "asset_id"))
        path = self.resolve_asset_path(asset.get("path"), _pointer(pointer, "path"))
        byte_count = asset.get("bytes")
        if not isinstance(byte_count, int) or isinstance(byte_count, bool) or byte_count < 0:
            self.issue("INVALID_BYTES", _pointer(pointer, "bytes"), "must be a non-negative integer")
        digest = asset.get("sha256")
        if not isinstance(digest, str) or not SHA256_PATTERN.fullmatch(digest):
            self.issue("INVALID_SHA256", _pointer(pointer, "sha256"), "must be lowercase SHA-256")
        if path is not None:
            actual_bytes = path.stat().st_size
            if isinstance(byte_count, int) and actual_bytes != byte_count:
                self.issue(
                    "ASSET_SIZE_MISMATCH",
                    _pointer(pointer, "bytes"),
                    f"declared {byte_count}, found {actual_bytes}",
                )
            actual_digest = hashlib.sha256(path.read_bytes()).hexdigest()
            if isinstance(digest, str) and actual_digest != digest:
                self.issue(
                    "ASSET_HASH_MISMATCH",
                    _pointer(pointer, "sha256"),
                    f"declared {digest}, found {actual_digest}",
                )
        for dimension in ("width", "height", "bit_depth", "channels"):
            if dimension in asset:
                number = asset[dimension]
                if not isinstance(number, int) or isinstance(number, bool) or number <= 0:
                    self.issue(
                        "INVALID_DIMENSION",
                        _pointer(pointer, dimension),
                        "must be a positive integer",
                    )
        return asset_id if isinstance(asset_id, str) else None


def _duplicates(values: Iterable[str]) -> set[str]:
    seen: set[str] = set()
    duplicates: set[str] = set()
    for value in values:
        if value in seen:
            duplicates.add(value)
        seen.add(value)
    return duplicates


def validate_benchmark(manifest_path: Path) -> list[Issue]:
    validator = Validator(manifest_path)
    try:
        data = load_json(manifest_path)
    except (OSError, json.JSONDecodeError, DuplicateKeyError, ValueError) as error:
        return [Issue("JSON_INVALID", "/", str(error))]

    top_required = {
        "schema_version",
        "dataset_id",
        "study_phase",
        "created_utc",
        "protocol",
        "access_policy",
        "assets",
        "source_groups",
        "captures",
        "target_instances",
        "annotations",
    }
    top_allowed = top_required | {"preregistration", "confirmatory_analysis", "extensions"}
    if not validator.require_keys(data, "", top_required):
        return sorted(validator.issues)
    validator.reject_unknown(data, "", top_allowed)

    if data.get("schema_version") != SCHEMA_VERSION:
        validator.issue(
            "SCHEMA_VERSION",
            "/schema_version",
            f"expected {SCHEMA_VERSION}",
        )
    validator.validate_id(data.get("dataset_id"), "/dataset_id")
    created = validator.validate_timestamp(data.get("created_utc"), "/created_utc")
    phase = data.get("study_phase")
    if phase not in {"protocol-development", "confirmatory"}:
        validator.issue(
            "STUDY_PHASE", "/study_phase", "must be protocol-development or confirmatory"
        )

    protocol = data.get("protocol")
    protocol_required = {
        "protocol_id",
        "version",
        "status",
        "validator_version",
        "capture_protocol_asset_id",
        "annotation_protocol_asset_id",
        "metric_spec_asset_id",
        "git_commit",
        "git_dirty",
    }
    if validator.require_keys(protocol, "/protocol", protocol_required):
        validator.reject_unknown(protocol, "/protocol", protocol_required | {"extensions"})
        validator.validate_id(protocol.get("protocol_id"), "/protocol/protocol_id")
        if protocol.get("status") not in {"draft", "frozen"}:
            validator.issue("PROTOCOL_STATUS", "/protocol/status", "must be draft or frozen")
        if protocol.get("validator_version") != VALIDATOR_VERSION:
            validator.issue(
                "VALIDATOR_VERSION",
                "/protocol/validator_version",
                f"expected {VALIDATOR_VERSION}",
            )
        if not isinstance(protocol.get("git_dirty"), bool):
            validator.issue("TYPE_BOOL", "/protocol/git_dirty", "must be a boolean")

    assets = data.get("assets")
    asset_ids: list[str] = []
    asset_paths: list[str] = []
    asset_records: dict[str, dict[str, Any]] = {}
    if not isinstance(assets, list):
        validator.issue("TYPE_ARRAY", "/assets", "must be an array")
        assets = []
    for index, asset in enumerate(assets):
        pointer = _pointer("/assets", index)
        asset_id = validator.validate_asset(asset, pointer)
        if asset_id is not None:
            asset_ids.append(asset_id)
            if isinstance(asset, dict):
                asset_records[asset_id] = asset
        if isinstance(asset, dict) and isinstance(asset.get("path"), str):
            asset_paths.append(asset["path"].casefold())
    for duplicate in _duplicates(asset_ids):
        validator.issue("DUPLICATE_ID", "/assets", f"duplicate asset_id: {duplicate}")
    for duplicate in _duplicates(asset_paths):
        validator.issue("PATH_COLLISION", "/assets", f"case-folding path collision: {duplicate}")
    asset_id_set = set(asset_ids)

    if isinstance(protocol, dict):
        for field in (
            "capture_protocol_asset_id",
            "annotation_protocol_asset_id",
            "metric_spec_asset_id",
        ):
            reference = protocol.get(field)
            if not isinstance(reference, str):
                validator.issue(
                    "INVALID_ID",
                    _pointer("/protocol", field),
                    "asset reference must be a string ID",
                )
            elif reference not in asset_id_set:
                validator.issue(
                    "UNRESOLVED_ASSET",
                    _pointer("/protocol", field),
                    f"unknown asset_id: {reference}",
                )

    source_groups = data.get("source_groups")
    if not isinstance(source_groups, list):
        validator.issue("TYPE_ARRAY", "/source_groups", "must be an array")
        source_groups = []
    group_ids: list[str] = []
    print_to_group: dict[str, tuple[str, str]] = {}
    allowed_splits = CONFIRMATORY_SPLITS if phase == "confirmatory" else DEVELOPMENT_SPLITS
    for group_index, group in enumerate(source_groups):
        pointer = _pointer("/source_groups", group_index)
        required = {"source_group_id", "split", "membership_basis", "prints"}
        if not validator.require_keys(group, pointer, required):
            continue
        validator.reject_unknown(group, pointer, required | {"extensions"})
        group_id = group.get("source_group_id")
        if validator.validate_id(group_id, _pointer(pointer, "source_group_id")):
            group_ids.append(group_id)
        split = group.get("split")
        if split not in allowed_splits:
            validator.issue(
                "INVALID_SPLIT",
                _pointer(pointer, "split"),
                f"{phase} allows only {sorted(allowed_splits)}",
            )
        prints = group.get("prints")
        if not isinstance(prints, list) or not prints:
            validator.issue("PRINTS_REQUIRED", _pointer(pointer, "prints"), "must be non-empty")
            continue
        for print_index, print_record in enumerate(prints):
            print_pointer = _pointer(_pointer(pointer, "prints"), print_index)
            print_required = {
                "physical_print_id",
                "rights_basis",
                "surface_finish",
                "print_process",
                "mounting",
                "geometry_conditions",
                "width_mm",
                "height_mm",
                "measurement_method",
                "content_tags",
            }
            if not validator.require_keys(print_record, print_pointer, print_required):
                continue
            validator.reject_unknown(print_record, print_pointer, print_required | {"extensions"})
            print_id = print_record.get("physical_print_id")
            if validator.validate_id(print_id, _pointer(print_pointer, "physical_print_id")):
                if print_id in print_to_group:
                    validator.issue(
                        "PRINT_SPLIT_LEAKAGE",
                        _pointer(print_pointer, "physical_print_id"),
                        f"{print_id} already belongs to {print_to_group[print_id][0]}",
                    )
                elif isinstance(group_id, str) and isinstance(split, str):
                    print_to_group[print_id] = (group_id, split)
            for dimension in ("width_mm", "height_mm"):
                value = print_record.get(dimension)
                if (
                    not isinstance(value, (int, float))
                    or isinstance(value, bool)
                    or not math.isfinite(value)
                    or value <= 0
                ):
                    validator.issue(
                        "INVALID_PHYSICAL_SIZE",
                        _pointer(print_pointer, dimension),
                        "must be a positive finite number",
                    )
    for duplicate in _duplicates(group_ids):
        validator.issue("DUPLICATE_ID", "/source_groups", f"duplicate source_group_id: {duplicate}")

    captures = data.get("captures")
    if not isinstance(captures, list):
        validator.issue("TYPE_ARRAY", "/captures", "must be an array")
        captures = []
    capture_ids: list[str] = []
    modalities_by_print: dict[str, set[str]] = {}
    earliest_capture: dt.datetime | None = None
    for capture_index, capture in enumerate(captures):
        pointer = _pointer("/captures", capture_index)
        required = {
            "acquisition_id",
            "physical_print_id",
            "comparison_block_id",
            "repeat_id",
            "attempt_index",
            "modality",
            "status",
            "accepted",
            "started_utc",
            "ended_utc",
            "operator_id",
            "device_snapshot_id",
            "software_build_id",
            "asset_ids",
            "protocol_deviations",
        }
        if not validator.require_keys(capture, pointer, required):
            continue
        validator.reject_unknown(capture, pointer, required | {"frames", "extensions"})
        capture_id = capture.get("acquisition_id")
        if validator.validate_id(capture_id, _pointer(pointer, "acquisition_id")):
            capture_ids.append(capture_id)
        print_id = capture.get("physical_print_id")
        print_id_valid = validator.validate_id(print_id, _pointer(pointer, "physical_print_id"))
        if print_id_valid and print_id not in print_to_group:
            validator.issue(
                "UNRESOLVED_PRINT",
                _pointer(pointer, "physical_print_id"),
                f"unknown physical_print_id: {print_id}",
            )
        modality = capture.get("modality")
        if modality not in CAPTURE_MODALITIES:
            validator.issue(
                "CAPTURE_MODALITY",
                _pointer(pointer, "modality"),
                f"must be one of {sorted(CAPTURE_MODALITIES)}",
            )
        status = capture.get("status")
        if status not in CAPTURE_STATUSES:
            validator.issue(
                "CAPTURE_STATUS",
                _pointer(pointer, "status"),
                f"must be one of {sorted(CAPTURE_STATUSES)}",
            )
        if not isinstance(capture.get("accepted"), bool):
            validator.issue("TYPE_BOOL", _pointer(pointer, "accepted"), "must be a boolean")
        accepted = capture.get("accepted")
        if isinstance(accepted, bool) and accepted != (status == "accepted"):
            validator.issue(
                "ACCEPTANCE_MISMATCH",
                _pointer(pointer, "accepted"),
                "accepted must be true exactly when status is accepted",
            )
        attempt = capture.get("attempt_index")
        if not isinstance(attempt, int) or isinstance(attempt, bool) or attempt < 0:
            validator.issue(
                "ATTEMPT_INDEX", _pointer(pointer, "attempt_index"), "must be non-negative"
            )
        started = validator.validate_timestamp(capture.get("started_utc"), _pointer(pointer, "started_utc"))
        ended = validator.validate_timestamp(capture.get("ended_utc"), _pointer(pointer, "ended_utc"))
        if started is not None:
            earliest_capture = started if earliest_capture is None else min(earliest_capture, started)
        if started is not None and ended is not None and ended < started:
            validator.issue("CAPTURE_TIME_ORDER", _pointer(pointer, "ended_utc"), "precedes start")
        references = capture.get("asset_ids")
        capture_payload_valid = status == "accepted" and accepted is True
        if not isinstance(references, list):
            validator.issue("TYPE_ARRAY", _pointer(pointer, "asset_ids"), "must be an array")
            capture_payload_valid = False
        else:
            if capture_payload_valid and not references:
                validator.issue(
                    "CAPTURE_ASSETS_REQUIRED",
                    _pointer(pointer, "asset_ids"),
                    "accepted capture must contain payload assets",
                )
                capture_payload_valid = False
            string_references = [reference for reference in references if isinstance(reference, str)]
            if len(string_references) != len(set(string_references)):
                validator.issue(
                    "DUPLICATE_CAPTURE_ASSET",
                    _pointer(pointer, "asset_ids"),
                    "capture asset references must be unique",
                )
            for reference_index, reference in enumerate(references):
                reference_pointer = _pointer(_pointer(pointer, "asset_ids"), reference_index)
                if not isinstance(reference, str):
                    validator.issue("INVALID_ID", reference_pointer, "asset reference must be a string ID")
                    capture_payload_valid = False
                    continue
                if reference not in asset_id_set:
                    validator.issue(
                        "UNRESOLVED_ASSET",
                        reference_pointer,
                        f"unknown asset_id: {reference}",
                    )
                    capture_payload_valid = False
        frames = capture.get("frames", [])
        if modality in {"phone-raw-burst", "phone-yuv-burst"}:
            _validate_frames(
                validator,
                frames,
                _pointer(pointer, "frames"),
                asset_records,
                modality,
            )
            frame_asset_ids: set[str] = set()
            if isinstance(frames, list):
                for frame in frames:
                    if not isinstance(frame, dict):
                        continue
                    frame_references = frame.get("asset_ids")
                    if isinstance(frame_references, list):
                        frame_asset_ids.update(
                            reference for reference in frame_references if isinstance(reference, str)
                        )
            capture_reference_set = {
                reference for reference in references if isinstance(reference, str)
            } if isinstance(references, list) else set()
            if not frame_asset_ids.issubset(capture_reference_set):
                validator.issue(
                    "FRAME_ASSET_NOT_CAPTURE_ASSET",
                    _pointer(pointer, "frames"),
                    "every frame asset must also belong to the capture",
                )
                capture_payload_valid = False
            if not isinstance(frames, list) or not frames:
                capture_payload_valid = False
        if capture_payload_valid and isinstance(references, list):
            roles = {
                asset_records[reference].get("role")
                for reference in references
                if reference in asset_records
            }
            required_roles = {
                "flatbed-reference": {"flatbed-master"},
                "phone-raw-burst": {"phone-raw-frame", "capture-result"},
                "phone-yuv-burst": {"phone-yuv-frame", "capture-result"},
                "photoscan-export": {"photoscan-export"},
                "physical-target": {"target-observation"},
            }.get(modality, set())
            if not required_roles.issubset(roles):
                validator.issue(
                    "CAPTURE_ASSET_ROLE",
                    _pointer(pointer, "asset_ids"),
                    f"{modality} requires asset roles {sorted(required_roles)}",
                )
                capture_payload_valid = False
        if capture_payload_valid and isinstance(print_id, str) and isinstance(modality, str):
            modalities_by_print.setdefault(print_id, set()).add(modality)
    for duplicate in _duplicates(capture_ids):
        validator.issue("DUPLICATE_ID", "/captures", f"duplicate acquisition_id: {duplicate}")

    for print_id in print_to_group:
        modalities = modalities_by_print.get(print_id, set())
        if "flatbed-reference" not in modalities:
            validator.issue(
                "FLATBED_REQUIRED",
                "/captures",
                f"{print_id} has no accepted flatbed-reference capture",
            )
        if not modalities.intersection({"phone-raw-burst", "phone-yuv-burst"}):
            validator.issue(
                "PHONE_BURST_REQUIRED",
                "/captures",
                f"{print_id} has no accepted phone burst",
            )

    target_instances = data.get("target_instances")
    verification_target_present = False
    if not isinstance(target_instances, list):
        validator.issue("TYPE_ARRAY", "/target_instances", "must be an array")
    else:
        target_ids: list[str] = []
        for target_index, target in enumerate(target_instances):
            pointer = _pointer("/target_instances", target_index)
            required = {"target_instance_id", "role", "reference_asset_id", "condition_checked_utc"}
            if not validator.require_keys(target, pointer, required):
                continue
            validator.reject_unknown(target, pointer, required | {"extensions"})
            target_id = target.get("target_instance_id")
            if validator.validate_id(target_id, _pointer(pointer, "target_instance_id")):
                target_ids.append(target_id)
            if target.get("role") not in {"profile-creation", "verification"}:
                validator.issue(
                    "TARGET_ROLE", _pointer(pointer, "role"), "must be profile-creation or verification"
                )
            elif target.get("role") == "verification":
                verification_target_present = True
            target_reference = target.get("reference_asset_id")
            if not isinstance(target_reference, str):
                validator.issue(
                    "INVALID_ID",
                    _pointer(pointer, "reference_asset_id"),
                    "asset reference must be a string ID",
                )
            elif target_reference not in asset_id_set:
                validator.issue(
                    "UNRESOLVED_ASSET",
                    _pointer(pointer, "reference_asset_id"),
                    "unknown target reference asset",
                )
            else:
                reference_asset = asset_records.get(target_reference, {})
                if reference_asset.get("role") != "target-reference":
                    validator.issue(
                        "TARGET_REFERENCE_ROLE",
                        _pointer(pointer, "reference_asset_id"),
                        "target reference asset must have role target-reference",
                    )
            validator.validate_timestamp(
                target.get("condition_checked_utc"), _pointer(pointer, "condition_checked_utc")
            )
        for duplicate in _duplicates(target_ids):
            validator.issue(
                "TARGET_ROLE_REUSE",
                "/target_instances",
                f"target instance occurs more than once: {duplicate}",
            )

    annotations = data.get("annotations")
    accepted_annotation_prints: set[str] = set()
    if not isinstance(annotations, list):
        validator.issue("TYPE_ARRAY", "/annotations", "must be an array")
    else:
        annotation_ids: list[str] = []
        for annotation_index, annotation in enumerate(annotations):
            pointer = _pointer("/annotations", annotation_index)
            required = {
                "annotation_set_id",
                "physical_print_id",
                "target_asset_id",
                "protocol_asset_id",
                "status",
                "blinded",
                "reviewed",
                "annotator_ids",
                "coordinate_space",
                "layers",
            }
            if not validator.require_keys(annotation, pointer, required):
                continue
            validator.reject_unknown(annotation, pointer, required | {"extensions"})
            annotation_id = annotation.get("annotation_set_id")
            if validator.validate_id(annotation_id, _pointer(pointer, "annotation_set_id")):
                annotation_ids.append(annotation_id)
            print_id = annotation.get("physical_print_id")
            print_id_valid = validator.validate_id(print_id, _pointer(pointer, "physical_print_id"))
            if print_id_valid and print_id not in print_to_group:
                validator.issue(
                    "UNRESOLVED_PRINT",
                    _pointer(pointer, "physical_print_id"),
                    f"unknown physical_print_id: {print_id}",
                )
            status = annotation.get("status")
            if status not in {"draft", "accepted", "rejected"}:
                validator.issue(
                    "ANNOTATION_STATUS",
                    _pointer(pointer, "status"),
                    "must be draft, accepted, or rejected",
                )
            elif status == "accepted" and isinstance(print_id, str):
                accepted_annotation_prints.add(print_id)
            for field in ("target_asset_id", "protocol_asset_id"):
                reference = annotation.get(field)
                if not isinstance(reference, str):
                    validator.issue(
                        "INVALID_ID",
                        _pointer(pointer, field),
                        "asset reference must be a string ID",
                    )
                elif reference not in asset_id_set:
                    validator.issue(
                        "UNRESOLVED_ASSET",
                        _pointer(pointer, field),
                        f"unknown asset_id: {annotation.get(field)}",
                    )
            for field in ("blinded", "reviewed"):
                if not isinstance(annotation.get(field), bool):
                    validator.issue("TYPE_BOOL", _pointer(pointer, field), "must be a boolean")
            annotator_ids = annotation.get("annotator_ids")
            if not isinstance(annotator_ids, list) or not annotator_ids:
                validator.issue(
                    "ANNOTATORS_REQUIRED",
                    _pointer(pointer, "annotator_ids"),
                    "must be a non-empty array",
                )
            else:
                for annotator_index, annotator_id in enumerate(annotator_ids):
                    validator.validate_id(
                        annotator_id,
                        _pointer(_pointer(pointer, "annotator_ids"), annotator_index),
                    )
            coordinate = annotation.get("coordinate_space")
            coordinate_required = {"width", "height", "origin", "x_axis", "y_axis", "unit", "orientation"}
            if validator.require_keys(coordinate, _pointer(pointer, "coordinate_space"), coordinate_required):
                validator.reject_unknown(
                    coordinate,
                    _pointer(pointer, "coordinate_space"),
                    coordinate_required,
                )
                expected = {
                    "origin": "top-left",
                    "x_axis": "right",
                    "y_axis": "down",
                    "unit": "pixel",
                    "orientation": "encoded-before-exif",
                }
                for field, expected_value in expected.items():
                    if coordinate.get(field) != expected_value:
                        validator.issue(
                            "COORDINATE_CONTRACT",
                            _pointer(_pointer(pointer, "coordinate_space"), field),
                            f"expected {expected_value}",
                        )
                for field in ("width", "height"):
                    value = coordinate.get(field)
                    if not isinstance(value, int) or isinstance(value, bool) or value <= 0:
                        validator.issue(
                            "INVALID_DIMENSION",
                            _pointer(_pointer(pointer, "coordinate_space"), field),
                            "must be a positive integer",
                        )
                target_asset_id = annotation.get("target_asset_id")
                target_asset = asset_records.get(target_asset_id, {}) if isinstance(
                    target_asset_id,
                    str,
                ) else {}
                if target_asset.get("width") != coordinate.get("width") or target_asset.get(
                    "height",
                ) != coordinate.get("height"):
                    validator.issue(
                        "ANNOTATION_TARGET_DIMENSIONS",
                        _pointer(pointer, "target_asset_id"),
                        "target asset dimensions must match coordinate_space",
                    )
            layers = annotation.get("layers")
            layer_kinds: list[str] = []
            if not isinstance(layers, list) or not layers:
                validator.issue("ANNOTATION_LAYERS", _pointer(pointer, "layers"), "must be non-empty")
            else:
                for layer_index, layer in enumerate(layers):
                    layer_pointer = _pointer(_pointer(pointer, "layers"), layer_index)
                    if not validator.require_keys(layer, layer_pointer, {"kind", "asset_id"}):
                        continue
                    validator.reject_unknown(layer, layer_pointer, {"kind", "asset_id", "extensions"})
                    kind = layer.get("kind")
                    if kind not in ANNOTATION_LAYERS:
                        validator.issue(
                            "ANNOTATION_LAYER_KIND",
                            _pointer(layer_pointer, "kind"),
                            f"must be one of {sorted(ANNOTATION_LAYERS)}",
                        )
                    elif isinstance(kind, str):
                        layer_kinds.append(kind)
                    layer_asset_id = layer.get("asset_id")
                    if not isinstance(layer_asset_id, str):
                        validator.issue(
                            "INVALID_ID",
                            _pointer(layer_pointer, "asset_id"),
                            "asset reference must be a string ID",
                        )
                    elif layer_asset_id not in asset_id_set:
                        validator.issue(
                            "UNRESOLVED_ASSET",
                            _pointer(layer_pointer, "asset_id"),
                            f"unknown asset_id: {layer.get('asset_id')}",
                        )
                    elif isinstance(kind, str) and kind in ANNOTATION_LAYERS:
                        layer_asset = asset_records.get(layer_asset_id, {})
                        if kind == "geometry":
                            if layer_asset.get("role") != "geometry-annotation" or layer_asset.get(
                                "media_type",
                            ) != "application/json":
                                validator.issue(
                                    "ANNOTATION_ASSET_TYPE",
                                    _pointer(layer_pointer, "asset_id"),
                                    "geometry requires a geometry-annotation application/json asset",
                                )
                        else:
                            if layer_asset.get("role") != "annotation-mask" or layer_asset.get(
                                "media_type",
                            ) != "image/png":
                                validator.issue(
                                    "ANNOTATION_ASSET_TYPE",
                                    _pointer(layer_pointer, "asset_id"),
                                    "mask layer requires an annotation-mask image/png asset",
                                )
                            if isinstance(coordinate, dict) and (
                                layer_asset.get("width") != coordinate.get("width")
                                or layer_asset.get("height") != coordinate.get("height")
                            ):
                                validator.issue(
                                    "ANNOTATION_MASK_DIMENSIONS",
                                    _pointer(layer_pointer, "asset_id"),
                                    "mask dimensions must match coordinate_space",
                                )
                for duplicate in _duplicates(layer_kinds):
                    validator.issue(
                        "DUPLICATE_ANNOTATION_LAYER",
                        _pointer(pointer, "layers"),
                        f"duplicate layer kind: {duplicate}",
                    )
            if phase == "confirmatory" and (
                status != "accepted" or annotation.get("blinded") is not True or annotation.get("reviewed") is not True
            ):
                validator.issue(
                    "CONFIRMATORY_ANNOTATION_NOT_ACCEPTED",
                    pointer,
                    "confirmatory annotations must be accepted, blinded, and reviewed",
                )
            if phase == "confirmatory" and isinstance(layers, list):
                missing_layers = ANNOTATION_LAYERS.difference(layer_kinds)
                if missing_layers:
                    validator.issue(
                        "CONFIRMATORY_ANNOTATION_LAYERS",
                        _pointer(pointer, "layers"),
                        f"missing required layers: {sorted(missing_layers)}",
                    )
        for duplicate in _duplicates(annotation_ids):
            validator.issue("DUPLICATE_ID", "/annotations", f"duplicate annotation_set_id: {duplicate}")

    if phase == "confirmatory":
        if not isinstance(protocol, dict) or protocol.get("status") != "frozen":
            validator.issue("CONFIRMATORY_NOT_FROZEN", "/protocol/status", "must be frozen")
        if isinstance(protocol, dict) and protocol.get("git_dirty") is not False:
            validator.issue("CONFIRMATORY_DIRTY", "/protocol/git_dirty", "must be false")
        preregistration = data.get("preregistration")
        prereg_required = {"registered_utc", "uri", "snapshot_asset_id"}
        registered: dt.datetime | None = None
        if validator.require_keys(preregistration, "/preregistration", prereg_required):
            validator.reject_unknown(preregistration, "/preregistration", prereg_required)
            registered = validator.validate_timestamp(
                preregistration.get("registered_utc"), "/preregistration/registered_utc"
            )
            snapshot_asset_id = preregistration.get("snapshot_asset_id")
            if not isinstance(snapshot_asset_id, str):
                validator.issue(
                    "INVALID_ID",
                    "/preregistration/snapshot_asset_id",
                    "asset reference must be a string ID",
                )
            elif snapshot_asset_id not in asset_id_set:
                validator.issue(
                    "UNRESOLVED_ASSET",
                    "/preregistration/snapshot_asset_id",
                    "unknown preregistration snapshot asset",
                )
            uri = preregistration.get("uri")
            if not isinstance(uri, str) or not uri.startswith(("https://", "http://")):
                validator.issue(
                    "PREREGISTRATION_URI",
                    "/preregistration/uri",
                    "must be a non-empty HTTP(S) URI",
                )
        analysis = data.get("confirmatory_analysis")
        analysis_required = {
            "analysis_unit",
            "dependence_cluster",
            "primary_endpoint",
            "practical_margin",
            "confidence_interval",
            "decision_rule",
            "repeat_aggregation",
            "exclusion_rule",
            "failure_rule",
            "no_regression_endpoints",
            "multiplicity_rule",
            "sample_size_justification",
        }
        if validator.require_keys(analysis, "/confirmatory_analysis", analysis_required):
            validator.reject_unknown(analysis, "/confirmatory_analysis", analysis_required | {"extensions"})
            if analysis.get("analysis_unit") != "physical-print":
                validator.issue(
                    "ANALYSIS_UNIT",
                    "/confirmatory_analysis/analysis_unit",
                    "must be physical-print",
                )
            if analysis.get("dependence_cluster") != "source-group":
                validator.issue(
                    "DEPENDENCE_CLUSTER",
                    "/confirmatory_analysis/dependence_cluster",
                    "must be source-group",
                )
            string_fields = analysis_required.difference(
                {"analysis_unit", "dependence_cluster", "practical_margin", "no_regression_endpoints"},
            )
            for field in string_fields:
                value = analysis.get(field)
                if not isinstance(value, str) or not value.strip():
                    validator.issue(
                        "ANALYSIS_FIELD_EMPTY",
                        _pointer("/confirmatory_analysis", field),
                        "must be a non-empty string",
                    )
            margin = analysis.get("practical_margin")
            if (
                not isinstance(margin, (int, float))
                or isinstance(margin, bool)
                or not math.isfinite(margin)
                or margin <= 0
            ):
                validator.issue(
                    "PRACTICAL_MARGIN",
                    "/confirmatory_analysis/practical_margin",
                    "must be a positive finite number",
                )
            no_regression = analysis.get("no_regression_endpoints")
            if (
                not isinstance(no_regression, list)
                or not no_regression
                or any(not isinstance(value, str) or not value.strip() for value in no_regression)
            ):
                validator.issue(
                    "NO_REGRESSION_ENDPOINTS",
                    "/confirmatory_analysis/no_regression_endpoints",
                    "must be a non-empty array of strings",
                )
        if registered is not None and earliest_capture is not None and registered > earliest_capture:
            validator.issue(
                "PREREGISTRATION_LATE",
                "/preregistration/registered_utc",
                "registration occurred after confirmatory capture began",
            )
        for print_id in print_to_group:
            if print_id not in accepted_annotation_prints:
                validator.issue(
                    "CONFIRMATORY_ANNOTATION_REQUIRED",
                    "/annotations",
                    f"{print_id} has no accepted annotation set",
                )
        if not verification_target_present:
            validator.issue(
                "VERIFICATION_TARGET_REQUIRED",
                "/target_instances",
                "confirmatory data requires an independent verification target",
            )
    elif data.get("preregistration") is not None or data.get("confirmatory_analysis") is not None:
        validator.issue(
            "DEVELOPMENT_CONFIRMATORY_FIELDS",
            "/preregistration",
            "development manifests must not present confirmatory controls",
        )

    return sorted(validator.issues)


def _validate_frames(
    validator: Validator,
    frames: Any,
    pointer: str,
    asset_records: dict[str, dict[str, Any]],
    modality: str,
) -> None:
    if not isinstance(frames, list) or not frames:
        validator.issue("FRAMES_REQUIRED", pointer, "phone burst must contain frames")
        return
    indexes: list[int] = []
    timestamps: list[int] = []
    used_assets: set[str] = set()
    expected_roles = {
        "phone-raw-burst": {"phone-raw-frame", "capture-result"},
        "phone-yuv-burst": {"phone-yuv-frame", "capture-result"},
    }[modality]
    for frame_index, frame in enumerate(frames):
        frame_pointer = _pointer(pointer, frame_index)
        required = {"frame_id", "frame_index", "sensor_timestamp_ns", "asset_ids"}
        if not validator.require_keys(frame, frame_pointer, required):
            continue
        validator.reject_unknown(frame, frame_pointer, required | {"extensions"})
        validator.validate_id(frame.get("frame_id"), _pointer(frame_pointer, "frame_id"))
        index = frame.get("frame_index")
        timestamp = frame.get("sensor_timestamp_ns")
        if not isinstance(index, int) or isinstance(index, bool) or index < 0:
            validator.issue("FRAME_INDEX", _pointer(frame_pointer, "frame_index"), "must be non-negative")
        else:
            indexes.append(index)
        if not isinstance(timestamp, int) or isinstance(timestamp, bool) or timestamp <= 0:
            validator.issue(
                "SENSOR_TIMESTAMP",
                _pointer(frame_pointer, "sensor_timestamp_ns"),
                "must be a positive integer",
            )
        else:
            timestamps.append(timestamp)
        references = frame.get("asset_ids")
        if not isinstance(references, list) or not references:
            validator.issue("FRAME_ASSETS", _pointer(frame_pointer, "asset_ids"), "must be non-empty")
        else:
            frame_roles: set[str] = set()
            for reference_index, reference in enumerate(references):
                reference_pointer = _pointer(_pointer(frame_pointer, "asset_ids"), reference_index)
                if not isinstance(reference, str):
                    validator.issue("INVALID_ID", reference_pointer, "asset reference must be a string ID")
                    continue
                if reference not in asset_records:
                    validator.issue(
                        "UNRESOLVED_ASSET",
                        reference_pointer,
                        f"unknown asset_id: {reference}",
                    )
                else:
                    frame_roles.add(asset_records[reference].get("role"))
                if reference in used_assets:
                    validator.issue(
                        "FRAME_ASSET_REUSED",
                        reference_pointer,
                        "a frame payload/sidecar asset may belong to only one frame",
                    )
                else:
                    used_assets.add(reference)
            if not expected_roles.issubset(frame_roles):
                validator.issue(
                    "FRAME_ASSET_ROLE",
                    _pointer(frame_pointer, "asset_ids"),
                    f"frame requires asset roles {sorted(expected_roles)}",
                )
    if indexes and sorted(indexes) != list(range(len(indexes))):
        validator.issue("FRAME_INDEX_GAP", pointer, "frame indexes must be contiguous from zero")
    if timestamps and any(right <= left for left, right in zip(timestamps, timestamps[1:])):
        validator.issue("TIMESTAMP_ORDER", pointer, "sensor timestamps must strictly increase")


def validate_capture_package(manifest_path: Path) -> list[Issue]:
    validator = Validator(manifest_path)
    try:
        data = load_json(manifest_path)
    except (OSError, json.JSONDecodeError, DuplicateKeyError, ValueError) as error:
        return [Issue("JSON_INVALID", "/", str(error))]
    required = {
        "schema_version",
        "capture_id",
        "print_id",
        "created_utc",
        "completed_utc",
        "status",
        "mode",
        "requested_frame_count",
        "received_frame_count",
        "persisted_frame_count",
        "camera",
        "device",
        "frames",
        "errors",
    }
    if not validator.require_keys(data, "", required):
        return sorted(validator.issues)
    validator.reject_unknown(data, "", required | {"extensions"})
    if data.get("schema_version") != SCHEMA_VERSION:
        validator.issue("SCHEMA_VERSION", "/schema_version", f"expected {SCHEMA_VERSION}")
    validator.validate_id(data.get("capture_id"), "/capture_id")
    validator.validate_id(data.get("print_id"), "/print_id")
    created = validator.validate_timestamp(data.get("created_utc"), "/created_utc")
    completed = validator.validate_timestamp(data.get("completed_utc"), "/completed_utc")
    if created is not None and completed is not None and completed < created:
        validator.issue("CAPTURE_TIME_ORDER", "/completed_utc", "precedes creation")
    mode = data.get("mode")
    if mode not in {"raw-sensor", "yuv-420-888"}:
        validator.issue("CAPTURE_MODE", "/mode", "must be raw-sensor or yuv-420-888")
    status = data.get("status")
    if status not in CAPTURE_STATUSES:
        validator.issue("CAPTURE_STATUS", "/status", f"must be one of {sorted(CAPTURE_STATUSES)}")
    requested = data.get("requested_frame_count")
    received = data.get("received_frame_count")
    persisted = data.get("persisted_frame_count")
    for field, value in (
        ("requested_frame_count", requested),
        ("received_frame_count", received),
        ("persisted_frame_count", persisted),
    ):
        if not isinstance(value, int) or isinstance(value, bool) or value < 0:
            validator.issue("FRAME_COUNT", f"/{field}", "must be a non-negative integer")
    frames = data.get("frames")
    frame_count = len(frames) if isinstance(frames, list) else -1
    errors = data.get("errors")
    if not isinstance(errors, list) or any(not isinstance(error, str) for error in errors):
        validator.issue("CAPTURE_ERRORS", "/errors", "must be an array of strings")
    if status == "accepted":
        if not (
            isinstance(requested, int)
            and isinstance(received, int)
            and isinstance(persisted, int)
            and requested > 0
            and requested == received == persisted == frame_count
        ):
            validator.issue(
                "INCOMPLETE_ACCEPTED_BURST",
                "/frames",
                "accepted capture requires requested == received == persisted == frame count > 0",
            )
        if errors:
            validator.issue("ACCEPTED_CAPTURE_ERRORS", "/errors", "accepted capture must have no errors")
    camera = data.get("camera")
    if isinstance(camera, dict) and "characteristics_file" in camera:
        _validate_capture_file_record(
            validator,
            camera.get("characteristics_file"),
            "/camera/characteristics_file",
            expected_role="camera-characteristics",
        )
    _validate_capture_package_frames(
        validator,
        frames,
        mode,
        data.get("capture_id"),
        status == "accepted",
    )
    return sorted(validator.issues)


def _validate_capture_package_frames(
    validator: Validator,
    frames: Any,
    mode: Any,
    capture_id: Any,
    accepted: bool,
) -> None:
    if not isinstance(frames, list):
        validator.issue("TYPE_ARRAY", "/frames", "must be an array")
        return
    indexes: list[int] = []
    timestamps: list[int] = []
    paths: set[str] = set()
    for index, frame in enumerate(frames):
        pointer = _pointer("/frames", index)
        required = {
            "frame_index",
            "sensor_timestamp_ns",
            "width",
            "height",
            "format",
            "files",
        }
        if not validator.require_keys(frame, pointer, required):
            continue
        validator.reject_unknown(frame, pointer, required | {"extensions"})
        frame_index = frame.get("frame_index")
        timestamp = frame.get("sensor_timestamp_ns")
        if isinstance(frame_index, int) and not isinstance(frame_index, bool) and frame_index >= 0:
            indexes.append(frame_index)
        else:
            validator.issue("FRAME_INDEX", _pointer(pointer, "frame_index"), "must be non-negative")
        if isinstance(timestamp, int) and not isinstance(timestamp, bool) and timestamp > 0:
            timestamps.append(timestamp)
        else:
            validator.issue(
                "SENSOR_TIMESTAMP",
                _pointer(pointer, "sensor_timestamp_ns"),
                "must be positive",
            )
        expected_format = "RAW_SENSOR" if mode == "raw-sensor" else "I420"
        if frame.get("format") != expected_format:
            validator.issue(
                "FRAME_FORMAT",
                _pointer(pointer, "format"),
                f"expected {expected_format}",
            )
        files = frame.get("files")
        if not isinstance(files, list) or not files:
            validator.issue("FRAME_FILES", _pointer(pointer, "files"), "must be non-empty")
            continue
        roles: set[str] = set()
        metadata_path: Path | None = None
        for file_index, file_record in enumerate(files):
            file_pointer = _pointer(_pointer(pointer, "files"), file_index)
            if not isinstance(file_record, dict):
                validator.issue("TYPE_OBJECT", file_pointer, "must be an object")
                continue
            resolved = _validate_capture_file_record(validator, file_record, file_pointer)
            path_value = file_record.get("path")
            if isinstance(path_value, str):
                if path_value.casefold() in paths:
                    validator.issue("PATH_COLLISION", _pointer(file_pointer, "path"), "duplicate path")
                paths.add(path_value.casefold())
            role = file_record.get("role")
            if isinstance(role, str):
                roles.add(role)
            if role == "capture-metadata":
                metadata_path = resolved
        expected_roles = {"dng", "capture-metadata"} if mode == "raw-sensor" else {
            "i420",
            "capture-metadata",
        }
        if not expected_roles.issubset(roles):
            validator.issue(
                "FRAME_FILE_ROLES",
                _pointer(pointer, "files"),
                f"must include {sorted(expected_roles)}",
            )
        if metadata_path is not None:
            try:
                metadata = load_json(metadata_path)
            except (OSError, json.JSONDecodeError, DuplicateKeyError, ValueError) as error:
                validator.issue(
                    "CAPTURE_METADATA_INVALID",
                    _pointer(pointer, "files"),
                    str(error),
                )
            else:
                metadata_required = {
                    "capture_id",
                    "frame_index",
                    "sensor_timestamp_ns",
                    "image_timestamp_ns",
                }
                if validator.require_keys(metadata, _pointer(pointer, "metadata"), metadata_required):
                    expected = {
                        "capture_id": capture_id,
                        "frame_index": frame_index,
                        "sensor_timestamp_ns": timestamp,
                    }
                    for field, expected_value in expected.items():
                        if metadata.get(field) != expected_value:
                            validator.issue(
                                "CAPTURE_METADATA_MISMATCH",
                                _pointer(_pointer(pointer, "metadata"), field),
                                f"expected {expected_value}",
                            )
                    if accepted and metadata.get("image_timestamp_ns") != timestamp:
                        validator.issue(
                            "TIMESTAMP_PAIR_MISMATCH",
                            _pointer(_pointer(pointer, "metadata"), "image_timestamp_ns"),
                            "accepted capture requires image timestamp to equal SENSOR_TIMESTAMP",
                        )
    if indexes and sorted(indexes) != list(range(len(indexes))):
        validator.issue("FRAME_INDEX_GAP", "/frames", "indexes must be contiguous from zero")
    ordered = [timestamp for _, timestamp in sorted(zip(indexes, timestamps))]
    if ordered and any(right <= left for left, right in zip(ordered, ordered[1:])):
        validator.issue("TIMESTAMP_ORDER", "/frames", "timestamps must strictly increase")


def _validate_capture_file_record(
    validator: Validator,
    file_record: Any,
    pointer: str,
    expected_role: str | None = None,
) -> Path | None:
    required = {"path", "role", "media_type", "bytes", "sha256"}
    if not validator.require_keys(file_record, pointer, required):
        return None
    validator.reject_unknown(file_record, pointer, required)
    if expected_role is not None and file_record.get("role") != expected_role:
        validator.issue("FILE_ROLE", _pointer(pointer, "role"), f"expected {expected_role}")
    resolved = validator.resolve_asset_path(file_record.get("path"), _pointer(pointer, "path"))
    byte_count = file_record.get("bytes")
    digest = file_record.get("sha256")
    if resolved is not None:
        if not isinstance(byte_count, int) or byte_count != resolved.stat().st_size:
            validator.issue("ASSET_SIZE_MISMATCH", _pointer(pointer, "bytes"), "size differs")
        actual = hashlib.sha256(resolved.read_bytes()).hexdigest()
        if digest != actual:
            validator.issue("ASSET_HASH_MISMATCH", _pointer(pointer, "sha256"), "hash differs")
    if not isinstance(digest, str) or not SHA256_PATTERN.fullmatch(digest):
        validator.issue("INVALID_SHA256", _pointer(pointer, "sha256"), "must be lowercase SHA-256")
    return resolved


def validate_scan_package(manifest_path: Path) -> list[Issue]:
    validator = Validator(manifest_path)
    try:
        data = load_json(manifest_path)
    except (OSError, json.JSONDecodeError, DuplicateKeyError, ValueError) as error:
        return [Issue("JSON_INVALID", "/", str(error))]
    required = {"schema_version", "scan_id", "state", "acquisition_manifest"}
    allowed = required | {
        "created_utc",
        "accepted_utc",
        "processing_attempt",
        "acquisition_sha256",
        "source_retained",
        "used_fusion",
        "preview_path",
        "working_image_path",
        "processing_report",
        "selected_quad",
        "manual_quad",
        "archival_scale",
        "derivatives",
        "error",
        "extensions",
    }
    if not validator.require_keys(data, "", required):
        return sorted(validator.issues)
    validator.reject_unknown(data, "", allowed)
    if data.get("schema_version") != "1.0.0":
        validator.issue("SCHEMA_VERSION", "/schema_version", "expected 1.0.0")
    validator.validate_id(data.get("scan_id"), "/scan_id")
    state = data.get("state")
    if state not in {"processing", "review", "accepted", "failed"}:
        validator.issue("SCAN_STATE", "/state", "invalid scan state")
    if "processing_attempt" in data and (
        not isinstance(data["processing_attempt"], int)
        or isinstance(data["processing_attempt"], bool)
        or data["processing_attempt"] < 1
    ):
        validator.issue("PROCESSING_ATTEMPT", "/processing_attempt", "must be a positive integer")
    acquisition = data.get("acquisition_manifest")
    expected_acquisition = f"capture-package:{data.get('scan_id')}"
    if acquisition != expected_acquisition:
        validator.issue(
            "ACQUISITION_REFERENCE",
            "/acquisition_manifest",
            f"expected {expected_acquisition}",
        )
    for field in ("created_utc", "accepted_utc"):
        if field in data:
            validator.validate_timestamp(data[field], f"/{field}")
    acquisition_digest = data.get("acquisition_sha256")
    if acquisition_digest is not None and (
        not isinstance(acquisition_digest, str)
        or not SHA256_PATTERN.fullmatch(acquisition_digest)
    ):
        validator.issue("INVALID_SHA256", "/acquisition_sha256", "must be lowercase SHA-256")
    for field in ("source_retained", "used_fusion"):
        if field in data and not isinstance(data[field], bool):
            validator.issue("TYPE_BOOLEAN", f"/{field}", "must be a boolean")
    if "extensions" in data and not isinstance(data["extensions"], dict):
        validator.issue("TYPE_OBJECT", "/extensions", "must be an object")

    def validate_quad(value: Any, pointer: str) -> None:
        if not isinstance(value, dict):
            validator.issue("TYPE_OBJECT", pointer, "must be an object")
            return
        validator.reject_unknown(value, pointer, {"area_pixels", "normalized_points"})
        points = value.get("normalized_points")
        if not isinstance(points, list) or len(points) != 4:
            validator.issue("QUAD_POINTS", f"{pointer}/normalized_points", "must contain four points")
            return
        for index, point in enumerate(points):
            point_pointer = f"{pointer}/normalized_points/{index}"
            if not isinstance(point, list) or len(point) != 2:
                validator.issue("QUAD_POINT", point_pointer, "must contain x and y")
                continue
            for axis, coordinate in enumerate(point):
                if (
                    not isinstance(coordinate, (int, float))
                    or isinstance(coordinate, bool)
                    or not math.isfinite(coordinate)
                    or not 0 <= coordinate <= 1
                ):
                    validator.issue("QUAD_COORDINATE", f"{point_pointer}/{axis}", "must be in [0, 1]")

    for field in ("selected_quad", "manual_quad"):
        if field in data:
            validate_quad(data[field], f"/{field}")

    archival = data.get("archival_scale")
    if archival is not None:
        pointer = "/archival_scale"
        archival_required = {
            "recorded_utc",
            "authority",
            "physical_width_mm",
            "physical_height_mm",
            "pixel_width",
            "pixel_height",
            "sampling_frequency_ppi_x",
            "sampling_frequency_ppi_y",
            "claim",
            "delivered_resolution_claimed",
        }
        archival_allowed = archival_required | {"target_id"}
        if validator.require_keys(archival, pointer, archival_required):
            validator.reject_unknown(archival, pointer, archival_allowed)
            validator.validate_timestamp(archival.get("recorded_utc"), f"{pointer}/recorded_utc")
            authority = archival.get("authority")
            if authority not in {"confirmed-dimensions", "coplanar-target"}:
                validator.issue("SCALE_AUTHORITY", f"{pointer}/authority", "invalid authority")
            if authority == "coplanar-target" and not archival.get("target_id"):
                validator.issue("TARGET_ID", f"{pointer}/target_id", "coplanar target requires ID")
            for field in (
                "physical_width_mm",
                "physical_height_mm",
                "sampling_frequency_ppi_x",
                "sampling_frequency_ppi_y",
            ):
                value = archival.get(field)
                if not isinstance(value, (int, float)) or isinstance(value, bool) or value <= 0:
                    validator.issue("POSITIVE_NUMBER", f"{pointer}/{field}", "must be positive")
            for field in ("pixel_width", "pixel_height"):
                value = archival.get(field)
                if not isinstance(value, int) or isinstance(value, bool) or value <= 0:
                    validator.issue("POSITIVE_INTEGER", f"{pointer}/{field}", "must be positive")
            if archival.get("claim") != "sampling-frequency-from-confirmed-physical-scale":
                validator.issue("SCALE_CLAIM", f"{pointer}/claim", "invalid claim")
            if archival.get("delivered_resolution_claimed") is not False:
                validator.issue("RESOLUTION_CLAIM", f"{pointer}/delivered_resolution_claimed", "must be false")
    if state in {"review", "accepted"}:
        final_required = {
            "created_utc",
            "acquisition_sha256",
            "source_retained",
            "used_fusion",
            "preview_path",
            "working_image_path",
            "processing_report",
            "selected_quad",
            "derivatives",
        }
        validator.require_keys(data, "", final_required)
        if data.get("source_retained") is not True:
            validator.issue("SOURCE_RETENTION", "/source_retained", "must be true")
        if state == "accepted" and "accepted_utc" not in data:
            validator.issue("ACCEPTED_TIMESTAMP", "/accepted_utc", "accepted scan requires timestamp")
        acquisition_file = None
        if validator.root.parent.name == "scans":
            acquisition_file = (
                validator.root.parent.parent
                / "acquisitions"
                / str(data.get("scan_id"))
                / "capture.json"
            )
        if acquisition_file is None or not acquisition_file.is_file():
            validator.issue(
                "ACQUISITION_MISSING",
                "/acquisition_manifest",
                "paired acquisitions/<scan_id>/capture.json is required",
            )
        elif hashlib.sha256(acquisition_file.read_bytes()).hexdigest() != acquisition_digest:
            validator.issue(
                "ACQUISITION_HASH_MISMATCH",
                "/acquisition_sha256",
                "paired capture.json hash differs",
            )
        else:
            for issue in validate_capture_package(acquisition_file):
                validator.issue(
                    f"ACQUISITION_{issue.code}",
                    f"/acquisition{issue.pointer}",
                    issue.message,
                )
        for field in ("preview_path", "working_image_path", "processing_report"):
            if field in data:
                validator.resolve_asset_path(data[field], f"/{field}")
        derivatives = data.get("derivatives")
        if not isinstance(derivatives, list) or not derivatives:
            validator.issue("DERIVATIVES", "/derivatives", "must be a non-empty array")
        else:
            paths: set[str] = set()
            for index, derivative in enumerate(derivatives):
                pointer = f"/derivatives/{index}"
                derivative_required = {"path", "kind", "bytes", "sha256"}
                derivative_allowed = derivative_required | {
                    "media_type",
                    "recipe",
                    "created_utc",
                    "parent_path",
                    "parent_sha256",
                }
                if not validator.require_keys(derivative, pointer, derivative_required):
                    continue
                validator.reject_unknown(derivative, pointer, derivative_allowed)
                if derivative.get("kind") not in {
                    "acquisition-master",
                    "acquisition-derived",
                    "confidence-map",
                    "failure-map",
                    "restored",
                    "generative",
                }:
                    validator.issue("DERIVATIVE_KIND", f"{pointer}/kind", "invalid derivative kind")
                byte_count = derivative.get("bytes")
                if not isinstance(byte_count, int) or isinstance(byte_count, bool) or byte_count <= 0:
                    validator.issue("INVALID_BYTES", f"{pointer}/bytes", "must be a positive integer")
                if "created_utc" in derivative:
                    validator.validate_timestamp(derivative["created_utc"], f"{pointer}/created_utc")
                path_value = derivative.get("path")
                resolved = validator.resolve_asset_path(path_value, f"{pointer}/path")
                if isinstance(path_value, str):
                    if path_value.casefold() in paths:
                        validator.issue("PATH_COLLISION", f"{pointer}/path", "duplicate path")
                    paths.add(path_value.casefold())
                if resolved is not None:
                    if derivative.get("bytes") != resolved.stat().st_size:
                        validator.issue("ASSET_SIZE_MISMATCH", f"{pointer}/bytes", "size differs")
                    actual = hashlib.sha256(resolved.read_bytes()).hexdigest()
                    if derivative.get("sha256") != actual:
                        validator.issue("ASSET_HASH_MISMATCH", f"{pointer}/sha256", "hash differs")
                digest = derivative.get("sha256")
                if not isinstance(digest, str) or not SHA256_PATTERN.fullmatch(digest):
                    validator.issue("INVALID_SHA256", f"{pointer}/sha256", "must be lowercase SHA-256")
                parent_path = derivative.get("parent_path")
                parent_digest = derivative.get("parent_sha256")
                if parent_path is not None:
                    parent = validator.resolve_asset_path(parent_path, f"{pointer}/parent_path")
                    if parent is not None and hashlib.sha256(parent.read_bytes()).hexdigest() != parent_digest:
                        validator.issue("PARENT_HASH_MISMATCH", f"{pointer}/parent_sha256", "hash differs")
    if state == "failed" and not isinstance(data.get("error"), str):
        validator.issue("FAILURE_REASON", "/error", "failed scan requires an error")
    return sorted(validator.issues)


def _run_validation(kind: str, path: Path, json_output: bool) -> int:
    if kind == "benchmark":
        issues = validate_benchmark(path)
    elif kind == "capture":
        issues = validate_capture_package(path)
    else:
        issues = validate_scan_package(path)
    if json_output:
        print(json.dumps([dataclasses.asdict(issue) for issue in issues], indent=2))
    elif issues:
        for issue in issues:
            print(issue.format(), file=sys.stderr)
    else:
        print(f"valid {kind} manifest: {path}")
    return 1 if issues else 0


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__)
    subparsers = parser.add_subparsers(dest="command", required=True)
    for command, help_text in (
        ("validate", "validate a benchmark manifest"),
        ("validate-capture", "validate an Android capture package"),
        ("validate-scan", "validate a product scan and derivative graph"),
    ):
        child = subparsers.add_parser(command, help=help_text)
        child.add_argument("manifest", type=Path)
        child.add_argument("--json", action="store_true", help="emit machine-readable issues")
    subparsers.add_parser("version", help="print validator/schema versions")
    return parser


def main(argv: Sequence[str] | None = None) -> int:
    args = build_parser().parse_args(argv)
    if args.command == "version":
        print(f"validator={VALIDATOR_VERSION} schema={SCHEMA_VERSION}")
        return 0
    kind = {
        "validate": "benchmark",
        "validate-capture": "capture",
        "validate-scan": "scan",
    }[args.command]
    return _run_validation(kind, args.manifest, args.json)


if __name__ == "__main__":
    raise SystemExit(main())
