"""Exercise the deployed admin/student HTTP flows using a separate labelled IITG demo."""
import http.cookiejar
import json
import os
import time
import urllib.request
from pathlib import Path
from campus_onboarding import load_environment

ROOT=Path(__file__).resolve().parents[1]
load_environment()
BASE=os.environ['PUBLIC_CAMPUS_REGISTRY_URL'].rstrip('/')
client=urllib.request.build_opener(urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))
checks=[]


def request(path,body=None,method=None):
    data=json.dumps(body).encode() if body is not None else None
    with client.open(urllib.request.Request(BASE+path,data=data,method=method,
            headers={'Content-Type':'application/json'} if data else {}),timeout=25) as r:
        return json.load(r)


def check(label,ok):
    checks.append({'check':label,'passed':bool(ok)})
    if not ok:raise AssertionError(label)


request('/api/onboarding/login',{'username':'admin','password':os.environ['PRISM_ADMIN_PASSWORD']})
check('Administrator sign-in',True)
state=request('/api/onboarding/state')
existing=next((d for d in state['drafts'] if d.get('campus_id','') and d['campus_id'].startswith('IITG_')),None)
if existing:
    draft=request('/api/onboarding/'+existing['id'])
else:
    draft=request('/api/onboarding/scan',{'website':'https://www.iitg.ac.in/','provider':'local'})
    deadline=time.monotonic()+100
    while draft['state']=='scanning':
        if time.monotonic()>deadline:raise RuntimeError('Website scan is still running. Re-run after it completes.')
        time.sleep(2);draft=request('/api/onboarding/'+draft['id'])
    check('Official website scan returns reviewable draft',draft['state']=='needs_review')
    doc=json.loads((ROOT/'app/src/main/assets/iitg_demo_pack.json').read_text(encoding='utf-8'))
    imported=request('/api/onboarding/import',{'id':draft['id'],'format':'json','content':json.dumps(doc)})
    check('JSON import preview',imported['preview_only'] and imported['validation']['ready'])
    document=imported['document']
    document.update(campus_name='IIT Guwahati — First-year demo',short_name='IITG',city='Guwahati')
    document['description']='Demo backup: official first-year course data, provisional theory slots and approximate OSM pins. Confirm locations before real travel.'
    document['sources']=draft['document'].get('sources',[])
    draft=request('/api/onboarding/save',{'id':draft['id'],'document':document,'revision':draft['revision']})
    check('Save reviewed demo data',draft['validation']['ready'])
    published=request('/api/onboarding/publish',{'id':draft['id'],'acknowledge_warnings':True,'reviewed':True})
    draft=request('/api/onboarding/'+draft['id'])
cid=draft['campus_id']
pack=request('/api/campus/'+cid+'/package')
check('Published ID returns correct campus',pack['campus_id']==cid and len(pack['sections'])==14)
check('Separate campus preserves MSRIT',request('/api/campus/MSRIT/package')['campus_id']=='MSRIT')
check('Campus list includes published campus once',sum(x['campus_id']==cid for x in request('/api/campuses'))==1)
connections=request('/api/onboarding/connections',{})
check('Agent37 credentials verified from deployed server',connections['agent37'].get('verified'))
message_id='iitg-demo-welcome'
request('/api/admin/announcement',{'campus_id':cid,'id':message_id,'title':'Welcome to the IITG demo campus',
    'content':'Demo package is ready. Timetable theory slots and map pins are provisional. Verify before travelling.',
    'priority':'Important','audience':'All Students'})
feed=request('/api/campus/'+cid+'/announcements')
check('Announcement reaches selected campus feed',any(m['id']==message_id for m in feed['messages']))
check('Announcement does not leak to MSRIT',all(m['id']!=message_id for m in request('/api/campus/MSRIT/announcements')['messages']))
override_id='iitg-demo-room-check'
request('/api/admin/override',{'id':override_id,'campus_id':cid,'targetCourseCode':'CS1113','targetSection':'FY_CSE',
    'newBuildingCode':'CORE5','newBuildingName':'Core 5','newRoomId':'5404','newFloor':0,
    'newFloorLabel':'Floor unverified','reason':'DEMO: classroom change verification','effective_time':'Immediate'})
check('Classroom override reaches student feed',any(o['id']==override_id for o in request('/api/campus/'+cid+'/announcements')['announcements']))
request('/api/admin/override?id='+override_id,method='DELETE')
check('Revoked override leaves student feed',all(o['id']!=override_id for o in request('/api/campus/'+cid+'/announcements')['announcements']))
payload={'campus_id':cid,'device_id':'demo-workflow-installation-one','reports':[
    {'id':'demo-fan-1','category':'Electrical','building':'Core 5','room':'5405','description':'DEMO verification: classroom fan not working','urgency':'Low'}]}
request('/api/reports/sync',payload);request('/api/reports/sync',payload)
reports=request('/api/reports/sync?campus='+cid)['reports']
group=next(r for r in reports if r['description']=='DEMO verification: classroom fan not working')
check('Repeated submission does not inflate supporter count',group['report_count']==1)
payload['device_id']='demo-workflow-installation-two';payload['reports'][0]['id']='demo-fan-2'
request('/api/reports/sync',payload)
group=next(r for r in request('/api/reports/sync?campus='+cid)['reports'] if r['id']==group['id'])
check('A distinct installation adds one supporter',group['report_count']==2)
request('/api/admin/reports/status',{'id':group['id'],'status':'Resolved','resolution_note':'Demo verification record, not a real maintenance complaint.'})
check('Report resolution persists',any(r['id']==group['id'] and r['status']=='Resolved' for r in request('/api/reports/sync?campus='+cid)['reports']))
result={'registry_url':BASE,'campus_id':cid,'draft_id':draft['id'],'checks':checks,
    'counts':draft['validation']['counts'],'caveat':'IITG first-year demo with provisional weekly theory slots and approximate map pins.'}
(ROOT/'artifacts/live-workflow-results.json').write_text(json.dumps(result,indent=2))
print(json.dumps(result))
