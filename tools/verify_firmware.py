"""Verify the published OTA without extracting, installing or executing it."""
import hashlib,json,zipfile
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
m=json.loads((ROOT/'firmware/manifest.json').read_text())
p=ROOT/'firmware/halliday-g2-1.1.1-official-ota.zip'
assert p.stat().st_size==m['bytes'],'Package size mismatch'
assert hashlib.sha256(p.read_bytes()).hexdigest()==m['sha256'],'Package SHA256 mismatch'
with zipfile.ZipFile(p) as z:
    assert z.testzip() is None,'ZIP CRC mismatch'
    assert sorted(z.namelist())==sorted(x['name'] for x in m['contents']),'Unexpected entries'
    for entry in m['contents']:
        data=z.read(entry['name'])
        assert len(data)==entry['bytes'] and hashlib.sha256(data).hexdigest()==entry['sha256'],entry['name']
print('Official OTA size, SHA256, entries, component hashes and CRCs match. Nothing installed.')
