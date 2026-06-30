# Billy

Billy is a Pebble smartwatch assistant built from Bobby with a different goal: keep the lightweight, Pebble-native assistant experience, but make it useful with modern personal AI and optional phone-side Google account tools.

Billy is designed to be a drop-in Pebble app. It does not require a hosted helper server, proxy, Redis instance, or developer-operated backend. Users bring their own Gemini API key for model usage, and the optional Android companion keeps private Google API calls on the user's phone.

## What Billy Does

- Answers natural-language questions from a Pebble watch using Gemini.
- Preserves Bobby's watch-local features: alarms, timers, timeline reminders, settings, feedback, sample prompts, and weather cards.
- Supports a companionless mode that runs through the Pebble phone app JavaScript runtime.
- Supports an optional Android companion for richer Google account and phone tools.
- Displays Pebble-style cards for weather, maps, clarification choices, and transferred media.
- Transfers selected photos and web images to the watch instead of only describing them.
- Uses brief smartwatch-focused responses instead of long desktop-chat output.

## Runtime Modes

Billy has three runtime options in Clay settings:

- `Automatic`: use the Android companion when it is available, otherwise use the companionless Gemini path.
- `Companionless`: use only the Pebble phone app JavaScript runtime.
- `Android companion`: require the Billy Companion app for all AI requests.

The companionless path is important because Billy should still be useful as a single PBW install. The Android companion is the enhanced mode for private data, faster phone-side execution, media handling, Google APIs, and maps.

## Bring Your Own Keys

Billy does not ship with the developer's API keys. Each user supplies their own credentials.

Gemini API key:

- Used for model calls to `generativelanguage.googleapis.com`.
- Created by the user in Google AI Studio.
- Stored locally by the Pebble phone app and/or Billy Companion.
- Paid for by the user's own Google account or billing setup.

Optional Google Maps Platform API key:

- Used by Billy Companion for richer Places, Routes, Geocoding, Time Zone, and Static Maps features.
- Stored locally on the phone.
- Should be restricted to the Maps APIs Billy uses.

Google OAuth:

- Used by Billy Companion for user-approved Google account access.
- Grants Calendar, Tasks, Gmail, Drive, Docs, Sheets, Slides, Forms, Contacts, and bounded Google Photos API access where Google permits it.
- Identifies the app by Android package name and signing certificate SHA-1.
- Does not pay for Gemini model usage and does not replace the Gemini API key.

## Optional Android Companion

The Android companion lives in `companion-android/`.

It can:

- receive watch prompts through PebbleKit,
- verify the user's Gemini API key,
- request Google account consent on device,
- read and create Google Calendar events,
- read and manage Google Tasks,
- draft and send Gmail with confirmation,
- search Drive metadata and work with Docs, Sheets, Slides, and Forms APIs,
- use Google Photos Picker and limited Photos Library API paths,
- read Android local photos when granted permission,
- render images for watch transfer,
- call Google Maps Platform APIs when the user supplies a Maps key,
- launch Android navigation intents from watch map flows.

The companion does not run a local server. It is an Android app that listens for Billy watch requests when Android allows it to run.

## Known Google API Limits

Google Keep is not available for normal personal OAuth access in the same way Calendar, Gmail, Tasks, Drive, Docs, Sheets, and Slides are. Billy reports that restriction instead of pretending to create a Keep note somewhere else.

Google Photos has public API limits. The Picker API can retrieve media the user explicitly selects. Current Photos Library API access does not provide the same full-library semantic search that the official Google Photos or Gemini apps can use.

## Building The Pebble App

From `app/` with the Pebble SDK installed:

```sh
pebble build
```

The PBW is written to:

```text
app/build/app.pbw
```

Billy currently targets Core Time 2 / Emery-class hardware so media and UI behavior do not have to be limited to older low-resolution Pebble targets.

## Building The Android Companion

From `companion-android/`:

```sh
./gradlew assembleDebug
```

On Windows:

```powershell
.\gradlew.bat assembleDebug
```

The debug APK is written to:

```text
companion-android/app/build/outputs/apk/debug/app-debug.apk
```

For distribution, use a properly signed release build or Play Store release, then add the release or Play App Signing SHA-1 to the Google OAuth client configuration.

## User Setup

See `docs/USER_SETUP.md` for the current setup notes covering:

- Gemini API keys,
- Google OAuth clients and scopes,
- Google API enablement,
- optional Google Maps Platform keys,
- credential separation so users pay for their own Gemini and Maps usage.

## Project Status

Billy 0.1 is the first public release. The main architecture is in place: one PBW, optional Android companion, no helper server, user-owned API keys, and Pebble-native cards/media. Google API coverage is still actively being hardened.

## Credits

Billy is forked from Bobby / Tiny Assistant by the Rebble and Pebble developer community.

Developer: Thomas Bolger.

Artwork and iconography: Sarah Bolger and Katherine Berry.

Original Bobby credits and Apache 2.0 licensing are preserved where applicable.

## License

Apache 2.0; see `LICENSE` for details.

## Disclaimer

Billy is not an official Google, Pebble, Core Devices, or Rebble product. Google API availability, scopes, pricing, quotas, and OAuth requirements are controlled by Google and can change independently of Billy.
