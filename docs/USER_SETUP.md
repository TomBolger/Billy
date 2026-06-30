# Billy user setup

Billy needs two separate Google setup steps.

## Gemini API key

Use this for model calls and web reasoning.

1. Open Google AI Studio: https://aistudio.google.com/app/apikey
2. Create or select a Google Cloud project with billing enabled.
3. Create a Gemini API key.
4. Copy the API key into Billy settings and Billy Companion settings.
5. Leave the Billy model as `gemini-3.5-flash` unless you intentionally choose another supported model.

Do not paste an OAuth client ID, OAuth client secret, access token, service
account JSON, or Google Cloud API key for a non-Gemini API into the Gemini API
key field.

For the lowest-friction public release, the setup UI should link directly to AI
Studio, explain that each user pays for their own Gemini usage, and provide a
one-tap key verification in both the watch settings flow and Android companion.

## Google account OAuth

Use this for private Google account data such as Calendar, Tasks, Gmail, Drive,
Docs, Sheets, and Slides.

The app developer must register OAuth clients for Billy's Android package and
signing certificates. Individual users should not have to create their own
OAuth client once Billy is published.

Current package:

`com.tombo.billyassistant.companion`

Current debug SHA-1:

`1B:98:A8:38:72:6D:27:12:AA:25:3B:2B:AE:AF:BB:B3:8D:2F:16:1B`

For Play Store release, add the Play App Signing SHA-1 in Google Auth Platform
using the same package name.

Users will only grant consent inside Billy Companion. They should not need to
visit Google Cloud Console for OAuth.

Enable these APIs in the same Google Cloud project that owns Billy Companion's
Android OAuth client:

- Google Calendar API: `calendar-json.googleapis.com`
- Google Tasks API: `tasks.googleapis.com`
- Gmail API: `gmail.googleapis.com`
- Google Drive API: `drive.googleapis.com`
- People API: `people.googleapis.com`
- Google Docs API: `docs.googleapis.com`
- Google Sheets API: `sheets.googleapis.com`
- Google Slides API: `slides.googleapis.com`
- Google Forms API: `forms.googleapis.com`
- Google Photos Picker API: `photospicker.googleapis.com`
- Google Photos Library API: `photoslibrary.googleapis.com`

Billy can also use an optional local Google Maps Platform API key. This is not
OAuth and is not covered by the Gemini API key. If you paste a Maps key into
Billy Companion, enable these APIs in that Maps key's project:

- Places API
- Routes API
- Geocoding API
- Time Zone API
- Maps Static API

Future Billy builds may also request Google Meet API and Google Chat API if
those workflows become useful on the watch. Current Meet links are created
through Google Calendar event creation.

Billy treats Google Keep as blocked for normal personal OAuth and does not
create substitute notes unless explicitly asked.

Billy has an experimental Google Photos path:

- the Photos Picker API can retrieve only media the user explicitly selected in
  Google Photos
- the current Photos Library API can search only media created by Billy through
  the API, not the user's full Google Photos library
- local camera-roll photos still use Android photo permission, not a Cloud
  Console API

If an API error mentions a project number, enable the API in that exact
project. A different project will not fix OAuth tokens issued for the current
Android OAuth client.

## Credential split

Gemini API key:

- pays for model calls
- comes from Google AI Studio
- is pasted by each user into Billy

OAuth client:

- identifies Billy Companion to Google
- is configured by the app developer
- enables user consent prompts for Calendar, Tasks, Gmail, Drive, Docs, Sheets,
  Slides, and experimental Google Photos API paths

OAuth grants:

- are approved by each user on their device
- can be revoked by the user
- do not replace the Gemini API key

Optional Google Maps API key:

- pays for Maps Platform web-service calls
- is pasted by each user only if they want richer Maps behavior
- should be restricted to the Maps APIs listed above
