"""Public Android build and loopback tests; never installs or accesses G2."""
import argparse, os, subprocess, zipfile
from pathlib import Path
from generate_fixtures import generate

ROOT=Path(__file__).resolve().parent
BUILD=ROOT/'build'
SDK=Path(os.environ.get('ANDROID_HOME',Path.home()/'AppData/Local/Android/Sdk'))
JDK=Path(os.environ.get('JAVA_HOME','C:/Program Files/Android/Android Studio/jbr'))
BT=SDK/'build-tools'/os.environ.get('ANDROID_BUILD_TOOLS','37.0.0')
ANDROID=SDK/'platforms/android-36/android.jar'
EXT='.exe' if os.name=='nt' else ''
SRC=ROOT/'src/local/halliday/g2lab'
TEST=ROOT/'tests'

def run(*args):subprocess.run([str(a) for a in args],cwd=ROOT,check=True)
def javac(*args):run(JDK/('bin/javac'+EXT),'--release','8','-encoding','UTF-8',*args)
def java(*args):run(JDK/('bin/java'+EXT),*args)

def tests():
    generate()
    out=BUILD/'tests';out.mkdir(parents=True,exist_ok=True)
    names=['Protocol','ProbeUpload','ConsoleCheck','ReadOnlyFtp','BackupFiles','RamDiagnosticRecord','RamFtpRead','NativeAppRecord','NativeAppFtp','NavGuidance','NavRelayState','NativeNavRecord','DisposableFtpTest','UsbFlagTest']
    suites=['ProtocolTest','ProbeUploadTest','ConsoleCheckTest','ReadOnlyFtpTest','RamDiagnosticRecordTest','NativeAppTest','NavigationTest','NativeNavigationTest','DisposableFtpTestTest','PublicBuildTest']
    javac('-d',out,*[SRC/(n+'.java') for n in names],*[TEST/(n+'.java') for n in suites])
    for name in suites:
        if name=='NativeAppTest':
            for variant in ('mobile','persistent'):java('-cp',out,'local.halliday.g2lab.'+name,TEST/'fixtures'/variant)
            continue
        args={'ProbeUploadTest':[ROOT.parent/'g2_native_probe/g2_probe.o'],
              'ReadOnlyFtpTest':[BUILD], 'DisposableFtpTestTest':[BUILD],
              'NativeNavigationTest':[TEST/'fixtures/navigation']}.get(name,[])
        java('-cp',out,'local.halliday.g2lab.'+name,*args)
    transport=BUILD/'nav-tests';transport.mkdir(exist_ok=True)
    javac('-d',transport,*sorted((TEST/'nav_android_stubs').rglob('*.java')),
          *[SRC/(n+'.java') for n in ('Protocol','NavGuidance','NavRelayState','NavSession','GattCallbackRouter','NavBleClient')],TEST/'NavTransportTest.java')
    java('-cp',transport,'local.halliday.g2lab.NavTransportTest')
    # JSON/authentication tests need Android's runtime; compile, do not pretend
    # the Android SDK stub jar executes org.json on this host.
    javac('-cp',ANDROID,'-d',BUILD/'auth-tests',SRC/'Protocol.java',SRC/'AccountClient.java',SRC/'DiagnosticReply.java',TEST/'AccountClientTest.java',TEST/'DiagnosticReplyTest.java')
    print('Host suites passed; Android JSON/auth tests compiled (not executed).')

def main():
    p=argparse.ArgumentParser();p.add_argument('--tests-only',action='store_true');args=p.parse_args()
    BUILD.mkdir(exist_ok=True);tests()
    if args.tests_only:return
    classes=BUILD/'classes';classes.mkdir(exist_ok=True)
    # Remove old compiled classes only inside this build's fixed directory.
    for old in classes.rglob('*.class'):old.unlink()
    javac('-cp',ANDROID,'-d',classes,*sorted((ROOT/'src').rglob('*.java')))
    jar=BUILD/'app-classes.jar'
    with zipfile.ZipFile(jar,'w',zipfile.ZIP_DEFLATED) as z:
        for f in sorted(classes.rglob('*.class')):z.write(f,f.relative_to(classes).as_posix())
    dex=BUILD/'dex';dex.mkdir(exist_ok=True)
    java('-cp',BT/'lib/d8.jar','com.android.tools.r8.D8','--lib',ANDROID,'--min-api','33','--output',dex,jar)
    unsigned=BUILD/'unsigned.apk'
    run(BT/('aapt2'+EXT),'link','-I',ANDROID,'--manifest',ROOT/'AndroidManifest.xml','-o',unsigned)
    with zipfile.ZipFile(unsigned,'a',zipfile.ZIP_DEFLATED) as z:
        z.write(dex/'classes.dex','classes.dex');z.write(ROOT.parent/'g2_native_probe/g2_probe.o','assets/g2_probe.o')
    aligned=BUILD/'aligned.apk';run(BT/('zipalign'+EXT),'-f','4',unsigned,aligned)
    key=BUILD/'public-lab-debug.jks'
    if not key.exists():
        run(JDK/('bin/keytool'+EXT),'-genkeypair','-keystore',key,'-storepass','android','-keypass','android','-alias','g2lab','-keyalg','RSA','-keysize','2048','-validity','3650','-dname','CN=G2 Display Lab public research build')
    apk=ROOT/'G2-Display-Lab.apk'
    java('-jar',BT/'lib/apksigner.jar','sign','--ks',key,'--ks-key-alias','g2lab','--ks-pass','pass:android','--key-pass','pass:android','--out',apk,aligned)
    java('-jar',BT/'lib/apksigner.jar','verify','--verbose',apk)
    print('Built public APK. Local signing key remains excluded from publication.')
if __name__=='__main__':main()
