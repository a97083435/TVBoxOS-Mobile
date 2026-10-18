#!/usr/bin/env python3
"""Collect separately built runtime APKs and emit a schema-1 download catalog.

Example: python3 scripts/runtime_catalog.py --variant release --base-url https://example.org/modules/
Sign all release APKs with the host certificate BEFORE generating the catalog.
The generated directory is a deployment artifact, never an app asset directory.
"""
import argparse
import hashlib
import json
from pathlib import Path
import re
import shutil
import subprocess
from urllib.parse import urljoin, urlparse


def module_version(root, name):
    module = root / 'runtime' / name / 'build.gradle'
    source = module.read_text(encoding='utf-8')
    # Native modules declare their defaults in the applied shared script.
    if re.search(r"apply\s+from:\s*rootProject\.file\(['\"]runtime/native\.gradle['\"]\)", source):
        source = (root / 'runtime/native.gradle').read_text(encoding='utf-8') + '\n' + source
    code = re.search(r"\bversionCode\s+(\d+)\b", source)
    version = re.search(r"\bversionName\s+['\"]([0-9]+\.[0-9]+\.[0-9]+)['\"]", source)
    if not code or not version or int(code[1]) <= 0:
        raise ValueError(f'Invalid declared module version: {name}')
    return int(code[1]), version[1]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--variant', choices=['debug', 'release'], default='release')
    parser.add_argument('--base-url', required=True)
    parser.add_argument('--validation', action='store_true', help='Use isolated corevalidation package IDs')
    parser.add_argument('--output', type=Path, default=Path('build/runtime-dist'))
    parser.add_argument('--host-apk', type=Path)
    parser.add_argument('--aapt')
    parser.add_argument('--apksigner')
    args = parser.parse_args()
    if urlparse(args.base_url).scheme != 'https' and not (args.variant == 'debug' and urlparse(args.base_url).scheme == 'http'):
        parser.error('Release catalogs require an HTTPS base URL')
    root = Path(__file__).resolve().parents[1]
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=True)
    def certificate(apk):
        report = subprocess.check_output([args.apksigner, 'verify', '--verbose', '--print-certs', str(apk)], text=True)
        values = set(re.findall(r'certificate SHA-256 digest: ([0-9a-fA-F]{64})', report))
        if len(values) != 1:
            raise ValueError(f'Expected one verified certificate: {apk}')
        return values.pop().lower()
    if args.variant == 'release' and not all([args.host_apk, args.aapt, args.apksigner]):
        parser.error('Release catalogs require --host-apk, --aapt and --apksigner for identity checks')
    host_certificate = certificate(args.host_apk) if args.apksigner and args.host_apk else None
    modules = []
    for name in ['python', 'quickjs', 'thunder', 'nodejs', 'bdj']:
        version_code, version_name = module_version(root, name)
        candidates = list((root / 'runtime' / name / 'build/outputs/apk' / args.variant).glob('*.apk'))
        if len(candidates) != 1:
            parser.error(f'Build exactly one {name} {args.variant} APK first')
        source = candidates[0]
        package = f'com.kukuqi.tvbox.runtime.{name}' + ('.corevalidation' if args.validation else '')
        # Unsigned APKs cannot pass the host certificate check. Refuse a misleading publishable catalog.
        if args.variant == 'release' and 'unsigned' in source.name:
            parser.error(f'{source} is unsigned. Sign it with the host release key first.')
        if args.aapt:
            badging = subprocess.check_output([args.aapt, 'dump', 'badging', str(source)], text=True)
            if not badging.startswith(f"package: name='{package}' versionCode='{version_code}' versionName='{version_name}'"):
                parser.error(f'Unexpected module identity: {source}')
            if args.variant == 'release' and 'application-debuggable' in badging:
                parser.error('Refusing to publish a debuggable runtime')
        if host_certificate and certificate(source) != host_certificate:
            parser.error(f'Module signing certificate differs from host: {source}')
        filename = f'runtime-{name}-{version_name}-arm64-v8a.apk'
        destination = output / filename
        shutil.copyfile(source, destination)
        modules.append(dict(id=name,
                            packageName=package,
                            compatibilityId=f'tvbox-{name}-v1', versionCode=version_code, versionName=version_name,
                            artifacts=[dict(abi='arm64-v8a', minSdk=24, size=destination.stat().st_size,
                                            sha256=hashlib.sha256(destination.read_bytes()).hexdigest(),
                                            url=urljoin(args.base_url.rstrip('/') + '/', filename))]))
    (output / 'catalog.json').write_text(json.dumps(dict(schema=1, modules=modules), indent=2) + '\n', encoding='utf-8')
    print(output / 'catalog.json')


if __name__ == '__main__':
    main()
