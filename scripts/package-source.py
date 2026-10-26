#!/usr/bin/env python3
"""Create a source-only handoff: no SDK, keys, media or build caches."""
from pathlib import Path
from zipfile import ZipFile, ZIP_DEFLATED
root=Path(__file__).resolve().parents[1]
version=__import__('re').search(r'versionName = \"([^\"]+)\"',(root/'app/build.gradle.kts').read_text()).group(1)
out=root/'deliverables'/f'shengyu-{version}-source.zip'
allowed_root={'.gitignore','README.md','TEST_REPORT.md','THIRD_PARTY_NOTICES.md','build.gradle.kts','settings.gradle.kts','gradle.properties','gradlew','gradlew.bat'}
files=[]
for path in root.rglob('*'):
    if not path.is_file(): continue
    rel=path.relative_to(root)
    parts=rel.parts
    include=(len(parts)==1 and path.name in allowed_root)
    include |= str(rel)=='app/build.gradle.kts'
    include |= str(rel).startswith('app/src/') and path.suffix in {'.kt','.java','.xml'}
    include |= str(rel).startswith('gradle/wrapper/') and path.suffix in {'.jar','.properties'}
    include |= str(rel).startswith('docs/') and path.suffix=='.md'
    include |= str(rel).startswith('scripts/') and path.suffix in {'.py','.sh'}
    include |= str(rel)=='test-media/SOURCES.md'
    include |= str(rel).startswith('compatibility-tests/') and path.suffix in {'.md','.sh','.py','.json','.java'}
    include |= str(rel)=='compatibility-tests/reports/SHA256SUMS'
    include |= str(rel).startswith(f'verification-{version}/') and path.suffix in {'.txt','.xml','.log','.png','.json','.md','.java','.py'}
    if include: files.append(path)
out.parent.mkdir(exist_ok=True)
with ZipFile(out,'w',ZIP_DEFLATED) as archive:
    for path in sorted(files): archive.write(path,Path('shengyu-source')/path.relative_to(root))
with ZipFile(out) as archive:
    assert not any(name.endswith(('.mp4','.webm','.wav','.m4a','.keystore','.jks','.bin','.apk')) for name in archive.namelist())
    assert not any(name.startswith(('shengyu-source/build/', 'shengyu-source/app/build/')) or '/.gradle/' in name or name.endswith('/local.properties') for name in archive.namelist())
    assert archive.testzip() is None
print(out, out.stat().st_size, 'bytes;',len(files),'source/evidence files')
