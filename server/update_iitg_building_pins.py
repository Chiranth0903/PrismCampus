"""Publish sourced academic pins without changing the campus ID or timetable."""
import copy, http.cookiejar, json, os, urllib.request
from pathlib import Path
from campus_onboarding import load_environment, validate

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT/'artifacts/iitg-pins'
CID = 'IITG_77356889E768'
SOURCE = 'https://spinlabiitg.github.io/iitg_umap/'
IDS = {'core-1':'CORE1','core-2':'CORE2','core-3':'CORE3','core-4':'CORE4',
       'dept-bsbe':'DEPT_BSBE','dept-che':'DEPT_CL','dept-chem':'DEPT_CH',
       'dept-ce':'DEPT_CE','dept-cse':'DEPT_CSE','dept-design':'DEPT_DESIGN',
       'dept-eee':'DEPT_EEE','dept-hss':'DEPT_HSS','dept-math':'DEPT_MATH',
       'dept-me':'DEPT_ME','dept-phy':'DEPT_PH','school-sese':'SCHOOL_ENERGY'}

def client(base):
    opener=urllib.request.build_opener(urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))
    def request(path, body=None):
        data=json.dumps(body).encode() if body is not None else None
        with opener.open(urllib.request.Request(base+path,data=data,
                headers={'Content-Type':'application/json'} if data else {}),timeout=25) as r:
            return json.load(r)
    request('/api/onboarding/login',{'username':'admin','password':os.environ['PRISM_ADMIN_PASSWORD']})
    return request

def main():
    load_environment();OUT.mkdir(parents=True,exist_ok=True)
    places=json.loads((OUT/'places.json').read_text(encoding='utf-8'))
    pins=[]
    for p in places['places']:
        if p['id'] not in IDS:continue
        lon,lat=p['lnglat'];assert 26.18<lat<26.20 and 91.685<lon<91.70
        pins.append(dict(id=IDS[p['id']],code=IDS[p['id']],name=p['name'],latitude=lat,longitude=lon,
            total_floors=0,rooms=[],source_url=SOURCE,source_place_id=p['id'],
            coordinate_source_url=SOURCE+'data/places.json',source_status=p.get('status',''),
            coordinate_accuracy='approximate community-mapped point; verify on site',
            description='Approximate academic-building/department pin from the IITG SPIN Lab community map. '
                        'Not a verified entrance or lab allocation. Floor information unverified.'))
    assert len(pins)==16
    (ROOT/'server/iitg_academic_pins.json').write_text(json.dumps({'source_url':SOURCE,
        'source_revision':places.get('revision'),'attribution':'SPIN Lab IIT Guwahati; map data © OpenStreetMap contributors (ODbL).',
        'buildings':pins},ensure_ascii=False,indent=2),encoding='utf-8')
    remote=client(os.environ['PUBLIC_CAMPUS_REGISTRY_URL'].rstrip('/'))
    before=remote('/api/campus/'+CID+'/package')
    draft=remote('/api/onboarding/cd3d0a66dca00461e3bcf7bd');assert draft['campus_id']==CID
    document=copy.deepcopy(before);codes={b['code'] for b in document['buildings']}
    for pin in pins:
        if pin['code'] not in codes:document['buildings'].append(pin)
    assert validate(document)['ready']
    backup=OUT/('before-'+before['pack_version']+'.json')
    if not backup.exists():backup.write_text(json.dumps(before,ensure_ascii=False,indent=2),encoding='utf-8')
    remote('/api/onboarding/save',{'id':draft['id'],'document':document,'revision':draft['revision']})
    remote('/api/onboarding/publish',{'id':draft['id'],'reviewed':True,'acknowledge_warnings':True})
    after=remote('/api/campus/'+CID+'/package')
    assert after['sections']==before['sections'] and after['campus_id']==CID
    actual={b['code']:b for b in after['buildings']}
    for p in pins:assert (actual[p['code']]['latitude'],actual[p['code']]['longitude'])==(p['latitude'],p['longitude'])
    asset=ROOT/'app/src/main/assets/iitg_demo_pack.json';bundled=json.loads(asset.read_text(encoding='utf-8'))
    existing={b['code'] for b in bundled['buildings']}
    bundled['buildings'].extend(copy.deepcopy(p) for p in pins if p['code'] not in existing)
    asset.write_text(json.dumps(bundled,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    result={'campus_id':CID,'pack_version':after['pack_version'],'pins_added':len(after['buildings'])-len(before['buildings']),
            'total_buildings':len(after['buildings']),'timetable_unchanged':True,'source_url':SOURCE}
    try:
        local=client('http://127.0.0.1:8000');local('/api/onboarding/mirror',{'campus_id':CID})
        mirrored=local('/api/campus/'+CID+'/package');assert mirrored['pack_version']==after['pack_version']
        result['local_mirror']='updated'
    except Exception as e:result['local_mirror']='unavailable: '+type(e).__name__
    (OUT/'update-result.json').write_text(json.dumps(result,indent=2));print(json.dumps(result))

if __name__=='__main__':main()
