Community Hero is an offline-ready, AI-powered citizen reporting application designed to transform everyday citizens into community guardians. By simplifying the reporting process to a single shake or photo upload, Community Hero enables immediate reporting of public issues.
Using advanced Google Gemini AI, the app automatically analyzes photographs of hazards to determine category, risk severity, and draft descriptions, eliminating manual entry barriers. Integrated with the Google Maps Platform, it aggregates and pins reports on an interactive map for citizen visibility and civic coordination, backed by a local Room Database for offline resilience and gamified leaderboards to incentivize civic actions.

Key Features:
  Instant "Shake-to-Report" Background Service
  Gemini AI Smart Image Analysis & Autofill
  Interactive Live Google Maps Dashboard
  Multi-Media Attachments
  Gamified Leaderboard & Profiles
  Dual-Language Localization (English & Hindi)
  Fully supports seamless runtime switching between English and Hindi, ensuring accessibility for diverse socio-economic communities.

Technologies Used:
  Kotlin & Coroutines: Written entirely in Kotlin, leveraging structured concurrency with Coroutines and Flow for smooth async processing.
  Jetpack Compose: Modern, declarative UI framework with fluid, Material 3-compliant styling, dynamic color adjustments, and beautiful edge-to-edge screens.
  Room Database (SQLite): Handles high-performance offline caching, local data persistence, user profile states, and robust offline report generation.
  OkHttp3: Manages high-efficiency REST network communication to external API gateways.
  Coil (Compose Image Loading): Offers asynchronous, cached, and performant remote image loading for Unsplash-based presets and reported hazard attachments.

Google Technologies Utilized:
Google Gemini API (gemini-2.5-flash)
Google Maps SDK for Android (Jetpack Compose Extension)
Google Identity Services (Simulation Design)


<div align="center">
<img width="1200" height="475" alt="GHBanner" src="https://ai.google.dev/static/site-assets/images/share-ais-513315318.png" />
</div>

# Run and deploy your AI Studio app

This contains everything you need to run your app locally.

View your app in AI Studio: https://ai.studio/apps/46c8769b-a848-4bbb-8d05-0f008a6a7781

## Run Locally

**Prerequisites:**  [Android Studio](https://developer.android.com/studio)


1. Open Android Studio
2. Select **Open** and choose the directory containing this project
3. Allow Android Studio to fix any incompatibilities as it imports the project.
4. Create a file named `.env` in the project directory and set `GEMINI_API_KEY` in that file to your Gemini API key (see `.env.example` for an example)
5. Remove this line from the app's `build.gradle.kts` file: `signingConfig = signingConfigs.getByName("debugConfig")`
6. Run the app on an emulator or physical device
