"""Bounded publication check. No credentials are printed; no network access.

Check a clean release/clone, not a private research workspace. Pattern checks
cannot prove that arbitrary binary data is free of every private value.
"""
from pathlib import Path
import hashlib,io,json,re,zipfile
ROOT=Path(__file__).resolve().parents[1]
SKIP_DIRS={'.git','build','build-temp','__pycache__','.venv','fixtures'}
SKIP_FILES={'elf-inspection.txt','build.json','G2-Display-Lab.apk','G2-Display-Lab.apk.idsig'}
ALLOWED_BINARIES={
    'firmware/halliday-g2-1.1.1-official-ota.zip',
    'native/mindylab/mindylab.o','native/navigation/mindylab.o',
    'g2_native_probe/g2_probe.o','releases/G2-Display-Lab-0.18-public.apk'}
PATTERNS={
    'private key':rb'-----BEGIN (?:RSA |EC |OPENSSH |ENCRYPTED )?PRIVATE KEY-----',
    'GitHub credential':rb'(?:gh[pousr]_[A-Za-z0-9]{25,}|github_pat_[A-Za-z0-9_]{30,})',
    'JWT':rb'eyJ[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]{8,}',
    'private Windows home':rb'(?i)C:[/\\]Users[/\\](?!Public[/\\])[^/\\\s]+[/\\]',
}
DENIED_EXT={'.jks','.keystore','.key','.pem','.pcap','.pcapng','.etl','.log','.idsig'}

def public_files():
    for p in sorted(ROOT.rglob('*')):
        if not p.is_file():continue
        rel=p.relative_to(ROOT)
        if set(rel.parts)&SKIP_DIRS or p.name in SKIP_FILES:continue
        yield p,rel.as_posix()

def scan_bytes(data,label,findings):
    for category,pattern in PATTERNS.items():
        matches=list(re.finditer(pattern,data))
        if category=='private key' and label=='firmware/halliday-g2-1.1.1-official-ota.zip!nuttx_ap.bin':
            # Manually reviewed parser delimiters in the hash-verified vendor
            # image: five NUL-terminated marker strings, no key body. Do not
            # exempt any actual PEM body or a changed firmware component.
            manifest=json.loads((ROOT/'firmware/manifest.json').read_text())
            expected=next(x['sha256'] for x in manifest['contents'] if x['name']=='nuttx_ap.bin')
            if hashlib.sha256(data).hexdigest()==expected and len(matches)==5 and all(data[m.end():m.end()+1]==b'\0' for m in matches):
                continue
        if matches:findings.append({'file':label,'category':category})

def audit():
    findings=[];inventory=[]
    for p,rel in public_files():
        data=p.read_bytes()
        if p.suffix.lower() in DENIED_EXT:findings.append({'file':rel,'category':'excluded file type'})
        if p.suffix.lower() in {'.bin','.o','.apk','.zip','.jpg','.png','.pdf'} and rel not in ALLOWED_BINARIES:
            findings.append({'file':rel,'category':'binary not explicitly allowed'})
        scan_bytes(data,rel,findings)
        if p.suffix in {'.apk','.zip'}:
            with zipfile.ZipFile(io.BytesIO(data)) as z:
                for info in z.infolist():
                    if info.file_size>32*1024*1024:raise ValueError('Archive scan bound exceeded')
                    scan_bytes(z.read(info),rel+'!'+info.filename,findings)
        if rel not in {'PUBLIC-MANIFEST.json','docs/privacy-audit.json'}:
            inventory.append({'path':rel,'bytes':len(data),'sha256':hashlib.sha256(data).hexdigest()})
    return findings,inventory

if __name__=='__main__':
    findings,inventory=audit()
    print(json.dumps({'files':len(inventory),'findings':findings},indent=2))
    raise SystemExit(bool(findings))
