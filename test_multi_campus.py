#!/usr/bin/env python3
"""
PrismCampus Automated Server & Multi-Campus Test Suite
Tests all 14 required functionalities locally.
"""

import urllib.request
import urllib.parse
import json
import time
import sys
import threading
import socketserver
import os

BASE_DIR = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(BASE_DIR, "server"))
import uno_q_server

PORT = 8999
HOST = "127.0.0.1"
BASE_URL = f"http://{HOST}:{PORT}"

def run_server():
    uno_q_server.PORT = PORT
    uno_q_server.HOST = HOST
    uno_q_server.init_db()
    socketserver.TCPServer.allow_reuse_address = True
    with socketserver.TCPServer((HOST, PORT), uno_q_server.UnoQEdgeServerHandler) as httpd:
        httpd.serve_forever()

def get_json(path):
    url = f"{BASE_URL}{path}"
    req = urllib.request.Request(url)
    with urllib.request.urlopen(req, timeout=5) as resp:
        return resp.status, json.loads(resp.read().decode("utf-8"))

def post_json(path, data):
    url = f"{BASE_URL}{path}"
    raw = json.dumps(data).encode("utf-8")
    req = urllib.request.Request(url, data=raw, headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=5) as resp:
        return resp.status, json.loads(resp.read().decode("utf-8"))

def main():
    print("=" * 65)
    print("Starting PrismCampus Edge Server Test on port", PORT)
    print("=" * 65)

    server_thread = threading.Thread(target=run_server, daemon=True)
    server_thread.start()
    time.sleep(1.0)

    # Test 1: Server Health Check
    status, res = get_json("/api/health")
    assert status == 200, f"Expected 200, got {status}"
    assert res["status"] == "healthy"
    print("[PASS] Test 1: Server health check succeeded (status: healthy)")

    # Test 2: List Campuses
    status, campuses = get_json("/api/campuses")
    assert status == 200
    assert any(c["campus_id"] == "MSRIT" for c in campuses)
    print(f"[PASS] Test 2: Campuses listed successfully ({len(campuses)} campuses found, including MSRIT)")

    # Test 3: Lookup Campus MSRIT
    status, msrit_meta = get_json("/api/campus/MSRIT/lookup")
    assert status == 200
    assert msrit_meta["campus_id"] == "MSRIT"
    assert "Ramaiah" in msrit_meta["college_name"]
    print(f"[PASS] Test 3: Campus lookup for MSRIT succeeded ('{msrit_meta['college_name']}')")

    # Test 4: Download MSRIT .prismpack Package
    status, pack = get_json("/api/campus/MSRIT/package")
    assert status == 200
    assert pack["campus_id"] == "MSRIT"
    assert len(pack["buildings"]) >= 3, f"Expected >= 3 buildings, got {len(pack['buildings'])}"
    assert len(pack["sections"]) >= 3, f"Expected >= 3 sections, got {len(pack['sections'])}"
    print(f"[PASS] Test 4: MSRIT package downloaded ({len(pack['buildings'])} buildings, {len(pack['sections'])} sections)")

    # Test 5: Backward-compatible /api/pack endpoint
    status, legacy_pack = get_json("/api/pack?campus=MSRIT")
    assert status == 200
    assert legacy_pack["campus_id"] == "MSRIT"
    print("[PASS] Test 5: Backward-compatible /api/pack endpoint works")

    # Test 6: Create New Campus (e.g. BMSCE)
    new_campus = {
        "campus_id": "BMSCE",
        "college_name": "B.M.S. College of Engineering",
        "short_name": "BMSCE",
        "city": "Bengaluru",
        "country": "India",
        "address": "Bull Temple Rd, Basavanagudi, Bengaluru, Karnataka 560019",
        "description": "Autonomous college affiliated with VTU",
        "base_name": "Main Gate",
        "base_lat": 12.9416,
        "base_lng": 77.5654
    }
    status, create_res = post_json("/api/admin/campus", new_campus)
    assert status == 200 and create_res["status"] == "saved"
    print("[PASS] Test 6: Created new campus 'BMSCE'")

    # Test 7: Add Building to BMSCE
    bldg_data = {
        "campus_id": "BMSCE",
        "code": "PG_BLOCK",
        "name": "Post Graduate Block",
        "latitude": 12.9420,
        "longitude": 77.5660,
        "total_floors": 6,
        "description": "Block for MCA, M.Tech and Research"
    }
    status, bldg_res = post_json("/api/admin/building", bldg_data)
    assert status == 200 and bldg_res["status"] == "saved"
    print("[PASS] Test 7: Added building 'PG_BLOCK' to BMSCE")

    # Test 8: Add Room to BMSCE PG_BLOCK
    room_data = {
        "campus_id": "BMSCE",
        "building_code": "PG_BLOCK",
        "room_id": "PG-301",
        "name": "Data Analytics Lab",
        "floor": 3,
        "floor_label": "3rd Floor",
        "category": "laboratory",
        "department": "Computer Applications",
        "description": "High performance computing cluster lab"
    }
    status, room_res = post_json("/api/admin/room", room_data)
    assert status == 200 and room_res["status"] == "saved"
    print("[PASS] Test 8: Added room 'PG-301' to BMSCE")

    # Test 9: Verify BMSCE Package contains the new Building and Room
    status, bmsce_pack = get_json("/api/campus/BMSCE/package")
    assert status == 200
    assert len(bmsce_pack["buildings"]) == 1
    assert bmsce_pack["buildings"][0]["code"] == "PG_BLOCK"
    assert len(bmsce_pack["buildings"][0]["rooms"]) == 1
    assert bmsce_pack["buildings"][0]["rooms"][0]["room_id"] == "PG-301"
    print("[PASS] Test 9: BMSCE hierarchical compilation verified (Campus -> Building -> Floor -> Room)")

    # Test 10: Broadcast Announcement
    anno_data = {
        "campus_id": "BMSCE",
        "title": "Semester Registration Deadline Extended",
        "content": "All students can register on the portal until 5:00 PM Friday.",
        "priority": "Important",
        "audience": "All Students"
    }
    status, anno_res = post_json("/api/admin/announcement", anno_data)
    assert status == 201 and anno_res["status"] == "created"
    print("[PASS] Test 10: Published announcement for BMSCE")

    # Test 11: Classroom Override Creation & Retrieval
    override_data = {
        "campus_id": "BMSCE",
        "targetCourseCode": "MCA301",
        "targetSection": "A",
        "newBuildingCode": "PG_BLOCK",
        "newBuildingName": "Post Graduate Block",
        "newRoomId": "PG-301",
        "newFloor": 3,
        "newFloorLabel": "3rd Floor",
        "reason": "Moved to Data Analytics Lab for hands-on machine learning session"
    }
    status, ov_res = post_json("/api/admin/override", override_data)
    assert status == 201 and ov_res["status"] == "created"

    status, announcements_query = get_json("/api/campus/BMSCE/announcements")
    assert status == 200
    assert len(announcements_query["messages"]) >= 1
    assert len(announcements_query["announcements"]) >= 1
    assert announcements_query["announcements"][0]["targetCourseCode"] == "MCA301"
    print("[PASS] Test 11: Classroom override created and retrieved via API")

    # Test 12: Civic Issue Report Clustering & Frequency Ranking
    report_batch_1 = {
        "campus_id": "MSRIT",
        "device_id": "Student_Phone_Alpha",
        "reports": [
            {
                "category": "Electrical",
                "building": "Hostel Complex",
                "room": "Hostel Mess",
                "description": "Ceiling fan vibrating and making noise",
                "urgency": "Medium"
            }
        ]
    }
    status, sync_res_1 = post_json("/api/reports/sync", report_batch_1)
    assert status == 200

    report_batch_2 = {
        "campus_id": "MSRIT",
        "device_id": "Student_Phone_Beta",
        "reports": [
            {
                "category": "Electrical",
                "building": "Hostel Complex",
                "room": "Hostel Mess",
                "description": "Hostel mess fan broken loud noise",
                "urgency": "Medium"
            }
        ]
    }
    status, sync_res_2 = post_json("/api/reports/sync", report_batch_2)
    assert status == 200
    assert sync_res_2["updated_existing_clusters"] >= 1, "Duplicate report should merge into existing cluster"
    print("[PASS] Test 12: Civic issue deduplication and clustering verified (merged into single cluster)")

    # Test 13: Admin Issue Status Resolution Note
    status, rep_query = get_json("/api/reports/sync?campus=MSRIT")
    assert status == 200
    hostel_fan_item = next(r for r in rep_query["reports"] if "hostel_mess" in r["cluster_key"])
    assert hostel_fan_item["report_count"] >= 5
    assert hostel_fan_item["escalated"] == 1

    status_update = {
        "id": hostel_fan_item["id"],
        "status": "Resolved",
        "resolution_note": "Electrician replaced fan motor and balanced blades.",
        "campus_id": "MSRIT"
    }
    status, res_update = post_json("/api/admin/reports/status", status_update)
    assert status == 200

    status, rep_query_after = get_json("/api/reports/sync?campus=MSRIT")
    updated_item = next(r for r in rep_query_after["reports"] if r["id"] == hostel_fan_item["id"])
    assert updated_item["status"] == "Resolved"
    assert updated_item["resolution_note"] == "Electrician replaced fan motor and balanced blades."
    print("[PASS] Test 13: Admin issue status update and resolution note persisted")

    # Test 14: Publish Campus Draft Version
    status, pub_res = post_json("/api/admin/campus/publish", {"campus_id": "MSRIT"})
    assert status == 200 and pub_res["status"] == "published"
    print(f"[PASS] Test 14: Campus publishing succeeded (Version: {pub_res['version']})")

    print("=" * 65)
    print("ALL 14 TESTS PASSED CLEANLY WITH ZERO REGRESSIONS!")
    print("=" * 65)

if __name__ == "__main__":
    main()
