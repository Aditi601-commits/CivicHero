# Community Hero - Project Description

## 1. Problem Statement Selected
In rapidly growing urban areas, municipal authorities face a major bottleneck in identifying, prioritizing, and resolving civic hazards (such as potholes, water leaks, broken streetlights, or waste pileups). Traditional civic reporting channels are slow, tedious, and often rely on complex forms that deter citizens from reporting. As a result, critical public infrastructure defects remain neglected, leading to accidents, economic losses, and decreased quality of life.

**Selected Problem Statement:** *Leveraging Mobile Technology and Artificial Intelligence to simplify civic engagement and streamline municipal infrastructure maintenance.*

---

## 2. Solution Overview
**Community Hero** is an offline-ready, AI-powered citizen reporting application designed to transform everyday citizens into community guardians. By simplifying the reporting process to a single shake or photo upload, Community Hero enables immediate reporting of public issues.

Using advanced Google Gemini AI, the app automatically analyzes photographs of hazards to determine category, risk severity, and draft descriptions, eliminating manual entry barriers. Integrated with the Google Maps Platform, it aggregates and pins reports on an interactive map for citizen visibility and civic coordination, backed by a local Room Database for offline resilience and gamified leaderboards to incentivize civic actions.

---

## 3. Key Features

### 🌟 Instant "Shake-to-Report" Background Service
*   **Persistent Monitoring:** A robust background Android Service (`ShakeDetectionService`) runs continuously, listening to on-device accelerometer sensors.
*   **Instant Trigger:** Shaking the smartphone at any time instantly opens a streamlined hazard-reporting dialogue, enabling citizens to log hazards immediately upon spotting them.
*   **Foreground Service Compliance:** Built strictly to modern Android standards, providing user transparency with an elegant, non-intrusive persistent notification.

### 🧠 Gemini AI Smart Image Analysis & Autofill
*   **Instant Classification:** Powered by `gemini-2.5-flash`, the app analyzes custom hazard photos to accurately categorize them (e.g., Pothole, Water Leak, Waste Management) and suggest risk levels.
*   **AI Autofill Engine:** When a user selects or takes a picture, Gemini dynamically drafts a professional, concise title and safety description of the issue, allowing reports to be submitted in under five seconds with zero typing.

### 🗺️ Interactive Live Google Maps Dashboard
*   **Geo-Location Pinpoints:** Displays active community hazards as dynamic markers on a beautiful interactive map.
*   **Map Gestures & Centering:** Supports fluid camera manipulation, zooming, panning, and automatic centering on user locations to inspect reports in immediate surroundings.
*   **Visual Pins:** Maps utilize real geo-coordinates, with separate icons/sheets indicating hazard severity and resolution status.

### 📸 Multi-Media Attachments
*   **Multi-Photo Support:** Citizens can capture and attach a primary hazard image, alongside multiple secondary angles, to provide comprehensive evidence for municipal workers.
*   **Video Recording:** Supports capturing or uploading extra video clips, allowing users to demonstrate active water leaks, flashing streetlights, or dangerous traffic environments.

### 🏆 Gamified Leaderboard & Profiles
*   **Points System:** Users earn "Hero Points" for reporting valid issues, and substantial bonuses when issues are confirmed or marked as "Resolved" by the community.
*   **Real-Time Ranking:** A localized leaderboard tab ranks active citizens, fostering healthy community-focused competition.
*   **Civic Badges:** Users unlock specialized badges (e.g., "Pothole Patrol", "Lighting Legend") as they hit milestone counts.

### 🌐 Dual-Language Localization (English & Hindi)
*   Fully supports seamless runtime switching between English and Hindi, ensuring accessibility for diverse socio-economic communities.

---

## 4. Technologies Used

*   **Kotlin & Coroutines:** Written entirely in Kotlin, leveraging structured concurrency with Coroutines and Flow for smooth async processing.
*   **Jetpack Compose:** Modern, declarative UI framework with fluid, Material 3-compliant styling, dynamic color adjustments, and beautiful edge-to-edge screens.
*   **Room Database (SQLite):** Handles high-performance offline caching, local data persistence, user profile states, and robust offline report generation.
*   **OkHttp3:** Manages high-efficiency REST network communication to external API gateways.
*   **Coil (Compose Image Loading):** Offers asynchronous, cached, and performant remote image loading for Unsplash-based presets and reported hazard attachments.

---

## 5. Google Technologies Utilized

### 1. Google Gemini API (`gemini-2.5-flash`)
The core intelligence engine uses the latest `gemini-2.5-flash` model. Instead of generic textual prompting, the app leverages multimodal capabilities:
*   **JSON-Schema Strict Outputs:** Prompts are structured to enforce rigid JSON return structures (e.g., matching category/severity lists exactly). This prevents parsing errors and integrates the LLM's responses natively with the Jetpack Compose state-handling layer.
*   **Image Understanding:** Directly reads JPEG byte arrays, distinguishing concrete physical issues like asphalt cracking (Potholes), water surface reflection (Water Leaks), or pileups (Waste).

### 2. Google Maps SDK for Android (Jetpack Compose Extension)
*   **Declarative Map Integration:** Replaces legacy XML MapViews with state-driven `GoogleMap`, `Marker`, and `rememberCameraPositionState` composables.
*   **Custom Map Markers:** Maps out active hazard report locations utilizing latitude and longitude pairs, offering click listeners that bind maps to local detail modals.

### 3. Google Identity Services (Simulation Design)
*   Incorporates a Material 3-aligned Google Sign-In portal, laying the groundwork for secure user authentication, syncing profiles, and maintaining leaderboards across cloud platforms.
