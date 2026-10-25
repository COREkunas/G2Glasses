"""Offline ONLY: transform an exact private 1.0.1 AP backup into the app-hook image.

Does not access a device, flash, generate a writer, or claim cold recovery.
Write the output OUTSIDE this public repository. Keep the original privately.
"""
import argparse,hashlib,json,struct
from pathlib import Path

ROOT=Path(__file__).resolve().parents[1]
ORIGINAL='7e32d44c98e5fee9a9d0876fbf5c63a46d7450c9d2a3a1a0d32eec4a37fa09ef'
PATCHED='13a931900b93777f6f3525d032d5469b5fd8dafe6c4c2d16952c3efba273678e'
SIZE=0x680000
RC=0x2807e8
SECTOR=0x280000
OLD=b'echo "testmode: " $TESTMODE'
NEW=b'insmod /data/ml.o ml'.ljust(len(OLD),b' ')

def sha(b):return hashlib.sha256(b).hexdigest()
def patch(data):
    if len(data)!=SIZE or sha(data)!=ORIGINAL:
        raise ValueError('Not the exact reviewed stock 1.0.1 AP partition. Refusing.')
    rc=data[RC:RC+324]
    if not rc.startswith(b'set +e\n') or rc.count(OLD)!=1:
        raise ValueError('Startup script layout mismatch.')
    at=RC+rc.index(OLD)
    result=bytearray(data);result[at:at+len(OLD)]=NEW;result=bytes(result)
    if sha(result)!=PATCHED:raise ValueError('Expected complete patched image hash mismatch.')
    if result[:SECTOR]!=data[:SECTOR] or result[SECTOR+8192:]!=data[SECTOR+8192:]:
        raise ValueError('Change escaped the reviewed sector.')
    if sum(struct.unpack('>128I',result[0x27dca8:0x27dca8+512]))&0xffffffff:
        raise ValueError('ROMFS superblock checksum mismatch.')
    if sum(struct.unpack('>8I',result[0x2807c8:RC]))&0xffffffff:
        raise ValueError('Startup inode checksum mismatch.')
    return result

def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('private_ap',type=Path);p.add_argument('private_output',type=Path)
    args=p.parse_args();dest=args.private_output.resolve()
    if dest==ROOT or ROOT in dest.parents:
        p.error('Choose an output outside this public repository; firmware outputs remain private.')
    if dest.exists():p.error('Output exists; refusing to overwrite.')
    data=args.private_ap.read_bytes();result=patch(data)
    with dest.open('xb') as f:f.write(result)
    print(json.dumps({'beforeSha256':sha(data),'afterSha256':sha(result),
                      'changedBytes':sum(a!=b for a,b in zip(data,result)),
                      'deviceAccess':False,'flashed':False,'independentRecovery':False}))

if __name__=='__main__':main()
