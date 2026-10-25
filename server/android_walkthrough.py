"""Small adb UI driver for the explicitly connected demo phone, without reading private app storage."""
import argparse
import json
import re
import shlex
import subprocess
import xml.etree.ElementTree as ET
from pathlib import Path

ADB='C:/Users/chira/AppData/Local/Android/Sdk/platform-tools/adb.exe'
OUT=Path(__file__).resolve().parents[1]/'artifacts/user-walkthrough'
OUT.mkdir(parents=True,exist_ok=True)


def adb(*args):
    r=subprocess.run([ADB,*args],capture_output=True,timeout=35)
    if r.returncode:raise RuntimeError(r.stderr.decode(errors='replace')[:200])
    return r.stdout.decode(errors='replace')


def observe():
    adb('shell','uiautomator dump /sdcard/prism-ui.xml')
    xml=adb('shell','cat /sdcard/prism-ui.xml')
    root=ET.fromstring(xml)
    if not any(n.get('package')=='com.prismcampus.app' for n in root.iter('node')):
        raise RuntimeError('PrismCampus is not the visible app. Stop and wait for the user.')
    (OUT/'current-ui.xml').write_text(xml,encoding='utf-8')
    rows=[]
    for n in root.iter('node'):
        a=n.attrib
        if a.get('text') or a.get('content-desc') or 'EditText' in a.get('class',''):
            row={'text':a.get('text'),'bounds':a.get('bounds')}
            if a.get('content-desc'):row['description']=a['content-desc']
            if 'EditText' in a.get('class',''):row['input']=True;row['focused']=a.get('focused')=='true'
            if a.get('password')=='true':row['text']='<redacted>'
            rows.append(row)
    print(json.dumps(rows,ensure_ascii=True))


if __name__=='__main__':
    parser=argparse.ArgumentParser();parser.add_argument('action',choices=['observe','tap','type','replace','key','swipe','start','browser','screenshot'])
    parser.add_argument('values',nargs='*');args=parser.parse_args()
    if args.action=='observe':observe()
    elif args.action=='tap':
        if 'com.prismcampus.app' not in adb('shell','dumpsys window | grep mCurrentFocus'):
            raise RuntimeError('PrismCampus is not foreground. No input sent.')
        if len(args.values)==2:adb('shell','input tap '+' '.join(args.values))
        else:
            wanted=args.values[0]
            root=ET.fromstring((OUT/'current-ui.xml').read_text(encoding='utf-8'))
            candidates=[n for n in root.iter('node') if n.get('text')==wanted or n.get('content-desc')==wanted]
            if len(candidates)!=1:raise RuntimeError(f'Expected one matching visible UI element; found {len(candidates)}')
            x1,y1,x2,y2=map(int,re.findall(r'\d+',candidates[0].get('bounds')))
            adb('shell',f'input tap {(x1+x2)//2} {(y1+y2)//2}')
        observe()
    elif args.action=='type':
        adb('shell','input text '+shlex.quote(' '.join(args.values).replace(' ','%s')));observe()
    elif args.action=='replace':
        root=ET.fromstring((OUT/'current-ui.xml').read_text(encoding='utf-8'))
        focused=[n for n in root.iter('node') if n.get('focused')=='true' and 'EditText' in n.get('class','')]
        if len(focused)!=1:raise RuntimeError('Focus the intended text field first.')
        adb('shell','input keyevent 123')
        adb('shell','input keyevent '+' '.join(['67']*max(1,len(focused[0].get('text','')))))
        adb('shell','input text '+shlex.quote(' '.join(args.values).replace(' ','%s')));observe()
    elif args.action=='key':adb('shell','input keyevent '+shlex.quote(args.values[0]));observe()
    elif args.action=='swipe':adb('shell','input swipe '+' '.join(args.values));observe()
    elif args.action=='start':adb('shell','am start -n com.prismcampus.app/.MainActivity');observe()
    elif args.action=='browser':adb('shell','am start -a android.intent.action.VIEW -d '+shlex.quote(args.values[0]));observe()
    elif args.action=='screenshot':
        if 'com.prismcampus.app' not in adb('shell','dumpsys window | grep mCurrentFocus'):
            raise RuntimeError('PrismCampus is not foreground. No screenshot captured.')
        name=args.values[0] if args.values else 'phone'
        if not re.fullmatch('[A-Za-z0-9_-]+',name):raise ValueError('Use a simple screenshot name')
        adb('shell','screencap -p /sdcard/prism-ui.png');adb('pull','/sdcard/prism-ui.png',str(OUT/(name+'.png')))
        print(str(OUT/(name+'.png')))
