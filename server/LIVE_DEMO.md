# PrismCampus live demo

Dashboard: **https://prismcampus-iv0v8tfxx2.agent37.app**. Sign in as **admin** using your configured `PRISM_ADMIN_PASSWORD` in `server/.env.local`. Keep that file private.

Android campus ID: **IITG_77356889E768**. The APK includes the public registry address. MSRIT remains separate.

IITG has 14 first-year groups, 262 weekly entries, 18 map places and 12 room entries including a TBC placeholder. Weekly theory times are provisional, pins approximate and floors unverified. Unknown lab destinations cannot be routed. Review before real travel.

## Local Wi-Fi

Run `start_admin_server.bat` and keep the laptop awake. The current background service uses port 8000. Its current address is **http://10.218.37.142:8000**; check `ipconfig` after changing networks. Enter that address and the same campus ID in the app.

IITG is mirrored into the laptop database. After cloud edits, download the published campus again through **Connections & sync**; cloud-to-laptop mirroring is not continuous. Phones retain downloaded data offline.

If connection fails, open `http://LAPTOP-IP:8000/api/health` from the phone browser. Check both devices' network, laptop sleep, trusted-private-network firewall access, VPN and guest/client isolation. Same Wi-Fi alone does not guarantee access. `localhost` on the phone means the phone.

## Updates and limits

Agent37 hosts the Python registry with persistent storage and a restart hook. Hermes-agent extraction is not implemented. Local website extraction works; Nodus remains optional and unconfigured. Later backend deployments: `python server/agent37_deploy.py deploy`, using the configured environment. Existing cloud data is retained.

Latest APK: `app/build/outputs/apk/debug/app-debug.apk`. Final assignment-isolation and map-prompt fixes are built but were not installed after you stopped remaining phone checks. See `artifacts/user-walkthrough/REPORT.md` for actual verification.
