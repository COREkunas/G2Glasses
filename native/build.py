"""Rebuild the frozen, assembled native source. No device transport or writes."""
import argparse, hashlib, json, os, platform, subprocess
from pathlib import Path

HERE = Path(__file__).resolve().parent

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('app', choices=('mindylab','navigation'))
    args = parser.parse_args()
    sdk = Path(os.environ.get('ANDROID_HOME', Path.home()/'AppData/Local/Android/Sdk'))
    ndk = Path(os.environ.get('ANDROID_NDK_HOME', sdk/'ndk/30.0.14904198'))
    host = 'windows-x86_64' if os.name=='nt' else ('darwin-x86_64' if platform.system()=='Darwin' else 'linux-x86_64')
    bin_dir = Path(os.environ.get('LLVM_BIN', ndk/'toolchains/llvm/prebuilt'/host/'bin'))
    ext = '.exe' if os.name=='nt' else ''
    work = HERE/args.app
    temp = work/'build-temp'; temp.mkdir(exist_ok=True)
    obj = work/'mindylab.o'
    before = hashlib.sha256(obj.read_bytes()).hexdigest() if obj.exists() else None
    flags = ['--target=arm-none-eabi','-mcpu=cortex-m33','-mthumb','-mfloat-abi=soft','-Oz','-mno-movt','-fno-builtin','-ffreestanding','-fno-stack-protector','-fno-unwind-tables','-fno-asynchronous-unwind-tables','-fno-vectorize','-fno-slp-vectorize','-Wall','-Wextra','-Werror']
    cmd = [str(bin_dir/('clang'+ext)),*flags,'-c',str(work/'mindylab.c'),'-o',str(obj)]
    for flag in ('disable-ifcvt-simple','disable-ifcvt-simple-false','disable-ifcvt-triangle','disable-ifcvt-triangle-false','disable-ifcvt-triangle-rev','disable-ifcvt-diamond','disable-ifcvt-forked-diamond','disable-early-ifcvt'):
        cmd += ['-mllvm','-'+flag]
    subprocess.run(cmd,check=True,env=dict(os.environ,TMP=str(temp),TEMP=str(temp)))
    subprocess.run([str(bin_dir/('llvm-objcopy'+ext)),'--remove-section=.ARM.exidx','--remove-section=.rel.ARM.exidx',str(obj)],check=True)
    ordered=temp/'ordered.o'
    subprocess.run([str(bin_dir/('ld.lld'+ext)),'-r','-T',str(work/'layout.ld'),str(obj),'-o',str(ordered)],check=True)
    obj.write_bytes(ordered.read_bytes())
    report=subprocess.check_output([str(bin_dir/('llvm-readelf'+ext)),'-h','-S','-s','-r',str(obj)],text=True)
    (work/'elf-inspection.txt').write_text(report,encoding='utf-8')
    digest=hashlib.sha256(obj.read_bytes()).hexdigest()
    print(json.dumps({'app':args.app,'bytes':obj.stat().st_size,'sha256':digest,'matchesPackagedObject':digest==before}))

if __name__=='__main__':main()
