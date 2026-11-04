#!/usr/bin/env python3
"""Manage the app-only PostgreSQL 16 cluster on localhost:5433."""
import argparse
import os
from pathlib import Path
import secrets
import re
import socket
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
DATA = ROOT / '.local' / 'postgres'
CONFIG = ROOT / 'application-local.properties'
BIN = Path(os.environ.get('POSTGRES_BIN', '/opt/homebrew/opt/postgresql@16/bin'))
PORT = '5433'

def run(program, *args, **kwargs):
    return subprocess.run([str(BIN / program), *map(str, args)], check=True, **kwargs)

def running():
    return subprocess.run([str(BIN / 'pg_ctl'), '-D', str(DATA), 'status'], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL).returncode == 0

def start():
    if running():
        print('App PostgreSQL is already running on port 5433.')
        return
    run('pg_ctl', '-D', DATA, '-l', ROOT / '.local' / 'postgres.log', '-w', 'start')

def initialize():
    if DATA.exists():
        raise SystemExit('Cluster already exists. Use start; initial schema will not be reapplied.')
    with socket.socket() as probe:
        try:
            probe.bind(('127.0.0.1', int(PORT)))
        except OSError:
            raise SystemExit('Port 5433 is in use. No changes made.')
    if CONFIG.exists() and (ROOT / '.local' / 'application-local.properties.before-init').exists():
        raise SystemExit('Configuration backup already exists. Inspect it before initializing.')
    DATA.parent.mkdir(parents=True, exist_ok=True)
    DATA.parent.chmod(0o700)
    password = secrets.token_hex(24)
    with tempfile.NamedTemporaryFile(mode='w') as pw:
        pw.write(password + '\n')
        pw.flush()
        run('initdb', '-D', DATA, '--username=careguard', '--auth-local=trust', '--auth-host=scram-sha-256', '--pwfile=' + pw.name, '--encoding=UTF8', '--no-locale')
    with (DATA / 'postgresql.conf').open('a') as config:
        config.write("\n# CareGuard local development cluster\nlisten_addresses = '127.0.0.1'\nport = 5433\nunix_socket_directories = ''\ntimezone = 'Asia/Seoul'\n")
    if CONFIG.exists():
        backup = ROOT / '.local' / 'application-local.properties.before-init'
        backup.write_bytes(CONFIG.read_bytes())
        backup.chmod(0o600)
    CONFIG.write_text(
        '# App-only local PostgreSQL. Do not commit this file.\n'
        'spring.datasource.url=${DB_URL:jdbc:postgresql://127.0.0.1:5433/fall_detection}\n'
        'spring.datasource.username=${DB_USER:careguard}\n'
        'spring.datasource.password=${DB_PASSWORD:' + password + '}\n'
    )
    CONFIG.chmod(0o600)
    start()
    env = dict(os.environ, PGPASSWORD=password)
    run('createdb', '-h', '127.0.0.1', '-p', PORT, '-U', 'careguard', '-w', 'fall_detection', env=env)
    run('psql', '-h', '127.0.0.1', '-p', PORT, '-U', 'careguard', '-w', '-d', 'fall_detection', '-v', 'ON_ERROR_STOP=1', '--single-transaction', '-f', ROOT.parent / 'docs/backend/schema.sql', env=env)
    print('Created fall_detection with its schema. Credentials saved in application-local.properties.')

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('action', choices=['init', 'start', 'stop', 'status', 'seed', 'verify'])
action = parser.parse_args().action
if action == 'init':
    initialize()
elif action in ('seed', 'verify'):
    if not running():
        raise SystemExit('Start the app database first.')
    config = CONFIG.read_text()
    match = re.search(r'spring.datasource.password=\$\{DB_PASSWORD:([^}]*)\}', config)
    if not match:
        raise SystemExit('Local password configuration was changed; inspect configuration first.')
    env = dict(os.environ, PGPASSWORD=match.group(1))
    args = ['-h', '127.0.0.1', '-p', PORT, '-U', 'careguard', '-d', 'fall_detection', '-w', '-v', 'ON_ERROR_STOP=1']
    if action == 'seed':
        run('psql', *args, '-f', ROOT.parent / 'docs/backend/seed-development.sql', env=env)
    else:
        run('psql', *args, '-c', " UNION ALL ".join("SELECT '" + table + "' AS table_name, count(*) AS row_count FROM " + table for table in ['users','patients','caregiver_links','patient_contacts','camera_devices','pairing_codes','clips','detection_events','incidents','incident_responses','posture_samples','care_records','event_notes','weekly_reports']), env=env)
elif action == 'start':
    if not DATA.exists():
        raise SystemExit('Run init first.')
    start()
elif action == 'stop':
    if running():
        run('pg_ctl', '-D', DATA, '-m', 'fast', '-w', 'stop')
    else:
        print('App PostgreSQL is stopped.')
else:
    print('running on 127.0.0.1:5433' if running() else 'stopped')
