"""Start the requested laptop LAN server quietly, retaining its log and PID."""
import json
import os
import socket
import subprocess
import sys
from pathlib import Path

root=Path(__file__).resolve().parents[1]
out=root/'artifacts';out.mkdir(exist_ok=True)
probe=socket.socket();occupied=probe.connect_ex(('127.0.0.1',8000))==0;probe.close()
if occupied:
    print('Port 8000 is already in use; existing service was not changed.')
    sys.exit(0)
env=dict(os.environ,PORT='8000',HOST='0.0.0.0')
with (out/'laptop-server.log').open('ab') as log:
    process=subprocess.Popen([sys.executable,'-u',str(root/'server/uno_q_server.py')],cwd=root,
        env=env,stdin=subprocess.DEVNULL,stdout=log,stderr=log,
        creationflags=getattr(subprocess,'CREATE_NO_WINDOW',0))
(out/'laptop-service.json').write_text(json.dumps({'pid':process.pid,'port':8000}))
print(json.dumps({'pid':process.pid,'port':8000,'status':'starting'}))
