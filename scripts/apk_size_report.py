#!/usr/bin/env python3
"""Inspect actual APK ZIP sizes without requiring Android SDK tools."""
import argparse
from collections import defaultdict
import json
from pathlib import Path
import struct
import zipfile


def section(name):
    if name.startswith("lib/"):
        return "native"
    if name.startswith("assets/chaquopy/"):
        return "python_assets"
    if name.startswith("assets/"):
        return "other_assets"
    if name.endswith(".dex") and "/" not in name:
        return "dex"
    if name.startswith("res/") or name == "resources.arsc":
        return "resources"
    return "other"


def inspect_dex(apk, entries):
    files = []
    prefixes = {"old_exoplayer": "Lcom/google/android/exoplayer2/", "media3": "Landroidx/media3/",
                "python_runtime": "Lcom/kukuqi/tvbox/engine/python/", "chaquopy": "Lcom/chaquo/python/",
                "quickjs_runtime": "Lcom/kukuqi/tvbox/engine/quickjs/", "quickjs_wrapper": "Lcom/whl/quickjs/",
                "thunder": "Lcom/xunlei/downloadlib/"}
    counts = dict.fromkeys(prefixes, 0)
    for entry in entries:
        if section(entry.filename) != "dex":
            continue
        data = apk.read(entry.filename)
        if not data.startswith(b"dex\n"):
            raise ValueError(f"Unsupported DEX header: {entry.filename}")
        uint = lambda offset: struct.unpack_from("<I", data, offset)[0]
        strings_offset, types_offset = uint(60), uint(68)
        classes, classes_offset = uint(96), uint(100)
        for index in range(classes):
            type_index = uint(classes_offset + index * 32)
            string_index = uint(types_offset + type_index * 4)
            offset = uint(strings_offset + string_index * 4)
            # Skip the ULEB128 UTF-16 length; class descriptors here are ASCII.
            while data[offset] & 0x80:
                offset += 1
            offset += 1
            descriptor = data[offset:data.index(0, offset)].decode("utf-8", errors="replace")
            for key, prefix in prefixes.items():
                if descriptor.startswith(prefix):
                    counts[key] += 1
        files.append({"name": entry.filename, "class_definitions": classes,
                      "method_references": uint(88)})
    return {"files": files, "class_definitions": sum(item["class_definitions"] for item in files),
            "package_class_definitions": counts}


def inspect(path):
    groups = defaultdict(lambda: {"entries": 0, "compressed_bytes": 0, "raw_bytes": 0})
    with zipfile.ZipFile(path) as apk:
        entries = [entry for entry in apk.infolist() if not entry.is_dir()]
        for entry in entries:
            group = groups[section(entry.filename)]
            group["entries"] += 1
            group["compressed_bytes"] += entry.compress_size
            group["raw_bytes"] += entry.file_size
        native = [entry for entry in entries if entry.filename.startswith("lib/")]
        return {
            "path": str(Path(path).resolve()),
            "apk_bytes": Path(path).stat().st_size,
            "sections": dict(groups),
            "dex": inspect_dex(apk, entries),
            "abis": sorted({entry.filename.split("/")[1] for entry in native}),
            "stored_native_bytes": sum(entry.file_size for entry in native if entry.compress_type == zipfile.ZIP_STORED),
            "python_arm32_entries": [entry.filename for entry in entries if entry.filename.startswith("assets/chaquopy/") and "armeabi-v7a" in entry.filename],
            "largest": [{"name": entry.filename, "compressed_bytes": entry.compress_size, "raw_bytes": entry.file_size}
                        for entry in sorted(entries, key=lambda item: item.compress_size, reverse=True)[:15]],
        }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("apk", nargs="+", type=Path)
    parser.add_argument("--json", action="store_true")
    args = parser.parse_args()
    reports = [inspect(path) for path in args.apk]
    if args.json:
        print(json.dumps(reports, indent=2, ensure_ascii=False))
        return
    for report in reports:
        print(f"{report['path']}: {report['apk_bytes'] / 1048576:.2f} MiB ({report['apk_bytes']} bytes)")
        print(f"  ABI: {', '.join(report['abis'])}; ARM32 Python entries: {len(report['python_arm32_entries'])}")
        dex = report["dex"]
        print(f"  DEX: {len(dex['files'])}; class definitions: {dex['class_definitions']}; packages: {dex['package_class_definitions']}")
        for name, group in report["sections"].items():
            print(f"  {name:15} ZIP {group['compressed_bytes'] / 1048576:7.2f} MiB / raw {group['raw_bytes'] / 1048576:7.2f} MiB")
        print(f"  Uncompressed native entries: {report['stored_native_bytes'] / 1048576:.2f} MiB")
    if len(reports) == 2:
        saved = reports[0]["apk_bytes"] - reports[1]["apk_bytes"]
        print(f"Reduction: {saved / 1048576:.2f} MiB ({saved / reports[0]['apk_bytes']:.1%})")


if __name__ == "__main__":
    main()
