"""Verify release JAR metadata, runtime mappings, bytecode and shared tests."""
import argparse
import json
from pathlib import Path
import struct
import tomllib
import xml.etree.ElementTree as ET
import zipfile

ROOT = Path(__file__).resolve().parents[1]
parser = argparse.ArgumentParser()
parser.add_argument('--loader', choices=('forge', 'fabric', 'all'), default='all')
args = parser.parse_args()
properties = dict(line.split('=', 1) for line in (ROOT / 'gradle.properties').read_text().splitlines()
                  if '=' in line and not line.startswith('#'))
version = properties['mod_version']
for loader in (('forge', 'fabric') if args.loader == 'all' else (args.loader,)):
    base = ROOT if loader == 'forge' else ROOT / 'fabric'
    artifact = base / 'build/libs' / f'CRTools-MTR-Map-Overlay-{loader}-mc1.20.1-{version}.jar'
    with zipfile.ZipFile(artifact) as jar:
        names = set(jar.namelist())
        classes = [name for name in names if name.endswith('.class')]
        assert classes, 'No classes packaged'
        forbidden = b'net/fabricmc/' if loader == 'forge' else b'net/minecraftforge/'
        for name in classes:
            data = jar.read(name)
            assert name.startswith('com/lx862/mtrmap/'), f'Bundled third-party class: {name}'
            assert struct.unpack('>H', data[6:8])[0] == 61, f'Not Java 17 bytecode: {name}'
            assert b'net/neoforged/' not in data and forbidden not in data, f'Wrong loader reference: {name}'
        assert not any(name.endswith('.jar') for name in names), 'Unexpected bundled dependency'
        assert json.loads(jar.read('pack.mcmeta'))['pack']['pack_format'] == 15
        assert b'org/mtr/mod/Init' in jar.read('com/lx862/mtrmap/mixin/MTRAccessorMixin.class')
        if loader == 'forge':
            metadata = tomllib.loads(jar.read('META-INF/mods.toml').decode())
            assert metadata['mods'][0]['modId'] == 'mtrmap'
            assert metadata['mods'][0]['version'] == version
            deps = {d['modId']: d for d in metadata['dependencies']['mtrmap']}
            assert deps['minecraft']['versionRange'] == '[1.20.1]'
            assert deps['mtr']['versionRange'] == '[4.0.5,4.1)'
            assert deps['journeymap']['versionRange'] == '[1.20.1-6.0.6,)'
            assert 'mtrmap.refmap.json' in names
            assert 'MixinConfigs: mtrmap.mixins.json' in jar.read('META-INF/MANIFEST.MF').decode()
            render_name, click_name = b'm_88315_', b'm_6375_'
        else:
            metadata = json.loads(jar.read('fabric.mod.json'))
            assert metadata['id'] == 'mtrmap' and metadata['version'] == version
            assert metadata['depends']['minecraft'] == '1.20.1'
            assert metadata['depends']['java'] == '>=17'
            assert metadata['depends']['mtr'] == '>=4.0.5 <4.1'
            assert 'META-INF/mods.toml' not in names
            render_name, click_name = b'method_25394', b'method_25402'
        hook = jar.read('com/lx862/mtrmap/mixin/client/xaero/XaeroWorldMapMixin.class')
        assert render_name in hook and click_name in hook, 'Missing production Xaero hooks'
    results = [ET.parse(p).getroot() for p in (base / 'build/test-results/test').glob('TEST-*.xml')]
    assert results, 'Missing test results'
    assert all(int(r.attrib['failures']) == 0 and int(r.attrib['errors']) == 0 for r in results)
    passed = sum(int(r.attrib['tests']) - int(r.attrib['skipped']) for r in results)
    assert passed >= 25, f'Incomplete shared test run: {passed}'
    print(f'{loader}: metadata, Java 17, loader isolation, Xaero hooks and {passed} tests verified: {artifact}')
