"""Synthetic protocol records only: no device dumps or live nonces required."""
from pathlib import Path
import struct
ROOT=Path(__file__).resolve().parent/'tests/fixtures'
def put(b,at,value):struct.pack_into('<I',b,at,value)
def digest(b):
    n=2166136261
    for v in b:n=((n^v)*16777619)&0xffffffff
    return n
def generate():
    for name,version in (('mobile',1),('persistent',2),('navigation',4)):
        folder=ROOT/name;folder.mkdir(parents=True,exist_ok=True)
        b=bytearray(128)
        for at,v in {0:0x31534c4d,4:version,8:3,12:2,20:5,32:1,36:1,40:1,48:123,52:1}.items():put(b,at,v)
        b[64:80]=bytes(range(1,17))
        put(b,80,7 if version==4 else 1 if version==2 else 0)
        put(b,124,digest(b[:124]));(folder/'status-fixture.bin').write_bytes(b)
        c=bytearray(64);put(c,0,0x31434c4d);put(c,4,version);put(c,8,1);put(c,12,4)
        c[16:32]=b[64:80];put(c,60,digest(c[:60]));(folder/'command-fixture.bin').write_bytes(c)
if __name__=='__main__':generate()
