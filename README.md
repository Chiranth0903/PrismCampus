# PrismCampus OS — Offline-First Edge-AI Campus Operating System

**PrismCampus** (package `com.prismcampus.app`) is an offline-first, low-RAM Campus Operating System tailored for **Ramaiah Institute of Technology (MSRIT)**, Bengaluru, and multi-campus deployments. 

The assistant answers predefined campus commands immediately from saved records, uses Gemini for general questions online, and runs the bundled **Qwen2.5-0.5B Instruct GGUF through llama.cpp** offline. Live process memory is displayed against an **1024 MiB (1 GiB) target budget**, with memory checks during local generation. See [assistant behavior and device checks](ASSISTANT_BEHAVIOR.md) for setup and limits.

---

## 🏛️ Campus Profile: MSRIT Bengaluru
- **Campus Name**: Ramaiah Institute of Technology (MSRIT)
- **Portal**: [https://msrit.edu](https://msrit.edu)
- **Base Anchor (Main Gate)**: Lat `13.0295`, Lng `77.5650`
- **Academic Blocks Mapped**:
  - **Apex Block (AB)** (`13.0303, 77.5647`, 7 Floors)
    - `AB-501`: 3rd Floor (Section A - CSE Default Classroom)
    - `AB-504`: 3rd Floor (Section D - ISE Default Classroom)
    - `AB-514`: 3rd Floor (Section I - AIML Default Classroom)
    - `Room 602`: 4th Floor (Physics Lab - PYC12)
    - `Room 703`: 5th Floor (C Programming Lab - PSCLCS18)
    - `Central Library`: Apex Annex (4 Floors)
    - `Principal & Academic Dean Offices`: Ground Floor
  - **Engineering Sciences Block (ESB)** (`13.0313, 77.5648`, 5 Floors)
    - `Room 402`: 4th Floor (CAD Lab - MELC19)
    - `Robotics & Prototyping Labs`
  - **Lecture Hall Complex (LHC)** (`13.0324, 77.5659`, 4 Floors)
    - `Room 212`: 2nd Floor (Soft Skills & Language Lab - HSCP15)
    - `Department of Training & Placement`: Ground Floor
    - `Auditoriums & Seminar Halls`

---

## 🚀 Key Modules & Architecture

### 1. Real-Time GPS Location & Navigation from Your Position
- **Native Android Location Manager (`location/CampusLocationManager.kt`)**:
  - Automatically queries GPS & Network location without external Google Play Services dependencies.
  - Computes real-time walking distance in meters to any classroom or facility.
- **Dynamic Origin Navigation (`ui/components/OfflineCampusCanvasMap.kt`)**:
  - Renders a pulsing cyan **"YOU ARE HERE (GPS)"** marker on the interactive vector map.
  - Draws the animated walking path directly from **your live GPS location** to the destination room!
  - Includes an **"On-Campus Simulation"** button for easy testing in Android Studio emulator or when off-campus.
- **"Pick Any Room" Selector**:
  - Tapping **[Pick Room]** opens a full directory of campus rooms (AB-501, AB-504, AB-514, Room 402 CAD Lab, Room 212 Soft Skills Lab, Room 602 Physics Lab, Room 703 C Lab, Library, etc.).
  - Selecting any room immediately recalibrates the navigation path from your GPS location directly to that room and speaks turn-by-turn guidance.

### 2. Natural On-Device Qwen Edge SLM Engine (`ai/HybridRouter.kt`)
- **Natural Responses Without Robotic System Placeholders**:
  - Eliminated robotic debug prefixes. Answers naturally in conversational tone.
  - Answers greetings (*"Hello! I am your PrismCampus companion. How can I assist you today?"*).
  - Answers academic questions (C pointers, recursion, arrays, Ohm's law, Newton's laws, calculus derivatives, Kirchhoff's laws, superconductivity).
  - Answers campus life questions (hostels, sports complex, library timings, canteen, Udbhav cultural fest, placement packages, Wi-Fi, CIE/SEE exams, SGPA/CGPA).
  - If a question is genuinely outside known domain, it responds naturally:
    - English: *"I'm sorry, I don't know that yet. You can ask me about campus locations, class schedules, engineering concepts, or report a facility issue."*
    - Kannada: *"ಕ್ಷಮಿಸಿ, ಇದರ ಬಗ್ಗೆ ನನಗೆ ಮಾಹಿತಿ ತಿಳಿದಿಲ್ಲ. ನೀವು ತರಗತಿಗಳು, ಲ್ಯಾಬ್‌ಗಳು, ಅಥವಾ ಇಂಜಿನಿಯರಿಂಗ್ ವಿಷಯಗಳ ಬಗ್ಗೆ ಕೇಳಬಹುದು."*
    - Hindi: *"माफ़ कीजिये, मुझे अभी इसके बारे में जानकारी नहीं है। आप मुझसे कक्षाओं, लैब या विषयों के बारे में पूछ सकते हैं।"*
- **Speech Synthesis:** All answers are immediately spoken aloud via Android's native `TextToSpeech` in your chosen language.

### 3. Integrated Voice Tour in Campus Map View (`ui/screens/CampusMapScreen.kt`)
- Voice Tour Guide is directly embedded in the Map screen with:
  - Push-to-talk mic button.
  - Quick tour chips: *"Where is CAD Lab?"*, *"Soft Skills Lab"*, *"Physics Lab"*, *"AB-501 (Sec A)"*, *"AB-504 (Sec D)"*, *"AB-514 (Sec I)"*, *"Apex Block"*, *"ESB Block"*, *"LHC Complex"*.
- Asking or tapping *"Where is CAD Lab?"*:
  1. Immediately selects **Engineering Sciences Block (ESB Room 402)**.
  2. Plots the animated walking path from your live GPS position to ESB.
  3. Speaks the narration out loud in English, Hindi, or Kannada.
  4. Shows the distance in meters and turn-by-turn walking steps.

### 4. Telemetry Bar with Airplane Mode & Compact Section Chips
- Top Telemetry Bar shows:
  - `✈️ Airplane Mode (100% Offline Qwen)` or `📶 Online (Edge Qwen + Gemini)`.
  - Battery percentage and actual app process memory, sampled every second against an 1024 MiB budget.
  - Compact active section chips: `[Sec A (AB-501)]` `[Sec D (AB-504)]` `[Sec I (AB-514)]`.

### 5. Voice-Driven Maintenance Reporting & Uno Q Edge Mesh Buffer
- Ingests reports by voice or text.
- Qwen SLM extracts category, building, floor, room, and urgency.
- Buffers in local SQLite and auto-flushes to Arduino Uno Q / ESP32 edge node (`http://192.168.4.1:8000/api/reports/sync`) when edge Wi-Fi SSID is detected.
- Pre-Emption Dashboard tracks incident clusters before social media leaks.

---

## 🛠️ Opening in Android Studio & Building

1. Launch **Android Studio**.
2. Select **Open** $\rightarrow$ Navigate to `c:\chiranth\projects\camp`.
3. Android Studio reads [`settings.gradle.kts`](file:///c:/chiranth/projects/camp/settings.gradle.kts), [`app/build.gradle.kts`](file:///c:/chiranth/projects/camp/app/build.gradle.kts), and [`local.properties`](file:///c:/chiranth/projects/camp/local.properties).
4. Click **Sync Project with Gradle Files** (Elephant icon).
5. Run on your physical Android phone (enable USB Debugging) or standard emulator.

