# Local Runtime

Billy's default assistant path is local to the Pebble phone app. The app calls Google Gemini directly with the Gemini API key configured in Billy settings.

There is no Billy-hosted Gemini helper service in this runtime. Prompts, optional city-level location context, and Gemini responses pass between the phone app and Google's Gemini API. The configured API key is stored by the Pebble phone app settings layer.

Optional Android enhanced mode is a separate runtime path for Android companion support. When selected, assistant processing may be routed through the Android companion instead of the direct phone-app Gemini path. In the current client, the Android companion bridge is not connected yet, so selecting Android companion mode reports that it is unavailable.

Feedback and report actions are user-initiated support flows. They can still send the selected feedback text or conversation report to Rebble and are separate from normal assistant processing.

## User-Facing Copy Rules

- Do not describe Billy as a Rebble-paid Gemini service.
- Say that users configure their own Gemini API key.
- Say that normal assistant requests go from the phone app to Google Gemini.
- Say that Billy does not use a hosted helper service for Gemini processing.
- Keep watch copy short and avoid implementation detail beyond consent needs.
