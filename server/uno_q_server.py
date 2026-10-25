#!/usr/bin/env python3
"""
PrismCampus - Arduino Uno Q / Laptop Edge Server & Multi-Campus Admin Dashboard
-------------------------------------------------------------------------------
Offline-first edge campus server running on the local network (LAN / Uno Q AP).
Uses persistent SQLite database (server/prism_campus.db) with zero external pip dependencies.

Key Capabilities:
1. Multi-Campus System:
   - Create, edit, and publish multiple colleges (e.g. MSRIT, BMSCE, RVCE).
   - Generates unique / readable Campus IDs.
   - Serves published campus packages to student devices over local Wi-Fi.
   - Built-in QR Code generation for 1-tap student onboarding.
2. Building, Floor & Room Hierarchy:
   - Hierarchical data model: Campus -> Buildings -> Floors -> Rooms.
   - Supports Classrooms, Labs, Washrooms, Libraries, Auditoriums, and Offices.
   - Draft vs Published versioning.
3. Broadcast Announcements:
   - General campus alerts, event news, and emergency notices.
4. Timetable & Classroom Overrides:
   - Temporary and permanent classroom shift overrides with reason and timestamp.
   - Preserves original timetable while providing active override routing.
5. Incident Command Center & Civic Reporting:
   - Deduplication and clustering of voice/text reports from student devices.
   - Frequency-based ranking (most reported issues #1).
   - Status tracking: Open, In Progress, Resolved, with admin notes.
6. Local-First & Zero-Cloud:
   - Pure Python standard library (http.server, sqlite3, json, hashlib, secrets).
   - Runs on Arduino Uno Q, Raspberry Pi, Linux, Windows, or Mac without internet.
"""

import http.server
import socketserver
import sqlite3
import json
import os
import sys
import time
import urllib.parse
import urllib.request
import uuid
import hashlib
import secrets
import csv
import io
import socket

PORT = 8000
HOST = "0.0.0.0"

BASE_DIR = os.path.dirname(os.path.abspath(__file__))
try:
    from .campus_onboarding import load_environment, enhance
except ImportError:
    from campus_onboarding import load_environment, enhance
load_environment()
DB_PATH = os.environ.get("PRISM_DB_PATH", os.path.join(BASE_DIR, "prism_campus.db"))
ASSETS_PACK = os.path.join(BASE_DIR, "..", "app", "src", "main", "assets", "msrit_pack.json")

# Natural-language assistant rules paths (supports runtime hot-reloads)
RULES_FILE_PATHS = [
    os.path.join(BASE_DIR, "..", "config", "assistant_rules.md"),
    os.path.join(BASE_DIR, "config", "assistant_rules.md"),
    os.path.join(BASE_DIR, "..", "app", "src", "main", "assets", "assistant_rules.md"),
]
_CACHED_RULES = None
_CACHED_RULES_MTIME = 0
_CACHED_RULES_PATH = None

def get_assistant_rules():
    global _CACHED_RULES, _CACHED_RULES_MTIME, _CACHED_RULES_PATH
    for path in RULES_FILE_PATHS:
        abs_p = os.path.abspath(path)
        if os.path.exists(abs_p) and os.path.isfile(abs_p):
            try:
                mtime = int(os.path.getmtime(abs_p) * 1000)
                if mtime > _CACHED_RULES_MTIME or _CACHED_RULES is None:
                    with open(abs_p, "r", encoding="utf-8") as f:
                        _CACHED_RULES = f.read().strip()
                        _CACHED_RULES_MTIME = mtime
                        _CACHED_RULES_PATH = abs_p
                        print(f"[RULES] Loaded assistant rules from {abs_p} (mtime={mtime})")
                if _CACHED_RULES:
                    return _CACHED_RULES, _CACHED_RULES_PATH or abs_p, _CACHED_RULES_MTIME
            except Exception as e:
                print(f"[RULES] Error reading {abs_p}: {e}")

    default_rules = (
        "- Keep answers concise and direct (typically 2 to 3 sentences) unless more detail is requested.\n"
        "- Use simple, accessible language and briefly explain technical terms.\n"
        "- Use only the selected campus records; do not invent placement or salary figures.\n"
        "- If you do not know something, state so clearly instead of inventing an answer.\n"
        "- When offline, clearly state when a question cannot be answered reliably using local information."
    )
    return default_rules, "Default In-Memory Rules", int(time.time() * 1000)

# ==============================================================================
# LOCAL QWEN2.5-0.5B-INSTRUCT INFERENCE RUNTIME (LLAMA.CPP ENGINE)
# ==============================================================================

LLAMA_SERVER_PORT = 8081
_LLAMA_PROCESS = None

def ensure_llama_server():
    global _LLAMA_PROCESS
    # Check if already listening on port 8081
    try:
        with urllib.request.urlopen(f"http://127.0.0.1:{LLAMA_SERVER_PORT}/health", timeout=1) as r:
            if r.status == 200:
                return True
    except Exception:
        pass

    candidate_model_paths = [
        os.path.join(BASE_DIR, "models", "qwen2.5-0.5b-instruct-q4_k_m.gguf"),
        os.path.join(os.path.expanduser("~"), "Downloads", "qwen2.5-0.5b-instruct-q4_k_m.gguf"),
    ]
    model_path = next((p for p in candidate_model_paths if os.path.exists(p)), None)
    binary_path = os.path.join(BASE_DIR, "bin", "llama-server.exe")

    if not model_path or not os.path.exists(binary_path):
        return False

    try:
        import subprocess
        _LLAMA_PROCESS = subprocess.Popen(
            [binary_path, "-m", model_path, "--port", str(LLAMA_SERVER_PORT), "-c", "2048", "-t", "4", "--log-disable"],
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL
        )
        for _ in range(20):
            time.sleep(0.4)
            try:
                with urllib.request.urlopen(f"http://127.0.0.1:{LLAMA_SERVER_PORT}/health", timeout=1) as r:
                    if r.status == 200:
                        return True
            except Exception:
                pass
        return False
    except Exception as e:
        print(f"[QWEN] Failed to start llama-server: {e}")
        return False

def campus_ai_context(campus_id, client_context=""):
    with get_db() as db:
        campus = db.execute("SELECT college_name,website FROM campuses WHERE campus_id=?", (campus_id,)).fetchone()
    if campus is None:
        raise ValueError("Unknown campus ID")
    return (f"Selected campus: {campus['college_name']} (ID: {campus_id}). Website: {campus['website']}.\n"
            "Use only this campus's records. Do not substitute another college's facts, invent salary figures, "
            "or add campus examples to general definitions. State when campus data is missing.\n" + client_context)


def campus_record_answer(campus_id, query):
    lower = query.lower()
    with get_db() as db:
        buildings = db.execute("SELECT name,code,description FROM buildings WHERE campus_id=?", (campus_id,)).fetchall()
    import re
    matches = [b for b in buildings if b['name'].lower() in lower or
               re.search(r"(?<![a-z0-9])" + re.escape(b['code'].lower()) + r"(?![a-z0-9])", lower)]
    if matches:
        return "\n".join(f"{b['name']} ({b['code']}): {b['description']}" for b in matches[:3])
    return "I could not generate a reliable answer. Ask again, or check the selected campus timetable and map."


def query_local_qwen(query: str, rules: str = "", campus_context: str = "") -> str | None:
    if not ensure_llama_server():
        return None

    prompt = (
        f"<|im_start|>system\n"
        f"You are PrismCampus AI. Use the selected campus identity in the supplied context. Never substitute another college. Answer general questions generally without unsolicited campus examples.\n\n"
        f"Rules & Instructions:\n{rules}\n\n"
        f"Campus Context:\n{campus_context}\n"
        f"<|im_end|>\n"
        f"<|im_start|>user\n{query}<|im_end|>\n"
        f"<|im_start|>assistant\n"
    )

    payload = {
        "prompt": prompt,
        "n_predict": 100,
        "temperature": 0.7,
        "stop": ["<|im_end|>", "<|endoftext|>"]
    }

    try:
        req = urllib.request.Request(
            f"http://127.0.0.1:{LLAMA_SERVER_PORT}/completion",
            data=json.dumps(payload).encode("utf-8"),
            headers={"Content-Type": "application/json"}
        )
        with urllib.request.urlopen(req, timeout=25) as response:
            if response.status == 200:
                data = json.loads(response.read().decode("utf-8"))
                content = data.get("content", "").strip()
                if content:
                    return content
    except Exception as e:
        print(f"[QWEN] Inference request error: {e}")
    return None

# ==============================================================================
# DATABASE LAYER & PERSISTENT SCHEMA
# ==============================================================================

class ClosingConnection(sqlite3.Connection):
    def __exit__(self, *args):
        try:
            return super().__exit__(*args)
        finally:
            self.close()


def get_db():
    conn = sqlite3.connect(DB_PATH, timeout=10, factory=ClosingConnection)
    conn.row_factory = sqlite3.Row
    return conn

def hash_password(password: str, salt: str) -> str:
    return hashlib.sha256((password + salt).encode("utf-8")).hexdigest()

def init_db():
    with get_db() as conn:
        cursor = conn.cursor()

        # 1. Admin users
        cursor.execute("""
            CREATE TABLE IF NOT EXISTS admins (
                id TEXT PRIMARY KEY,
                username TEXT UNIQUE NOT NULL,
                password_hash TEXT NOT NULL,
                salt TEXT NOT NULL,
                created_at INTEGER NOT NULL
            )
        """)

        # 2. Sessions
        cursor.execute("""
            CREATE TABLE IF NOT EXISTS sessions (
                token TEXT PRIMARY KEY,
                admin_id TEXT NOT NULL,
                created_at INTEGER NOT NULL,
                expires_at INTEGER NOT NULL
            )
        """)

        # 3. Campuses
        cursor.execute("""
            CREATE TABLE IF NOT EXISTS campuses (
                campus_id TEXT PRIMARY KEY,
                college_name TEXT NOT NULL,
                short_name TEXT NOT NULL,
                website TEXT NOT NULL,
                city TEXT NOT NULL,
                country TEXT NOT NULL,
                address TEXT NOT NULL,
                description TEXT NOT NULL,
                base_name TEXT NOT NULL,
                base_lat REAL NOT NULL,
                base_lng REAL NOT NULL,
                base_desc TEXT NOT NULL,
                pack_version TEXT NOT NULL DEFAULT '1.0.0',
                is_published INTEGER NOT NULL DEFAULT 1,
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL
            )
        """)

        # 4. Buildings
        cursor.execute("""
            CREATE TABLE IF NOT EXISTS buildings (
                id TEXT PRIMARY KEY,
                campus_id TEXT NOT NULL,
                code TEXT NOT NULL,
                name TEXT NOT NULL,
                latitude REAL NOT NULL,
                longitude REAL NOT NULL,
                total_floors INTEGER NOT NULL DEFAULT 1,
                description TEXT NOT NULL,
                FOREIGN KEY (campus_id) REFERENCES campuses(campus_id) ON DELETE CASCADE
            )
        """)

        # 5. Rooms
        cursor.execute("""
            CREATE TABLE IF NOT EXISTS rooms (
                id TEXT PRIMARY KEY,
                campus_id TEXT NOT NULL,
                building_code TEXT NOT NULL,
                room_id TEXT NOT NULL,
                name TEXT NOT NULL,
                floor INTEGER NOT NULL,
                floor_label TEXT NOT NULL,
                category TEXT NOT NULL DEFAULT 'classroom',
                assigned_section TEXT,
                assigned_course TEXT,
                department TEXT NOT NULL DEFAULT '',
                description TEXT NOT NULL DEFAULT '',
                FOREIGN KEY (campus_id) REFERENCES campuses(campus_id) ON DELETE CASCADE
            )
        """)

        # 6. Sections
        cursor.execute("""
            CREATE TABLE IF NOT EXISTS sections (
                id TEXT PRIMARY KEY,
                campus_id TEXT NOT NULL,
                section_id TEXT NOT NULL,
                name TEXT NOT NULL,
                department TEXT NOT NULL,
                default_room_id TEXT NOT NULL,
                default_building_code TEXT NOT NULL,
                default_floor INTEGER NOT NULL,
                schedule_json TEXT NOT NULL DEFAULT '{}',
                FOREIGN KEY (campus_id) REFERENCES campuses(campus_id) ON DELETE CASCADE
            )
        """)

        # 7. Lab Routing Rules
        cursor.execute("""
            CREATE TABLE IF NOT EXISTS lab_routing_rules (
                id TEXT PRIMARY KEY,
                campus_id TEXT NOT NULL,
                course_code TEXT NOT NULL,
                building_code TEXT NOT NULL,
                building_name TEXT NOT NULL,
                room_id TEXT NOT NULL,
                floor INTEGER NOT NULL,
                floor_label TEXT NOT NULL,
                description TEXT NOT NULL DEFAULT '',
                FOREIGN KEY (campus_id) REFERENCES campuses(campus_id) ON DELETE CASCADE
            )
        """)

        # 8. Announcements
        cursor.execute("""
            CREATE TABLE IF NOT EXISTS announcements (
                id TEXT PRIMARY KEY,
                campus_id TEXT NOT NULL,
                title TEXT NOT NULL,
                content TEXT NOT NULL,
                priority TEXT NOT NULL DEFAULT 'Important',
                audience TEXT NOT NULL DEFAULT 'All Students',
                created_at INTEGER NOT NULL,
                expires_at INTEGER,
                is_archived INTEGER NOT NULL DEFAULT 0,
                FOREIGN KEY (campus_id) REFERENCES campuses(campus_id) ON DELETE CASCADE
            )
        """)

        # 9. Classroom Overrides
        cursor.execute("""
            CREATE TABLE IF NOT EXISTS overrides (
                id TEXT PRIMARY KEY,
                campus_id TEXT NOT NULL,
                target_course_code TEXT NOT NULL,
                target_section TEXT,
                new_building_code TEXT NOT NULL,
                new_building_name TEXT NOT NULL,
                new_room_id TEXT NOT NULL,
                new_floor INTEGER NOT NULL,
                new_floor_label TEXT NOT NULL,
                reason TEXT NOT NULL,
                is_permanent INTEGER NOT NULL DEFAULT 0,
                effective_time TEXT NOT NULL DEFAULT 'Immediate',
                created_at INTEGER NOT NULL,
                is_active INTEGER NOT NULL DEFAULT 1,
                FOREIGN KEY (campus_id) REFERENCES campuses(campus_id) ON DELETE CASCADE
            )
        """)

        # 10. Issue Reports
        cursor.execute("""
            CREATE TABLE IF NOT EXISTS issue_reports (
                id TEXT PRIMARY KEY,
                campus_id TEXT NOT NULL,
                cluster_key TEXT NOT NULL,
                category TEXT NOT NULL,
                building TEXT NOT NULL,
                room TEXT NOT NULL,
                title TEXT NOT NULL,
                description TEXT NOT NULL,
                urgency TEXT NOT NULL,
                report_count INTEGER NOT NULL DEFAULT 1,
                status TEXT NOT NULL DEFAULT 'Open',
                resolution_note TEXT NOT NULL DEFAULT '',
                escalated INTEGER NOT NULL DEFAULT 0,
                first_reported INTEGER NOT NULL,
                last_reported INTEGER NOT NULL,
                reporters_json TEXT NOT NULL DEFAULT '[]',
                FOREIGN KEY (campus_id) REFERENCES campuses(campus_id) ON DELETE CASCADE
            )
        """)

        # 11. College Timetables (Master Academic Timetables across all semesters)
        cursor.execute("""
            CREATE TABLE IF NOT EXISTS college_timetables (
                id TEXT PRIMARY KEY,
                campus_id TEXT NOT NULL,
                semester INTEGER NOT NULL DEFAULT 1,
                department TEXT NOT NULL,
                section_id TEXT NOT NULL,
                day_of_week TEXT NOT NULL,
                period INTEGER NOT NULL DEFAULT 1,
                start_time TEXT NOT NULL,
                end_time TEXT NOT NULL,
                course_code TEXT NOT NULL,
                course_name TEXT NOT NULL,
                instructor TEXT NOT NULL DEFAULT 'Faculty',
                room_id TEXT NOT NULL,
                building_name TEXT NOT NULL DEFAULT 'Apex Block',
                is_lab INTEGER NOT NULL DEFAULT 0,
                created_at INTEGER NOT NULL,
                FOREIGN KEY (campus_id) REFERENCES campuses(campus_id) ON DELETE CASCADE
            )
        """)

        conn.commit()

        # Seed default admin if none exists
        cursor.execute("SELECT COUNT(*) FROM admins")
        if cursor.fetchone()[0] == 0:
            salt = secrets.token_hex(16)
            pwd_hash = hash_password("admin123", salt)
            cursor.execute(
                "INSERT INTO admins (id, username, password_hash, salt, created_at) VALUES (?, ?, ?, ?, ?)",
                ("admin_default", "admin", pwd_hash, salt, int(time.time() * 1000))
            )
            conn.commit()
            print("[INFO] Seeded default administrator account: admin / admin123")

        # Seed default MSRIT campus if no campuses exist
        cursor.execute("SELECT COUNT(*) FROM campuses")
        if cursor.fetchone()[0] == 0:
            seed_msrit_campus(conn)

        # Seed default master timetables if none exist
        cursor.execute("SELECT COUNT(*) FROM college_timetables")
        if cursor.fetchone()[0] == 0:
            seed_default_timetables(conn)

def seed_msrit_campus(conn):
    cursor = conn.cursor()
    print("[INFO] Seeding Ramaiah Institute of Technology (MSRIT) into SQLite database...")
    now = int(time.time() * 1000)

    # Read msrit_pack.json if available
    pack_data = None
    if os.path.exists(ASSETS_PACK):
        try:
            with open(ASSETS_PACK, "r", encoding="utf-8") as f:
                pack_data = json.load(f)
        except Exception as e:
            print(f"[WARN] Failed reading {ASSETS_PACK}: {e}")

    if not pack_data:
        pack_data = {
            "campus_name": "Ramaiah Institute of Technology (MSRIT)",
            "short_name": "MSRIT",
            "website": "https://msrit.edu",
            "city": "Bengaluru",
            "country": "India",
            "base_location": {
                "name": "Main Gate",
                "latitude": 13.0295,
                "longitude": 77.5650,
                "description": "Primary campus entrance on MSRIT Road / New BEL Road"
            },
            "buildings": [],
            "sections": [],
            "lab_routing_rules": []
        }

    campus_id = "MSRIT"
    base_loc = pack_data.get("base_location", {})

    cursor.execute("""
        INSERT INTO campuses (
            campus_id, college_name, short_name, website, city, country,
            address, description, base_name, base_lat, base_lng, base_desc,
            pack_version, is_published, created_at, updated_at
        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 1, ?, ?)
    """, (
        campus_id,
        pack_data.get("campus_name", "Ramaiah Institute of Technology (MSRIT)"),
        pack_data.get("short_name", "MSRIT"),
        pack_data.get("website", "https://msrit.edu"),
        pack_data.get("city", "Bengaluru"),
        pack_data.get("country", "India"),
        "MSR Nagar, Mathikere, Bengaluru, Karnataka 560054",
        "Premier engineering institution established in 1962, affiliated to VTU.",
        base_loc.get("name", "Main Gate"),
        base_loc.get("latitude", 13.0295),
        base_loc.get("longitude", 77.5650),
        base_loc.get("description", "Primary campus entrance on MSRIT Road"),
        pack_data.get("pack_version", "1.0.0"),
        now, now
    ))

    # Insert buildings and rooms
    for b in pack_data.get("buildings", []):
        b_id = b.get("id") or f"bldg_{b.get('code')}"
        cursor.execute("""
            INSERT INTO buildings (id, campus_id, code, name, latitude, longitude, total_floors, description)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
        """, (
            b_id, campus_id, b.get("code", ""), b.get("name", ""),
            b.get("latitude", 0.0), b.get("longitude", 0.0),
            b.get("total_floors", 1), b.get("description", "")
        ))

        for r in b.get("rooms", []):
            r_id = f"room_{campus_id}_{r.get('room_id')}"
            cursor.execute("""
                INSERT INTO rooms (
                    id, campus_id, building_code, room_id, name, floor,
                    floor_label, category, assigned_section, assigned_course,
                    department, description
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """, (
                r_id, campus_id, b.get("code", ""), r.get("room_id", ""),
                r.get("name", r.get("room_id", "")), r.get("floor", 1),
                r.get("floor_label", f"{r.get('floor', 1)}th Floor"),
                r.get("category", "classroom"), r.get("assigned_section"),
                r.get("assigned_course"), r.get("department", ""),
                r.get("description", "")
            ))

    # Insert sections
    for s in pack_data.get("sections", []):
        s_id = f"sec_{campus_id}_{s.get('section_id')}"
        sched_json = json.dumps(s.get("schedule", {}))
        cursor.execute("""
            INSERT INTO sections (
                id, campus_id, section_id, name, department,
                default_room_id, default_building_code, default_floor, schedule_json
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
        """, (
            s_id, campus_id, s.get("section_id", "A"), s.get("name", ""),
            s.get("department", ""), s.get("default_room_id", ""),
            s.get("default_building_code", ""), s.get("default_floor", 1),
            sched_json
        ))

    # Insert lab rules
    for rule in pack_data.get("lab_routing_rules", []):
        rule_id = f"rule_{campus_id}_{rule.get('course_code')}"
        cursor.execute("""
            INSERT INTO lab_routing_rules (
                id, campus_id, course_code, building_code, building_name,
                room_id, floor, floor_label, description
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
        """, (
            rule_id, campus_id, rule.get("course_code", ""),
            rule.get("building_code", ""), rule.get("building_name", ""),
            rule.get("room_id", ""), rule.get("floor", 1),
            rule.get("floor_label", ""), rule.get("description", "")
        ))

    # Insert demo announcements
    cursor.execute("""
        INSERT INTO announcements (id, campus_id, title, content, priority, audience, created_at, is_archived)
        VALUES (?, ?, ?, ?, ?, ?, ?, 0)
    """, (
        "anno_demo_1", campus_id,
        "Cultural Fest 'Udbhav 2026' Registrations Opened",
        "Stage events, music, and dance team registrations are now open on the official portal (https://msrit.edu).",
        "Important", "All Students", now - 1800000
    ))
    cursor.execute("""
        INSERT INTO announcements (id, campus_id, title, content, priority, audience, created_at, is_archived)
        VALUES (?, ?, ?, ?, ?, ?, ?, 0)
    """, (
        "anno_demo_2", campus_id,
        "Central Library Extended Hours",
        "Apex Annex Central Library will remain open until 10:00 PM for internal assessment preparations.",
        "General", "All Students & Faculty", now - 7200000
    ))

    # Insert demo classroom override
    cursor.execute("""
        INSERT INTO overrides (
            id, campus_id, target_course_code, target_section,
            new_building_code, new_building_name, new_room_id,
            new_floor, new_floor_label, reason, is_permanent, effective_time, created_at, is_active
        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0, 'Immediate', ?, 1)
    """, (
        "override_demo_1", campus_id, "MAC11", "A",
        "LHC", "Lecture Hall Complex", "LHC Room 212",
        2, "2nd Floor", "Shifted to LHC Room 212 for guest lecture series (Dean Notice)",
        now - 1800000
    ))

    # Insert demo civic issue reports
    seed_demo_reports(cursor, campus_id, now)

    conn.commit()
    print("[INFO] Successfully seeded MSRIT campus into SQLite.")

def seed_demo_reports(cursor, campus_id, now):
    reps = [
        {
            "id": "cluster_hostel_fan",
            "cluster_key": "hostel_mess_electrical_fan",
            "category": "Electrical",
            "building": "Hostel Complex",
            "room": "Hostel Mess",
            "title": "Hostel mess fan broken / vibrating loudly",
            "description": "Ceiling fan in the hostel mess is vibrating dangerously and making loud grinding noise.",
            "report_count": 5,
            "urgency": "CRITICAL (Escalated to Head of Maintenance - 5 Reports)",
            "status": "In Progress",
            "resolution_note": "Electrician team dispatched to hostel mess.",
            "escalated": 1,
            "first_reported": now - 7200000,
            "last_reported": now - 600000,
            "reporters": ["Pixel_8_Student", "Galaxy_S23_Student", "OnePlus_11_Student", "iPhone_Client", "Student_Sync"]
        },
        {
            "id": "cluster_esb_402_fan",
            "cluster_key": "esb_402_electrical_fan",
            "category": "Electrical",
            "building": "Engineering Sciences Block",
            "room": "Room 402",
            "title": "CAD Lab fan regulator sparking / speed stuck",
            "description": "Fan switch board sparking when turned on in ESB 402.",
            "report_count": 2,
            "urgency": "High",
            "status": "Open",
            "resolution_note": "",
            "escalated": 0,
            "first_reported": now - 10800000,
            "last_reported": now - 3600000,
            "reporters": ["Student_Laptop_Sync", "Vivo_Y200_Student"]
        },
        {
            "id": "cluster_apex_3f_tap",
            "cluster_key": "apex_3f_plumbing_tap",
            "category": "Plumbing",
            "building": "Apex Block",
            "room": "AB-3F-Washroom",
            "title": "Apex Block 3rd floor washroom tap leaking",
            "description": "Continuous water leakage causing floor flooding.",
            "report_count": 1,
            "urgency": "Medium",
            "status": "Resolved",
            "resolution_note": "Washer replaced by facilities plumbing crew.",
            "escalated": 0,
            "first_reported": now - 14400000,
            "last_reported": now - 14400000,
            "reporters": ["Galaxy_A54_Student"]
        }
    ]

    for r in reps:
        cursor.execute("""
            INSERT INTO issue_reports (
                id, campus_id, cluster_key, category, building, room,
                title, description, urgency, report_count, status,
                resolution_note, escalated, first_reported, last_reported, reporters_json
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """, (
            r["id"], campus_id, r["cluster_key"], r["category"],
            r["building"], r["room"], r["title"], r["description"],
            r["urgency"], r["report_count"], r["status"],
            r["resolution_note"], r["escalated"], r["first_reported"],
            r["last_reported"], json.dumps(r["reporters"])
        ))

def seed_default_timetables(conn):
    cursor = conn.cursor()
    campus_id = "MSRIT"
    now = int(time.time() * 1000)
    default_slots = [
        # Semester 1 - CSE - Section A
        ("tt_m1_1", campus_id, 1, "Computer Science & Engineering", "A", "MONDAY", 1, "09:00", "10:00", "MAT11", "Engineering Mathematics I", "Dr. Sharma", "AB-501", "Apex Block", 0, now),
        ("tt_m1_2", campus_id, 1, "Computer Science & Engineering", "A", "MONDAY", 2, "10:00", "11:00", "PYC12", "Engineering Physics", "Dr. Geetha", "AB-501", "Apex Block", 0, now),
        ("tt_m1_3", campus_id, 1, "Computer Science & Engineering", "A", "MONDAY", 3, "11:15", "12:15", "ELE13", "Basic Electrical Engineering", "Prof. Suresh", "AB-501", "Apex Block", 0, now),
        ("tt_m1_4", campus_id, 1, "Computer Science & Engineering", "A", "MONDAY", 4, "14:00", "16:00", "MELC19", "CAD / Mechanical Lab", "Prof. Rajesh", "Room 302", "Mechanical Block", 1, now),
        ("tt_m1_5", campus_id, 1, "Computer Science & Engineering", "A", "TUESDAY", 1, "09:00", "10:00", "MAT11", "Engineering Mathematics I", "Dr. Sharma", "AB-501", "Apex Block", 0, now),
        ("tt_m1_6", campus_id, 1, "Computer Science & Engineering", "A", "TUESDAY", 2, "10:00", "11:00", "PYC12", "Engineering Physics", "Dr. Geetha", "AB-501", "Apex Block", 0, now),

        # Semester 1 - ISE - Section D
        ("tt_m1_d1", campus_id, 1, "Information Science & Engineering", "D", "MONDAY", 1, "09:00", "10:00", "PYC12", "Engineering Physics", "Dr. Ramesh", "AB-504", "Apex Block", 0, now),
        ("tt_m1_d2", campus_id, 1, "Information Science & Engineering", "D", "MONDAY", 2, "10:00", "11:00", "MAT11", "Engineering Mathematics I", "Dr. Anita", "AB-504", "Apex Block", 0, now),
        ("tt_m1_d3", campus_id, 1, "Information Science & Engineering", "D", "TUESDAY", 3, "14:00", "16:00", "PSCLCS18", "C Programming Lab", "Prof. Meera", "Room 703", "Apex Block", 1, now),

        # Semester 1 - AIML - Section I
        ("tt_m1_i1", campus_id, 1, "Artificial Intelligence & Machine Learning", "I", "MONDAY", 1, "09:00", "10:00", "ELE13", "Basic Electrical Engineering", "Dr. Ravindra B.", "AB-514", "Apex Block", 0, now),
        ("tt_m1_i2", campus_id, 1, "Artificial Intelligence & Machine Learning", "I", "MONDAY", 2, "10:00", "11:00", "PYC12", "Engineering Physics", "Dr. Geetha", "AB-514", "Apex Block", 0, now),
        ("tt_m1_i3", campus_id, 1, "Artificial Intelligence & Machine Learning", "I", "MONDAY", 3, "11:15", "12:15", "MAT11", "Engineering Mathematics I", "Dr. Sharma", "AB-514", "Apex Block", 0, now),
        ("tt_m1_i4", campus_id, 1, "Artificial Intelligence & Machine Learning", "I", "MONDAY", 4, "14:00", "16:00", "HSCP15", "Soft Skills Lab", "Prof. David", "Room 212", "Lecture Hall Complex", 1, now),

        # Semester 3 - CSE - Section A
        ("tt_m3_1", campus_id, 3, "Computer Science & Engineering", "A", "MONDAY", 1, "09:00", "10:00", "CS301", "Data Structures & Algorithms", "Dr. B. Prasad", "AB-301", "Apex Block", 0, now),
        ("tt_m3_2", campus_id, 3, "Computer Science & Engineering", "A", "MONDAY", 2, "10:00", "11:00", "CS302", "Digital Logic & System Design", "Prof. Aruna", "AB-301", "Apex Block", 0, now),
        ("tt_m3_3", campus_id, 3, "Computer Science & Engineering", "A", "TUESDAY", 3, "14:00", "16:00", "CSL37", "Data Structures Lab", "Dr. Prasad", "Room 602", "Apex Block", 1, now),

        # Semester 5 - CSE - Section A
        ("tt_m5_1", campus_id, 5, "Computer Science & Engineering", "A", "MONDAY", 1, "09:00", "10:00", "CS501", "Database Management Systems", "Dr. K. Swamy", "AB-501", "Apex Block", 0, now),
        ("tt_m5_2", campus_id, 5, "Computer Science & Engineering", "A", "MONDAY", 2, "10:00", "11:00", "CS502", "Operating Systems", "Dr. Veena", "AB-501", "Apex Block", 0, now),
        ("tt_m5_3", campus_id, 5, "Computer Science & Engineering", "A", "WEDNESDAY", 4, "14:00", "16:00", "CSL58", "DBMS Lab", "Dr. Swamy", "Room 604", "Apex Block", 1, now),

        # Semester 7 - CSE - Section A
        ("tt_m7_1", campus_id, 7, "Computer Science & Engineering", "A", "MONDAY", 1, "09:00", "10:00", "CS701", "Machine Learning & AI", "Dr. N. Chandran", "AB-701", "Apex Block", 0, now),
        ("tt_m7_2", campus_id, 7, "Computer Science & Engineering", "A", "MONDAY", 2, "10:00", "11:00", "CS702", "Cloud Computing & DevOps", "Prof. Harish", "AB-701", "Apex Block", 0, now),
    ]
    cursor.executemany("""
        INSERT OR REPLACE INTO college_timetables
        (id, campus_id, semester, department, section_id, day_of_week, period, start_time, end_time, course_code, course_name, instructor, room_id, building_name, is_lab, created_at)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
    """, default_slots)
    conn.commit()
    print(f"[INFO] Seeded {len(default_slots)} default master timetable slots for MSRIT.")

# ==============================================================================
# REPORT CLUSTERING & DEDUPLICATION (PRESERVED & ENHANCED)
# ==============================================================================

def normalize_cluster_key(report):
    """Clusters similar student reports into one unified maintenance item."""
    bldg = (report.get("building") or "").strip().lower()
    room = (report.get("room") or "").strip().lower()
    cat = (report.get("category") or "").strip().lower()
    desc = (report.get("description") or "").strip().lower()

    subject = "general"
    if "fan" in desc: subject = "fan"
    elif any(k in desc for k in ["tap", "leak", "pipe", "water", "flush", "sink"]): subject = "plumbing"
    elif any(k in desc for k in ["light", "bulb", "switch", "spark"]): subject = "lighting"
    elif any(k in desc for k in ["ac", "cooler", "air conditioner"]): subject = "ac_cooling"
    elif "projector" in desc: subject = "projector"
    elif any(k in desc for k in ["bench", "chair", "desk", "table"]): subject = "furniture"

    if "hostel" in bldg or "mess" in room or "hostel" in room or "mess" in desc: loc = "hostel_mess"
    elif "402" in room or "402" in desc: loc = "esb_402"
    elif "212" in room or "212" in desc: loc = "lhc_212"
    elif "602" in room or "602" in desc: loc = "ab_602"
    elif "703" in room or "703" in desc: loc = "ab_703"
    elif "501" in room: loc = "ab_501"
    elif "504" in room: loc = "ab_504"
    elif "514" in room: loc = "ab_514"
    elif "canteen" in bldg or "canteen" in room or "canteen" in desc: loc = "main_canteen"
    elif "library" in bldg or "library" in room or "library" in desc: loc = "library"
    else: loc = f"{bldg[:10]}_{room[:10]}".replace(" ", "_")

    return f"{loc}_{cat}_{subject}"

def ingest_raw_reports_db(campus_id, incoming_reports, device_id):
    added_new = 0
    updated_existing = 0
    now = int(time.time() * 1000)

    with get_db() as conn:
        cursor = conn.cursor()
        for rep in incoming_reports:
            key = normalize_cluster_key(rep)
            cursor.execute(
                "SELECT * FROM issue_reports WHERE campus_id = ? AND cluster_key = ? AND status != 'Resolved'",
                (campus_id, key)
            )
            row = cursor.fetchone()
            if row:
                try:
                    reporters = json.loads(row["reporters_json"])
                except Exception:
                    reporters = []
                if device_id not in reporters:
                    reporters.append(device_id)
                count = len(set(reporters))

                escalated = 1 if count >= 3 else row["escalated"]
                if count >= 3:
                    urgency = f"CRITICAL (Escalated to Head of Maintenance - {count} Reports)"
                elif count >= 2:
                    urgency = f"High ({count} Reports)"
                else:
                    urgency = row["urgency"]

                cursor.execute("""
                    UPDATE issue_reports
                    SET report_count = ?, last_reported = ?, reporters_json = ?, urgency = ?, escalated = ?
                    WHERE id = ?
                """, (count, now, json.dumps(reporters), urgency, escalated, row["id"]))
                updated_existing += 1
            else:
                new_id = f"cluster_{now}_{uuid.uuid4().hex[:6]}"
                category = rep.get("category", "General")
                bldg = rep.get("building", "Campus")
                room = rep.get("room", "General Area")
                desc = rep.get("description", "Reported by student voice companion")
                title = f"{category} issue in {room}"
                urgency = rep.get("urgency", "Medium")

                cursor.execute("""
                    INSERT INTO issue_reports (
                        id, campus_id, cluster_key, category, building, room,
                        title, description, urgency, report_count, status,
                        resolution_note, escalated, first_reported, last_reported, reporters_json
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 1, 'Open', '', 0, ?, ?, ?)
                """, (
                    new_id, campus_id, key, category, bldg, room,
                    title, desc, urgency, now, now, json.dumps([device_id])
                ))
                added_new += 1
        conn.commit()
    return added_new, updated_existing

# ==============================================================================
# CAMPUS PACKAGE COMPILATION (.prismpack)
# ==============================================================================

def compile_campus_pack(campus_id):
    """Assembles the full .prismpack JSON representation from SQLite for a campus."""
    with get_db() as conn:
        cursor = conn.cursor()
        cursor.execute("SELECT * FROM campuses WHERE campus_id = ?", (campus_id,))
        c_row = cursor.fetchone()
        if not c_row:
            return None

        # Buildings & Rooms
        cursor.execute("SELECT * FROM buildings WHERE campus_id = ?", (campus_id,))
        buildings_list = []
        for b in cursor.fetchall():
            cursor.execute("SELECT * FROM rooms WHERE campus_id = ? AND building_code = ?", (campus_id, b["code"]))
            rooms_list = []
            for r in cursor.fetchall():
                rooms_list.append({
                    "room_id": r["room_id"],
                    "name": r["name"],
                    "floor": r["floor"],
                    "floor_label": r["floor_label"],
                    "building_code": r["building_code"],
                    "category": r["category"],
                    "assigned_section": r["assigned_section"],
                    "assigned_course": r["assigned_course"],
                    "department": r["department"],
                    "description": r["description"]
                })
            buildings_list.append({
                "id": b["id"],
                "code": b["code"],
                "name": b["name"],
                "latitude": b["latitude"],
                "longitude": b["longitude"],
                "total_floors": b["total_floors"],
                "description": b["description"],
                "rooms": rooms_list
            })

        # Sections
        cursor.execute("SELECT * FROM sections WHERE campus_id = ?", (campus_id,))
        sections_list = []
        for s in cursor.fetchall():
            try:
                sched = json.loads(s["schedule_json"])
            except Exception:
                sched = {}
            sections_list.append({
                "section_id": s["section_id"],
                "name": s["name"],
                "department": s["department"],
                "default_room_id": s["default_room_id"],
                "default_building_code": s["default_building_code"],
                "default_floor": s["default_floor"],
                "schedule": sched
            })

        # Lab routing rules
        cursor.execute("SELECT * FROM lab_routing_rules WHERE campus_id = ?", (campus_id,))
        rules_list = []
        for r in cursor.fetchall():
            rules_list.append({
                "course_code": r["course_code"],
                "building_code": r["building_code"],
                "building_name": r["building_name"],
                "room_id": r["room_id"],
                "floor": r["floor"],
                "floor_label": r["floor_label"],
                "description": r["description"]
            })

        pack = {
            "pack_version": c_row["pack_version"],
            "generated_at": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime(c_row["updated_at"] / 1000)),
            "campus_id": c_row["campus_id"],
            "campus_name": c_row["college_name"],
            "short_name": c_row["short_name"],
            "website": c_row["website"],
            "city": c_row["city"],
            "country": c_row["country"],
            "address": c_row["address"],
            "description": c_row["description"],
            "base_location": {
                "name": c_row["base_name"],
                "latitude": c_row["base_lat"],
                "longitude": c_row["base_lng"],
                "description": c_row["base_desc"]
            },
            "buildings": buildings_list,
            "sections": sections_list,
            "lab_routing_rules": rules_list
        }
        return pack

def get_lan_ip():
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.connect(("8.8.8.8", 80))
        ip = s.getsockname()[0]
        s.close()
        return ip
    except Exception:
        return "192.168.1.8"

SERVER_LAN_IP = get_lan_ip()

# ==============================================================================
# AUTHENTICATION HELPERS
# ==============================================================================

def authenticate_session(headers):
    auth_header = headers.get("Authorization", "")
    token = None
    if auth_header.startswith("Bearer "):
        token = auth_header.split(" ", 1)[1].strip()
    elif "Cookie" in headers:
        cookies = headers["Cookie"].split(";")
        for c in cookies:
            if "prism_token=" in c:
                token = c.split("prism_token=")[1].strip()

    if not token:
        return None

    with get_db() as conn:
        cursor = conn.cursor()
        cursor.execute("SELECT * FROM sessions WHERE token = ? AND expires_at > ?", (token, int(time.time() * 1000)))
        return cursor.fetchone()

# ==============================================================================
# EMBEDDED ADMIN DASHBOARD SINGLE-PAGE APPLICATION (HTML, CSS, JS)
# ==============================================================================

HTML_DASHBOARD = """<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="UTF-8">
  <meta name="viewport" content="width=device-width, initial-scale=1.0">
  <title>PrismCampus | Announcements & Class Updates</title>
  <script src="/qrcode.js"></script>
  <style>
    :root {
      --bg: #070B14;
      --surface: #0E1626;
      --surface-variant: #152238;
      --border: #1E3150;
      --text: #F1F5F9;
      --text-muted: #94A3B8;
      --emerald: #00F0A8;
      --emerald-dark: #059669;
      --blue: #38BDF8;
      --amber: #FBBF24;
      --crimson: #F43F5E;
      --violet: #A855F7;
    }
    * { box-sizing: border-box; margin: 0; padding: 0; }
    body {
      background: var(--bg);
      color: var(--text);
      font-family: 'Inter', -apple-system, sans-serif;
      padding: 20px;
      line-height: 1.5;
    }
    .container { max-width: 1320px; margin: 0 auto; }
    
    header {
      display: flex;
      justify-content: space-between;
      align-items: center;
      padding: 18px 24px;
      background: var(--surface);
      border: 1px solid var(--border);
      border-radius: 14px;
      margin-bottom: 20px;
      flex-wrap: wrap;
      gap: 16px;
    }
    .logo-group h1 {
      font-size: 22px;
      font-weight: 800;
      letter-spacing: -0.5px;
      background: linear-gradient(135deg, #FFF, var(--emerald));
      -webkit-background-clip: text;
      -webkit-text-fill-color: transparent;
    }
    .logo-group p {
      color: var(--text-muted);
      font-size: 13px;
      margin-top: 2px;
    }
    .header-actions {
      display: flex;
      align-items: center;
      gap: 12px;
      flex-wrap: wrap;
    }
    .node-badge {
      display: flex;
      align-items: center;
      gap: 8px;
      background: rgba(0, 240, 168, 0.08);
      border: 1px solid var(--emerald);
      padding: 6px 14px;
      border-radius: 30px;
      font-family: 'JetBrains Mono', monospace;
      font-size: 12px;
      color: var(--emerald);
    }
    .pulse-dot {
      width: 8px;
      height: 8px;
      background: var(--emerald);
      border-radius: 50%;
      box-shadow: 0 0 10px var(--emerald);
      animation: pulse 1.5s infinite;
    }
    @keyframes pulse { 0%, 100% { opacity: 1; transform: scale(1); } 50% { opacity: 0.4; transform: scale(0.8); } }

    /* Nav Tabs */
    .nav-tabs {
      display: flex;
      gap: 8px;
      border-bottom: 1px solid var(--border);
      margin-bottom: 20px;
      overflow-x: auto;
      padding-bottom: 6px;
    }
    .tab-btn {
      background: transparent;
      border: 1px solid transparent;
      color: var(--text-muted);
      padding: 10px 18px;
      border-radius: 8px;
      font-size: 13px;
      font-weight: 600;
      cursor: pointer;
      display: flex;
      align-items: center;
      gap: 8px;
      transition: all 0.2s ease;
      white-space: nowrap;
    }
    .tab-btn:hover {
      background: var(--surface-variant);
      color: var(--text);
    }
    .tab-btn.active {
      background: var(--surface);
      border-color: var(--emerald);
      color: var(--emerald);
    }

    /* Stats Grid */
    .stats-grid {
      display: grid;
      grid-template-columns: repeat(auto-fit, minmax(210px, 1fr));
      gap: 14px;
      margin-bottom: 20px;
    }
    .stat-card {
      background: var(--surface);
      border: 1px solid var(--border);
      border-radius: 12px;
      padding: 16px;
    }
    .stat-label {
      color: var(--text-muted);
      font-size: 11px;
      text-transform: uppercase;
      letter-spacing: 0.5px;
      font-weight: 600;
    }
    .stat-val {
      font-size: 26px;
      font-weight: 800;
      margin-top: 6px;
      font-family: 'JetBrains Mono', monospace;
      color: var(--text);
    }

    /* Cards & Panels */
    .panel {
      background: var(--surface);
      border: 1px solid var(--border);
      border-radius: 14px;
      padding: 22px;
      margin-bottom: 22px;
    }
    .panel-header {
      display: flex;
      justify-content: space-between;
      align-items: center;
      margin-bottom: 16px;
      flex-wrap: wrap;
      gap: 10px;
    }
    .panel-title {
      font-size: 16px;
      font-weight: 700;
      color: var(--text);
      display: flex;
      align-items: center;
      gap: 8px;
    }

    /* Form Inputs */
    .form-grid {
      display: grid;
      grid-template-columns: repeat(auto-fit, minmax(240px, 1fr));
      gap: 14px;
      margin-bottom: 14px;
    }
    .form-group {
      display: flex;
      flex-direction: column;
      gap: 6px;
    }
    label {
      font-size: 12px;
      font-weight: 600;
      color: var(--text-muted);
    }
    input, select, textarea {
      background: var(--bg);
      border: 1px solid var(--border);
      color: var(--text);
      padding: 10px 14px;
      border-radius: 8px;
      font-size: 13px;
      font-family: inherit;
    }
    input:focus, select:focus, textarea:focus {
      outline: none;
      border-color: var(--emerald);
    }

    /* Buttons */
    .btn {
      background: var(--emerald-dark);
      color: #FFF;
      border: none;
      padding: 10px 18px;
      border-radius: 8px;
      font-size: 13px;
      font-weight: 700;
      cursor: pointer;
      display: inline-flex;
      align-items: center;
      gap: 6px;
      transition: background 0.15s ease;
    }
    .btn:hover { background: #047857; }
    .btn-blue { background: #0284C7; }
    .btn-blue:hover { background: #0369A1; }
    .btn-amber { background: #D97706; color: #FFF; }
    .btn-amber:hover { background: #B45309; }
    .btn-crimson { background: #E11D48; color: #FFF; }
    .btn-crimson:hover { background: #BE123C; }
    .btn-outline {
      background: transparent;
      border: 1px solid var(--border);
      color: var(--text);
    }
    .btn-outline:hover { background: var(--surface-variant); }

    /* Tables & Lists */
    .item-list {
      display: flex;
      flex-direction: column;
      gap: 10px;
    }
    .list-item {
      background: var(--bg);
      border: 1px solid var(--border);
      border-radius: 10px;
      padding: 14px 18px;
      display: flex;
      justify-content: space-between;
      align-items: center;
      gap: 12px;
      transition: border-color 0.15s ease;
    }
    .list-item:hover { border-color: rgba(56, 189, 248, 0.4); }
    .list-item.escalated {
      border-color: var(--crimson);
      background: rgba(244, 63, 94, 0.05);
    }
    .item-main { flex: 1; }
    .item-header {
      display: flex;
      align-items: center;
      gap: 8px;
      margin-bottom: 4px;
      flex-wrap: wrap;
    }
    .item-desc {
      color: var(--text-muted);
      font-size: 13px;
    }
    .item-meta {
      font-size: 11px;
      color: #64748B;
      font-family: 'JetBrains Mono', monospace;
      margin-top: 4px;
    }

    /* Badges */
    .tag {
      padding: 3px 8px;
      border-radius: 4px;
      font-size: 11px;
      font-weight: 700;
      font-family: 'JetBrains Mono', monospace;
    }
    .tag-emerald { background: rgba(0, 240, 168, 0.15); color: var(--emerald); }
    .tag-blue { background: rgba(56, 189, 248, 0.15); color: var(--blue); }
    .tag-amber { background: rgba(251, 191, 36, 0.15); color: var(--amber); }
    .tag-crimson { background: rgba(244, 63, 94, 0.2); color: var(--crimson); }
    .tag-rank { background: #334155; color: #F8FAFC; }

    /* Modal */
    .modal-backdrop {
      position: fixed;
      inset: 0;
      background: rgba(0, 0, 0, 0.75);
      display: none;
      justify-content: center;
      align-items: center;
      z-index: 1000;
      padding: 16px;
    }
    .modal-box {
      background: var(--surface);
      border: 1px solid var(--border);
      border-radius: 14px;
      width: 100%;
      max-width: 600px;
      padding: 24px;
      max-height: 90vh;
      overflow-y: auto;
    }
    .modal-footer {
      display: flex;
      justify-content: flex-end;
      gap: 10px;
      margin-top: 20px;
    }

    /* Notifications & Alert */
    .alert-banner {
      background: rgba(56, 189, 248, 0.1);
      border: 1px solid var(--blue);
      border-radius: 8px;
      padding: 12px 16px;
      margin-bottom: 16px;
      font-size: 13px;
      display: flex;
      justify-content: space-between;
      align-items: center;
    }

    footer {
      text-align: center;
      padding: 30px;
      color: #64748B;
      font-size: 12px;
      font-family: 'JetBrains Mono', monospace;
    }
  </style>
</head>
<body>
  <div class="container">
    <header>
      <div class="logo-group"><a href="/" style="color:var(--blue);font-size:12px;">← Campus Studio</a>
        <h1>Announcements & Class Updates</h1>
        <p>Announcements, classroom changes, timetables and campus facilities</p>
      </div>
      <div class="header-actions">
        <!-- Campus Selector -->
        <div style="display:flex; align-items:center; gap:8px;">
          <label style="color:var(--text); font-weight:700;">Campus:</label>
          <select id="campus-select" onchange="onCampusChange()" style="padding:6px 12px; font-weight:700; color:var(--emerald);">
            <!-- Populated dynamically -->
          </select>
        </div>
        <div class="node-badge">
          <div class="pulse-dot"></div>
          <span id="node-status-text">UNO_Q_MSRIT_01 • ONLINE</span>
        </div>
        <button class="btn btn-outline" onclick="openPublishModal()" title="Publish official campus version">🚀 Publish</button>
      </div>
    </header>

    <!-- Nav Tabs -->
    <div class="nav-tabs">
      <button class="tab-btn active" onclick="switchTab('overview')">📊 Overview & QR Code</button>
      <button class="tab-btn" onclick="switchTab('timetables')">📅 College Timetables</button>
      <button class="tab-btn" onclick="switchTab('buildings')">🏛️ Buildings & Rooms</button>
      <button class="tab-btn" onclick="switchTab('announcements')">📢 Broadcast Notices</button>
      <button class="tab-btn" onclick="switchTab('overrides')">⚡ Classroom Overrides</button>
      <button class="tab-btn" onclick="switchTab('reports')">🔧 Civic Reports</button>
      <button class="tab-btn" onclick="switchTab('campus-info')">⚙️ Campus Settings</button>
      <button class="tab-btn" onclick="switchTab('import-export')">💾 Import / Export</button>
    </div>

    <!-- TAB 1: OVERVIEW & QR CODE -->
    <div id="tab-overview" class="tab-content">
      <div class="stats-grid">
        <div class="stat-card">
          <div class="stat-label">Active Campus ID</div>
          <div class="stat-val" id="stat-campus-id" style="color:var(--emerald);">MSRIT</div>
        </div>
        <div class="stat-card">
          <div class="stat-label">Master Timetable Slots</div>
          <div class="stat-val" id="stat-timetables" style="color:var(--blue);">0</div>
        </div>
        <div class="stat-card">
          <div class="stat-label">Campus Buildings</div>
          <div class="stat-val" id="stat-buildings">0</div>
        </div>
        <div class="stat-card">
          <div class="stat-label">Total Rooms / Labs</div>
          <div class="stat-val" id="stat-rooms">0</div>
        </div>
        <div class="stat-card">
          <div class="stat-label">Active Overrides</div>
          <div class="stat-val" id="stat-overrides" style="color:var(--amber);">0</div>
        </div>
        <div class="stat-card">
          <div class="stat-label">Unresolved Civic Issues</div>
          <div class="stat-val" id="stat-reports" style="color:var(--crimson);">0</div>
        </div>
      </div>

      <div style="display:grid; grid-template-columns: repeat(auto-fit, minmax(360px, 1fr)); gap:20px;">
        <!-- Left: Quick QR Onboarding Card -->
        <div class="panel">
          <div class="panel-header">
            <span class="panel-title">📱 Student 1-Tap QR Onboarding</span>
            <span class="tag tag-emerald">Zero-Cloud Wi-Fi</span>
          </div>
          <p style="color:var(--text-muted); font-size:13px; margin-bottom:16px;">
            Students connected to campus Wi-Fi can enter the Campus ID below or scan this QR code to load timetables, maps, and offline AI pack.
          </p>
          <div style="display:flex; justify-content:center; align-items:center; background:#FFF; padding:16px; border-radius:12px; max-width:240px; margin:0 auto 16px auto;">
            <canvas id="qr-canvas"></canvas>
          </div>
          <div style="text-align:center;">
            <p style="font-family:'JetBrains Mono', monospace; font-size:15px; font-weight:700; color:var(--emerald);">
              Campus ID: <span id="qr-campus-id-label">MSRIT</span>
            </p>
            <p style="font-size:12px; color:var(--text-muted); margin-top:4px;">
              Server Endpoint: <span id="qr-server-endpoint">http://192.168.1.8:8000</span>
            </p>
          </div>
        </div>

        <!-- Right: Recent Activity / Live Telemetry -->
        <div class="panel">
          <div class="panel-header">
            <span class="panel-title">⚡ Local Edge Synchronization Status</span>
            <span class="tag tag-blue" id="data-version-tag">Version 1.0.0</span>
          </div>
          <div class="alert-banner">
            <div>
              <b>Uno Q Local Server Host:</b> <span id="lan-ip-display">http://192.168.1.8:8000</span><br>
              <span style="font-size:11px; color:var(--text-muted);">Serving published campus bundle, live timetable shifts, and civic mesh sync without external internet.</span>
            </div>
          </div>
          <div style="display:flex; flex-direction:column; gap:10px;">
            <div style="display:flex; justify-content:space-between; padding:10px 14px; background:var(--bg); border-radius:8px; font-size:13px;">
              <span>Database Storage</span>
              <span style="font-family:'JetBrains Mono', monospace; color:var(--emerald);">SQLite (server/prism_campus.db)</span>
            </div>
            <div style="display:flex; justify-content:space-between; padding:10px 14px; background:var(--bg); border-radius:8px; font-size:13px;">
              <span>Campus Publish State</span>
              <span id="published-status-badge" class="tag tag-emerald">Published & Live</span>
            </div>
            <div style="display:flex; justify-content:space-between; padding:10px 14px; background:var(--bg); border-radius:8px; font-size:13px;">
              <span>Local Wi-Fi Network</span>
              <span style="font-family:'JetBrains Mono', monospace; color:var(--blue);">Connected (Port 8000)</span>
            </div>
          </div>
        </div>
      </div>
    </div>

    <!-- TAB: COLLEGE TIMETABLES -->
    <div id="tab-timetables" class="tab-content" style="display:none;">
      <div class="panel">
        <div class="panel-header">
          <div>
            <span class="panel-title">📅 College-Wide Master Timetable Repository</span>
            <div style="font-size:12px; color:var(--text-muted); margin-top:2px;">
              Manage and store academic timetables for the entire college across all semesters (1–8), engineering departments, sections, and days.
            </div>
          </div>
          <div style="display:flex; gap:8px;">
            <button class="btn btn-outline" onclick="handleSeedTimetables()">⚡ Seed Default Timetables</button>
            <button class="btn btn-emerald" onclick="openAddTimetableModal()">+ Add Class Slot</button>
          </div>
        </div>

        <!-- Filters -->
        <div class="form-grid" style="margin-bottom:16px; background:var(--surface); padding:14px; border-radius:10px; border:1px solid var(--border);">
          <div class="form-group">
            <label>Semester</label>
            <select id="tt-filter-sem" onchange="fetchTimetables()">
              <option value="all">All Semesters (1–8)</option>
              <option value="1">1st Semester</option>
              <option value="2">2nd Semester</option>
              <option value="3">3rd Semester</option>
              <option value="4">4th Semester</option>
              <option value="5">5th Semester</option>
              <option value="6">6th Semester</option>
              <option value="7">7th Semester</option>
              <option value="8">8th Semester</option>
            </select>
          </div>
          <div class="form-group">
            <label>Department</label>
            <select id="tt-filter-dept" onchange="fetchTimetables()">
              <option value="all">All Departments</option>
              <option value="Computer Science & Engineering">Computer Science & Engineering (CSE)</option>
              <option value="Information Science & Engineering">Information Science & Engineering (ISE)</option>
              <option value="Artificial Intelligence & Machine Learning">Artificial Intelligence & Machine Learning (AIML)</option>
              <option value="Electronics & Communication">Electronics & Communication (ECE)</option>
              <option value="Mechanical Engineering">Mechanical Engineering</option>
            </select>
          </div>
          <div class="form-group">
            <label>Section</label>
            <select id="tt-filter-sec" onchange="fetchTimetables()">
              <option value="all">All Sections</option>
              <option value="A">Section A</option>
              <option value="B">Section B</option>
              <option value="C">Section C</option>
              <option value="D">Section D</option>
              <option value="E">Section E</option>
              <option value="F">Section F</option>
              <option value="G">Section G</option>
              <option value="H">Section H</option>
              <option value="I">Section I</option>
            </select>
          </div>
          <div class="form-group">
            <label>Day of Week</label>
            <select id="tt-filter-day" onchange="fetchTimetables()">
              <option value="all">All Days</option>
              <option value="MONDAY">Monday</option>
              <option value="TUESDAY">Tuesday</option>
              <option value="WEDNESDAY">Wednesday</option>
              <option value="THURSDAY">Thursday</option>
              <option value="FRIDAY">Friday</option>
              <option value="SATURDAY">Saturday</option>
            </select>
          </div>
        </div>

        <div style="display:flex; justify-content:space-between; align-items:center; margin-bottom:12px;">
          <span id="tt-count-badge" class="tag tag-emerald" style="font-size:12px; padding:4px 10px;">0 Class Slots Configured</span>
        </div>

        <!-- Master Timetable Table -->
        <div style="overflow-x:auto;">
          <table style="width:100%; border-collapse:collapse; font-size:13px; text-align:left;">
            <thead>
              <tr style="border-bottom:2px solid var(--border); color:var(--text-muted);">
                <th style="padding:10px 8px;">Sem</th>
                <th style="padding:10px 8px;">Sec</th>
                <th style="padding:10px 8px;">Department</th>
                <th style="padding:10px 8px;">Day</th>
                <th style="padding:10px 8px;">Time</th>
                <th style="padding:10px 8px;">Course</th>
                <th style="padding:10px 8px;">Title</th>
                <th style="padding:10px 8px;">Room & Building</th>
                <th style="padding:10px 8px;">Faculty</th>
                <th style="padding:10px 8px;">Type</th>
                <th style="padding:10px 8px;">Action</th>
              </tr>
            </thead>
            <tbody id="timetables-tbody">
              <!-- Populated dynamically -->
            </tbody>
          </table>
        </div>
      </div>
    </div>

    <!-- TAB 2: BUILDINGS & ROOMS -->
    <div id="tab-buildings" class="tab-content" style="display:none;">
      <div class="panel">
        <div class="panel-header">
          <span class="panel-title">🏛️ Campus Architecture (Hierarchy: Campus ➜ Buildings ➜ Floors ➜ Rooms)</span>
          <div style="display:flex; gap:10px;">
            <button class="btn btn-outline" onclick="openAddBuildingModal()">+ Add Building</button>
            <button class="btn" onclick="openAddRoomModal()">+ Add Room / Lab</button>
          </div>
        </div>
        <p style="color:var(--text-muted); font-size:13px; margin-bottom:16px;">
          Manage all academic blocks, lecture halls, laboratories, washrooms, and faculty spaces.
        </p>

        <div id="buildings-tree-container" class="item-list">
          <!-- Populated dynamically -->
        </div>
      </div>
    </div>

    <!-- TAB 3: ANNOUNCEMENTS -->
    <div id="tab-announcements" class="tab-content" style="display:none;">
      <div class="panel">
        <div class="panel-header">
          <span class="panel-title">📢 Broadcast Campus Announcement</span>
          <span class="tag tag-blue">Pushed to Student App</span>
        </div>
        <form onsubmit="handleCreateAnnouncement(event)">
          <div class="form-grid">
            <div class="form-group" style="grid-column: span 2;">
              <label>Announcement Title</label>
              <input type="text" id="anno-title" placeholder="e.g. Cultural Fest 'Udbhav 2026' Registrations Opened" required>
            </div>
            <div class="form-group">
              <label>Priority</label>
              <select id="anno-priority">
                <option value="Important">Important</option>
                <option value="General">General</option>
                <option value="Emergency">Emergency Alert</option>
              </select>
            </div>
            <div class="form-group">
              <label>Audience</label>
              <input type="text" id="anno-audience" value="All Students & Faculty">
            </div>
          </div>
          <div class="form-group" style="margin-bottom:14px;">
            <label>Message Content</label>
            <textarea id="anno-content" rows="3" placeholder="Enter message text broadcasted to students over local Wi-Fi..." required></textarea>
          </div>
          <button type="submit" class="btn">Broadcast Announcement ➜</button>
        </form>
      </div>

      <div class="panel">
        <div class="panel-header">
          <span class="panel-title">Active Campus Notices</span>
          <span class="tag tag-emerald" id="active-anno-count">0 Notices</span>
        </div>
        <div id="announcements-list-container" class="item-list">
          <!-- Populated dynamically -->
        </div>
      </div>
    </div>

    <!-- TAB 4: OVERRIDES -->
    <div id="tab-overrides" class="tab-content" style="display:none;">
      <div class="panel">
        <div class="panel-header">
          <span class="panel-title">⚡ Classroom Shift & Timetable Override</span>
          <span class="tag tag-amber">Overrides Base Timetable</span>
        </div>
        <p style="color:var(--text-muted); font-size:13px; margin-bottom:16px;">
          Direct classes to an alternate venue (e.g. for guest lectures, room repairs, or lab rescheduling). Takes precedence in student navigation without destroying original timetable.
        </p>

        <form onsubmit="handleCreateOverride(event)">
          <div class="form-grid">
            <div class="form-group">
              <label>Target Course Code</label>
              <input type="text" id="ov-course" placeholder="e.g. MAC11, CS501, CIV14" required>
            </div>
            <div class="form-group">
              <label>Target Section</label>
              <input type="text" id="ov-section" placeholder="e.g. A, D, I, or All" value="A">
            </div>
            <div class="form-group">
              <label>New Building</label>
              <select id="ov-bldg" onchange="onOverrideBuildingChange()">
                <!-- Populated dynamically -->
              </select>
            </div>
            <div class="form-group">
              <label>New Room ID</label>
              <input type="text" id="ov-room" placeholder="e.g. LHC Room 212" required>
            </div>
            <div class="form-group">
              <label>Floor Number</label>
              <input type="number" id="ov-floor" value="2" min="0" max="10">
            </div>
            <div class="form-group">
              <label>Floor Label</label>
              <input type="text" id="ov-floor-label" value="2nd Floor">
            </div>
          </div>
          <div class="form-group" style="margin-bottom:14px;">
            <label>Reason for Classroom Change</label>
            <input type="text" id="ov-reason" placeholder="e.g. Shifted to LHC Room 212 for guest lecture series (Dean Notice)" required>
          </div>
          <button type="submit" class="btn btn-amber">Publish Classroom Override ➜</button>
        </form>
      </div>

      <div class="panel">
        <div class="panel-header">
          <span class="panel-title">Active Timetable Overrides</span>
          <span class="tag tag-amber" id="active-ov-count">0 Active</span>
        </div>
        <div id="overrides-list-container" class="item-list">
          <!-- Populated dynamically -->
        </div>
      </div>
    </div>

    <!-- TAB 5: CIVIC REPORTS -->
    <div id="tab-reports" class="tab-content" style="display:none;">
      <div class="panel">
        <div class="panel-header">
          <span class="panel-title">🔧 Incident Command Center & Smart Issue Clusters</span>
          <div style="display:flex; gap:10px; align-items:center;">
            <select id="report-filter-status" onchange="renderReportsUI()" style="padding:6px 10px;">
              <option value="ALL">All Statuses</option>
              <option value="Open">Open</option>
              <option value="In Progress">In Progress</option>
              <option value="Resolved">Resolved</option>
            </select>
            <span class="tag tag-emerald" id="reports-badge">0 Reports</span>
          </div>
        </div>
        <p style="color:var(--text-muted); font-size:13px; margin-bottom:16px;">
          Student voice and app reports automatically cluster and rank by frequency. 3+ complaints auto-escalate to Critical.
        </p>

        <div id="reports-list-container" class="item-list">
          <!-- Populated dynamically -->
        </div>
      </div>
    </div>

    <!-- TAB 6: CAMPUS INFO / SETTINGS -->
    <div id="tab-campus-info" class="tab-content" style="display:none;">
      <div class="panel">
        <div class="panel-header">
          <span class="panel-title">⚙️ Campus Metadata & Anchor Configuration</span>
          <button class="btn btn-blue" onclick="openCreateCampusModal()">+ Create New Campus</button>
        </div>
        <form onsubmit="handleSaveCampusInfo(event)">
          <div class="form-grid">
            <div class="form-group">
              <label>College Name</label>
              <input type="text" id="ci-name" required>
            </div>
            <div class="form-group">
              <label>Campus ID (Unique identifier)</label>
              <input type="text" id="ci-id" readonly style="background:#0F172A; color:var(--emerald);">
            </div>
            <div class="form-group">
              <label>Short Name</label>
              <input type="text" id="ci-short" required>
            </div>
            <div class="form-group">
              <label>Website URL</label>
              <input type="url" id="ci-website" required>
            </div>
            <div class="form-group">
              <label>City</label>
              <input type="text" id="ci-city" required>
            </div>
            <div class="form-group">
              <label>Country</label>
              <input type="text" id="ci-country" required>
            </div>
            <div class="form-group">
              <label>Base Anchor Name (e.g. Main Gate)</label>
              <input type="text" id="ci-base-name" required>
            </div>
            <div class="form-group">
              <label>Base Latitude</label>
              <input type="number" step="0.000001" id="ci-base-lat" required>
            </div>
            <div class="form-group">
              <label>Base Longitude</label>
              <input type="number" step="0.000001" id="ci-base-lng" required>
            </div>
          </div>
          <div class="form-group" style="margin-bottom:14px;">
            <label>Address</label>
            <input type="text" id="ci-address" required>
          </div>
          <div class="form-group" style="margin-bottom:14px;">
            <label>Description</label>
            <textarea id="ci-desc" rows="2"></textarea>
          </div>
          <button type="submit" class="btn">Save Campus Details</button>
        </form>
      </div>
    </div>

    <!-- TAB 7: IMPORT / EXPORT -->
    <div id="tab-import-export" class="tab-content" style="display:none;">
      <div class="panel">
        <div class="panel-header">
          <span class="panel-title">💾 Backup, Export & Ingestion</span>
        </div>
        <p style="color:var(--text-muted); font-size:13px; margin-bottom:16px;">
          Export official .prismpack JSON bundle for this campus, or import a campus dataset with automated schema validation.
        </p>

        <div style="display:flex; gap:14px; flex-wrap:wrap; margin-bottom:20px;">
          <button class="btn btn-blue" onclick="handleExportPack()">⬇️ Export .prismpack JSON</button>
          <label class="btn btn-outline" style="cursor:pointer;">
            📁 Import JSON / CSV Pack
            <input type="file" id="import-file-input" accept=".json,.prismpack,.csv" onchange="handleImportFile(event)" style="display:none;">
          </label>
        </div>

        <div id="import-validation-box" style="display:none; padding:14px; border-radius:8px; font-size:13px;">
          <!-- Validation errors or success message -->
        </div>
      </div>
    </div>

    <!-- FOOTER -->
    <footer>
      PrismCampus Edge Operating System • Arduino Uno Q / Laptop Edge Server (0.0.0.0:8000) • Offline-First Multi-Campus Architecture
    </footer>
  </div>

  <!-- MODAL: ADD BUILDING -->
  <div id="modal-building" class="modal-backdrop">
    <div class="modal-box">
      <div class="panel-header">
        <span class="panel-title">Add / Edit Campus Building</span>
        <button class="btn btn-outline" onclick="closeModals()">✕</button>
      </div>
      <form onsubmit="handleSaveBuilding(event)">
        <div class="form-group" style="margin-bottom:10px;">
          <label>Building Code (e.g. AB, ESB, LHC)</label>
          <input type="text" id="mb-code" required>
        </div>
        <div class="form-group" style="margin-bottom:10px;">
          <label>Building Name</label>
          <input type="text" id="mb-name" required>
        </div>
        <div class="form-grid">
          <div class="form-group">
            <label>Latitude</label>
            <input type="number" step="0.000001" id="mb-lat" required>
          </div>
          <div class="form-group">
            <label>Longitude</label>
            <input type="number" step="0.000001" id="mb-lng" required>
          </div>
          <div class="form-group">
            <label>Total Floors</label>
            <input type="number" id="mb-floors" value="4" min="1" max="20" required>
          </div>
        </div>
        <div class="form-group" style="margin-bottom:16px;">
          <label>Description</label>
          <input type="text" id="mb-desc">
        </div>
        <div class="modal-footer">
          <button type="button" class="btn btn-outline" onclick="closeModals()">Cancel</button>
          <button type="submit" class="btn">Save Building</button>
        </div>
      </form>
    </div>
  </div>

  <!-- MODAL: ADD ROOM -->
  <div id="modal-room" class="modal-backdrop">
    <div class="modal-box">
      <div class="panel-header">
        <span class="panel-title">Add / Edit Room or Laboratory</span>
        <button class="btn btn-outline" onclick="closeModals()">✕</button>
      </div>
      <form onsubmit="handleSaveRoom(event)">
        <div class="form-grid">
          <div class="form-group">
            <label>Building</label>
            <select id="mr-bldg" required>
              <!-- Populated dynamically -->
            </select>
          </div>
          <div class="form-group">
            <label>Room Identifier (e.g. AB-501, Room 402)</label>
            <input type="text" id="mr-room-id" required>
          </div>
          <div class="form-group">
            <label>Room Display Name</label>
            <input type="text" id="mr-name" required>
          </div>
          <div class="form-group">
            <label>Floor Number</label>
            <input type="number" id="mr-floor" value="1" min="0" max="20" required>
          </div>
          <div class="form-group">
            <label>Floor Label</label>
            <input type="text" id="mr-floor-label" value="1st Floor" required>
          </div>
          <div class="form-group">
            <label>Category</label>
            <select id="mr-category">
              <option value="classroom">Classroom</option>
              <option value="laboratory">Laboratory</option>
              <option value="washroom">Washroom</option>
              <option value="library">Library</option>
              <option value="office">Faculty / Department Office</option>
              <option value="auditorium">Auditorium / Seminar Hall</option>
            </select>
          </div>
          <div class="form-group">
            <label>Assigned Section (Optional)</label>
            <input type="text" id="mr-sec" placeholder="e.g. A, D, I">
          </div>
          <div class="form-group">
            <label>Department</label>
            <input type="text" id="mr-dept" placeholder="e.g. Computer Science">
          </div>
        </div>
        <div class="form-group" style="margin-bottom:16px;">
          <label>Description</label>
          <input type="text" id="mr-desc" placeholder="e.g. CAD Workstation Lab with 60 terminals">
        </div>
        <div class="modal-footer">
          <button type="button" class="btn btn-outline" onclick="closeModals()">Cancel</button>
          <button type="submit" class="btn">Save Room</button>
        </div>
      </form>
    </div>
  </div>

  <!-- MODAL: CREATE CAMPUS -->
  <div id="modal-create-campus" class="modal-backdrop">
    <div class="modal-box">
      <div class="panel-header">
        <span class="panel-title">Create New Campus</span>
        <button class="btn btn-outline" onclick="closeModals()">✕</button>
      </div>
      <form onsubmit="handleCreateCampus(event)">
        <div class="form-group" style="margin-bottom:10px;">
          <label>Campus ID (Readable, e.g. BMSCE, RVCE, MSRIT)</label>
          <input type="text" id="mcc-id" placeholder="e.g. BMSCE" required style="font-family:'JetBrains Mono', monospace;">
        </div>
        <div class="form-group" style="margin-bottom:10px;">
          <label>College Full Name</label>
          <input type="text" id="mcc-name" placeholder="e.g. B.M.S. College of Engineering" required>
        </div>
        <div class="form-grid">
          <div class="form-group">
            <label>City</label>
            <input type="text" id="mcc-city" value="Bengaluru" required>
          </div>
          <div class="form-group">
            <label>Country</label>
            <input type="text" id="mcc-country" value="India" required>
          </div>
          <div class="form-group">
            <label>Main Gate Latitude</label>
            <input type="number" step="0.000001" id="mcc-lat" value="12.9416" required>
          </div>
          <div class="form-group">
            <label>Main Gate Longitude</label>
            <input type="number" step="0.000001" id="mcc-lng" value="77.5654" required>
          </div>
        </div>
        <div class="modal-footer">
          <button type="button" class="btn btn-outline" onclick="closeModals()">Cancel</button>
          <button type="submit" class="btn btn-blue">Create Campus</button>
        </div>
      </form>
    </div>
  </div>

  <!-- MODAL: RESOLVE ISSUE -->
  <div id="modal-resolve-issue" class="modal-backdrop">
    <div class="modal-box">
      <div class="panel-header">
        <span class="panel-title">Update Civic Report Status</span>
        <button class="btn btn-outline" onclick="closeModals()">✕</button>
      </div>
      <input type="hidden" id="mri-id">
      <div class="form-group" style="margin-bottom:12px;">
        <label>Status</label>
        <select id="mri-status">
          <option value="Open">Open</option>
          <option value="In Progress">In Progress</option>
          <option value="Resolved">Resolved</option>
        </select>
      </div>
      <div class="form-group" style="margin-bottom:16px;">
        <label>Administrative Resolution Note</label>
        <textarea id="mri-note" rows="3" placeholder="e.g. Repaired ceiling fan capacitor and secured mounting bracket."></textarea>
      </div>
      <div class="modal-footer">
        <button type="button" class="btn btn-outline" onclick="closeModals()">Cancel</button>
        <button type="button" class="btn btn-emerald" onclick="submitIssueStatusUpdate()">Save Status</button>
      </div>
    </div>
  </div>

  <!-- MODAL: ADD TIMETABLE SLOT -->
  <div id="modal-add-timetable" class="modal-backdrop">
    <div class="modal-box" style="max-width:600px;">
      <div class="panel-header">
        <span class="panel-title">Add Timetable Class Slot</span>
        <button class="btn btn-outline" onclick="closeModals()">✕</button>
      </div>
      <form onsubmit="handleAddTimetableSlot(event)">
        <div class="form-grid">
          <div class="form-group">
            <label>Semester (1–8)</label>
            <select id="mtt-sem" required>
              <option value="1">1st Semester</option>
              <option value="2">2nd Semester</option>
              <option value="3">3rd Semester</option>
              <option value="4">4th Semester</option>
              <option value="5">5th Semester</option>
              <option value="6">6th Semester</option>
              <option value="7">7th Semester</option>
              <option value="8">8th Semester</option>
            </select>
          </div>
          <div class="form-group">
            <label>Section (e.g. A, D, I)</label>
            <input type="text" id="mtt-sec" value="A" required style="font-family:'JetBrains Mono', monospace; text-transform:uppercase;">
          </div>
          <div class="form-group" style="grid-column: span 2;">
            <label>Department</label>
            <select id="mtt-dept" required>
              <option value="Computer Science & Engineering">Computer Science & Engineering</option>
              <option value="Information Science & Engineering">Information Science & Engineering</option>
              <option value="Artificial Intelligence & Machine Learning">Artificial Intelligence & Machine Learning</option>
              <option value="Electronics & Communication">Electronics & Communication</option>
              <option value="Mechanical Engineering">Mechanical Engineering</option>
            </select>
          </div>
          <div class="form-group">
            <label>Day of Week</label>
            <select id="mtt-day" required>
              <option value="MONDAY">Monday</option>
              <option value="TUESDAY">Tuesday</option>
              <option value="WEDNESDAY">Wednesday</option>
              <option value="THURSDAY">Thursday</option>
              <option value="FRIDAY">Friday</option>
              <option value="SATURDAY">Saturday</option>
            </select>
          </div>
          <div class="form-group">
            <label>Time Slot</label>
            <div style="display:flex; gap:6px; align-items:center;">
              <input type="text" id="mtt-start" value="09:00" placeholder="09:00" required style="width:70px;">
              <span>to</span>
              <input type="text" id="mtt-end" value="10:00" placeholder="10:00" required style="width:70px;">
            </div>
          </div>
          <div class="form-group">
            <label>Course Code</label>
            <input type="text" id="mtt-code" placeholder="e.g. CS501" required style="font-family:'JetBrains Mono', monospace; text-transform:uppercase;">
          </div>
          <div class="form-group">
            <label>Course Name</label>
            <input type="text" id="mtt-name" placeholder="e.g. Database Management Systems" required>
          </div>
          <div class="form-group">
            <label>Room ID</label>
            <input type="text" id="mtt-room" value="AB-501" placeholder="e.g. AB-501" required>
          </div>
          <div class="form-group">
            <label>Building Name</label>
            <input type="text" id="mtt-bldg" value="Apex Block" placeholder="e.g. Apex Block" required>
          </div>
          <div class="form-group">
            <label>Faculty / Instructor</label>
            <input type="text" id="mtt-faculty" placeholder="e.g. Dr. K. Swamy">
          </div>
          <div class="form-group" style="display:flex; align-items:center; gap:8px; margin-top:24px;">
            <input type="checkbox" id="mtt-islab" style="width:18px; height:18px;">
            <label for="mtt-islab" style="cursor:pointer;">Lab / Practical Session</label>
          </div>
        </div>
        <div class="modal-footer">
          <button type="button" class="btn btn-outline" onclick="closeModals()">Cancel</button>
          <button type="submit" class="btn btn-emerald">Save Class Slot</button>
        </div>
      </form>
    </div>
  </div>

  <script>
    let activeCampusId = new URLSearchParams(location.search).get('campus') || localStorage.getItem('prism_admin_campus') || 'MSRIT';
    let currentCampusData = null;
    let allCampuses = [];
    let reportsData = [];
    let announcementsData = [];
    let overridesData = [];
    let timetablesData = [];

    // Initialize application
    window.addEventListener('DOMContentLoaded', async () => {
      await fetchCampusesList();
      await loadActiveCampus();
    });

    function switchTab(tabId) {
      document.querySelectorAll('.tab-content').forEach(el => el.style.display = 'none');
      document.querySelectorAll('.tab-btn').forEach(btn => btn.classList.remove('active'));
      const activeContent = document.getElementById(`tab-${tabId}`);
      if (activeContent) activeContent.style.display = 'block';
      const clickedBtn = Array.from(document.querySelectorAll('.tab-btn')).find(b => b.getAttribute('onclick')?.includes(tabId));
      if (clickedBtn) clickedBtn.classList.add('active');
      if (tabId === 'timetables') {
        fetchTimetables();
      }
    }

    async function fetchCampusesList() {
      try {
        const res = await fetch('/api/campuses');
        allCampuses = await res.json();
        const sel = document.getElementById('campus-select');
        sel.innerHTML = allCampuses.map(c => 
          `<option value="${escapeHtml(c.campus_id)}" ${c.campus_id === activeCampusId ? 'selected' : ''}>${escapeHtml(c.short_name)} (${escapeHtml(c.campus_id)})</option>`
        ).join('');
      } catch (e) {
        console.error("Failed to fetch campuses list", e);
      }
    }

    async function onCampusChange() {
      activeCampusId = document.getElementById('campus-select').value;
      localStorage.setItem('prism_admin_campus', activeCampusId);
      resetTimetableFilters();
      await loadActiveCampus();
    }

    async function loadActiveCampus() {
      try {
        const res = await fetch(`/api/campus/${activeCampusId}/package`);
        if (!res.ok) throw new Error("Campus not found");
        const loaded = await res.json();
        if (loaded.campus_id !== activeCampusId) return;
        currentCampusData = loaded;
        
        // Update stats
        document.getElementById('stat-campus-id').innerText = currentCampusData.campus_id || activeCampusId;
        document.getElementById('qr-campus-id-label').innerText = currentCampusData.campus_id || activeCampusId;
        document.getElementById('stat-buildings').innerText = (currentCampusData.buildings || []).length;
        
        let totalRooms = 0;
        (currentCampusData.buildings || []).forEach(b => totalRooms += (b.rooms || []).length);
        document.getElementById('stat-rooms').innerText = totalRooms;
        document.getElementById('data-version-tag').innerText = `Version ${currentCampusData.pack_version || '1.0.0'}`;
        
        // Optional QR rendering must never block buildings or class tabs.
        try { renderQrCode(); } catch (error) { console.warn('QR unavailable', error); }
        populateTimetableFilters();
        document.getElementById('node-status-text').innerText = activeCampusId + ' • CONNECTED';

        // Populate Campus Settings Form
        populateCampusSettingsForm();

        // Populate Building/Room selectors
        populateBuildingSelectors();

        // Render Buildings hierarchy
        renderBuildingsTree();

        // Fetch announcements, overrides, reports & timetables
        await fetchAnnouncements();
        await fetchOverrides();
        await fetchReports();
        await fetchTimetables();
      } catch (e) {
        console.error("Error loading campus data:", e);
        document.getElementById('buildings-tree-container').textContent = 'Could not load campus data. Refresh this page and sign in again if needed.';
      }
    }

    function renderQrCode() {
      const serverUrl = window.location.origin;
      document.getElementById('qr-server-endpoint').innerText = serverUrl;
      const payload = JSON.stringify({
        campus_id: activeCampusId,
        server_url: serverUrl,
        name: currentCampusData?.campus_name || activeCampusId
      });
      const canvas = document.getElementById('qr-canvas');
      if (typeof qrcode !== 'function') { canvas.hidden = true; return; }
      qrcode.stringToBytes = qrcode.stringToBytesFuncs['UTF-8'];
      const qr = qrcode(0, 'M');qr.addData(payload);qr.make();
      const modules = qr.getModuleCount(), cell = 4, margin = 4;
      canvas.width = canvas.height = (modules + margin * 2) * cell;
      const ctx = canvas.getContext('2d');ctx.fillStyle = '#FFFFFF';ctx.fillRect(0,0,canvas.width,canvas.height);
      ctx.fillStyle = '#070B14';
      for(let row=0;row<modules;row++)for(let col=0;col<modules;col++){
        if(qr.isDark(row,col))ctx.fillRect((col+margin)*cell,(row+margin)*cell,cell,cell);
      }
    }

    function resetTimetableFilters() {
      for (const id of ['tt-filter-sem','tt-filter-dept','tt-filter-sec','tt-filter-day']) {
        const control=document.getElementById(id);if(control)control.value='all';
      }
    }

    function populateTimetableFilters() {
      const sections=currentCampusData?.sections||[];
      for(const [id,values,label] of [
        ['tt-filter-dept',[...new Set(sections.map(s=>s.department).filter(Boolean))],'All Departments'],
        ['tt-filter-sec',sections.map(s=>s.section_id),'All Sections']]){
        const control=document.getElementById(id),old=control.value;
        control.innerHTML='<option value="all">'+label+'</option>'+values.map(v=>'<option value="'+escapeHtml(v)+'">'+escapeHtml(v)+'</option>').join('');
        control.value=values.includes(old)?old:'all';
      }
    }

    function populateCampusSettingsForm() {
      if (!currentCampusData) return;
      document.getElementById('ci-name').value = currentCampusData.campus_name || '';
      document.getElementById('ci-id').value = currentCampusData.campus_id || activeCampusId;
      document.getElementById('ci-short').value = currentCampusData.short_name || '';
      document.getElementById('ci-website').value = currentCampusData.website || '';
      document.getElementById('ci-city').value = currentCampusData.city || '';
      document.getElementById('ci-country').value = currentCampusData.country || '';
      document.getElementById('ci-address').value = currentCampusData.address || '';
      document.getElementById('ci-desc').value = currentCampusData.description || '';
      document.getElementById('ci-base-name').value = currentCampusData.base_location?.name || 'Main Gate';
      document.getElementById('ci-base-lat').value = currentCampusData.base_location?.latitude || 13.0295;
      document.getElementById('ci-base-lng').value = currentCampusData.base_location?.longitude || 77.5650;
    }

    function populateBuildingSelectors() {
      const bldgs = currentCampusData?.buildings || [];
      const opts = bldgs.map(b => `<option value="${escapeHtml(b.code)}">${escapeHtml(b.name)} (${escapeHtml(b.code)})</option>`).join('');
      document.getElementById('ov-bldg').innerHTML = opts;
      document.getElementById('mr-bldg').innerHTML = opts;
    }

    function renderGpsPins() {
      const host=document.getElementById('buildings-tree-container');
      let map=document.getElementById('gps-pin-map');
      if(!map){map=document.createElement('section');map.id='gps-pin-map';host.before(map);}
      const pins=(currentCampusData?.buildings||[]).filter(b=>Number.isFinite(b.latitude)&&Number.isFinite(b.longitude)&&Math.abs(b.latitude)<=90&&Math.abs(b.longitude)<=180&&(b.latitude!==0||b.longitude!==0));
      if(!pins.length){map.textContent='No GPS pins saved for this campus.';return;}
      const lats=pins.map(b=>b.latitude),lngs=pins.map(b=>b.longitude);
      const latPad=Math.max((Math.max(...lats)-Math.min(...lats))*.18,.0005);
      const lngPad=Math.max((Math.max(...lngs)-Math.min(...lngs))*.18,.0005);
      const south=Math.min(...lats)-latPad,north=Math.max(...lats)+latPad,west=Math.min(...lngs)-lngPad,east=Math.max(...lngs)+lngPad;
      map.innerHTML=`<div style="padding:16px;margin-bottom:20px;background:var(--bg);border:1px solid var(--border);border-radius:12px;">
        <h3 style="color:var(--emerald);margin-bottom:6px;">${pins.length} saved GPS pins</h3>
        <p style="color:var(--text-muted);font-size:12px;margin-bottom:12px;">Offline coordinate map • Select a marker or choose a place below. Pins are approximate; no street tiles are required.</p>
        <svg id="campus-gps-map" role="img" aria-label="${pins.length} campus GPS pins" viewBox="0 0 960 420" style="width:100%;max-height:360px;background:#0E1626;border-radius:8px;">
          <text x="16" y="24" fill="#94A3B8" font-size="14">N ↑</text>
          ${pins.map(b=>{const x=(b.longitude-west)/(east-west)*960,y=(north-b.latitude)/(north-south)*420;
            return `<g data-pin-code="${escapeHtml(b.code)}" tabindex="0" role="button" aria-label="${escapeHtml(b.name)}" style="cursor:pointer;"><title>${escapeHtml(b.name)}: ${b.latitude}, ${b.longitude}</title><circle cx="${x}" cy="${y}" r="7" fill="#00F0A8" stroke="#070B14" stroke-width="2"/>${b.code.startsWith('CORE')?`<text x="${x+10}" y="${y-8}" fill="#F1F5F9" font-size="12">${escapeHtml(b.code)}</text>`:''}</g>`;}).join('')}
        </svg>
        <label style="display:block;font-size:12px;margin-top:12px;">Find a building / place
          <select id="gps-place-select" style="width:100%;margin-top:6px;">${pins.map(b=>`<option value="${escapeHtml(b.code)}">${escapeHtml(b.name)} (${escapeHtml(b.code)})</option>`).join('')}</select>
        </label><p id="gps-place-detail" style="color:var(--blue);font-size:13px;margin-top:12px;"></p>
        <a id="gps-place-link" target="_blank" rel="noopener noreferrer" style="color:var(--emerald);font-size:12px;">Open selected pin in OpenStreetMap ↗</a>
      </div>`;
      function selectPin(code){
        const b=pins.find(p=>p.code===code);if(!b)return;
        document.getElementById('gps-place-select').value=code;
        document.getElementById('gps-place-detail').textContent=b.name+' — Latitude '+b.latitude+', Longitude '+b.longitude;
        document.getElementById('gps-place-link').href='https://www.openstreetmap.org/?mlat='+b.latitude+'&mlon='+b.longitude+'#map=18/'+b.latitude+'/'+b.longitude;
        map.querySelectorAll('[data-pin-code]').forEach(g=>{const circle=g.querySelector('circle');circle.setAttribute('fill',g.dataset.pinCode===code?'#38BDF8':'#00F0A8');circle.setAttribute('r',g.dataset.pinCode===code?'10':'7');});
      }
      document.getElementById('gps-place-select').onchange=e=>selectPin(e.target.value);
      map.querySelectorAll('[data-pin-code]').forEach(g=>{g.onclick=()=>selectPin(g.dataset.pinCode);g.onkeydown=e=>{if(e.key==='Enter'||e.key===' '){e.preventDefault();selectPin(g.dataset.pinCode);}};});
      selectPin(pins[0].code);
    }

    function renderBuildingsTree() {
      renderGpsPins();
      const container = document.getElementById('buildings-tree-container');
      const bldgs = currentCampusData?.buildings || [];
      if (bldgs.length === 0) {
        container.innerHTML = '<div class="alert-banner">No buildings registered yet. Click "+ Add Building" to create one.</div>';
        return;
      }

      container.innerHTML = bldgs.map(b => {
        const rooms = b.rooms || [];
        return `
          <div class="list-item" style="flex-direction:column; align-items:stretch;">
            <div style="display:flex; justify-content:space-between; align-items:center;">
              <div class="item-header">
                <span class="tag tag-emerald">${escapeHtml(b.code)}</span>
                <span style="font-weight:700; font-size:15px;">${escapeHtml(b.name)}</span>
                <span class="tag tag-blue">${b.total_floors} Floors</span>
                <span class="tag tag-rank">${rooms.length} Rooms</span>
              </div>
              <button class="btn btn-outline" style="padding:4px 8px; font-size:11px;" onclick="handleDeleteBuilding('${escapeHtml(b.id)}')">Remove</button>
            </div>
            <div class="item-desc" style="margin-top:4px;">${escapeHtml(b.description || 'Academic block')} • Lat ${b.latitude}, Lng ${b.longitude}</div>
            
            <!-- Rooms List -->
            <div style="margin-top:10px; display:flex; flex-wrap:wrap; gap:6px;">
              ${rooms.map(r => `
                <div style="background:var(--surface-variant); border:1px solid var(--border); border-radius:6px; padding:4px 8px; font-size:11px; display:flex; align-items:center; gap:6px;">
                  <span style="font-weight:700; color:var(--emerald);">${escapeHtml(r.room_id)}</span>
                  <span style="color:var(--text-muted);">${escapeHtml(r.floor_label)}</span>
                  <span class="tag tag-${r.category === 'laboratory' ? 'blue' : 'amber'}" style="font-size:9px;">${escapeHtml(r.category)}</span>
                  <button onclick="handleDeleteRoom('${escapeHtml(r.room_id)}')" style="background:none; border:none; color:var(--crimson); cursor:pointer; font-weight:bold;">✕</button>
                </div>
              `).join('')}
            </div>
          </div>
        `;
      }).join('');
    }

    async function fetchAnnouncements() {
      try {
        const res = await fetch(`/api/campus/${activeCampusId}/announcements`);
        const data = await res.json();
        announcementsData = data.messages || [];
        document.getElementById('active-anno-count').innerText = `${announcementsData.length} Notices`;
        renderAnnouncementsUI();
      } catch (e) {
        console.error("Failed to fetch announcements", e);
      }
    }

    function renderAnnouncementsUI() {
      const container = document.getElementById('announcements-list-container');
      if (announcementsData.length === 0) {
        container.innerHTML = '<div class="alert-banner">No active broadcast notices for this campus.</div>';
        return;
      }
      container.innerHTML = announcementsData.map(m => `
        <div class="list-item">
          <div class="item-main">
            <div class="item-header">
              <span class="tag ${m.priority === 'Emergency' ? 'tag-crimson' : 'tag-blue'}">${escapeHtml(m.priority)}</span>
              <span class="tag tag-amber">${escapeHtml(m.audience || 'All')}</span>
              <span style="font-weight:700; font-size:13px;">${escapeHtml(m.title)}</span>
            </div>
            <div class="item-desc">${escapeHtml(m.content)}</div>
            <div class="item-meta">${new Date(m.created_at || m.timestamp).toLocaleString()}</div>
          </div>
          <button class="btn btn-outline" style="color:var(--crimson);" onclick="handleDeleteAnnouncement('${escapeHtml(m.id)}')">Revoke</button>
        </div>
      `).join('');
    }

    async function fetchOverrides() {
      try {
        const res = await fetch(`/api/campus/${activeCampusId}/announcements`);
        const data = await res.json();
        overridesData = data.announcements || [];
        document.getElementById('stat-overrides').innerText = overridesData.length;
        document.getElementById('active-ov-count').innerText = `${overridesData.length} Active`;
        renderOverridesUI();
      } catch (e) {
        console.error("Failed to fetch overrides", e);
      }
    }

    function renderOverridesUI() {
      const container = document.getElementById('overrides-list-container');
      if (overridesData.length === 0) {
        container.innerHTML = '<div class="alert-banner">No active timetable overrides. Normal schedule applies.</div>';
        return;
      }
      container.innerHTML = overridesData.map(a => `
        <div class="list-item">
          <div class="item-main">
            <div class="item-header">
              <span class="tag tag-amber">${escapeHtml(a.targetCourseCode || a.target_course_code)}</span>
              <span class="tag tag-blue">Sec ${escapeHtml(a.targetSection || a.target_section || 'All')}</span>
              <span style="font-weight:700; font-size:14px;">➜ ${escapeHtml(a.newBuildingName || a.new_building_name)} • ${escapeHtml(a.newRoomId || a.new_room_id)}</span>
            </div>
            <div class="item-desc">${escapeHtml(a.reason)}</div>
            <div class="item-meta">${escapeHtml(a.newFloorLabel || a.new_floor_label)} • Effective: ${escapeHtml(a.effective_time || 'Immediate')}</div>
          </div>
          <button class="btn btn-outline" style="color:var(--crimson);" onclick="handleDeleteOverride('${escapeHtml(a.id)}')">Revoke</button>
        </div>
      `).join('');
    }

    async function fetchReports() {
      try {
        const res = await fetch(`/api/reports/sync?campus=${activeCampusId}`);
        const data = await res.json();
        reportsData = data.reports || [];
        const unres = reportsData.filter(r => r.status !== 'Resolved').length;
        document.getElementById('stat-reports').innerText = unres;
        document.getElementById('reports-badge').innerText = `${unres} Unresolved / ${reportsData.length} Total`;
        renderReportsUI();
      } catch (e) {
        console.error("Failed to fetch reports", e);
      }
    }

    function renderReportsUI() {
      const container = document.getElementById('reports-list-container');
      const filter = document.getElementById('report-filter-status').value;
      let list = reportsData;
      if (filter !== 'ALL') {
        list = list.filter(r => r.status === filter);
      }

      if (list.length === 0) {
        container.innerHTML = '<div class="alert-banner">No civic reports found matching selected filter.</div>';
        return;
      }

      container.innerHTML = list.map((r, i) => {
        const isEsc = r.escalated || r.report_count >= 3;
        const isResolved = r.status === 'Resolved';
        return `
          <div class="list-item ${isEsc && !isResolved ? 'escalated' : ''}" style="${isResolved ? 'opacity:0.6;' : ''}">
            <div class="item-main">
              <div class="item-header">
                <span class="tag tag-rank">#${i + 1}</span>
                <span class="tag ${r.report_count >= 3 ? 'tag-crimson' : r.report_count >= 2 ? 'tag-amber' : 'tag-emerald'}">${r.report_count} Reports</span>
                <span class="tag tag-blue">${escapeHtml(r.category)}</span>
                <span style="font-weight:700; font-size:14px;">${escapeHtml(r.building)} • ${escapeHtml(r.room)}</span>
                ${isEsc && !isResolved ? '<span class="tag tag-crimson">⚡ ESCALATED TO HEAD OF MAINTENANCE</span>' : ''}
              </div>
              <div class="item-desc" style="font-weight:600; color:var(--text);">${escapeHtml(r.description || r.title)}</div>
              ${r.resolution_note ? `<div style="font-size:12px; color:var(--emerald); margin-top:3px;"><b>Resolution Note:</b> ${escapeHtml(r.resolution_note)}</div>` : ''}
              <div class="item-meta">Status: <b>${escapeHtml(r.status)}</b> • First reported: ${new Date(r.first_reported).toLocaleTimeString()}</div>
            </div>
            <div>
              <button class="btn ${isResolved ? 'btn-outline' : 'btn-emerald'}" onclick="openResolveModal('${escapeHtml(r.id)}', '${escapeHtml(r.status)}', '${escapeHtml(r.resolution_note || '')}')">
                ${isResolved ? 'Edit Resolution' : 'Update Status'}
              </button>
            </div>
          </div>
        `;
      }).join('');
    }

    // Handlers for Forms
    async function handleCreateAnnouncement(e) {
      e.preventDefault();
      const body = {
        campus_id: activeCampusId,
        title: document.getElementById('anno-title').value,
        priority: document.getElementById('anno-priority').value,
        audience: document.getElementById('anno-audience').value,
        content: document.getElementById('anno-content').value
      };
      await fetch('/api/admin/announcement', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(body)
      });
      document.getElementById('anno-title').value = '';
      document.getElementById('anno-content').value = '';
      await fetchAnnouncements();
      alert("Announcement broadcasted successfully to student phones!");
    }

    async function handleDeleteAnnouncement(id) {
      if (!confirm("Revoke this announcement?")) return;
      await fetch(`/api/admin/announcement?id=${id}&campus=${activeCampusId}`, { method: 'DELETE' });
      await fetchAnnouncements();
    }

    async function handleCreateOverride(e) {
      e.preventDefault();
      const bldgSelect = document.getElementById('ov-bldg');
      const bldgName = bldgSelect.options[bldgSelect.selectedIndex]?.text.split(' (')[0] || 'Campus Building';
      const body = {
        campus_id: activeCampusId,
        targetCourseCode: document.getElementById('ov-course').value.toUpperCase(),
        targetSection: document.getElementById('ov-section').value.toUpperCase(),
        newBuildingCode: bldgSelect.value,
        newBuildingName: bldgName,
        newRoomId: document.getElementById('ov-room').value,
        newFloor: parseInt(document.getElementById('ov-floor').value),
        newFloorLabel: document.getElementById('ov-floor-label').value,
        reason: document.getElementById('ov-reason').value
      };
      await fetch('/api/admin/override', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(body)
      });
      document.getElementById('ov-course').value = '';
      document.getElementById('ov-room').value = '';
      document.getElementById('ov-reason').value = '';
      await fetchOverrides();
      alert("Classroom override published! Student timetables and routing are updated.");
    }

    async function handleDeleteOverride(id) {
      if (!confirm("Revoke this classroom override?")) return;
      await fetch(`/api/admin/override?id=${id}&campus=${activeCampusId}`, { method: 'DELETE' });
      await fetchOverrides();
    }

    // Master Timetable Handlers
    async function fetchTimetables() {
      const requestedCampus = activeCampusId;
      try {
        const sem = document.getElementById('tt-filter-sem')?.value || 'all';
        const dept = document.getElementById('tt-filter-dept')?.value || 'all';
        const sec = document.getElementById('tt-filter-sec')?.value || 'all';
        const day = document.getElementById('tt-filter-day')?.value || 'all';

        const url = `/api/campus/${activeCampusId}/timetables?semester=${encodeURIComponent(sem)}&department=${encodeURIComponent(dept)}&section=${encodeURIComponent(sec)}&day=${encodeURIComponent(day)}`;
        const res = await fetch(url);
        if (!res.ok) throw new Error('Timetable request failed: ' + res.status);
        const rows = await res.json();
        if (!Array.isArray(rows)) throw new Error('Invalid timetable response');
        if (requestedCampus !== activeCampusId) return;
        timetablesData = rows;
        
        const countEl = document.getElementById('stat-timetables');
        if (countEl) countEl.innerText = timetablesData.length;
        const countBadge = document.getElementById('tt-count-badge');
        if (countBadge) countBadge.innerText = `${timetablesData.length} Class Slots Configured`;

        renderTimetablesTable();
      } catch (e) {
        console.error("Failed to fetch timetables", e);
        document.getElementById('timetables-tbody').innerHTML='<tr><td colspan="11">Could not load classes. Refresh and sign in again if needed.</td></tr>';
      }
    }

    function renderTimetablesTable() {
      const container = document.getElementById('timetables-tbody');
      if (!container) return;

      if (!timetablesData || timetablesData.length === 0) {
        container.innerHTML = '<tr><td colspan="11" style="text-align:center; padding:24px; color:var(--text-muted);">No timetable slots found matching filter criteria. Click "+ Add Class Slot" to add one.</td></tr>';
        return;
      }

      container.innerHTML = timetablesData.map((slot) => {
        const isLab = slot.is_lab === 1 || slot.is_lab === true;
        return `
          <tr style="border-bottom:1px solid var(--border);">
            <td style="padding:8px;"><span class="tag tag-blue">Sem ${slot.semester}</span></td>
            <td style="padding:8px;"><span class="tag tag-emerald">Sec ${escapeHtml(slot.section_id)}</span></td>
            <td style="padding:8px; font-weight:600;">${escapeHtml(slot.department)}</td>
            <td style="padding:8px;"><span class="tag" style="background:#1e293b; color:#94a3b8;">${escapeHtml(slot.day_of_week)}</span></td>
            <td style="padding:8px; font-family:'JetBrains Mono', monospace; font-size:12px;">${escapeHtml(slot.start_time)} - ${escapeHtml(slot.end_time)}</td>
            <td style="padding:8px;"><span class="tag" style="background:#0284c7; color:#fff; font-weight:700;">${escapeHtml(slot.course_code)}</span></td>
            <td style="padding:8px; font-weight:600;">${escapeHtml(slot.course_name)}</td>
            <td style="padding:8px;">${escapeHtml(slot.room_id)} (${escapeHtml(slot.building_name)})</td>
            <td style="padding:8px; color:var(--text-muted); font-size:12px;">${escapeHtml(slot.instructor || 'Faculty')}</td>
            <td style="padding:8px;"><span class="tag ${isLab ? 'tag-amber' : 'tag-emerald'}">${isLab ? '🔬 Lab' : '📖 Theory'}</span></td>
            <td style="padding:8px;">
              <button class="btn btn-outline" style="color:var(--crimson); padding:4px 8px; font-size:11px;" onclick="handleDeleteTimetableSlot('${escapeHtml(slot.id)}')">Delete</button>
            </td>
          </tr>
        `;
      }).join('');
    }

    function openAddTimetableModal() {
      document.getElementById('mtt-sem').value = '1';
      document.getElementById('mtt-dept').value = 'Computer Science & Engineering';
      document.getElementById('mtt-sec').value = currentCampusData?.sections?.[0]?.section_id || '';
      document.getElementById('mtt-day').value = 'MONDAY';
      document.getElementById('mtt-code').value = '';
      document.getElementById('mtt-name').value = '';
      document.getElementById('mtt-room').value = currentCampusData?.sections?.[0]?.default_room_id || '';
      document.getElementById('mtt-bldg').value = currentCampusData?.buildings?.find(b=>b.code===currentCampusData?.sections?.[0]?.default_building_code)?.name || '';
      document.getElementById('mtt-faculty').value = '';
      document.getElementById('modal-add-timetable').style.display = 'flex';
    }

    async function handleAddTimetableSlot(e) {
      e.preventDefault();
      const body = {
        campus_id: activeCampusId,
        semester: parseInt(document.getElementById('mtt-sem').value),
        department: document.getElementById('mtt-dept').value,
        section_id: document.getElementById('mtt-sec').value.toUpperCase(),
        day_of_week: document.getElementById('mtt-day').value.toUpperCase(),
        start_time: document.getElementById('mtt-start').value,
        end_time: document.getElementById('mtt-end').value,
        course_code: document.getElementById('mtt-code').value.toUpperCase(),
        course_name: document.getElementById('mtt-name').value,
        room_id: document.getElementById('mtt-room').value,
        building_name: document.getElementById('mtt-bldg').value,
        instructor: document.getElementById('mtt-faculty').value || 'Faculty',
        is_lab: document.getElementById('mtt-islab').checked ? 1 : 0
      };

      await fetch('/api/admin/timetable', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(body)
      });

      closeModals();
      await fetchTimetables();
      alert("Timetable slot saved successfully!");
    }

    async function handleDeleteTimetableSlot(id) {
      if (!confirm("Delete this timetable slot?")) return;
      await fetch(`/api/admin/timetable?id=${encodeURIComponent(id)}&campus=${activeCampusId}`, { method: 'DELETE' });
      await fetchTimetables();
    }

    async function handleSeedTimetables() {
      if (!confirm("Seed default master timetables across Semesters 1, 3, 5, 7?")) return;
      await fetch(`/api/admin/timetable/seed?campus=${activeCampusId}`, { method: 'POST' });
      await fetchTimetables();
      alert("Default master timetable seeded successfully!");
    }

    async function handleSaveCampusInfo(e) {
      e.preventDefault();
      const body = {
        campus_id: activeCampusId,
        college_name: document.getElementById('ci-name').value,
        short_name: document.getElementById('ci-short').value,
        website: document.getElementById('ci-website').value,
        city: document.getElementById('ci-city').value,
        country: document.getElementById('ci-country').value,
        address: document.getElementById('ci-address').value,
        description: document.getElementById('ci-desc').value,
        base_name: document.getElementById('ci-base-name').value,
        base_lat: parseFloat(document.getElementById('ci-base-lat').value),
        base_lng: parseFloat(document.getElementById('ci-base-lng').value)
      };
      await fetch('/api/admin/campus', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(body)
      });
      await loadActiveCampus();
      alert("Campus details saved successfully!");
    }

    async function handleCreateCampus(e) {
      e.preventDefault();
      const newId = document.getElementById('mcc-id').value.trim().toUpperCase();
      const body = {
        campus_id: newId,
        college_name: document.getElementById('mcc-name').value,
        short_name: newId,
        city: document.getElementById('mcc-city').value,
        country: document.getElementById('mcc-country').value,
        base_lat: parseFloat(document.getElementById('mcc-lat').value),
        base_lng: parseFloat(document.getElementById('mcc-lng').value),
        address: document.getElementById('mcc-city').value,
        description: 'Multi-campus deployment node',
        website: 'https://msrit.edu'
      };
      await fetch('/api/admin/campus', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(body)
      });
      closeModals();
      activeCampusId = newId;
      await fetchCampusesList();
      await loadActiveCampus();
      alert(`Campus ${newId} created successfully! You can now add buildings and rooms.`);
    }

    async function handleSaveBuilding(e) {
      e.preventDefault();
      const body = {
        campus_id: activeCampusId,
        code: document.getElementById('mb-code').value.toUpperCase(),
        name: document.getElementById('mb-name').value,
        latitude: parseFloat(document.getElementById('mb-lat').value),
        longitude: parseFloat(document.getElementById('mb-lng').value),
        total_floors: parseInt(document.getElementById('mb-floors').value),
        description: document.getElementById('mb-desc').value
      };
      await fetch('/api/admin/building', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(body)
      });
      closeModals();
      await loadActiveCampus();
    }

    async function handleDeleteBuilding(id) {
      if (!confirm("Remove building and its rooms?")) return;
      await fetch(`/api/admin/building?id=${id}&campus=${activeCampusId}`, { method: 'DELETE' });
      await loadActiveCampus();
    }

    async function handleSaveRoom(e) {
      e.preventDefault();
      const body = {
        campus_id: activeCampusId,
        building_code: document.getElementById('mr-bldg').value,
        room_id: document.getElementById('mr-room-id').value,
        name: document.getElementById('mr-name').value,
        floor: parseInt(document.getElementById('mr-floor').value),
        floor_label: document.getElementById('mr-floor-label').value,
        category: document.getElementById('mr-category').value,
        assigned_section: document.getElementById('mr-sec').value || null,
        department: document.getElementById('mr-dept').value,
        description: document.getElementById('mr-desc').value
      };
      await fetch('/api/admin/room', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(body)
      });
      closeModals();
      await loadActiveCampus();
    }

    async function handleDeleteRoom(roomId) {
      if (!confirm(`Delete room ${roomId}?`)) return;
      await fetch(`/api/admin/room?room_id=${roomId}&campus=${activeCampusId}`, { method: 'DELETE' });
      await loadActiveCampus();
    }

    function openResolveModal(id, status, note) {
      document.getElementById('mri-id').value = id;
      document.getElementById('mri-status').value = status;
      document.getElementById('mri-note').value = note;
      document.getElementById('modal-resolve-issue').style.display = 'flex';
    }

    async function submitIssueStatusUpdate() {
      const id = document.getElementById('mri-id').value;
      const status = document.getElementById('mri-status').value;
      const note = document.getElementById('mri-note').value;
      await fetch('/api/admin/reports/status', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ id, status, resolution_note: note, campus_id: activeCampusId })
      });
      closeModals();
      await fetchReports();
    }

    function handleExportPack() {
      if (!currentCampusData) return;
      const dataStr = "data:text/json;charset=utf-8," + encodeURIComponent(JSON.stringify(currentCampusData, null, 2));
      const dl = document.createElement('a');
      dl.setAttribute("href", dataStr);
      dl.setAttribute("download", `${activeCampusId}_pack.prismpack`);
      dl.click();
    }

    async function handleImportFile(e) {
      const file = e.target.files[0];
      if (!file) return;
      const text = await file.text();
      const validationBox = document.getElementById('import-validation-box');
      validationBox.style.display = 'block';

      try {
        const parsed = JSON.parse(text);
        if (!parsed.campus_name && !parsed.buildings) {
          throw new Error("Invalid .prismpack schema: Missing campus_name or buildings list.");
        }
        validationBox.style.background = 'rgba(0, 240, 168, 0.15)';
        validationBox.style.border = '1px solid var(--emerald)';
        validationBox.style.color = 'var(--emerald)';
        validationBox.innerHTML = `✓ Valid .prismpack structure for "${escapeHtml(parsed.campus_name)}". Ingesting into SQLite...`;

        await fetch('/api/admin/import', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ campus_id: activeCampusId, pack: parsed })
        });
        await loadActiveCampus();
        alert("Campus pack imported and published successfully!");
      } catch (err) {
        validationBox.style.background = 'rgba(244, 63, 94, 0.15)';
        validationBox.style.border = '1px solid var(--crimson)';
        validationBox.style.color = 'var(--crimson)';
        validationBox.innerHTML = `<b>Import Validation Error:</b> ${escapeHtml(err.message)}`;
      }
    }

    async function openPublishModal() {
      if (!confirm(`Publish all current drafts for ${activeCampusId} as the official version?`)) return;
      const res = await fetch('/api/admin/campus/publish', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ campus_id: activeCampusId })
      });
      const data = await res.json();
      alert(`Campus version ${data.version} published! Student phones will now automatically retrieve this version.`);
      await loadActiveCampus();
    }

    // Modal toggles
    function openAddBuildingModal() { document.getElementById('modal-building').style.display = 'flex'; }
    function openAddRoomModal() { document.getElementById('modal-room').style.display = 'flex'; }
    function openCreateCampusModal() { document.getElementById('modal-create-campus').style.display = 'flex'; }
    function closeModals() {
      document.querySelectorAll('.modal-backdrop').forEach(m => m.style.display = 'none');
    }

    function onOverrideBuildingChange() {
      const bldgSelect = document.getElementById('ov-bldg');
      const bldgCode = bldgSelect.value;
      const b = (currentCampusData?.buildings || []).find(x => x.code === bldgCode);
      if (b && (b.rooms || []).length > 0) {
        document.getElementById('ov-room').value = b.rooms[0].room_id;
        document.getElementById('ov-floor').value = b.rooms[0].floor;
        document.getElementById('ov-floor-label').value = b.rooms[0].floor_label;
      }
    }

    function escapeHtml(str) {
      if (!str) return '';
      return String(str).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;');
    }
  </script>
</body>
</html>
"""

# ==============================================================================
# HTTP REQUEST HANDLER
# ==============================================================================

class UnoQEdgeServerHandler(http.server.BaseHTTPRequestHandler):
    def _set_cors_headers(self, content_type="application/json"):
        self.send_header("Access-Control-Allow-Origin", "*")
        self.send_header("Access-Control-Allow-Methods", "GET, POST, DELETE, OPTIONS")
        self.send_header("Access-Control-Allow-Headers", "Content-Type, Authorization")
        self.send_header("Content-Type", content_type)

    def do_OPTIONS(self):
        self.send_response(204)
        self._set_cors_headers()
        self.end_headers()

    def do_GET(self):
        parsed = urllib.parse.urlparse(self.path)
        path = parsed.path
        query = urllib.parse.parse_qs(parsed.query)

        # 1. Admin Web Dashboard
        if path in ("/", "/index.html", "/admin"):
            self.send_response(200)
            self._set_cors_headers("text/html; charset=utf-8")
            self.end_headers()
            self.wfile.write(HTML_DASHBOARD.encode("utf-8"))
            return

        # 2. GET /api/health
        if path == "/api/health":
            self.send_response(200)
            self._set_cors_headers()
            self.end_headers()
            self.wfile.write(json.dumps({
                "status": "healthy",
                "node_id": "UNO_Q_MSRIT_CORE_01",
                "lan_ip": SERVER_LAN_IP,
                "timestamp": int(time.time() * 1000)
            }).encode("utf-8"))
            return

        # 2b. GET /models/{filename} (Serve offline ML models over local Wi-Fi)
        if path.startswith("/models/"):
            model_filename = os.path.basename(path)
            model_path = os.path.join(BASE_DIR, "models", model_filename)
            if os.path.exists(model_path):
                self.send_response(200)
                self.send_header("Content-Type", "application/octet-stream")
                self.send_header("Content-Length", str(os.path.getsize(model_path)))
                self._set_cors_headers()
                self.end_headers()
                try:
                    with open(model_path, "rb") as f:
                        while chunk := f.read(65536):
                            self.wfile.write(chunk)
                except (ConnectionResetError, ConnectionAbortedError, BrokenPipeError):
                    pass
                return
            self.send_response(404)
            self._set_cors_headers()
            self.end_headers()
            self.wfile.write(json.dumps({"error": f"Model {model_filename} not found on Uno Q edge server"}).encode("utf-8"))
            return

        # 2c. GET /api/ai/rules (Expose active assistant rules & configuration)
        if path == "/api/ai/rules":
            rules_content, rules_src, rules_mtime = get_assistant_rules()
            self.send_response(200)
            self._set_cors_headers("application/json")
            self.end_headers()
            self.wfile.write(json.dumps({
                "rules": rules_content,
                "source": rules_src,
                "last_modified": rules_mtime,
                "success": True
            }).encode("utf-8"))
            return

        # 3. GET /api/campuses (List all available campuses)
        if path == "/api/campuses":
            with get_db() as conn:
                cursor = conn.cursor()
                cursor.execute("SELECT campus_id, college_name, short_name, city, country, is_published FROM campuses")
                campuses = [dict(row) for row in cursor.fetchall()]
            self.send_response(200)
            self._set_cors_headers()
            self.end_headers()
            self.wfile.write(json.dumps(campuses).encode("utf-8"))
            return

        # 4. GET /api/campus/{campus_id}/lookup
        if path.startswith("/api/campus/") and path.endswith("/lookup"):
            campus_id = path.split("/")[3]
            with get_db() as conn:
                cursor = conn.cursor()
                cursor.execute("SELECT * FROM campuses WHERE campus_id = ?", (campus_id,))
                row = cursor.fetchone()
                if row:
                    self.send_response(200)
                    self._set_cors_headers()
                    self.end_headers()
                    self.wfile.write(json.dumps(dict(row)).encode("utf-8"))
                    return
            self.send_response(404)
            self._set_cors_headers()
            self.end_headers()
            self.wfile.write(json.dumps({"error": f"Campus {campus_id} not found"}).encode("utf-8"))
            return

        # 5. GET /api/campus/{campus_id}/package or /api/pack
        if (path.startswith("/api/campus/") and path.endswith("/package")) or path == "/api/pack":
            campus_id = query.get("campus", ["MSRIT"])[0]
            if path.startswith("/api/campus/"):
                campus_id = path.split("/")[3]

            pack = compile_campus_pack(campus_id)
            if pack:
                self.send_response(200)
                self._set_cors_headers()
                self.end_headers()
                self.wfile.write(json.dumps(pack, indent=2).encode("utf-8"))
                return
            elif campus_id == "MSRIT" and os.path.exists(ASSETS_PACK):
                with open(ASSETS_PACK, "r", encoding="utf-8") as f:
                    content = f.read()
                self.send_response(200)
                self._set_cors_headers()
                self.end_headers()
                self.wfile.write(content.encode("utf-8"))
                return
            self.send_response(404)
            self._set_cors_headers()
            self.end_headers()
            self.wfile.write(json.dumps({"error": "Campus pack not found"}).encode("utf-8"))
            return

        # 6. GET /api/campus/{campus_id}/announcements or /api/announcements or /api/messages
        if (path.startswith("/api/campus/") and path.endswith("/announcements")) or path in ("/api/announcements", "/api/messages"):
            campus_id = query.get("campus", ["MSRIT"])[0]
            if path.startswith("/api/campus/"):
                campus_id = path.split("/")[3]

            with get_db() as conn:
                cursor = conn.cursor()
                cursor.execute(
                    "SELECT * FROM announcements WHERE campus_id = ? AND is_archived = 0 AND (expires_at IS NULL OR expires_at > ?) ORDER BY created_at DESC",
                    (campus_id,int(time.time()*1000))
                )
                annos = [dict(r) for r in cursor.fetchall()]

                cursor.execute(
                    "SELECT * FROM overrides WHERE campus_id = ? AND is_active = 1 ORDER BY created_at DESC",
                    (campus_id,)
                )
                overrides = []
                for r in cursor.fetchall():
                    overrides.append({
                        "id": r["id"],
                        "targetCourseCode": r["target_course_code"],
                        "targetSection": r["target_section"],
                        "newBuildingCode": r["new_building_code"],
                        "newBuildingName": r["new_building_name"],
                        "newRoomId": r["new_room_id"],
                        "newFloor": r["new_floor"],
                        "newFloorLabel": r["new_floor_label"],
                        "reason": r["reason"],
                        "timestamp": r["created_at"],
                        "effective_time": r["effective_time"]
                    })

            self.send_response(200)
            self._set_cors_headers()
            self.end_headers()
            self.wfile.write(json.dumps({
                "status": "success",
                "campus_id": campus_id,
                "node_id": "UNO_Q_MSRIT_CORE_01",
                "timestamp": int(time.time() * 1000),
                "pack_version": (compile_campus_pack(campus_id) or {}).get("pack_version", ""),
                "messages": annos,
                "announcements": overrides
            }).encode("utf-8"))
            return

        # 7. GET /api/reports/sync (Civic reports query)
        if path == "/api/reports/sync":
            campus_id = query.get("campus", ["MSRIT"])[0]
            with get_db() as conn:
                cursor = conn.cursor()
                cursor.execute(
                    "SELECT * FROM issue_reports WHERE campus_id = ? ORDER BY report_count DESC, last_reported DESC",
                    (campus_id,)
                )
                reports = []
                for r in cursor.fetchall():
                    item = dict(r)
                    try:
                        item["reporters"] = json.loads(item["reporters_json"])
                    except Exception:
                        item["reporters"] = []
                    item["resolved"] = (item["status"] == "Resolved")
                    reports.append(item)

            self.send_response(200)
            self._set_cors_headers()
            self.end_headers()
            self.wfile.write(json.dumps({"status": "success", "reports": reports}).encode("utf-8"))
            return

        # 8. GET /api/status
        if path == "/api/status":
            with get_db() as conn:
                cursor = conn.cursor()
                cursor.execute("SELECT COUNT(*) FROM campuses")
                c_cnt = cursor.fetchone()[0]
                cursor.execute("SELECT COUNT(*) FROM issue_reports WHERE status != 'Resolved'")
                open_rep = cursor.fetchone()[0]
                cursor.execute("SELECT COUNT(*) FROM overrides WHERE is_active = 1")
                act_ov = cursor.fetchone()[0]

            self.send_response(200)
            self._set_cors_headers()
            self.end_headers()
            self.wfile.write(json.dumps({
                "node": "UNO_Q_MSRIT_CORE_01",
                "status": "ONLINE",
                "lan_ip": SERVER_LAN_IP,
                "total_campuses": c_cnt,
                "active_overrides": act_ov,
                "open_issues": open_rep
            }).encode("utf-8"))
            return

        # 9. GET /api/campus/{campus_id}/timetables or /api/timetables
        if (path.startswith("/api/campus/") and path.endswith("/timetables")) or path == "/api/timetables":
            if path == "/api/timetables":
                campus_id = query.get("campus", ["MSRIT"])[0]
            else:
                campus_id = path.split("/")[3]

            sem = query.get("semester", [None])[0]
            dept = query.get("department", [None])[0]
            sec = query.get("section", [None])[0]
            day = query.get("day", [None])[0]

            sql = "SELECT * FROM college_timetables WHERE campus_id = ?"
            params = [campus_id]
            if sem and sem != "all":
                sql += " AND semester = ?"
                params.append(int(sem))
            if dept and dept != "all":
                sql += " AND department = ?"
                params.append(dept)
            if sec and sec != "all":
                sql += " AND section_id = ?"
                params.append(sec.upper())
            if day and day != "all":
                sql += " AND day_of_week = ?"
                params.append(day.upper())
            sql += " ORDER BY semester ASC, department ASC, section_id ASC, day_of_week ASC, period ASC"

            with get_db() as conn:
                cursor = conn.cursor()
                cursor.execute(sql, params)
                timetables = [dict(row) for row in cursor.fetchall()]

            self.send_response(200)
            self._set_cors_headers()
            self.end_headers()
            self.wfile.write(json.dumps(timetables).encode("utf-8"))
            return

        self.send_response(404)
        self._set_cors_headers()
        self.end_headers()
        self.wfile.write(json.dumps({"error": "Endpoint not found"}).encode("utf-8"))

    def do_POST(self):
        parsed = urllib.parse.urlparse(self.path)
        path = parsed.path
        content_length = int(self.headers.get("Content-Length", 0))
        raw_body = self.rfile.read(content_length)

        try:
            body = json.loads(raw_body.decode("utf-8")) if raw_body else {}
        except Exception:
            body = {}

        # 0. Edge AI Assistant Rules Update (POST /api/ai/rules)
        if path == "/api/ai/rules":
            new_rules = body.get("rules", "").strip()
            if new_rules:
                target_path = os.path.abspath(os.path.join(BASE_DIR, "..", "config", "assistant_rules.md"))
                os.makedirs(os.path.dirname(target_path), exist_ok=True)
                with open(target_path, "w", encoding="utf-8") as f:
                    f.write(new_rules)
                asset_path = os.path.abspath(os.path.join(BASE_DIR, "..", "app", "src", "main", "assets", "assistant_rules.md"))
                if os.path.exists(os.path.dirname(asset_path)):
                    try:
                        with open(asset_path, "w", encoding="utf-8") as f:
                            f.write(new_rules)
                    except Exception:
                        pass
                get_assistant_rules()
                self.send_response(200)
                self._set_cors_headers()
                self.end_headers()
                self.wfile.write(json.dumps({
                    "status": "updated",
                    "path": target_path,
                    "success": True
                }).encode("utf-8"))
                return
            else:
                self.send_response(400)
                self._set_cors_headers()
                self.end_headers()
                self.wfile.write(json.dumps({"error": "Rules content cannot be empty"}).encode("utf-8"))
                return

        # 0b. Edge AI Chat Proxy (POST /api/ai/chat & POST /api/ai/qwen)
        if path in ["/api/ai/chat", "/api/ai/qwen"]:
            query = body.get("query") or body.get("prompt") or ""
            lang = body.get("language", "en")
            campus_id = str(body.get("campus_id", "MSRIT")).upper()
            try:
                context_data = campus_ai_context(campus_id, body.get("context", ""))
            except ValueError:
                self.send_error(404, "Unknown campus ID")
                return

            # Load active assistant rules (markdown config with hot-reloading)
            rules_content, _, _ = get_assistant_rules()
            active_rules = body.get("rules") or rules_content

            answer = None
            provider = "Uno Q Edge AI"

            # Route A: Direct Qwen request (/api/ai/qwen)
            if path == "/api/ai/qwen":
                answer = query_local_qwen(query, active_rules, context_data)
                if answer:
                    provider = "Qwen2.5-0.5B-Instruct (Uno Q Local Edge)"

            # Route B: General chat request (/api/ai/chat)
            if path == "/api/ai/chat" and not answer:
                # 1. Try Gemini Cloud first if key available
                gemini_key = os.environ.get("GEMINI_API_KEY", "").strip()
                if not gemini_key:
                    # Check if client sent an Authorization header or body key
                    gemini_key = body.get("api_key", "").strip()

                if gemini_key:
                    for model in ["gemini-2.5-flash", "gemini-flash-latest", "gemini-2.5-flash-lite"]:
                        try:
                            endpoint = f"https://generativelanguage.googleapis.com/v1beta/models/{model}:generateContent?key={gemini_key}"
                            prompt_payload = {
                                "contents": [{
                                    "parts": [{
                                        "text": f"You are PrismCampus AI for the selected campus in Context. Answer general definitions generally. Never substitute another college or invent placement statistics.\n\nRules & Guidelines:\n{active_rules}\n\nContext:\n{context_data}\n\nUser Query: {query}"
                                    }]
                                }],
                                "generationConfig": {
                                    "maxOutputTokens": 300,
                                    "temperature": 0.7
                                }
                            }
                            req = urllib.request.Request(
                                endpoint,
                                data=json.dumps(prompt_payload).encode("utf-8"),
                                headers={"Content-Type": "application/json"}
                            )
                            with urllib.request.urlopen(req, timeout=6) as response:
                                if response.status == 200:
                                    data = json.loads(response.read().decode("utf-8"))
                                    answer = data["candidates"][0]["content"]["parts"][0]["text"].strip()
                                    provider = f"Gemini ({model}) (Uno Q Edge)"
                                    break
                        except urllib.error.HTTPError as he:
                            print(f"[AI PROXY] Gemini ({model}) HTTP error: {he.code} {he.reason}")
                            if he.code in [400, 401, 403]:
                                break
                        except Exception as e:
                            print(f"[AI PROXY] Gemini ({model}) call failed: {e}")

                # 2. If Gemini unavailable or offline, fall back to local Qwen SLM inference
                if not answer:
                    answer = query_local_qwen(query, active_rules, context_data)
                    if answer:
                        provider = "Qwen2.5-0.5B-Instruct (Uno Q Fallback Edge)"

            # No model answer: only search the selected campus's own records.
            if not answer:
                answer = campus_record_answer(campus_id, query)
                provider = "Selected Campus Records (Offline)"

            self.send_response(200)
            self._set_cors_headers()
            self.end_headers()
            self.wfile.write(json.dumps({
                "response": answer,
                "provider": provider,
                "success": True
            }).encode("utf-8"))
            return

        # 1. Student App Civic Issue Sync (POST /api/reports/sync)
        if path == "/api/reports/sync":
            incoming_reports = body.get("reports", [])
            device_id = body.get("device_id", "Unknown_Student_Device")
            campus_id = body.get("campus_id", "MSRIT")

            with get_db() as conn:
                if not conn.execute('SELECT 1 FROM campuses WHERE campus_id=?',(campus_id,)).fetchone():
                    self.send_response(404); self._set_cors_headers(); self.end_headers()
                    self.wfile.write(b'{"error":"Unknown campus ID"}'); return
            added_new, updated_existing = ingest_raw_reports_db(campus_id, incoming_reports, device_id)
            print(f"[UNO Q SYNC] Received {len(incoming_reports)} reports from {device_id} ({added_new} new, {updated_existing} merged into existing complaints)")

            self.send_response(200)
            self._set_cors_headers()
            self.end_headers()
            self.wfile.write(json.dumps({
                "status": "synced",
                "added_new_clusters": added_new,
                "updated_existing_clusters": updated_existing
            }).encode("utf-8"))
            return

        # 2. Admin Create / Update Campus (POST /api/admin/campus)
        if path == "/api/admin/campus":
            campus_id = body.get("campus_id", "").strip().toUpperCase() if hasattr(body.get("campus_id", ""), "toUpperCase") else body.get("campus_id", "").strip().upper()
            if not campus_id:
                campus_id = f"CAMPUS_{uuid.uuid4().hex[:6].upper()}"

            now = int(time.time() * 1000)
            with get_db() as conn:
                cursor = conn.cursor()
                cursor.execute("""
                    INSERT INTO campuses (
                        campus_id, college_name, short_name, website, city, country,
                        address, description, base_name, base_lat, base_lng, base_desc,
                        pack_version, is_published, created_at, updated_at
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, '1.0.0', 1, ?, ?)
                    ON CONFLICT(campus_id) DO UPDATE SET
                        college_name = excluded.college_name,
                        short_name = excluded.short_name,
                        website = excluded.website,
                        city = excluded.city,
                        country = excluded.country,
                        address = excluded.address,
                        description = excluded.description,
                        base_name = excluded.base_name,
                        base_lat = excluded.base_lat,
                        base_lng = excluded.base_lng,
                        updated_at = excluded.updated_at
                """, (
                    campus_id,
                    body.get("college_name", campus_id),
                    body.get("short_name", campus_id),
                    body.get("website", "https://msrit.edu"),
                    body.get("city", "Bengaluru"),
                    body.get("country", "India"),
                    body.get("address", ""),
                    body.get("description", ""),
                    body.get("base_name", "Main Gate"),
                    float(body.get("base_lat", 13.0295)),
                    float(body.get("base_lng", 77.5650)),
                    body.get("base_desc", "Main Campus Entrance"),
                    now, now
                ))
                conn.commit()

            self.send_response(200)
            self._set_cors_headers()
            self.end_headers()
            self.wfile.write(json.dumps({"status": "saved", "campus_id": campus_id}).encode("utf-8"))
            return

        # 3. Admin Publish Campus Changes (POST /api/admin/campus/publish)
        if path == "/api/admin/campus/publish":
            campus_id = body.get("campus_id", "MSRIT")
            now = int(time.time() * 1000)
            with get_db() as conn:
                cursor = conn.cursor()
                cursor.execute("SELECT pack_version FROM campuses WHERE campus_id = ?", (campus_id,))
                row = cursor.fetchone()
                current_ver = row[0] if row else "1.0.0"
                parts = current_ver.split(".")
                new_patch = int(parts[-1]) + 1 if len(parts) == 3 else 1
                new_ver = f"{parts[0]}.{parts[1] if len(parts)>1 else '0'}.{new_patch}"
                cursor.execute(
                    "UPDATE campuses SET pack_version = ?, updated_at = ?, is_published = 1 WHERE campus_id = ?",
                    (new_ver, now, campus_id)
                )
                conn.commit()

            print(f"[UNO Q PUBLISH] Published campus {campus_id} to official version {new_ver}")
            self.send_response(200)
            self._set_cors_headers()
            self.end_headers()
            self.wfile.write(json.dumps({"status": "published", "campus_id": campus_id, "version": new_ver}).encode("utf-8"))
            return

        # 4. Admin Add / Edit Building (POST /api/admin/building)
        if path == "/api/admin/building":
            campus_id = body.get("campus_id", "MSRIT")
            code = body.get("code", "BLD").upper()
            b_id = body.get("id") or f"bldg_{campus_id}_{code}"
            with get_db() as conn:
                cursor = conn.cursor()
                existing = cursor.execute('SELECT id FROM buildings WHERE campus_id=? AND code=?',(campus_id,code)).fetchone()
                if existing and not body.get('id'): b_id = existing['id']
                cursor.execute("""
                    INSERT INTO buildings (id, campus_id, code, name, latitude, longitude, total_floors, description)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                    ON CONFLICT(id) DO UPDATE SET
                        code = excluded.code,
                        name = excluded.name,
                        latitude = excluded.latitude,
                        longitude = excluded.longitude,
                        total_floors = excluded.total_floors,
                        description = excluded.description
                """, (
                    b_id, campus_id, code,
                    body.get("name", code),
                    float(body.get("latitude", 13.0295)),
                    float(body.get("longitude", 77.5650)),
                    int(body.get("total_floors", 1)),
                    body.get("description", "")
                ))
                conn.commit()
            self.send_response(200)
            self._set_cors_headers()
            self.end_headers()
            self.wfile.write(json.dumps({"status": "saved", "id": b_id}).encode("utf-8"))
            return

        # 5. Admin Add / Edit Room (POST /api/admin/room)
        if path == "/api/admin/room":
            campus_id = body.get("campus_id", "MSRIT")
            room_id = body.get("room_id", "101")
            r_id = body.get("id") or f"room_{campus_id}_{room_id}"
            floor = int(body.get("floor", 1))
            floor_label = body.get("floor_label") or f"{floor}th Floor"
            with get_db() as conn:
                cursor = conn.cursor()
                existing = cursor.execute('SELECT id FROM rooms WHERE campus_id=? AND building_code=? AND room_id=?',
                    (campus_id,body.get('building_code','AB'),room_id)).fetchone()
                if existing and not body.get('id'): r_id = existing['id']
                cursor.execute("""
                    INSERT INTO rooms (
                        id, campus_id, building_code, room_id, name, floor,
                        floor_label, category, assigned_section, assigned_course,
                        department, description
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    ON CONFLICT(id) DO UPDATE SET
                        building_code = excluded.building_code,
                        room_id = excluded.room_id,
                        name = excluded.name,
                        floor = excluded.floor,
                        floor_label = excluded.floor_label,
                        category = excluded.category,
                        assigned_section = excluded.assigned_section,
                        assigned_course = excluded.assigned_course,
                        department = excluded.department,
                        description = excluded.description
                """, (
                    r_id, campus_id,
                    body.get("building_code", "AB"),
                    room_id,
                    body.get("name", room_id),
                    floor, floor_label,
                    body.get("category", "classroom"),
                    body.get("assigned_section"),
                    body.get("assigned_course"),
                    body.get("department", ""),
                    body.get("description", "")
                ))
                conn.commit()
            self.send_response(200)
            self._set_cors_headers()
            self.end_headers()
            self.wfile.write(json.dumps({"status": "saved", "id": r_id}).encode("utf-8"))
            return

        # 6. Admin Create Announcement (POST /api/admin/announcement or POST /api/messages)
        if path in ("/api/admin/announcement", "/api/messages"):
            campus_id = body.get("campus_id", "MSRIT")
            new_id = body.get("id") or f"msg_{int(time.time()*1000)}_{uuid.uuid4().hex[:6]}"
            now = int(time.time() * 1000)
            with get_db() as conn:
                cursor = conn.cursor()
                cursor.execute("""
                    INSERT INTO announcements (id, campus_id, title, content, priority, audience, created_at, is_archived)
                    VALUES (?, ?, ?, ?, ?, ?, ?, 0)
                """, (
                    new_id, campus_id,
                    body.get("title", "Campus Notice"),
                    body.get("content", ""),
                    body.get("priority", "Important"),
                    body.get("audience", "All Students"),
                    now
                ))
                conn.commit()
            print(f"[UNO Q BROADCAST] Created announcement: {body.get('title')}")
            self.send_response(201)
            self._set_cors_headers()
            self.end_headers()
            self.wfile.write(json.dumps({"status": "created", "id": new_id}).encode("utf-8"))
            return

        # 7. Admin Create Classroom Override (POST /api/admin/override or POST /api/announcements)
        if path in ("/api/admin/override", "/api/announcements"):
            campus_id = body.get("campus_id", "MSRIT")
            new_id = body.get("id") or f"anno_{int(time.time()*1000)}_{uuid.uuid4().hex[:6]}"
            now = int(time.time() * 1000)
            course_code = body.get("targetCourseCode") or body.get("target_course_code") or "GENERAL"
            with get_db() as conn:
                cursor = conn.cursor()
                # Deactivate older override for same course code
                cursor.execute(
                    "UPDATE overrides SET is_active = 0 WHERE campus_id = ? AND target_course_code = ?",
                    (campus_id, course_code)
                )
                cursor.execute("""
                    INSERT INTO overrides (
                        id, campus_id, target_course_code, target_section,
                        new_building_code, new_building_name, new_room_id,
                        new_floor, new_floor_label, reason, is_permanent, effective_time, created_at, is_active
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 1)
                """, (
                    new_id, campus_id, course_code,
                    body.get("targetSection") or body.get("target_section") or "All",
                    body.get("newBuildingCode") or body.get("new_building_code") or "LHC",
                    body.get("newBuildingName") or body.get("new_building_name") or "Lecture Hall Complex",
                    body.get("newRoomId") or body.get("new_room_id") or "LHC Room 212",
                    int(body.get("newFloor") or body.get("new_floor") or 2),
                    body.get("newFloorLabel") or body.get("new_floor_label") or "2nd Floor",
                    body.get("reason", "Classroom reassignment"),
                    1 if body.get("is_permanent") else 0,
                    body.get("effective_time", "Immediate"),
                    now
                ))
                conn.commit()
            print(f"[UNO Q BROADCAST] Published override for {course_code}")
            self.send_response(201)
            self._set_cors_headers()
            self.end_headers()
            self.wfile.write(json.dumps({"status": "created", "id": new_id}).encode("utf-8"))
            return

        # 8. Admin Update Issue Status / Note (POST /api/admin/reports/status or POST /api/reports/resolve)
        if path in ("/api/admin/reports/status", "/api/reports/resolve"):
            target_id = body.get("id")
            new_status = body.get("status", "Resolved")
            note = body.get("resolution_note", "")
            with get_db() as conn:
                cursor = conn.cursor()
                cursor.execute(
                    "UPDATE issue_reports SET status = ?, resolution_note = ? WHERE id = ?",
                    (new_status, note, target_id)
                )
                conn.commit()
            print(f"[UNO Q STATUS] Issue {target_id} updated to {new_status}")
            self.send_response(200)
            self._set_cors_headers()
            self.end_headers()
            self.wfile.write(json.dumps({"status": "updated", "id": target_id}).encode("utf-8"))
            return

        # 9. Admin Import Pack (POST /api/admin/import)
        if path == "/api/admin/import":
            campus_id = body.get("campus_id", "MSRIT")
            pack = body.get("pack", {})
            now = int(time.time() * 1000)
            with get_db() as conn:
                cursor = conn.cursor()
                # Update campus metadata
                cursor.execute("""
                    INSERT INTO campuses (
                        campus_id, college_name, short_name, website, city, country,
                        address, description, base_name, base_lat, base_lng, base_desc,
                        pack_version, is_published, created_at, updated_at
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 1, ?, ?)
                    ON CONFLICT(campus_id) DO UPDATE SET
                        college_name = excluded.college_name,
                        pack_version = excluded.pack_version,
                        updated_at = excluded.updated_at
                """, (
                    campus_id,
                    pack.get("campus_name", campus_id),
                    pack.get("short_name", campus_id),
                    pack.get("website", ""),
                    pack.get("city", "Bengaluru"),
                    pack.get("country", "India"),
                    pack.get("address", ""),
                    pack.get("description", ""),
                    pack.get("base_location", {}).get("name", "Main Gate"),
                    pack.get("base_location", {}).get("latitude", 13.0295),
                    pack.get("base_location", {}).get("longitude", 77.5650),
                    pack.get("base_location", {}).get("description", ""),
                    pack.get("pack_version", "1.0.0"),
                    now, now
                ))

                # Clear and insert buildings/rooms
                cursor.execute("DELETE FROM rooms WHERE campus_id = ?", (campus_id,))
                cursor.execute("DELETE FROM buildings WHERE campus_id = ?", (campus_id,))

                for b in pack.get("buildings", []):
                    b_id = b.get("id") or f"bldg_{campus_id}_{b.get('code')}"
                    cursor.execute("""
                        INSERT INTO buildings (id, campus_id, code, name, latitude, longitude, total_floors, description)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                    """, (
                        b_id, campus_id, b.get("code", ""), b.get("name", ""),
                        float(b.get("latitude", 0.0)), float(b.get("longitude", 0.0)),
                        int(b.get("total_floors", 1)), b.get("description", "")
                    ))
                    for r in b.get("rooms", []):
                        r_id = f"room_{campus_id}_{r.get('room_id')}"
                        cursor.execute("""
                            INSERT INTO rooms (
                                id, campus_id, building_code, room_id, name, floor,
                                floor_label, category, assigned_section, assigned_course,
                                department, description
                            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """, (
                            r_id, campus_id, b.get("code", ""), r.get("room_id", ""),
                            r.get("name", r.get("room_id", "")), int(r.get("floor", 1)),
                            r.get("floor_label", f"{r.get('floor', 1)}th Floor"),
                            r.get("category", "classroom"), r.get("assigned_section"),
                            r.get("assigned_course"), r.get("department", ""),
                            r.get("description", "")
                        ))
                conn.commit()

            self.send_response(200)
            self._set_cors_headers()
            self.end_headers()
            self.wfile.write(json.dumps({"status": "imported", "campus_id": campus_id}).encode("utf-8"))
            return

        # 10. Admin Add / Edit Timetable Slot (POST /api/admin/timetable or /api/timetable)
        if path in ("/api/admin/timetable", "/api/timetable"):
            campus_id = body.get("campus_id", "MSRIT")
            slot_id = body.get("id") or f"tt_{campus_id.lower()}_{secrets.token_hex(4)}"
            semester = int(body.get("semester", 1))
            department = body.get("department", "Computer Science & Engineering").strip()
            section_id = body.get("section_id", "A").strip().upper()
            day_of_week = body.get("day_of_week", "MONDAY").strip().upper()
            period = int(body.get("period", 1))
            start_time = body.get("start_time", "09:00").strip()
            end_time = body.get("end_time", "10:00").strip()
            course_code = body.get("course_code", "").strip().upper()
            course_name = body.get("course_name", "").strip()
            instructor = body.get("instructor", "Faculty").strip()
            room_id = body.get("room_id", "").strip()
            building_name = body.get("building_name", "Apex Block").strip()
            is_lab = 1 if body.get("is_lab") in (True, 1, "1", "true") else 0
            created_at = int(time.time() * 1000)

            with get_db() as conn:
                cursor = conn.cursor()
                cursor.execute("""
                    INSERT OR REPLACE INTO college_timetables
                    (id, campus_id, semester, department, section_id, day_of_week, period, start_time, end_time, course_code, course_name, instructor, room_id, building_name, is_lab, created_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, (slot_id, campus_id, semester, department, section_id, day_of_week, period, start_time, end_time, course_code, course_name, instructor, room_id, building_name, is_lab, created_at))
                conn.commit()

            self.send_response(200)
            self._set_cors_headers()
            self.end_headers()
            self.wfile.write(json.dumps({"success": True, "id": slot_id, "message": "Timetable slot saved"}).encode("utf-8"))
            return

        # 11. Admin Seed Timetable Slots (POST /api/admin/timetable/seed)
        if path == "/api/admin/timetable/seed":
            query = urllib.parse.parse_qs(parsed.query)
            campus_id = query.get("campus", ["MSRIT"])[0]
            with get_db() as conn:
                seed_default_timetables(conn)
            self.send_response(200)
            self._set_cors_headers()
            self.end_headers()
            self.wfile.write(json.dumps({"success": True, "message": "Default timetables seeded"}).encode("utf-8"))
            return

        self.send_response(404)
        self._set_cors_headers()
        self.end_headers()
        self.wfile.write(json.dumps({"error": "Unknown POST endpoint"}).encode("utf-8"))

    def do_DELETE(self):
        parsed = urllib.parse.urlparse(self.path)
        path = parsed.path
        query = urllib.parse.parse_qs(parsed.query)
        target_id = query.get("id", [None])[0]
        campus_id = query.get("campus", ["MSRIT"])[0]

        # 1. Delete Announcement
        if path in ("/api/admin/announcement", "/api/messages"):
            if target_id:
                with get_db() as conn:
                    cursor = conn.cursor()
                    cursor.execute("DELETE FROM announcements WHERE id = ?", (target_id,))
                    conn.commit()
            self.send_response(200)
            self._set_cors_headers()
            self.end_headers()
            self.wfile.write(json.dumps({"status": "deleted", "id": target_id}).encode("utf-8"))
            return

        # 2. Delete Override
        if path in ("/api/admin/override", "/api/announcements"):
            if target_id:
                with get_db() as conn:
                    cursor = conn.cursor()
                    cursor.execute("DELETE FROM overrides WHERE id = ?", (target_id,))
                    conn.commit()
            self.send_response(200)
            self._set_cors_headers()
            self.end_headers()
            self.wfile.write(json.dumps({"status": "deleted", "id": target_id}).encode("utf-8"))
            return

        # 3. Delete Building
        if path == "/api/admin/building":
            if target_id:
                with get_db() as conn:
                    cursor = conn.cursor()
                    cursor.execute("SELECT code FROM buildings WHERE id = ?", (target_id,))
                    b_row = cursor.fetchone()
                    if b_row:
                        cursor.execute("DELETE FROM rooms WHERE campus_id = ? AND building_code = ?", (campus_id, b_row[0]))
                    cursor.execute("DELETE FROM buildings WHERE id = ?", (target_id,))
                    conn.commit()
            self.send_response(200)
            self._set_cors_headers()
            self.end_headers()
            self.wfile.write(json.dumps({"status": "deleted", "id": target_id}).encode("utf-8"))
            return

        # 4. Delete Room
        if path == "/api/admin/room":
            room_id = query.get("room_id", [None])[0]
            if room_id:
                with get_db() as conn:
                    cursor = conn.cursor()
                    cursor.execute("DELETE FROM rooms WHERE campus_id = ? AND room_id = ?", (campus_id, room_id))
                    conn.commit()
            self.send_response(200)
            self._set_cors_headers()
            self.end_headers()
            self.wfile.write(json.dumps({"status": "deleted", "room_id": room_id}).encode("utf-8"))
            return

        # 5. Delete Timetable Slot
        if path in ("/api/admin/timetable", "/api/timetable"):
            if target_id:
                with get_db() as conn:
                    cursor = conn.cursor()
                    cursor.execute("DELETE FROM college_timetables WHERE id = ?", (target_id,))
                    conn.commit()
            self.send_response(200)
            self._set_cors_headers()
            self.end_headers()
            self.wfile.write(json.dumps({"status": "deleted", "id": target_id}).encode("utf-8"))
            return

        self.send_response(404)
        self._set_cors_headers()
        self.end_headers()
        self.wfile.write(json.dumps({"error": "Unknown DELETE endpoint"}).encode("utf-8"))

    def log_message(self, format, *args):
        sys.stderr.write(f"[{time.strftime('%H:%M:%S')}] {format % args}\n")

def run():
    global PORT, HOST
    PORT = int(os.environ.get("PORT", PORT))
    HOST = os.environ.get("HOST", HOST)
    os.makedirs(os.path.dirname(os.path.abspath(DB_PATH)), exist_ok=True)
    init_db()
    print("=" * 70)
    print("PrismCampus - Arduino Uno Q / Laptop LAN Edge Server")
    print(f"Server online on http://localhost:{PORT} and http://{SERVER_LAN_IP}:{PORT}")
    print(f"Open http://localhost:{PORT} in your browser for the Administrator Dashboard.")
    print("=" * 70)

    http.server.ThreadingHTTPServer.allow_reuse_address = True
    with http.server.ThreadingHTTPServer((HOST, PORT), enhance(sys.modules[__name__])) as httpd:
        try:
            httpd.serve_forever()
        except KeyboardInterrupt:
            print("\nShutting down Uno Q Edge Server...")
            httpd.server_close()

if __name__ == "__main__":
    run()
