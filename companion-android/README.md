# Billy Android Companion

This is the optional Billy companion app for private phone and Google account tools.

The app is intentionally conservative:

- it is a standalone Gradle project under `companion-android/`
- it stores local companion settings with `SharedPreferences`
- it can verify the configured Gemini API key with a direct phone-to-Gemini request
- it can request Google API OAuth consent directly on device through Google
  Identity Services
- it receives enhanced assistant requests from the Pebble bridge when enabled
- it exposes API-backed Google Calendar, Tasks, Gmail, Drive, Contacts, Docs,
  Sheets, Slides, Forms, and experimental Google Photos tools to Gemini
- it can use an optional locally stored Google Maps Platform key for Places,
  Routes, Geocoding, Time Zone, and Google Static Maps
- it keeps Google API execution on the phone; no hosted helper service is used

## Google API OAuth setup

The companion uses Google Identity Services `AuthorizationClient` for
short-lived, on-device access tokens. It does not request offline access and
does not require a hosted token exchange service.

The Gemini API key is separate from Google OAuth. OAuth grants access to your
Google account data, such as Calendar, Tasks, Gmail, Drive, Contacts, Docs,
Sheets, Slides, Forms, and experimental Photos API paths. The Gemini API key pays for and authorizes model calls to
`generativelanguage.googleapis.com`.

If you create the Gemini API key in Google Cloud Console, make sure the Gemini
API / Generative Language API is enabled in that project and that the key's API
restrictions allow `generativelanguage.googleapis.com`. If the same key is used
from the companionless Pebble path, do not restrict it to the Billy Companion
Android package/SHA-1; companionless requests run through the Pebble phone app
environment, not the Billy Companion package. For the least friction, use an
API-limited Gemini key with no Android-app restriction, or use separate keys for
companionless and companion requests.

Create an Android OAuth client in the Google Auth Platform / Cloud Console with:

- package name: `com.tombo.billyassistant.companion`
- debug SHA-1 for the current local APK:
  `1B:98:A8:38:72:6D:27:12:AA:25:3B:2B:AE:AF:BB:B3:8D:2F:16:1B`
- release or Play App Signing SHA-1 later for distributed builds

OAuth is not bound to the APK filename or Gradle variant name. Google identifies
this Android app by:

- application ID / package name: `com.tombo.billyassistant.companion`
- signing certificate SHA-1

The local debug APK uses Android's debug signing key, so it needs the debug
SHA-1 above. A Play Store build will use a different signing certificate and
needs a second Android OAuth client entry for the Play App Signing certificate
SHA-1 shown in Play Console. Keep the package name the same for both entries.

Enable the Google APIs and consent-screen scopes the companion will request
before using the authorization button:

- `https://www.googleapis.com/auth/calendar`
- `https://www.googleapis.com/auth/tasks`
- `https://www.googleapis.com/auth/gmail.readonly`
- `https://www.googleapis.com/auth/gmail.compose`
- `https://www.googleapis.com/auth/gmail.send`
- `https://www.googleapis.com/auth/drive.metadata.readonly`
- `https://www.googleapis.com/auth/drive.readonly`
- `https://www.googleapis.com/auth/contacts.readonly`
- `https://www.googleapis.com/auth/documents`
- `https://www.googleapis.com/auth/documents.readonly`
- `https://www.googleapis.com/auth/spreadsheets`
- `https://www.googleapis.com/auth/spreadsheets.readonly`
- `https://www.googleapis.com/auth/presentations`
- `https://www.googleapis.com/auth/presentations.readonly`
- `https://www.googleapis.com/auth/forms.body.readonly`
- `https://www.googleapis.com/auth/photospicker.mediaitems.readonly`
- `https://www.googleapis.com/auth/photoslibrary.readonly.appcreateddata`

Enable these APIs in the same Cloud project:

- Google Calendar API
- Google Tasks API
- Gmail API
- Google Drive API
- People API
- Google Docs API
- Google Sheets API
- Google Slides API
- Google Forms API
- Google Photos Picker API
- Google Photos Library API

Use these API service names if searching in Cloud Console:

- `calendar-json.googleapis.com`
- `tasks.googleapis.com`
- `gmail.googleapis.com`
- `drive.googleapis.com`
- `people.googleapis.com`
- `docs.googleapis.com`
- `sheets.googleapis.com`
- `slides.googleapis.com`
- `forms.googleapis.com`
- `photospicker.googleapis.com`
- `photoslibrary.googleapis.com`

Optional Google Maps Platform behavior is API-key based, not OAuth based. Paste
a Maps key into Billy Companion only if you want richer Maps features, and
enable/restrict that key for:

- Places API
- Routes API
- Geocoding API
- Time Zone API
- Maps Static API

Google Keep is not part of the normal personal-account OAuth setup. Google's
Keep API is documented for enterprise/admin use, and personal OAuth attempts
can return `invalid_scope`. Billy reports that restriction instead of creating
substitute notes.

Google Photos is also limited by Google's current public API rules. The Picker
API can retrieve only photos the user explicitly selects in Google Photos. The
Library API no longer exposes normal full-library search to third-party apps;
Billy's Library API tool can only search app-created media and reports that
limit instead of pretending it has WearOS/Gemini app-level access.

A web client ID is only needed later if the app adds Credential Manager
ID-token sign-in or server-side/offline authorization. Google's current
AuthorizationClient setup guide still says to create a Web application client
alongside the Android client, even if the Android app does not use the web
client directly. If consent closes with `RESULT_CANCELED`, create that Web
client in the same Google Cloud project as a setup sanity check.

The current serverless authorization path identifies the Android app by package
name plus signing certificate SHA-1.

The companion UI lets you choose Calendar, Tasks, Gmail, Drive, Contacts, Docs,
Sheets, Slides, Forms, and Google Photos APIs from one Google account access
dialog. Grant only the services you want Billy to use. Forms and Photos APIs are
unchecked by default because they are experimental and bounded.

## Build

Open this directory in Android Studio, or build from this directory with a local
Gradle installation and Android SDK:

```sh
gradle :app:assembleDebug
```

The debug APK is only for local side-loading. For Play Store distribution,
build and upload a release Android App Bundle signed through the normal Play
App Signing flow, then add the Play App Signing SHA-1 to Google Auth Platform.

If you prefer a Gradle wrapper, generate one from this directory after choosing
the repository's desired Gradle distribution policy.
