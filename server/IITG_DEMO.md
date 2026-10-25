# IITG rehearsal backup

The standalone JSON is `app/src/main/assets/iitg_demo_pack.json`. In the Android campus selector choose **IITG_DEMO**. It opens instantly without Agent37, Nodus, a laptop server or internet. MSRIT remains a separate option. The demo ID deliberately does not sync live announcements.

This dataset covers first-year groups only, based on the official July–November 2026 first-year course/classroom PDF. It preserves the original course assignments in `first_year_course_catalog`. The displayed schedule chooses post-midsemester courses. Unknown laboratory rooms are labelled TBC and navigation to them is blocked. BDes studio times are unspecified and have no invented schedule.

Sources:
- https://www.iitg.ac.in/acad/pdfs/ctt/1st_year_timetable_classroom.pdf
- https://www.iitg.ac.in/chemeng/pdf/time_table_july_nov_2026.pdf
- https://www.openstreetmap.org/ (each pin also retains its feature URL in JSON)

Map attribution: © OpenStreetMap contributors, ODbL 1.0. Map points are existing points or footprint centres, not verified entrances. This is a partial directory, not a survey of most campus rooms. Classroom numbers come from the official first-year source; Core 5 association is inferred from numbering and floors remain unverified. The library pin uses the Computer Centre point in their shared building.

The official department's 2026 PDF contains a stale 2025 caption on its slot grid. Weekly theory times expanded from that grid are **provisional demo times**, including generic tutorial repetitions, not a validated personal attendance timetable. Explicit afternoon lab times are taken from the first-year PDF. When a lab occupies an afternoon, generic theory-slot repetitions are omitted for the demonstration. Alternative room numbers use the first listed room. Confirm the actual group, slot-time mapping, floor and room before real navigation or attendance. These caveats are also embedded in JSON `demo_metadata`.

Rebuild from public sources with `python server/build_iitg_backup.py` (requires pypdf). This does not publish a campus or contact paid APIs.

For rehearsal, switch to Schedule and select a weekday. Voice and general AI keep their existing online/offline routing. The app never saves chat history to disk: tabs share the current open session, and closing the activity or restarting the process begins a fresh chat. Backgrounding briefly keeps the current session. Previously downloaded AI models and campus packages remain stored.
