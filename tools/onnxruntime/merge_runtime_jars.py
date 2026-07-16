#!/usr/bin/env python3
from __future__ import annotations

import argparse
import glob
import os
import zipfile
from pathlib import Path


EXPECTED = {
    "linux-aarch64": {"libonnxruntime.so", "libonnxruntime4j_jni.so"},
    "linux-x64": {"libonnxruntime.so", "libonnxruntime4j_jni.so"},
    "osx-aarch64": {"libonnxruntime.dylib", "libonnxruntime4j_jni.dylib"},
    "osx-x64": {"libonnxruntime.dylib", "libonnxruntime4j_jni.dylib"},
    "win-x64": {"onnxruntime.dll", "onnxruntime4j_jni.dll"},
}
NATIVE_PREFIX = "ai/onnxruntime/native/"


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Merge verified single-platform ORT Java JARs.")
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("inputs", nargs="+", help="JAR paths or glob patterns")
    return parser.parse_args()


def expanded_inputs(patterns: list[str]) -> list[Path]:
    result: list[Path] = []
    for pattern in patterns:
        matches = [Path(path) for path in glob.glob(pattern, recursive=True)]
        result.extend(path for path in matches if path.is_file())
    unique = sorted(set(path.resolve() for path in result))
    if not unique:
        raise SystemExit("No input runtime JARs were found.")
    return unique


def wanted_native(name: str) -> bool:
    if not name.startswith(NATIVE_PREFIX) or name.endswith("/"):
        return False
    relative = name[len(NATIVE_PREFIX):]
    parts = relative.split("/")
    return len(parts) == 2 and parts[0] in EXPECTED and parts[1] in EXPECTED[parts[0]]


def main() -> None:
    args = parse_args()
    inputs = expanded_inputs(args.inputs)
    entries: dict[str, bytes] = {}
    native_seen: dict[str, set[str]] = {platform: set() for platform in EXPECTED}

    for index, jar_path in enumerate(inputs):
        with zipfile.ZipFile(jar_path) as source:
            for info in source.infolist():
                name = info.filename
                if info.is_dir() or name.endswith((".SF", ".RSA", ".DSA")):
                    continue
                if name.startswith(NATIVE_PREFIX):
                    if not wanted_native(name):
                        continue
                    platform, filename = name[len(NATIVE_PREFIX):].split("/", 1)
                    native_seen[platform].add(filename)
                elif index != 0:
                    continue

                data = source.read(info)
                previous = entries.get(name)
                if previous is not None and previous != data:
                    raise SystemExit(f"Conflicting entry {name} from {jar_path}")
                entries[name] = data

    missing = {
        platform: sorted(expected - native_seen[platform])
        for platform, expected in EXPECTED.items()
        if expected != native_seen[platform]
    }
    if missing:
        raise SystemExit(f"Universal runtime is missing native libraries: {missing}")

    args.output.parent.mkdir(parents=True, exist_ok=True)
    temporary = args.output.with_suffix(args.output.suffix + ".tmp")
    with zipfile.ZipFile(temporary, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=9) as target:
        for name in sorted(entries):
            info = zipfile.ZipInfo(name, date_time=(1980, 1, 1, 0, 0, 0))
            info.compress_type = zipfile.ZIP_DEFLATED
            info.external_attr = 0o644 << 16
            target.writestr(info, entries[name])
    os.replace(temporary, args.output)
    print(f"Wrote {args.output} ({args.output.stat().st_size} bytes)")


if __name__ == "__main__":
    main()
