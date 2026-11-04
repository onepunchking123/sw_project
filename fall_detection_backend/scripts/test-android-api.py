#!/usr/bin/env python3
"""Exercise Android's repository against a disposable real PostgreSQL + Spring server."""
import getpass
import os
from pathlib import Path
import socket
import subprocess
import tempfile
import time
import urllib.request

backend = Path(__file__).resolve().parents[1]
android = backend.parent / 'CareGuardCamera'
pgbin = Path(os.environ.get('POSTGRES_BIN', '/opt/homebrew/opt/postgresql@16/bin'))
root = Path(tempfile.mkdtemp(prefix='careguard-android-api-'))
def free_port():
    with socket.socket() as s:
        s.bind(('127.0.0.1', 0)); return s.getsockname()[1]
def run(*cmd, **kwargs): return subprocess.run(list(map(str,cmd)), check=True, **kwargs)
def pg(cmd,*args):return run(pgbin / cmd,*args)
pg_port, api_port = free_port(), free_port()
server = None
started = False
log = None
try:
    pg('initdb','-D',root/'data','-A','trust','--no-locale','-E','UTF8')
    pg('pg_ctl','-D',root/'data','-l',root/'postgres.log','-o',f'-h 127.0.0.1 -k {root} -p {pg_port}','-w','start')
    started=True
    pg('psql','-h','127.0.0.1','-p',pg_port,'-d','postgres','-v','ON_ERROR_STOP=1','-f',backend.parent/'docs/backend/schema.sql')
    run(backend/'gradlew','bootJar','--offline',cwd=backend)
    env=dict(
        os.environ,
        DB_URL=f'jdbc:postgresql://127.0.0.1:{pg_port}/postgres',
        DB_USER=getpass.getuser(),
        DB_PASSWORD='',
        SERVER_PORT=str(api_port),
        CAREGUARD_LIVE_ENABLED=os.environ.get('CAREGUARD_LIVE_ENABLED', 'true'),
    )
    log=open(root/'backend.log','w')
    jar=next(p for p in (backend/'build/libs').glob('*.jar') if not p.name.endswith('-plain.jar'))
    server=subprocess.Popen(['java','-jar',str(jar)],cwd=backend,env=env,stdout=log,stderr=subprocess.STDOUT)
    for attempt in range(90):
        if server.poll() is not None: raise RuntimeError(f'Backend stopped. See {root}/backend.log')
        try:
            urllib.request.urlopen(f'http://127.0.0.1:{api_port}/api/patients',timeout=1).close()
            break
        except OSError: time.sleep(1)
    else: raise RuntimeError('Backend startup timed out')
    run(android/'gradlew',':app:testDebugUnitTest',':app:lintDebug','--offline','--no-parallel',cwd=android)
    test_classes = (
        'com.example.careguardcamera.data.CameraCredentialStoreTest',
        'com.example.careguardcamera.BackendCrudIntegrationTest',
        'com.example.careguardcamera.CameraPairingIntegrationTest',
        'com.example.careguardcamera.CameraPairingUiSmokeTest',
    )
    requested_test_class = os.environ.get('CAREGUARD_TEST_CLASS')
    if requested_test_class:
        test_classes = tuple(name.strip() for name in requested_test_class.split(',') if name.strip())
    live_test_class = os.environ.get('CAREGUARD_LIVE_TEST_CLASS')
    if live_test_class and not requested_test_class:
        test_classes = (*test_classes, live_test_class)
    for test_class in test_classes:
        run(android/'gradlew',':app:connectedDebugAndroidTest','--offline','--no-parallel',
            f'-Pandroid.testInstrumentationRunnerArguments.class={test_class}',
            '-Pandroid.testInstrumentationRunnerArguments.disposableBackend=true',
            f'-Pandroid.testInstrumentationRunnerArguments.backendUrl=http://10.0.2.2:{api_port}',cwd=android)
finally:
    if server is not None:
        server.terminate()
        try: server.wait(timeout=15)
        except subprocess.TimeoutExpired: server.kill();server.wait()
    if log is not None: log.close()
    if started:pg('pg_ctl','-D',root/'data','-m','fast','-w','stop')
    print(f'Integration logs: {root}')
