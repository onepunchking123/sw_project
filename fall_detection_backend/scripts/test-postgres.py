#!/usr/bin/env python3
"""Run backend tests against a disposable local PostgreSQL (no production DB access)."""
import getpass
import os
from pathlib import Path
import socket
import subprocess
import tempfile

project = Path(__file__).resolve().parents[1]
bin_dir = os.environ.get('POSTGRES_BIN', '/opt/homebrew/opt/postgresql@16/bin')
root = Path(tempfile.mkdtemp(prefix='careguard-backend-tests-'))
with socket.socket() as candidate:
    candidate.bind(('127.0.0.1', 0))
    port = candidate.getsockname()[1]

def pg(command, *args):
    return subprocess.run([str(Path(bin_dir) / command), *map(str, args)], check=True)

started = False
try:
    pg('initdb', '-D', root / 'data', '-A', 'trust', '--no-locale', '-E', 'UTF8')
    pg('pg_ctl', '-D', root / 'data', '-l', root / 'postgres.log', '-o', f'-h 127.0.0.1 -k {root} -p {port}', '-w', 'start')
    started = True
    pg('psql', '-h', '127.0.0.1', '-p', port, '-d', 'postgres', '-v', 'ON_ERROR_STOP=1', '-f', project.parent / 'docs/backend/schema.sql')
    env = dict(os.environ, CAREGUARD_DISPOSABLE_DB='true', DB_URL=f'jdbc:postgresql://127.0.0.1:{port}/postgres', DB_USER=getpass.getuser(), DB_PASSWORD='')
    subprocess.run([str(project / 'gradlew'), 'cleanTest', 'test', 'bootJar', '--offline', '--no-parallel'], cwd=project, env=env, check=True)
finally:
    if started:
        pg('pg_ctl', '-D', root / 'data', '-m', 'fast', '-w', 'stop')
    print(f'PostgreSQL test log: {root / "postgres.log"}')
