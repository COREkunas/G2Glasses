"""Build the marker module only; never opens a device."""
import hashlib, os, platform, subprocess
from pathlib import Path
HERE=Path(__file__).resolve().parent
sdk=Path(os.environ.get('ANDROID_HOME',Path.home()/'AppData/Local/Android/Sdk'))
ndk=Path(os.environ.get('ANDROID_NDK_HOME',sdk/'ndk/30.0.14904198'))
host='windows-x86_64' if os.name=='nt' else ('darwin-x86_64' if platform.system()=='Darwin' else 'linux-x86_64')
BIN=Path(os.environ.get('LLVM_BIN',ndk/'toolchains/llvm/prebuilt'/host/'bin'))
ext='.exe' if os.name=='nt' else ''
if __name__=='__main__':
    temp=HERE/'build-temp';temp.mkdir(exist_ok=True)
    subprocess.run([str(BIN/('clang'+ext)),'--target=arm-none-eabi','-mcpu=cortex-m55','-mthumb','-mfloat-abi=soft','-c',str(HERE/'probe.S'),'-o',str(HERE/'g2_probe.o')],check=True,env=dict(os.environ,TMP=str(temp),TEMP=str(temp)))
    print(hashlib.sha256((HERE/'g2_probe.o').read_bytes()).hexdigest())
