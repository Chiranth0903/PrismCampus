"""Refresh the published demo's legacy admin tables and record final health."""
import http.cookiejar
import json
import os
import urllib.request
from pathlib import Path
from campus_onboarding import load_environment

load_environment()
root = Path(__file__).resolve().parents[1]
base = os.environ['PUBLIC_CAMPUS_REGISTRY_URL'].rstrip('/')
client = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))

def request(path, body=None):
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(base + path, data=data,
        headers={'Content-Type': 'application/json'} if data else {})
    with client.open(req, timeout=30) as response:
        return json.load(response)

request('/api/onboarding/login', {'username': 'admin', 'password': os.environ['PRISM_ADMIN_PASSWORD']})
cid = 'IITG_77356889E768'
request('/api/onboarding/publish', {'id': 'cd3d0a66dca00461e3bcf7bd',
    'acknowledge_warnings': True, 'reviewed': True})
pack = request('/api/campus/' + cid + '/package')
slots = request('/api/campus/' + cid + '/timetables')
assert pack['campus_id'] == cid and len(pack['sections']) == 14
assert len(slots) == 262, len(slots)
assert sum(c['campus_id'] == cid for c in request('/api/campuses')) == 1
result = {'registry_url': base, 'campus_id': cid, 'health': request('/api/health')['status'],
    'master_timetable_slots': len(slots), 'sections': len(pack['sections']),
    'phone_checks_stopped_at_user_request': True}
(root/'artifacts/final-live-handoff.json').write_text(json.dumps(result, indent=2))
print(json.dumps(result))
