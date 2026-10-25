<div align="center">

# PrismCampus
### Your campus companion. Online or offline.

Find your next class, organize your day, explore campus and get help—even when the internet drops.

**Android · Voice · On-device AI · Campus maps · Local Wi-Fi sync**

Built by **Chiranth B K** · **CrystalX** · **BUILD 2026**

</div>

---

## Why PrismCampus?

Your first day on campus should not mean searching WhatsApp groups for a timetable, asking around for classrooms and opening several websites for announcements.

PrismCampus brings those everyday needs into one Android app. Students get their schedule, saved campus information and an assistant that keeps helping offline. Administrators get a web workspace to onboard campuses and publish updates through one persistent campus ID.

## What it does

- **Organize your day:** classes, assignments, next-class answers and timetable image import with ML Kit OCR.
- **Ask by voice or text:** spoken responses and English, Hindi and Kannada language options.
- **Get help online and offline:** Gemini online; Qwen2.5-0.5B-Instruct running directly on the phone offline.
- **Explore campus:** saved building GPS pins, room references and approximate directions from your location.
- **Keep your conversation across tabs:** chat stays while navigating Home, Schedule and Map during the current app session.
- **Report facility issues:** save reports locally and retry synchronization when a campus server is reachable.
- **See memory use:** live monitoring and generation checks against a **1024 MiB app memory budget**.

## How the assistant chooses an answer

1. **Campus commands and arithmetic** use saved records or direct calculation for an instant answer.
2. **General questions online** go to Gemini, with an on-device fallback if the cloud request fails.
3. **General questions offline** run through Qwen and native llama.cpp on the phone, without the laptop server.

Campus answers use the selected campus's records. General definitions should not include unrelated college facts or invented placement statistics.

Try **“Where is my next class?”**, **“Show my timetable”**, **“What is a Java package?”** or **“Eight plus five.”** Arithmetic demonstrates the instant path, not Qwen inference.

## Campus Studio & class updates

**Campus Studio** starts with a college website, collects public information and asks for missing details. Administrators can review sources, preview imports, edit buildings and GPS pins, add rooms and student groups, and publish a persistent campus ID. Drafts can be deleted without deleting published campuses.

**Announcements & Class Updates** provides the working admin view for announcements, classroom overrides, timetables, buildings, rooms and maintenance reports. Timetable filters follow the selected campus.

- Website extraction works locally; missing schedules and locations need manual input.
- GPS coordinates support manual entry and an offline coordinate grid. The grid is not a street map.
- Agent37 hosts the registry and dashboard. Hermes-driven autonomous extraction is not currently implemented.
- Nodus is an optional extraction integration requiring separate credentials, not a requirement for local onboarding or Wi-Fi sync.

## One campus ID, two connection paths

**Internet:** a public HTTPS registry serves the package for a published campus ID.

**Local Wi-Fi:** a laptop or campus machine serves a downloaded package, announcements, classroom changes and report updates. Public internet is not needed when the phone can reach this local server. Previously downloaded campus information remains available on the phone offline.

Same Wi-Fi alone does not guarantee connectivity: client isolation, firewalls, VPNs and laptop sleep can block local connections. Cloud changes must be downloaded onto the local server again; mirroring is not continuous.

## Demo data

MSRIT demonstration data and the IIT Guwahati first-year demo are kept separate.

The IITG package includes **14 first-year groups, 262 weekly class entries and 34 mapped places**. The published demo ID is `IITG_77356889E768`; the separate bundled backup is `IITG_DEMO`. Hosted-demo availability depends on the running registry.

**This is demonstration data:** course and room references come from IITG's first-year timetable, expanded theory times are provisional, GPS pins are approximate, and floors and some lab rooms remain unverified. Unknown lab rooms cannot be routed. It is not an official campus service or a complete classroom survey.

Sources: [IITG timetable](https://www.iitg.ac.in/acad/pdfs/ctt/1st_year_timetable_classroom.pdf), [provisional slot grid](https://www.iitg.ac.in/chemeng/pdf/time_table_july_nov_2026.pdf), [IITG SPIN Lab community map](https://spinlabiitg.github.io/iitg_umap/) and [OpenStreetMap](https://www.openstreetmap.org/). Map data © OpenStreetMap contributors, ODbL. The community map is explicitly unofficial.

## Start the server

Requires **Python 3.12+**. The core server uses the Python standard library and SQLite.

```bash
git clone https://github.com/chiranth0903/PrismCampus.git
cd PrismCampus
```

Copy `server/.env.example` to `server/.env.local`. Set `PRISM_ADMIN_PASSWORD` to a strong password of at least 12 characters. Configure cloud-provider settings only for integrations you use.

On Windows:

```bat
start_admin_server.bat
```

Or run directly:

```bash
python server/uno_q_server.py
```

Open **http://localhost:8000**. Sign in as `admin` with your configured password. Campus Studio is at `/`; the updates workspace is at `/operations`.

For phones, enter **`http://YOUR-LAPTOP-IP:8000`** and the campus ID. Find the laptop's IPv4 address with `ipconfig`. Test `/api/health` from the phone browser if connection fails, keep the laptop awake and allow the server through your trusted private-network firewall. `localhost` on the phone means the phone.

A fresh clone creates a new local database. Published cloud data is not included in Git: configure the registry URL and download a campus through **Connections & sync**, or onboard your own campus.

## Build Android

Use Android Studio, **JDK 17 or 21**, Android SDK **34**, NDK **26.1.10909125** and CMake **3.22.1**. Minimum Android version: **8.0 / API 26**. Native targets: `arm64-v8a` and `x86_64`.

1. Open the project root in Android Studio and sync Gradle. Initial dependency setup needs internet.
2. Keep the SDK path in Android Studio's ignored `local.properties`. Add your own Gemini key there, or supply the `GEMINI_API_KEY` build environment variable:

   ```properties
   GEMINI_API_KEY=YOUR_GEMINI_API_KEY
   ```

3. Obtain compatible **Qwen2.5-0.5B-Instruct Q4_K_M GGUF** weights and place them at:

   ```text
   server/models/qwen2.5-0.5b-instruct-q4_k_m.gguf
   ```

   Gradle bundles this file into generated APK assets. Model downloads are excluded from Git; a fresh clone needs this step for offline general answers. llama.cpp source is vendored.
4. Build on Windows:

   ```bat
   gradlew.bat :app:assembleDebug
   ```

APK output: `app/build/outputs/apk/debug/app-debug.apk`.

For your own public server, set `campusRegistryUrl` in `gradle.properties`, or build with `-PcampusRegistryUrl=https://YOUR-REGISTRY`. Server credentials belong only on the server.

## Technology

- **Kotlin + Jetpack Compose:** Android interface and navigation.
- **Gemini:** general online answers.
- **Qwen2.5-0.5B-Instruct + llama.cpp:** on-device offline generation.
- **Vosk, Android speech APIs and TextToSpeech:** voice input and spoken responses.
- **ML Kit:** timetable text recognition.
- **Python, SQLite, HTML/CSS/JavaScript:** campus registry and admin dashboard.
- **Agent37:** backend hosting. **Nodus:** optional extraction assistance.

## Current limits & next steps

- **1024 MiB is a runtime budget, not an Android-enforced ceiling.** PSS monitoring, RSS generation checks and Java heap readings are different measurements. No universal sub-1-GB peak is claimed.
- Offline model latency and speech support depend on the device. The bundled offline speech model is English; other speech languages depend on available recognition services and installed voices.
- Directions are approximate, not verified indoor positioning or guaranteed walkable routes.
- Maintenance grouping currently uses rules and distinct installation identities. Backend Qwen semantic grouping, complete merge/split workflows and verified real-person support counts remain future work.
- Production use needs verified campus data, an authorized data owner and a student pilot. App-embedded API keys are extractable from APKs; production cloud access needs an appropriate protected backend.

Next priorities: verified schedules and room locations, stronger offline multilingual speech, semantic maintenance grouping and a campus pilot.

## Development checks

```bash
python -m unittest server.test_onboarding -v
```

```bat
gradlew.bat :app:testDebugUnitTest
```

These checks do not replace microphone, GPS or disconnected-network verification on a real phone.

## Repository notes

`.gitignore` excludes private settings, APKs, runtime databases and sessions, model downloads, signing keys, logs and temporary profiling files. Keep these local and do not force-add secrets.

Third-party code, models and map data retain their respective licenses and attribution requirements. No license is currently declared for the original PrismCampus code.
