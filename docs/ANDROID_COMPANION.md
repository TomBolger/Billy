# Android Companion

`companion-android/` contains the optional Billy companion app. It is separate
from the Pebble app so the private phone and Google account runtime can evolve
without requiring a helper server.

Current scope:

- Kotlin Android app
- local settings storage for the user's Gemini API key
- direct Gemini API key verification
- always-enabled Pebble request listener
- Android Calendar Provider and local photo access
- Google OAuth grants for Calendar, Tasks, Gmail, Drive, Docs, Sheets, and Slides
- API-backed Calendar, Tasks, Gmail, Drive, Docs, Sheets, and Slides tools

Current non-goals:

- no Billy-hosted helper service
- no bundled Gemini API key
- no silent Google Keep save path while Google rejects the Keep OAuth scope

Build from the companion directory after installing a compatible Android SDK:

```sh
gradle :app:assembleDebug
```
