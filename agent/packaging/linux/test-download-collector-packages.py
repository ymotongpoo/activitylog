#!/usr/bin/env python3
"""Exercise release import with real Debian metadata and a fake GitHub client."""
import hashlib
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

SCRIPT = Path(__file__).with_name('download-collector-packages.sh').resolve()


class ImportTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        self.releases = self.root / 'releases'
        self.pool = self.root / 'pool'
        self.bin = self.root / 'bin'
        self.bin.mkdir()
        gh = self.bin / 'gh'
        gh.write_text('''#!/usr/bin/python3
import fnmatch, os, pathlib, shutil, sys
args = sys.argv[1:]
if args[:2] == ['release', 'list']:
    print('otelcol-extra/v0.2.2\\notelcol-extra/v0.2.0\\ndiscord-otelcol/v0.1.1')
else:
    assert args[2].startswith('otelcol-extra/v'), 'legacy release must never be downloaded'
    tag = args[2].replace('/', '_')
    target = pathlib.Path(args[args.index('--dir') + 1])
    patterns = [args[index + 1] for index, arg in enumerate(args) if arg == '--pattern']
    for artifact in (pathlib.Path(os.environ['FIXTURE_RELEASES']) / tag).iterdir():
        if any(fnmatch.fnmatch(artifact.name, pattern) for pattern in patterns):
            assert not artifact.name.startswith('discord-otelcol_'), 'legacy package must never be downloaded'
            shutil.copyfile(artifact, target / artifact.name)
''')
        gh.chmod(0o755)
        self.env = dict(os.environ, PATH=str(self.bin) + ':' + os.environ['PATH'], FIXTURE_RELEASES=str(self.releases))
        for tag, name, version, arches in [('otelcol-extra/v0.2.2', 'otelcol-extra', '0.2.2', ['amd64', 'arm64']),
                                           ('otelcol-extra/v0.2.0', 'otelcol-extra', '0.2.0', ['amd64', 'arm64']),
                                           ('discord-otelcol/v0.1.1', 'discord-otelcol', '0.1.1', ['amd64', 'arm64'])]:
            folder = self.releases / tag.replace('/', '_')
            folder.mkdir(parents=True)
            for arch in arches:
                self.package(folder, name, version, arch)
            if name == 'otelcol-extra' and version == '0.2.0':
                self.package(folder, 'discord-otelcol', version, 'all')
            self.checksums(folder)

    def package(self, folder, name, version, arch, metadata_arch=None, metadata_name=None):
        source = self.root / 'package'
        shutil.rmtree(source, ignore_errors=True)
        (source / 'DEBIAN').mkdir(parents=True)
        (source / 'DEBIAN/control').write_text(f'''Package: {metadata_name or name}
Version: {version}
Architecture: {metadata_arch or arch}
Maintainer: Fixture <fixture@example.invalid>
Description: Import test fixture
''')
        output = folder / f'{name}_{version}_{arch}.deb'
        subprocess.run(['dpkg-deb', '--root-owner-group', '--build', str(source), str(output)], check=True, stdout=subprocess.DEVNULL)
        return output

    def checksums(self, folder):
        (folder / 'SHA256SUMS').write_text(''.join(hashlib.sha256(p.read_bytes()).hexdigest() + '  ' + p.name + '\n' for p in sorted(folder.glob('*.deb'))))

    def run_import(self):
        return subprocess.run(['sh', str(SCRIPT), str(self.pool)], env=self.env, capture_output=True, text=True)

    def test_native_only_and_historical_manifests_ignore_all_legacy_packages(self):
        result = self.run_import()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(len(list(self.pool.glob('*.deb'))), 4)
        self.assertEqual(list(self.pool.glob('discord-otelcol_*.deb')), [])

    def test_reused_pool_removes_legacy_packages_and_keeps_unrelated_packages(self):
        self.pool.mkdir()
        (self.pool / 'discord-otelcol_0.2.0_all.deb').write_bytes(b'archived fixture')
        unrelated = self.pool / 'unrelated_1.0.0_all.deb'
        unrelated.write_bytes(b'keep this fixture')
        result = self.run_import()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(list(self.pool.glob('discord-otelcol_*.deb')), [])
        self.assertEqual(unrelated.read_bytes(), b'keep this fixture')

    def test_tampered_release_rejected_before_copy(self):
        folder = self.releases / 'otelcol-extra_v0.2.2'
        with (folder / 'otelcol-extra_0.2.2_arm64.deb').open('ab') as out:
            out.write(b'tampered')
        self.assertNotEqual(self.run_import().returncode, 0)
        self.assertEqual(list(self.pool.iterdir()), [])

    def test_wrong_architecture_rejected_even_with_valid_checksum(self):
        folder = self.releases / 'otelcol-extra_v0.2.2'
        self.package(folder, 'otelcol-extra', '0.2.2', 'arm64', metadata_arch='amd64')
        self.checksums(folder)
        self.assertNotEqual(self.run_import().returncode, 0)
        self.assertEqual(list(self.pool.iterdir()), [])

    def test_retired_package_cannot_masquerade_as_native_filename(self):
        folder = self.releases / 'otelcol-extra_v0.2.2'
        self.package(folder, 'otelcol-extra', '0.2.2', 'arm64', metadata_name='discord-otelcol')
        self.checksums(folder)
        self.assertNotEqual(self.run_import().returncode, 0)
        self.assertEqual(list(self.pool.iterdir()), [])


if __name__ == '__main__':
    unittest.main()
