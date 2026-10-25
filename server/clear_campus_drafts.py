"""Back up and delete current onboarding drafts, preserving published campuses."""
import http.cookiejar,json,os,urllib.request
from pathlib import Path
from campus_onboarding import load_environment

ROOT=Path(__file__).resolve().parents[1]
OUT=ROOT/'artifacts/dashboard-update';OUT.mkdir(parents=True,exist_ok=True)
load_environment()
results=[]
for label,base,password in [
    ('cloud',os.environ['PUBLIC_CAMPUS_REGISTRY_URL'].rstrip('/'),os.environ['PRISM_ADMIN_PASSWORD']),
    ('laptop','http://127.0.0.1:8000',os.environ['PRISM_ADMIN_PASSWORD']),
    ('preview','http://127.0.0.1:8022','dashboard-ui-check-only')]:
    opener=urllib.request.build_opener(urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))
    def request(path,body=None):
        data=json.dumps(body).encode() if body is not None else None
        with opener.open(urllib.request.Request(base+path,data=data,headers={'Content-Type':'application/json'} if data else {}),timeout=25) as r:
            return json.load(r)
    request('/api/onboarding/login',{'username':'admin','password':password})
    state=request('/api/onboarding/state')
    campus_ids=[c['campus_id'] for c in state['campuses']]
    before={cid:request('/api/campus/'+cid+'/package') for cid in campus_ids}
    drafts=[request('/api/onboarding/'+d['id']) for d in state['drafts']]
    backup=OUT/(label+'-draft-backup.json')
    if drafts:
        if backup.exists():raise RuntimeError('Draft backup already exists; preserve it before another cleanup')
        backup.write_text(json.dumps(drafts,ensure_ascii=False,indent=2),encoding='utf-8')
    for draft in drafts:request('/api/onboarding/delete',{'id':draft['id']})
    assert request('/api/onboarding/state')['drafts']==[]
    for cid,pack in before.items():assert request('/api/campus/'+cid+'/package')==pack
    cid='IITG_77356889E768'
    slots=request('/api/campus/'+cid+'/timetables?semester=all&department=all&section=all&day=all')
    assert len(slots)==262,len(slots)
    assert len(before[cid]['buildings'])==34
    result={'server':label,'deleted_drafts':len(drafts),'published_campuses_preserved':campus_ids,
            'iitg_buildings':34,'iitg_timetable_entries':len(slots)}
    results.append(result);print(json.dumps(result))
(OUT/'verification.json').write_text(json.dumps(results,indent=2))
