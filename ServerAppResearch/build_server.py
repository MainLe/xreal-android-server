"""Build a new server UI + reviewed display fix using the supplied APK as a local binary dependency.

No original signing key, package replacement, firmware writes, or global tool installation.
The proprietary DEX/native runtime is reused; this is not a source rebuild of XREAL's C++ SDK.
"""
import argparse, hashlib, json, os, re, subprocess, sys, zipfile, tempfile
from pathlib import Path

parser=argparse.ArgumentParser(description=__doc__)
parser.add_argument('--release',action='store_true',help='Build optimized DEX with a non-debuggable manifest; retain the existing signing identity.')
RELEASE=parser.parse_args().release

ROOT=Path(__file__).resolve().parent
APK=ROOT.parent/'ControlGlasses-3.1.0.20251118115716-release.apk'
SDK=Path(os.environ.get('XREAL_ANDROID_SDK',r'D:\FilesLibrary\SDK'))
JDK=Path(os.environ.get('XREAL_JDK',r'C:\Program Files\Java\jdk-17'))
BT=SDK/'build-tools'/'35.0.0'
ANDROID=SDK/'platforms'/'android-36'/'android.jar'
JADX=Path(os.environ.get('XREAL_JADX_JAR',str(ROOT/'tools/jadx/lib/jadx-1.5.6-all.jar')))
OUT=ROOT/'build'; OUT.mkdir(exist_ok=True)
CLASSES=Path(tempfile.mkdtemp(prefix='classes-',dir=OUT))
DEX=OUT/'dex';DEX.mkdir(exist_ok=True)
JAVA=str(JDK/'bin/java.exe');JAVAC=str(JDK/'bin/javac.exe')

def run(*args):
    args=[str(a) for a in args]
    if Path(args[0]).name.lower()=='java.exe':
        args[1:1]=['-Xmx256m','-XX:+UseSerialGC']
    elif Path(args[0]).name.lower()=='javac.exe':
        args[1:1]=['-J-Xmx192m','-J-XX:+UseSerialGC']
    print('RUN',Path(args[0]).name,' '.join(args[1:4]),flush=True)
    subprocess.run(args,check=True,cwd=ROOT)

for p in (APK,ANDROID,JADX,BT/'aapt2.exe'):
    if not p.exists():raise FileNotFoundError(p)
source_hash=hashlib.sha256(APK.read_bytes()).hexdigest()
if source_hash!='49c9484ebd34e495984b219bb73988a366c03339f7251a9fe9a4702f58f5c76e':
    raise RuntimeError('Input APK differs from reviewed build')

sources=list((ROOT/'server-app/src').rglob('*.java'))
STUBS=Path(tempfile.mkdtemp(prefix='stubs-',dir=OUT))
run(JAVAC,'-encoding','UTF-8','--release','8','-d',STUBS,*list((ROOT/'server-app/stubs').rglob('*.java')))
run(JAVAC,'-encoding','UTF-8','--release','8','-classpath',str(ANDROID)+os.pathsep+str(STUBS),'-d',CLASSES,*sources)
with zipfile.ZipFile(OUT/'new-classes.jar','w') as z:
    for p in CLASSES.rglob('*.class'): z.write(p,p.relative_to(CLASSES).as_posix())
with zipfile.ZipFile(OUT/'compile-stubs.jar','w') as z:
    for p in STUBS.rglob('*.class'): z.write(p,p.relative_to(STUBS).as_posix())
run(JAVA,'-cp',BT/'lib/d8.jar','com.android.tools.r8.D8','--release' if RELEASE else '--debug','--min-api','28','--lib',ANDROID,'--classpath',OUT/'compile-stubs.jar','--output',DEX,OUT/'new-classes.jar')
run(JAVAC,'-encoding','UTF-8','-cp',JADX,'-d',OUT,ROOT/'tools/DexReplace.java')
with zipfile.ZipFile(APK) as z:(OUT/'original-classes3.dex').write_bytes(z.read('classes3.dex'))
run(JAVA,'-cp',str(OUT)+os.pathsep+str(JADX),'DexReplace',OUT/'original-classes3.dex',OUT/'patched-classes3.dex',DEX/'classes.dex','Lcom/xreal/glassesdisplayplugevent/display/DisplayModel;')
manifest=ROOT/'server-app/AndroidManifest.xml'
if RELEASE:
    import xml.etree.ElementTree as ET
    ET.register_namespace('android','http://schemas.android.com/apk/res/android')
    release_manifest=ET.parse(manifest)
    release_manifest.getroot().find('application').set('{http://schemas.android.com/apk/res/android}debuggable','false')
    manifest=OUT/'release-manifest/AndroidManifest.xml'
    manifest.parent.mkdir(exist_ok=True)
    release_manifest.write(manifest,encoding='utf-8',xml_declaration=True)
run(BT/'aapt2.exe','link','-I',ANDROID,'--manifest',manifest,'-o',OUT/'manifest.apk')
with zipfile.ZipFile(OUT/'unsigned.apk','w',compression=zipfile.ZIP_DEFLATED,compresslevel=6) as dst:
    with zipfile.ZipFile(OUT/'manifest.apk') as base:dst.writestr('AndroidManifest.xml',base.read('AndroidManifest.xml'))
    with zipfile.ZipFile(APK) as src:
        for item in src.infolist():
            n=item.filename
            if re.fullmatch(r'classes\d*\.dex',n) or n.startswith(('lib/','res/')) or n in ('resources.arsc','assets/data/nr_api.dat'):
                dst.writestr(n,(OUT/'patched-classes3.dex').read_bytes() if n=='classes3.dex' else src.read(n),compress_type=zipfile.ZIP_STORED if n=='resources.arsc' else zipfile.ZIP_DEFLATED)
    dst.writestr('classes23.dex',(DEX/'classes.dex').read_bytes())
    with zipfile.ZipFile(APK) as src:
        inventory={i.filename:{'sha256':hashlib.sha256(src.read(i)).hexdigest(),'bytes':i.file_size} for i in src.infolist() if i.filename.startswith('lib/') and i.filename.endswith('.so')}
    dst.writestr('assets/runtime-inventory.json',json.dumps({'runtime':'3.1.0.20251118115716','libraries':inventory}))
run(BT/'zipalign.exe','-P','16','-f','4',OUT/'unsigned.apk',OUT/'aligned.apk')
KEY=OUT/'development.p12'
if not KEY.exists():
    run(JDK/'bin/keytool.exe','-genkeypair','-keystore',KEY,'-storetype','PKCS12','-storepass','android','-keypass','android','-alias','development','-keyalg','RSA','-keysize','2048','-validity','3650','-dname','CN=Local XREAL Server Development')
FINAL=OUT/('XrealServer-v1.0.1-release.apk' if RELEASE else 'XrealMinimalServer-debug.apk')
run(JAVA,'-jar',BT/'lib/apksigner.jar','sign','--ks',KEY,'--ks-pass','pass:android','--out',FINAL,OUT/'aligned.apk')
run(JAVA,'-jar',BT/'lib/apksigner.jar','verify','--verbose',FINAL)
run(BT/'zipalign.exe','-c','-P','16','4',FINAL)
import shutil
DELIVERY=ROOT.parent/'Build/Android'/('XrealServer-v1.0.1-release.apk' if RELEASE else 'XrealServer-v1.0.1.apk')
DELIVERY.parent.mkdir(parents=True,exist_ok=True)
shutil.copy2(FINAL,DELIVERY)
metadata={'input_apk_sha256':source_hash,'output':str(FINAL),'output_sha256':hashlib.sha256(FINAL.read_bytes()).hexdigest(),'bytes':FINAL.stat().st_size,'compile_sdk':36,'target_sdk':33,'vendor_runtime':'3.1.0.20251118115716','reused_native_libraries':True,'firmware_assets_included':False,'build_type':'release' if RELEASE else 'debug','application_debuggable':not RELEASE,'signing':'existing local development certificate'}
(OUT/'build-info.json').write_text(json.dumps(metadata,indent=2),encoding='utf-8')
print(json.dumps(metadata,indent=2))
