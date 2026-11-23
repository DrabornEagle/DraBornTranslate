#!/usr/bin/env python3
# SPDX-License-Identifier: AGPL-3.0-only
"""Yerel çevirinin CTranslate2 çalışma zamanını hazırlar: windows/build/ct2 (uygulamayla "ct2" klasörü olarak gelir).

CouchSubs'ın .github/workflows/windows.yml'deki adımlarının aynısı:
  - python.org'dan gömülü Python 3.12.10 (SHA-256 denetlenir)
  - windows/ct2/requirements.txt'teki paketler sabit sürüm ve özetle (pip --require-hashes)
  - windows/ct2/worker.py
  - Visual C++ çalışma zamanı DLL'leri (CTranslate2 ister; uygulamanın yanında taşınabilir)
Kullanım:  python scripts/prepare-ct2-runtime.py   (Windows'ta, Python 3.12 ile; derlemeden önce bir kez)
"""
import hashlib, io, os, shutil, subprocess, sys, urllib.request, zipfile

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = os.path.join(ROOT, 'windows', 'build', 'ct2')
EMBED_URL = 'https://www.python.org/ftp/python/3.12.10/python-3.12.10-embed-amd64.zip'
EMBED_SHA = '4acbed6dd1c744b0376e3b1cf57ce906f9dc9e95e68824584c8099a63025a3c3'
VC_DLLS = ['msvcp140.dll', 'msvcp140_1.dll', 'msvcp140_2.dll', 'vcruntime140.dll', 'vcruntime140_1.dll', 'concrt140.dll']


def main():
    if os.path.isdir(OUT):
        shutil.rmtree(OUT)
    os.makedirs(OUT)

    print('gömülü Python indiriliyor...')
    data = urllib.request.urlopen(EMBED_URL, timeout=120).read()
    digest = hashlib.sha256(data).hexdigest()
    if digest != EMBED_SHA:
        sys.exit(f'Python gömülü paketinin özeti tutmuyor: {digest}')
    zipfile.ZipFile(io.BytesIO(data)).extractall(OUT)
    with open(os.path.join(OUT, 'python312._pth'), 'w', encoding='ascii') as f:
        f.write('python312.zip\n.\nLib\\site-packages\nimport site\n')

    print('paketler kuruluyor (sabit sürüm ve özet)...')
    subprocess.check_call([sys.executable, '-m', 'pip', 'install', '--disable-pip-version-check', '--no-deps', '--require-hashes',
                           '--only-binary=:all:', '--python-version', '3.12', '--platform', 'win_amd64', '--implementation', 'cp',
                           '--target', os.path.join(OUT, 'Lib', 'site-packages'),
                           '-r', os.path.join(ROOT, 'windows', 'ct2', 'requirements.txt')])
    shutil.copy2(os.path.join(ROOT, 'windows', 'ct2', 'worker.py'), os.path.join(OUT, 'worker.py'))

    system32 = os.path.join(os.environ.get('SystemRoot', r'C:\Windows'), 'System32')
    for name in VC_DLLS:
        source = os.path.join(system32, name)
        if os.path.exists(source) and not os.path.exists(os.path.join(OUT, name)):
            shutil.copy2(source, os.path.join(OUT, name))

    check = subprocess.run([os.path.join(OUT, 'python.exe'), '-I', '-c', 'import ctranslate2; print(ctranslate2.__version__)'],
                           capture_output=True, text=True)
    if check.returncode != 0:
        sys.exit('CTranslate2 açılmadı:\n' + check.stderr)
    size = sum(os.path.getsize(os.path.join(d, f)) for d, _, fs in os.walk(OUT) for f in fs)
    print(f'hazır: {OUT} (CTranslate2 {check.stdout.strip()}, {size // 1_000_000} MB)')


if __name__ == '__main__':
    main()
