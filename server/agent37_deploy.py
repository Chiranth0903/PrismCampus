"""Deploy the small PrismCampus backend to the configured existing Agent37 instance.

Never prints credentials, uploads phone models, changes instance size, or replaces a database.
"""
import argparse
import json
import os
import shlex
import time
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path
from campus_onboarding import load_environment

ROOT = Path(__file__).resolve().parents[1]
REMOTE = '/home/node/prism'


def call(url, method='GET', value=None, raw=None, control=False):
    key = os.environ['AGENT37_API_KEY']
    headers = {'Authorization':'Bearer '+key} if control else {'X-Agent37-Key':key}
    if value is not None: raw=json.dumps(value).encode();headers['Content-Type']='application/json'
    elif raw is not None: headers['Content-Type']='application/octet-stream'
    try:
        with urllib.request.urlopen(urllib.request.Request(url,data=raw,method=method,headers=headers),timeout=45) as r:
            return json.load(r)
    except urllib.error.HTTPError as e:
        raise RuntimeError(f'Agent37 request failed with HTTP {e.code} at {urllib.parse.urlsplit(url).path}') from None


def exec_remote(command):
    instance=os.environ['AGENT37_INSTANCE_ID']
    result=call('https://sandbox.agent37.app/v1/instances/'+instance+'/exec','POST',{'command':command})
    if result.get('exit_code') != 0:
        raise RuntimeError('Remote operation failed: '+result.get('stderr','')[:400])
    return result.get('stdout','')


def upload(remote, data):
    instance=os.environ['AGENT37_INSTANCE_ID']
    return call('https://'+instance+'.agent37.app/v1/files/content?'+urllib.parse.urlencode({'path':remote}),
                'PUT',raw=data)


def deployment_env(url):
    values={k:os.environ[k] for k in ('PRISM_ADMIN_PASSWORD','AGENT37_API_KEY','AGENT37_INSTANCE_ID')}
    values.update(PUBLIC_CAMPUS_REGISTRY_URL=url,PRISM_DB_PATH=REMOTE+'/data/prism_campus.db',HOST='0.0.0.0',PORT='8000')
    for k in ('NODUS_API_KEY','NODUS_MODEL'):
        if os.environ.get(k):values[k]=os.environ[k]
    if any('\n' in v or '\r' in v for v in values.values()):raise ValueError('Configuration values must be single lines.')
    return ('\n'.join(k+'='+v for k,v in values.items())+'\n').encode()


def deploy():
    load_environment()
    if len(os.environ.get('PRISM_ADMIN_PASSWORD','')) < 12:
        raise ValueError('Set a dashboard password of at least 12 characters before public hosting.')
    instance=os.environ['AGENT37_INSTANCE_ID']
    base='https://api.agent37.com/v1/instances/'+instance
    info=call(base,control=True)
    if info.get('status') != 'running':raise ValueError('Start the existing instance before deployment.')
    release=REMOTE+'/releases/'+time.strftime('%Y%m%d-%H%M%S')
    files=['server/uno_q_server.py','server/campus_onboarding.py','server/web/onboarding.html',
           'server/web/onboarding.css','server/web/onboarding.js','server/web/qrcode.js','app/src/main/assets/msrit_pack.json',
           'app/src/main/assets/iitg_demo_pack.json','config/assistant_rules.md']
    for name in files:
        upload(release+'/'+name,(ROOT/name).read_bytes())
    ports=info.get('public_ports',[])
    url=next((p['url'] for p in ports if p['port']==8000),'')
    upload(release+'/server/.env.local',deployment_env(url))
    # New script, scoped process ownership, persistent DB, and hook append only.
    launcher='''import json,os,signal,subprocess,pathlib,sys
root=pathlib.Path('/home/node/prism');root.mkdir(exist_ok=True)
(root/'data').mkdir(exist_ok=True)
release=pathlib.Path(sys.argv[1])
os.chmod(release/'server/.env.local',0o600)
pidfile=root/'server.pid'
if pidfile.exists():
 try:
  pid=int(pidfile.read_text());cmd=pathlib.Path('/proc')/str(pid)/'cmdline'
  if cmd.exists() and '/home/node/prism/' in cmd.read_bytes().decode(errors='ignore') and 'uno_q_server.py' in cmd.read_bytes().decode(errors='ignore'):
   os.kill(pid,signal.SIGTERM)
 except (ValueError,ProcessLookupError):pass
log=open(root/'server.log','ab')
p=subprocess.Popen(['python3','-u',str(release/'server/uno_q_server.py')],cwd=release,stdin=subprocess.DEVNULL,stdout=log,stderr=log,start_new_session=True)
pidfile.write_text(str(p.pid));(root/'current-release').write_text(str(release))
print(json.dumps({'pid':p.pid,'release':str(release)}))
'''
    upload(REMOTE+'/launch.py',launcher.encode())
    print(exec_remote('python3 '+REMOTE+'/launch.py '+shlex.quote(release)).strip())
    check="import urllib.request,json; print(json.load(urllib.request.urlopen('http://127.0.0.1:8000/api/health',timeout=5))['status'])"
    for attempt in range(5):
        try:
            if 'healthy' in exec_remote('python3 -c '+shlex.quote(check)):break
        except RuntimeError:
            if attempt==4:raise
            time.sleep(1)
    if not url:
        port=call(base+'/public-ports','POST',{'port':8000,'prefix':'prismcampus','label':'PrismCampus registry and signed-in admin'},control=True)
        url=port['url']
        upload(release+'/server/.env.local',deployment_env(url))
        exec_remote('python3 '+REMOTE+'/launch.py '+shlex.quote(release))
    # Preserve existing provider boot-hook contents and add our own idempotent block.
    hook="""from pathlib import Path
p=Path('/home/node/.agent37/hooks/post-restart.sh')
old=p.read_text() if p.exists() else '#!/bin/sh\\n'
marker='# PrismCampus backend startup'
if marker not in old:
 p.parent.mkdir(parents=True,exist_ok=True)
 p.write_text(old+'\\n'+marker+'\\nif [ -f /home/node/prism/current-release ]; then python3 /home/node/prism/launch.py "$(cat /home/node/prism/current-release)"; fi\\n')
 p.chmod(0o700)
"""
    exec_remote('python3 -c '+shlex.quote(hook))
    envfile=ROOT/'server/.env.local'
    lines=envfile.read_text(encoding='utf-8-sig').splitlines()
    lines=[line for line in lines if not line.startswith('PUBLIC_CAMPUS_REGISTRY_URL=')]
    envfile.write_text('\n'.join(lines)+'\nPUBLIC_CAMPUS_REGISTRY_URL='+url+'\n',encoding='utf-8')
    (ROOT/'artifacts').mkdir(exist_ok=True)
    (ROOT/'artifacts/agent37-deployment.json').write_text(json.dumps({'instance_id':instance,'registry_url':url,'release':release},indent=2))
    print(json.dumps({'registry_url':url,'status':'deployed','credentials_printed':False}))


if __name__=='__main__':
    parser=argparse.ArgumentParser();parser.add_argument('action',choices=['deploy'])
    parser.parse_args();deploy()
