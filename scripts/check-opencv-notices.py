#!/usr/bin/env python3
"""Fail when the APK's notices miss code statically linked into OpenCV.

libopencv_java4.so from the OpenCV AAR embeds its build information
(cv::getBuildInformation()). It names the third-party libraries linked into
each ABI's library and their versions, which no Maven POM describes. This
check reads that text from every lib/<abi>/libopencv_java<major>.so in the APK
and requires a matching entry, with the same version, in the AboutLibraries
definitions (res/raw/aboutlibraries.json; bundled entries live in
app/config/libraries). An OpenCV upgrade that adds a library, changes a
version or rewords or renames one of these lines therefore fails until the
notices and this script are refreshed.

Usage: python3 -I scripts/check-opencv-notices.py app/build/outputs/apk/debug/app-debug.apk
Python 3.9+ standard library only. Exit status: 0 clean, 1 findings, 2 bad input.
"""

from __future__ import annotations

import json
import re
import sys
import zipfile

DEFINITIONS_ENTRY = "res/raw/aboutlibraries.json"
# Any major version: OpenCV 5 ships libopencv_java5.so.
OPENCV_LIBRARY = re.compile(r"lib/[^/]+/libopencv_java\d+\.so")
BUILD_INFO_START = b"General configuration for OpenCV"

# Names on the "3rdparty dependencies:" line -> notice entries in app/config/libraries.
THIRD_PARTY = {
    "libcpufeatures": "kirsch:cpufeatures",
    "libprotobuf": "kirsch:protobuf",
    "ade": "kirsch:ade",
    "tbb": "kirsch:onetbb",
    "ittnotify": "kirsch:ittnotify",
    "libjpeg-turbo": "kirsch:libjpeg-turbo",
    "libwebp": "kirsch:libwebp",
    "libpng": "kirsch:libpng",
    "libtiff": "kirsch:libtiff",
    "libopenjp2": "kirsch:openjpeg",
    "IlmImf": "kirsch:openexr",
    "tegra_hal": "kirsch:carotene",
    "ippiw": "kirsch:intel-ipp-icv",
    "ippicv": "kirsch:intel-ipp-icv",
}

# Code inside OpenCV modules that OpenCV installs its own license file for
# (sdk/etc/licenses), keyed by the module names on the "To be built:" line.
MODULE_CODE = {
    "core": ["kirsch:softfloat"],
    "features2d": ["kirsch:mscr-chi-table"],
    "gapi": ["kirsch:vas-ot"],
}

# Build-information lines (label, pattern for the line's value, entry) whose
# version the entry's artifactVersion must contain. Label None is the header.
# A line absent from one library (no IPP on ARM) requires nothing there, but a
# label that no library prints fails (see check), and so does a line whose
# value no longer matches: a renamed or reworded line cannot turn its check off.
VERSIONS = [
    (None, r"General configuration for OpenCV ([\d.]+)", "org.opencv:opencv"),
    ("JPEG", r"^build-libjpeg-turbo \(ver ([\d.]+)", "kirsch:libjpeg-turbo"),
    ("PNG", r"^build \(ver ([\d.]+)\)", "kirsch:libpng"),
    ("TIFF", r"^build \(ver \d+ - ([\d.]+)\)", "kirsch:libtiff"),
    ("JPEG 2000", r"^build \(ver ([\d.]+)\)", "kirsch:openjpeg"),
    ("OpenEXR", r"^build \(ver ([\d.]+)\)", "kirsch:openexr"),
    ("Protobuf", r"^build \(([\d.]+)\)", "kirsch:protobuf"),
    ("Flatbuffers", r"^builtin/3rdparty \(([\d.]+)\)", "kirsch:flatbuffers"),
    ("Parallel framework", r"^TBB \(ver ([\d.]+)", "kirsch:onetbb"),
    ("Intel IPP", r"^(\d+(?:\.\d+)+)", "kirsch:intel-ipp-icv"),
    # libc++_shared.so in the AAR comes from the NDK that built OpenCV.
    ("Android NDK", r"\(ver ([\d.]+)\)", "kirsch:llvm-libcxx"),
]

# Lines that say what is linked in; if OpenCV renamed one, nothing would be required.
THIRD_PARTY_LINE = "3rdparty dependencies"
MODULES_LINE = "To be built"


class InputError(Exception):
    """Unusable input: exit status 2."""


def build_info(data: bytes) -> str | None:
    start = data.find(BUILD_INFO_START)
    if start < 0:
        return None
    end = data.find(b"\0", start)
    return data[start:end].decode("utf-8", "replace")


def value(info: str, name: str) -> str | None:
    match = re.search(rf"^\s*{re.escape(name)}:[ \t]*(.*)$", info, re.MULTILINE)
    return match.group(1).strip() if match else None


def field(info: str, name: str) -> list[str]:
    return (value(info, name) or "").split()


def has_version(entry_version: str, version: str) -> bool:
    return re.search(rf"(?<![\d.]){re.escape(version)}(?!\d)", entry_version) is not None


def check(apk_path: str) -> list[str]:
    try:
        apk = zipfile.ZipFile(apk_path)
    except (OSError, zipfile.BadZipFile) as exc:
        raise InputError(f"cannot open {apk_path}: {exc}") from exc
    names = apk.namelist()
    libraries = sorted(name for name in names if OPENCV_LIBRARY.fullmatch(name))
    if not libraries:
        return []
    if DEFINITIONS_ENTRY not in names:
        return [f"{DEFINITIONS_ENTRY} missing: the APK ships OpenCV without notice definitions"]
    try:
        definitions = json.loads(apk.read(DEFINITIONS_ENTRY))
    except (UnicodeDecodeError, json.JSONDecodeError) as exc:
        raise InputError(f"{DEFINITIONS_ENTRY}: {exc}") from exc
    versions = {
        lib.get("uniqueId"): str(lib.get("artifactVersion") or "")
        for lib in definitions.get("libraries", [])
    }

    errors = []
    linked = set()  # entries named on some library's "3rdparty dependencies:" line
    reported = set()  # VERSIONS labels that some library prints
    for name in libraries:
        info = build_info(apk.read(name))
        if info is None:
            errors.append(f"{name}: no OpenCV build information found")
            continue
        for label in (THIRD_PARTY_LINE, MODULES_LINE):
            if value(info, label) is None:
                errors.append(
                    f"{name}: no '{label}:' line in the build information; if OpenCV renamed "
                    "it, update scripts/check-opencv-notices.py and the notices"
                )
        required = set()
        for token in field(info, THIRD_PARTY_LINE):
            if token not in THIRD_PARTY:
                errors.append(
                    f"{name}: statically linked '{token}' has no notice mapping; add a bundled "
                    "entry in app/config and map it in scripts/check-opencv-notices.py"
                )
                continue
            required.add(THIRD_PARTY[token])
        linked |= required
        for module in field(info, MODULES_LINE):
            required.update(MODULE_CODE.get(module, []))
        for label, pattern, ident in VERSIONS:
            line = info if label is None else value(info, label)
            if line is None:
                continue
            reported.add(label)
            match = re.search(pattern, line)
            if not match:
                errors.append(
                    f"{name}: unrecognized '{label or 'OpenCV version'}' line in the build "
                    "information; check its notices and update VERSIONS in scripts/check-opencv-notices.py"
                )
                continue
            required.add(ident)
            if ident in versions and not has_version(versions[ident], match.group(1)):
                errors.append(
                    f"{name}: {ident} is {match.group(1)} in the build information but "
                    f"'{versions[ident]}' in the notices; refresh its entry and license text"
                )
        for ident in sorted(required - versions.keys()):
            errors.append(f"{name}: no notice entry '{ident}' in {DEFINITIONS_ENTRY}")
    # A label that no library prints was renamed or dropped, unless no library links
    # the component it belongs to (IPP when the APK has no x86 ABIs).
    for label, _, ident in VERSIONS:
        if label is None or label in reported:
            continue
        if ident in THIRD_PARTY.values() and ident not in linked:
            continue
        errors.append(
            f"no library prints a '{label}:' line in its build information; if OpenCV "
            "renamed or dropped it, update VERSIONS in scripts/check-opencv-notices.py and the notices"
        )
    return errors


def main() -> int:
    if len(sys.argv) != 2:
        print(__doc__.strip(), file=sys.stderr)
        return 2
    try:
        errors = check(sys.argv[1])
    except InputError as exc:
        print(f"error: {exc}", file=sys.stderr)
        return 2
    for error in errors:
        print(f"error: {error}", file=sys.stderr)
    if errors:
        return 1
    print("ok")
    return 0


if __name__ == "__main__":
    sys.exit(main())
