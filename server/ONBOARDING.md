# Campus Studio setup

## Start

**Current live deployment:** see [LIVE_DEMO.md](LIVE_DEMO.md) for the deployed Agent37 dashboard, published IITG campus ID and verified limitations. The deployment prerequisites and older unverified-status notes below describe the original setup; the current handoff supersedes those status notes.
Run `start_admin_server.bat` from the project root (Python 3.12+). Open http://localhost:8000. Local demo login: **admin / admin123**. Before sharing, set `PRISM_ADMIN_PASSWORD` to a strong password (12+ characters) and restart. It overrides the admin login password. Existing MSRIT data stays in `server/prism_campus.db`; back up this database before moving hosting.

## Your existing accounts
Edit **server/.env.local** (copy `.env.example` if absent). Never paste keys in chat or include them in an APK.

- `AGENT37_API_KEY`: server control API key.
- `AGENT37_INSTANCE_ID`: existing instance intended to host the registry.
- `NODUS_API_KEY`: inference key with invocation permission.
- `NODUS_MODEL`: a model available to your account; default `nodus/gpt-oss-20b`.
- `PUBLIC_CAMPUS_REGISTRY_URL`: the public HTTPS address of the running PrismCampus server, not Agent37's control API.
- `PRISM_ADMIN_PASSWORD`: strong dashboard password.

Restart after editing. Open **Connections & sync** and verify connections (read-only checks). Entering keys does not create resources or expose a public port. Nodus is optional: choosing it sends public source excerpts for inference and may incur charges; the interface asks first.

Agent37 hosting is prepared through the root Dockerfile. Run the server on port 8000 with persistent database storage; set `PRISM_DB_PATH` to the mounted database path. Configure secrets privately on the host. Confirm the target instance, hosting costs, persistent volume and public HTTPS port before exposing it. Test `/api/health` and dashboard sign-in on the deployed address before sharing. Campus packages are public; admin operations require sign-in. No cloud deployment is automatically performed.

## Onboard IIT Guwahati
1. Choose **Onboard a campus** and enter https://www.iitg.ac.in/.
2. Review official sources, course candidates and timetable links. Extraction is bounded; image-only, protected or differently structured pages may need manual input. AI suggestions require review.
3. Fill missing campus details, gate coordinates, buildings, floors, rooms, student groups and weekly class times. The offline coordinate grid is not a street map; manual coordinates work without tiles. No IITG locations or schedules are invented.
4. Preview a JSON campus document or timetable CSV before applying. Required CSV columns: `section_id,day,start_time,end_time,course_code,course_name,building_code,room_id`. Optional: `department,instructor,is_lab`. Days: Monday–Sunday. Times: HH:MM, 24-hour. Identical CSV slots are deduplicated.
5. Save/check, fix missing references, review booking warnings and the student preview, then publish. A permanent unique ID is generated and reused on updates.

Slot letters are not assumed to represent weekly times. Supply official time mappings or a corrected weekly CSV. Keep MSRIT demonstration schedules separate.

## Connect phones
**Local Wi-Fi without internet:** keep the laptop running. Find its active Wi-Fi IPv4 with `ipconfig`; enter `http://THAT-ADDRESS:8000` and the published campus ID in the app. Test `/api/health` from the phone browser first. Allow the server through Windows Firewall on your trusted private network if prompted. Guest Wi-Fi/client isolation, VPNs, blocked ports and laptop sleep can prevent access. `localhost` on a phone means the phone. No router internet port forwarding is needed.

**Internet by ID:** deploy the registry, then build Android using `-PcampusRegistryUrl=https://YOUR-REGISTRY` or the `PUBLIC_CAMPUS_REGISTRY_URL` build environment variable. No cloud credentials belong in Android. IDs require this shared registry address; entering a key alone does not make internet syncing operational.

**Cloud to local:** while online, configure the local server's public registry URL and download the published ID through Connections & sync. The same ID/package can then be served over Wi-Fi without internet. Repeat the download for later cloud changes. Previously downloaded phone data remains available offline.

## Validation and scope
Run `python -m unittest server.test_onboarding -v` from the root. Tests cover required data, stable IDs, draft snapshots, coordinates/references, stale edits, CSV retries, overlaps, isolation, private URL rejection, extraction and HTTP authentication. Android has separate Gradle tests/build.

Paid Nodus inference, Agent37 deployment, real Wi-Fi without internet and internet-to-phone sync need live credentials/deployment/devices and are not verified by these local tests. The legacy admin workspace is preserved. Advanced maintenance-report grouping and the earlier full announcement/override redesign remain separate outstanding work.
