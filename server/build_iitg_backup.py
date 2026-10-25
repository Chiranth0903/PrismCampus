"""Rebuild the separate, attributed first-year demo asset from public sources.

No credentials, student identities, or live campus publication are involved.
"""
import io
import json
import re
import urllib.request
import xml.etree.ElementTree as ET
from pathlib import Path
from pypdf import PdfReader

ROOT = Path(__file__).resolve().parents[1]
PDF = 'https://www.iitg.ac.in/acad/pdfs/ctt/1st_year_timetable_classroom.pdf'
GRID = 'https://www.iitg.ac.in/chemeng/pdf/time_table_july_nov_2026.pdf'
OSM = 'https://www.openstreetmap.org/api/0.6/map?bbox=91.685,26.18,91.70,26.20'


def fetch(url):
    return urllib.request.urlopen(urllib.request.Request(url, headers={
        'User-Agent': 'PrismCampus-public-demo/1.0'}), timeout=35).read()


def build():
    source = PdfReader(io.BytesIO(fetch(PDF)))
    osm = ET.fromstring(fetch(OSM))
    nodes = {n.attrib['id']: (float(n.attrib['lat']), float(n.attrib['lon']))
             for n in osm.findall('node')}
    selected = {'Academic Complex': 'ACADEMIC', 'Core 5': 'CORE5',
                'Lecture Halls': 'LHC', 'Administrative Block': 'ADMIN',
                'Computer centre': 'LIBCC', 'Mechnical Workshop': 'WORKSHOP',
                'IITG Hospital': 'HOSPITAL', 'Student Activity Center': 'SAC',
                'Conference Hall': 'CONFERENCE', 'Bhupen Hazarika Auditorium': 'AUDITORIUM',
                'Food Court': 'FOOD', 'Brahmaputra Hostel': 'BRAHMAPUTRA',
                'Dihing Hostel': 'DIHING', 'Siang Hostel': 'SIANG',
                'Subansiri Hostel': 'SUBANSIRI', 'Kapili Hostel': 'KAPILI',
                'Manas Hostel': 'MANAS', 'Lohit Hostel': 'LOHIT'}
    buildings = []
    for element in osm:
        tags = {t.attrib['k']: t.attrib['v'] for t in element.findall('tag')}
        name = tags.get('name', '')
        if name not in selected:
            continue
        points = ([nodes[element.attrib['id']]] if element.tag == 'node' else
                  [nodes[n.attrib['ref']] for n in element.findall('nd') if n.attrib['ref'] in nodes])
        if not points:
            continue
        if len(points) > 1 and points[0] == points[-1]:
            points = points[:-1]
        lat, lon = (sum(p[i] for p in points) / len(points) for i in (0, 1))
        name = {'Computer centre': 'Library & Computer Centre (approximate pin)',
                'Mechnical Workshop': 'Mechanical Workshop'}.get(name, name)
        buildings.append(dict(id=selected[tags['name']], code=selected[tags['name']], name=name,
            latitude=round(lat, 7), longitude=round(lon, 7), total_floors=0, rooms=[],
            description='OpenStreetMap point / footprint centre; not a verified entrance. Floor information unverified.',
            source_url=f"https://www.openstreetmap.org/{element.tag}/{element.attrib['id']}",
            coordinate_accuracy='approximate building point; verify on site'))
    core = next(b for b in buildings if b['code'] == 'CORE5')
    names = [
        ('CSE', 'Computer Science and Engineering'),
        ('ECE', 'Electronics and Communication Engineering'),
        ('EEE', 'Electronics and Electrical Engineering'),
        ('ME', 'Mechanical Engineering'), ('CE', 'Civil Engineering'),
        ('BSBE', 'Biosciences and Bioengineering'), ('CL', 'Chemical Engineering'),
        ('EP', 'Engineering Physics'), ('CST', 'Chemical Science and Technology'),
        ('MNC', 'Mathematics and Computing'), ('DSAI', 'Data Science and Artificial Intelligence'),
        ('EN', 'Energy Engineering'), ('BSE', 'Biomedical Science and Engineering'), ('BDES', 'Design')]
    # Matrix reproduced from the official department's published slot grid.
    # The grid has a stale 2025 caption inside its 2026 file: retain that caveat.
    days = ['MONDAY', 'TUESDAY', 'WEDNESDAY', 'THURSDAY', 'FRIDAY']
    matrix = ['A B C D F F1 D1 C1 B1 A1', 'E A B C F F1 C1 B1 A1 E1',
              'D E A B G G1 B1 A1 E1 D1', 'C D E A G G1 A1 E1 D1 C1',
              'B C D F G G1 F1 D1 C1 B1']
    groups = []
    for page in source.pages:
        text = page.extract_text()
        chunks = re.split(r'Course (?:Number|No\.) Course Name', text)[1:]
        groups.extend(chunks)
    assert len(groups) == len(names), f'Unexpected PDF structure: {len(groups)} groups'
    sections, catalog = [], []
    rooms = set()
    for (code, name), chunk in zip(names, groups):
        rows = []
        for line in chunk.splitlines():
            match = re.match(r'^([A-Z]{2}\d{4}[A-Z]?)\s+(.+?)\s+\d\s+\d\s+\d\s+\d+\s+(.+)$', line.strip())
            if not match:
                continue
            course, title, tail = match.groups()
            lab = re.search(r'(MON|TUE|WED|THU|FRI)\s*\(2-5 PM\)', tail)
            theory = re.match(r'([A-G])\s+[A-G]\d\s+(\d{4}(?:/\d{4})?)', tail)
            row = dict(course_code=course, course_name=title, original_assignment=tail,
                       section_id=f'FY_{code}', source_url=PDF,
                       semester_phase='post-midsem' if 'Post-midsem' in tail else
                       'pre-midsem' if 'Pre-midsem' in tail else 'full-semester')
            if theory:
                row.update(class_slot=theory[1], room_ids=theory[2].split('/'))
                rooms.update(row['room_ids'])
            if lab:
                row.update(day={'MON':'MONDAY','TUE':'TUESDAY','WED':'WEDNESDAY',
                                'THU':'THURSDAY','FRI':'FRIDAY'}[lab[1]], start_time='14:00', end_time='17:00',
                           location_status='Lab room not specified by source; ask department')
            rows.append(row)
        catalog.extend(rows)
        schedule = {day: [] for day in days}
        for day, slots in zip(days, matrix):
            for index, slot in enumerate(slots.split()):
                for row in rows:
                    if row['semester_phase'] == 'pre-midsem' or row.get('class_slot') != slot:
                        continue
                    if row['course_code'] == 'CE1101' and day != 'TUESDAY':
                        continue
                    room = row['room_ids'][0]
                    schedule[day].append(dict(period=index+1, start_time=f'{8+index:02}:00',
                        end_time=f'{8+index:02}:55', course_code=row['course_code'],
                        course_name=row['course_name'], is_lab=False, building_code='CORE5', room_id=room))
            for row in rows:
                if row.get('day') == day:
                    schedule[day] = [s for s in schedule[day] if s['start_time'] < '14:00']
                    schedule[day].append(dict(period=7, start_time='14:00', end_time='17:00',
                        course_code=row['course_code'], course_name=row['course_name']+' · room TBC',
                        is_lab=True, building_code='CORE5', room_id='LOCATION_TBC', route_available=False))
            schedule[day].sort(key=lambda s:s['start_time'])
        default = next((r['room_ids'][0] for r in rows if 'room_ids' in r), 'LOCATION_TBC')
        sections.append(dict(section_id=f'FY_{code}', name=f'Year 1 · {name}', department=name,
            default_room_id=default, default_building_code='CORE5', default_floor=0, schedule=schedule))
    core['rooms'] = [dict(room_id=r, name=f'Classroom {r}', floor=0,
        floor_label='Floor unverified', building_code='CORE5', category='classroom',
        description='Room number from official first-year PDF; Core 5 grouping inferred from room code, verify on site.')
        for r in sorted(rooms)] + [dict(room_id='LOCATION_TBC', name='Lab location to be confirmed',
        floor=0, floor_label='Location unconfirmed', building_code='CORE5', category='laboratory')]
    pack = dict(pack_version='1.0.0-demo', generated_at='2026-10-25', campus_id='IITG_DEMO',
        campus_name='Indian Institute of Technology Guwahati · demo backup', short_name='IITG Demo',
        website='https://www.iitg.ac.in/', city='Guwahati', country='India',
        description='First-year demo backup, July–November 2026; post-midsem view. Verify before real travel.',
        base_location=dict(name='Core 5 demo anchor', latitude=core['latitude'], longitude=core['longitude'],
            description='Approximate OpenStreetMap building centre, not the main gate.'),
        buildings=buildings, sections=sections, lab_routing_rules=[], first_year_course_catalog=catalog,
        demo_metadata=dict(is_demo=True, attendance_authority=False,
            timetable_source=PDF, slot_grid_source=GRID,
            map_source=OSM, map_attribution='© OpenStreetMap contributors, ODbL 1.0',
            limitations=['The official 2026 department file has a stale 2025 slot-grid caption; weekly theory expansion is provisional.',
                'Generic slot expansion does not establish exact contact hours or tutorial assignments for every course.',
                'Only explicit 14:00–17:00 lab times are included; lab rooms remain unconfirmed and cannot be routed.',
                'Room-to-Core-5 association is inferred from numbering; floors are unverified.',
                'Alternative room assignments use the first listed room for demonstration; consult original PDF.',
                'Pre-midsem courses remain in catalog but are omitted from the post-midsem demo schedule.',
                'BDes studio timings and rooms require department confirmation; no invented times.',
                'Map points are approximate and incomplete; this is not a majority-of-all-campus-rooms survey.']))
    curated = ROOT/'server/iitg_academic_pins.json'
    if curated.exists():
        known = {b['code'] for b in buildings}
        buildings.extend(b for b in json.loads(curated.read_text(encoding='utf-8'))['buildings'] if b['code'] not in known)
    target = ROOT/'app/src/main/assets/iitg_demo_pack.json'
    target.write_text(json.dumps(pack, ensure_ascii=False, indent=2), encoding='utf-8')
    print(json.dumps(dict(file=str(target), buildings=len(buildings), rooms=len(core['rooms']),
        first_year_groups=len(sections), courses=len(catalog), weekly_sessions=sum(len(v) for s in sections for v in s['schedule'].values()))))


if __name__ == '__main__':
    build()
