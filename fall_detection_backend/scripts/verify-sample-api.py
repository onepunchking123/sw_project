#!/usr/bin/env python3
"""Read-only smoke checks for the sample data through the running backend."""
import hashlib
import json
import os
import urllib.request
import uuid

base = os.environ.get('API_BASE_URL', 'http://127.0.0.1:8080').rstrip('/') + '/api'
def get(path):
    with urllib.request.urlopen(base + path, timeout=10) as response:
        return json.load(response)
def sample_id(label):
    return str(uuid.UUID(hashlib.md5(('careguard-development-v1:' + label).encode()).hexdigest()))
p = sample_id('patient')
patient = get('/patients/' + p)
assert patient['id'] == p
print('Sample patient:', patient['name'])
for resource, minimum in [('contacts',2),('caregivers',2),('cameras',2),('events',3),('records',13),('notes',4),('incidents',1),('weekly-reports',1)]:
    rows = get('/patients/' + p + '/' + resource)
    assert len(rows) >= minimum, (resource, len(rows))
    print(resource, len(rows))
# Derive the seeded day from the event, so verification works on later days too.
from datetime import datetime, timezone, timedelta
row = get('/patients/' + p + '/events/' + sample_id('event1'))
day = datetime.fromisoformat(row['occurredAt'].replace('Z', '+00:00')).astimezone(timezone(timedelta(hours=9))).date()
timeline = get('/patients/' + p + '/timeline?date=' + str(day))
counts = {section['period']: section['count'] for section in timeline}
assert counts == {'MORNING': 2, 'AFTERNOON': 2, 'EVENING': 2, 'DAWN': 0}, counts
print('Timeline counts:', counts)
calendar = get('/patients/' + p + '/calendar?month=' + str(day)[:7])
assert next(r for r in calendar if r['date'] == str(day))['level'] == 'HIGH'
for ago, level in [(1,'LOW'),(2,'MEDIUM'),(3,'HIGH')]:
    date = day - timedelta(days=ago)
    month = get('/patients/' + p + '/calendar?month=' + str(date)[:7])
    assert next(r for r in month if r['date'] == str(date))['level'] == level
responses = get('/incidents/' + sample_id('incident') + '/responses')
assert len(responses) == 2
print('Verified calendar LOW/MEDIUM/HIGH and incident responses.')
