"""Verify the actual APK packaging and vendor-class/native-library preservation."""
import argparse, hashlib, json, re, struct, zipfile
from collections import Counter
from pathlib import Path

ROOT=Path(__file__).resolve().parent
(ROOT/'evidence').mkdir(exist_ok=True)
SOURCE=ROOT.parent/'ControlGlasses-3.1.0.20251118115716-release.apk'
parser=argparse.ArgumentParser(description=__doc__)
parser.add_argument('--release',action='store_true')
args=parser.parse_args()
RESULT=ROOT/'build'/('XrealServer-v1.0.1-release.apk' if args.release else 'XrealMinimalServer-debug.apk')

def classes(data):
    n,off=struct.unpack_from('<II',data,56)
    strings=[]
    for i in range(n):
        p=struct.unpack_from('<I',data,off+4*i)[0]
        while data[p]&128:p+=1
        p+=1;strings.append(data[p:data.index(b'\0',p)].decode('utf8','replace'))
    n,off=struct.unpack_from('<II',data,64)
    types=[strings[struct.unpack_from('<I',data,off+i*4)[0]] for i in range(n)]
    n,off=struct.unpack_from('<II',data,96)
    return [types[struct.unpack_from('<I',data,off+i*32)[0]] for i in range(n)]

with zipfile.ZipFile(SOURCE) as original,zipfile.ZipFile(RESULT) as result:
    before=[];after=[]
    for name in original.namelist():
        if re.fullmatch(r'classes\d*\.dex',name):before.extend(classes(original.read(name)))
    for name in result.namelist():
        if re.fullmatch(r'classes\d*\.dex',name):after.extend(classes(result.read(name)))
    duplicates_before={c:n for c,n in Counter(before).items() if n>1}
    duplicates_after={c:n for c,n in Counter(after).items() if n>1}
    assert duplicates_after==duplicates_before, ('New duplicate classes',duplicates_before,duplicates_after)
    assert set(before).issubset(after),'Missing original runtime classes'
    assert 'Ldev/xreal/server/MainActivity;' in after
    replacement='Lcom/xreal/glassesdisplayplugevent/display/DisplayModel;'
    assert replacement not in classes(result.read('classes3.dex'))
    assert replacement in classes(result.read('classes23.dex'))
    libs=[x for x in original.namelist() if x.startswith('lib/') and x.endswith('.so')]
    for name in libs:
        assert hashlib.sha256(original.read(name)).digest()==hashlib.sha256(result.read(name)).digest(),name
    assert result.getinfo('resources.arsc').compress_type==zipfile.ZIP_STORED
    assert result.read('resources.arsc')==original.read('resources.arsc')
    assert set(x for x in result.namelist() if x.startswith('assets/'))=={'assets/data/nr_api.dat','assets/runtime-inventory.json'}
    assert Counter(after)['Lcom/xreal/glasses/servicesdk/AbsNRCallback;']==Counter(before)['Lcom/xreal/glasses/servicesdk/AbsNRCallback;']==1, 'Compile stub leaked into APK'
    inventory=json.loads(result.read('assets/runtime-inventory.json'))['libraries']
    assert set(inventory)==set(libs)
    for name in libs:assert inventory[name]['sha256']==hashlib.sha256(result.read(name)).hexdigest()
    summary=dict(status='passed',original_runtime_classes=len(before),output_classes=len(after),preexisting_duplicate_classes=duplicates_before,new_duplicate_classes=0,unchanged_native_libraries=len(libs),display_model_replaced_once=True,firmware_payloads_excluded=True,resources_stored_uncompressed=True)
(ROOT/'evidence/build-verification.json').write_text(json.dumps(summary,indent=2),encoding='utf8')
print(json.dumps(summary,indent=2))
