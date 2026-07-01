# Billy Assistant Google Services Plan

## Goal

Make Billy dramatically more useful while preserving the current product shape:

- one PBW as a drop-in replacement for Bobby/Billy
- optional Android companion for enhanced private-data access
- no helper service, proxy, backend, or always-on local server
- one watch chat that can route between fast public reasoning, watch actions, and private Google/Android actions
- explicit login/consent is allowed, but only through the Android companion
- preserve Bobby's existing watch-local features: alarms, timers, reminders, weather cards, sample prompts, settings, quota/feedback UI, and Pebble-style chat presentation
- enhance the AI and personal Gemini access on top of those features, not by replacing them with brittle companion-only paths

## Pinned Delegation Rule

The agent shall delegate subagents to parallelize all possible work.

Implementation policy:

- Use subagents for independent slices with disjoint write sets, such as OAuth foundation, service-specific tools, UI/status work, and verification.
- Keep the main agent on the current critical path instead of waiting on side work.
- Workers must not revert changes made by others and must report the files they changed.
- Subagents should be used for concrete implementation or bounded codebase questions, not vague brainstorming.
- Integration remains the main agent's responsibility.

## Core Architecture

The Gemini API key only powers reasoning. It does not inherit the user's consumer Gemini Connected Apps or Google account grants. Private Google data requires OAuth grants per Google service.

The Android companion becomes the private-data runtime:

1. The watch sends a prompt and selected runtime.
2. The phone-side JS runtime handles fast companionless Gemini and watch-local actions when possible.
3. The Android companion handles prompts that require private Android/Google data.
4. The companion asks Gemini to choose tools.
5. Tools call either Android local providers or Google REST APIs.
6. Every mutating tool verifies the result by reading it back before claiming success.

## Bobby Feature Parity Tasks

Principle: Billy must preserve every Bobby feature in companionless mode. Automatic mode may use the Android companion for richer private-data tools, but the companionless flag must keep Bobby-era behavior usable without Android, and companion-enabled mode must not be slowed or degraded by companionless fallbacks.

Status legend: `[x]` implemented and reviewed, `[~]` partially implemented or needs focused verification, `[ ]` not yet complete.

### Launch, Consent, And Home

- [x] Normal launcher opens the Billy home screen with mascot, greeting, clock, version, dictation button, sample prompt button, and root menu.
- [x] Quick Launch starts dictation or opens home according to the `QUICK_LAUNCH_BEHAVIOUR` setting.
- [x] Alarm wakeup launch opens the ringing alarm/timer flow instead of the normal assistant.
- [x] First-run consent still covers LLM warning, Gemini/data warning, and location permission.
- [x] Location permission setting syncs to phone JS for companionless location context.
- [x] Cobble warning remains watch-local and does not require Android companion.
- [x] Sample prompts are loaded from `app/resources/text/sample_prompts.txt` and launch the same conversation path as dictation.

### Conversation UI

- [x] Dictation starts a chat session.
- [x] Transcript confirmation setting can require user confirmation before the prompt is sent.
- [x] Chat stream supports user prompts, assistant fragments, thinking/function text, warnings, close errors, and done events.
- [x] Long-select report flow remains available from a conversation.
- [x] Clarification cards support selectable options and a `Dictate...` escape hatch.
- [~] Clarification-card rendering and chained picker behavior need emulator regression tests after every change because scroll/input crashes have recurred.
- [x] Image/media transfer path supports map/photo cards without requiring Android as long as the bytes come from JS companionless tools.

### Watch-Owned Actions

- [x] Alarms: create, list, delete, named alarms, wakeup persistence, duplicate/past/limit errors, ringing UI, snooze/dismiss, and menu deletion remain watch-local.
- [x] Timers: create, list, delete, named timers, countdown widget, ringing UI, vibration pattern, and menu deletion remain watch-local.
- [x] Reminders: create, list, delete, local storage, Rebble Timeline pin insert/delete, near-future warning, and reminders menu remain phone-JS/watch features.
- [x] Conversational settings: units, response language, alarm vibration, timer vibration, quick launch behavior, and transcript confirmation are exposed again as companionless watch tools.
- [x] These watch-owned actions intentionally bypass Android even in Automatic mode so companion-enabled mode does not intercept alarms, timers, reminders, or Billy settings.

### Bobby Service Content Features

- [x] General LLM Q&A and current internet research are handled by companionless Gemini with search grounding.
- [x] Weather current card is available companionlessly through `get_weather` using Open-Meteo and Bobby's existing weather widget messages.
- [~] Bobby single-day and multi-day weather widgets are still renderable, but companionless currently sends the current card plus forecast text. Add explicit single-day/multi-day tools only if testing shows a real UX gap.
- [x] Map cards are available companionlessly through `show_openstreetmap_map` when Android companion is absent or Google Maps Platform key is not configured.
- [~] Places and routing parity is split: Android companion has richer Google Places/Routes; companionless has OSM map-card display but not phone navigation or full route cards.
- [~] Numeric highlight widget still renders, but companionless Gemini does not yet have a dedicated `show_number` tool for currency/calculation-style answers.
- [~] Currency conversion, calculations, Wikipedia/Bulbapedia, and public factual answers are covered by Gemini/search text. Dedicated Bobby-style widgets are not yet restored for these.

### Menus, Settings, And Utility Screens

- [x] Alarms menu, timers menu, reminders menu, About, Legal, Feedback, Report, Quota, root menu, and release notes windows remain in the PBW.
- [x] Quota screen shows local Gemini API usage estimates for non-legacy runtime and keeps the legacy Bobby quota path gated behind `legacy`.
- [x] Feedback and report send Bobby-style JSON POST when configured, otherwise open a prefilled GitHub issue for Billy.
- [x] About/legal/feedback copy has been moved to Billy ownership.
- [x] Release notes now load the 1.5 changelog resource instead of the stale 1.4 resource.
- [x] Clay settings include runtime mode, Gemini API key/model/budget, feedback routing, language, units, vibration patterns, quick launch behavior, transcript confirmation, and location.

### Companionless/Android Routing Reliability

- [x] Manual typed prompt bridge keys now align to clean generated app keys: `FEEDBACK_POST_URL=10120`, `WATCH_PROMPT=10121`, `WATCH_READY=10122`, `ANDROID_COMPANION_READY=10123`.
- [x] Automatic mode listens for the Android heartbeat before deciding whether to stand down or run companionless.
- [~] Automatic mode can still duplicate or drop a response if Android heartbeat/answer timing is pathological. Future fix should add request IDs or an explicit Android-claim acknowledgement instead of relying only on recent heartbeat.
- [x] Gemini, OSM, and companionless weather network calls now have timeouts so the watch does not think forever on stalled requests.
- [x] Companionless OSM map cards now wait briefly after image metadata before enqueuing the map widget, reducing blank-card races.
- [x] Message queue failure handling no longer dequeues an empty queue after a send failure.

### Review Notes From 2026-06-29

- High-risk protocol drift was fixed in C and Android constants.
- Companionless weather parity was added without changing Android weather behavior.
- Conversational settings parity was restored through the existing Bobby `update_settings` action.
- Remaining parity work should prioritize dedicated number/currency widgets and request-ID-based Android/JS runtime arbitration.

## Current Repair Notes

### 2026-06-26 Calendar/Photos Repair Pass

Observed failures:

- Calendar creation still reports low-level Google/API failure text and does not reliably create visible events.
- Calendar create ignores the model-provided `calendar_id`; the Google tool always chooses its own default calendar.
- `list_writable_calendars` reports Android Calendar Provider calendars, while `create_calendar_event` writes through Google Calendar API, so the user cannot see or choose the actual destination.
- The new clarification widget exists but calendar tools do not proactively use it when multiple writable Google calendars are available.
- Ghost cleanup mixes local Android cleanup and Google Calendar cleanup, but Google API failures are surfaced as implementation noise instead of a short actionable status.
- Photo selection still allows stale app/media folders to outrank actual camera roll photos in some cases.

Repair goals:

- Calendar tools must first read the Google calendars Billy can actually access, including id, name, access role, selected/hidden state, and writable status.
- If a create request has no calendar id and multiple plausible writable Google calendars exist, ask the user with the clarification widget instead of guessing.
- If a calendar id is supplied, create on that exact Google calendar and include the calendar name/id in the final watch response.
- Android Calendar Provider must not be used to create events. It is only a read fallback and local ghost cleanup path.
- Error text sent to the watch should be short, human, and actionable; detailed API errors can remain in JSON/debug payloads.
- Photo requests for "latest camera roll/photo" should strongly prefer DCIM/Camera/Open Camera/Pixel Camera and reject WhatsApp/messages/downloads unless the user asks for broad media or search results.

Implemented in this pass:

- `create_calendar_event` now treats Google calendar ids as strings and passes the selected id through to the Calendar API.
- `list_writable_calendars` now reports Google Calendar API calendars instead of Android provider calendars when OAuth is available.
- Calendar create asks a clarification-card question when multiple visible writable Google calendars are available and no `calendar_id` was supplied.
- Default calendar choice now prefers selected, non-hidden calendars before primary/hidden calendars.
- Watch cleanup has a combined `delete_billy_calendar_ghosts` tool that runs local Android cleanup and Google Calendar cleanup.
- Watch-facing calendar/network errors are shorter and human-readable.
- Camera-roll photo requests now filter for likely camera photos instead of falling back to stale app media.
- `media_type=image` no longer means arbitrary media; explicit `any`/`media` is required for broad image search.

### 2026-06-26 Emulator Stabilization Pass

Observed failures after the calendar/photo repair build:

- Clarification UI appears as a plain bulleted list instead of the custom selectable card.
- Up/down chat scrolling can exit back to the app list, which means the watch click handling is broken or the app is crashing during scroll/input.
- Calendar selection/list output is plain text and does not use the clarification card or structured watch UI.
- Photo requests still return non-camera images, which means MediaStore selection is not deterministic enough.

Repair goals:

- Create a repeatable Pebble emulator test build and install/run it before shipping more artifacts.
- Add deterministic debug/test entry points for the clarification card so card rendering and button behavior can be tested without depending on Gemini tool choice.
- Use logs and emulator behavior to find the actual watch-side crash/exit, not guess.
- Add photo diagnostics that identify selected bucket/path/date/id so bad selections can be traced.
- Final production build should keep debugging useful but not dump low-level machine text into normal watch answers.

## OAuth And Consent

Use Google Identity Services for Android authorization:

- Add `com.google.android.gms:play-services-auth`.
- Use `AuthorizationClient` to request incremental OAuth scopes.
- Store granted scope state locally.
- Store access tokens only locally and treat them as refreshable/replaceable.
- If a token is missing, expired, revoked, or lacks scope, return `needs_sign_in` or `needs_scope`.
- The companion UI owns interactive scope grants because watch prompts cannot launch Google consent UI.

Required Google Cloud setup:

- Google Cloud project for Billy Companion.
- Android OAuth client for package `com.tombo.billyassistant.companion`.
- Debug SHA-1 for test APK and later release SHA-1 for distributed APK.
- Enabled APIs as features land: Calendar, Tasks, Gmail, Drive, Docs, Sheets, Slides.
- OAuth consent screen with the user added as a test user until published/verified.

## Service Rollout

### Phase 1: Foundation

- Add Google authorization dependency and scope grant UI.
- Add a shared token provider used by companion tools.
- Add capability/status reporting:
  - `available`
  - `needs_sign_in`
  - `needs_scope`
  - `permission_denied`
  - `unsupported`
  - `draft_only`
  - `open_only`
- Add direct tool final responses for deterministic actions.

### Phase 2: Calendar API

Calendar is the first must-fix service because Android Calendar Provider create has reported success while the event did not appear.

Work:

- Keep Android Calendar Provider as fallback.
- Add Google Calendar API list/create.
- Let the user grant calendar scope in the companion.
- On create:
  - choose a primary writable Google calendar from the API
  - create the event
  - read the event back
  - return calendar summary, calendar id, account, event id, and start/end
- Never report success unless readback succeeds.

### Phase 3: Google Tasks

Tasks is the cleanest next service.

Work:

- Grant Tasks scope.
- List task lists.
- List due/open tasks.
- Create tasks with title, notes, due date.
- Complete or update tasks only after explicit user intent.
- Read back created/updated tasks before success.

### Phase 4: Gmail

Start conservative because email is high risk.

Work:

- Grant Gmail read/compose scopes incrementally.
- Search/list recent messages.
- Summarize selected messages briefly.
- Create drafts.
- Sending should require an explicit confirmation step and a very clear watch response.

### Phase 5: Drive, Docs, Sheets, Slides

Drive is the file discovery layer; Docs/Sheets/Slides are content layers.

Work:

- Grant Drive metadata/read scope first.
- Search files and return exact file names, owners, modified times, and links.
- Read Docs text for summaries.
- Read Sheets ranges and summarize tables.
- Create Drive text files or Docs only after direct user request.
- Add edit/write scopes only after read-only flows work.

### Phase 6: Photos And Keep

Photos:

- Keep local Android photo access for immediate camera roll use.
- Cloud Google Photos needs Google Photos API/Picker constraints; it should be built separately from Drive.

Keep:

- Do not expose Android intent draft fallback.
- Do not promise silent Keep read/write for personal OAuth.
- If the user asks for Keep, state that Google restricts Keep API access for personal OAuth and do not create a substitute unless the user explicitly asks for one.

## Watch UX Rules

- Say `Thinking...` for Gemini work.
- Stream or update thinking/progress text where supported.
- Keep final responses watch-short.
- For private-data failures, name the missing piece:
  - "Grant Google Tasks in the companion."
  - "Gmail draft scope is missing."
  - "Drive search needs sign-in."
- For creates/updates, include the destination:
  - calendar name/account
  - task list
  - Gmail draft id
  - Drive filename/folder

## Verification

Every build should run:

- JS syntax checks for `app/src/pkjs`.
- Android debug build.
- PBW build.
- `git diff --check`.

For service features, manual test prompts should be in the sample prompt menu:

- Create a calendar event tomorrow at 3 PM called Billy test for 30 minutes.
- What is on my calendar today?
- Add a Google Task called test Billy.
- What tasks are due today?
- Find emails from Google about Gemini.
- Create a Gmail draft to myself about testing Billy.
- Find my latest Drive document about Billy.
- Which Google services can the companion use?

## Emulator Stabilization Notes

Status on 2026-06-26:

- Upgraded WSL Pebble tooling to `pebble-tool` 5.0.38 and SDK 4.17. SDK 4.9.169 repeatedly timed out in pypkjs during app install.
- Added an emulator-only fixed-prompt path that can produce a deterministic clarification card. It is guarded by `ENABLE_FEATURE_FIXED_PROMPT`; production builds keep this flag off.
- Verified on the `emery` emulator:
  - Billy installs and launches on SDK 4.17.
  - The clarification picker renders as a real card with cyan selection highlight, not a normal bulleted chat response.
  - Up/down changes the selected option without leaving the app.
  - Select sends the selected answer back into the chat.
  - Normal chat scrolling uses Pebble's scroll layer handlers and stays in-chat.

Repairs made from emulator findings:

- Watch button handlers no longer trust the Pebble click callback context for chat sessions. They recover the active `SessionWindow` from `window_stack_get_top_window()`.
- Non-picker up/down actions delegate back to Pebble's built-in `scroll_layer_scroll_up_click_handler` and `scroll_layer_scroll_down_click_handler`.
- Emulator prerecorded data now includes a deterministic clarification-card response and fixes a malformed random response array.
- Android calendar creation flow now asks with the picker when a create-event request reaches `list_writable_calendars` and multiple writable Google calendars exist.
- Camera-roll photo lookup now requires verified camera source metadata such as `DCIM/Camera/` or a camera bucket, rather than accepting camera-like filenames from arbitrary app folders.

Status on 2026-06-26, follow-up:

- Calendar clarification cards no longer serialize Google Calendar IDs through Pebble text. The companion stores a pending create request and full calendar IDs locally, then sends the watch only a short token plus human calendar labels.
- The companion app now has a `Watch test prompt` field that sends typed text into the watch via the same `PROMPT` dictionary path used after dictation, so public/noisy testing can exercise the real watch flow.
- The watch home screen now accepts companion-injected prompts and opens a normal chat session from them.
- Camera-photo recency now ranks by media capture metadata first. `DATE_ADDED` is only a fallback because it is when Android first added the item to MediaStore, not necessarily when the photo was taken.

Status on 2026-06-26, corrective pass:

- Android Calendar Provider event creation is disabled. It remains available only for read fallback and deleting old Billy/Bobby local ghosts.
- The companion persists the last seen Pebble `WatchIdentifier` from watch traffic and app-open events. The typed prompt sender now targets that watch instead of broadcasting to a null watch list.
- The watch sends a private ready ping when the home screen appears so the companion can learn the watch ID before the first typed prompt.
- Photo lookup now merges multiple MediaStore candidate windows (`DATE_TAKEN`, `DATE_ADDED`, and `_ID`) before ranking, and normalizes timestamp values that arrive as seconds instead of milliseconds.

Status on 2026-06-26, prompt and relative-time correction:

- Calendar create no longer responds to obvious relative-date model mistakes by asking the user again. For `today` and `tomorrow`, the companion deterministically rewrites the tool arguments to the phone-local date, preserving duration and using an explicit prompt time like `3 PM` when present.
- The companion typed-prompt field now queues the prompt and relies on the watch home-screen ready ping to deliver it, instead of reporting success after a timed best-effort send.

Status on 2026-06-26, typed-prompt transport correction:

- Clay settings are not the right transport for repeated typed test prompts. Settings sync is configuration state; typed prompts are immediate commands and must enter the same `conversation_manager_add_input()` path used by dictation and sample prompts.
- The watch now has a global AppMessage inbox handler for companion-injected `PROMPT` messages, so typed prompts can open a chat from the home screen or while another Billy window is active.
- The root home-screen handler no longer consumes typed prompts itself, avoiding duplicate session launches.
- The companion sender now queues the prompt, starts Billy on the watch, waits briefly for the watch-ready path to consume the queue, and only sends a direct prompt packet if the queue is still pending.
- Android companion version `0.1.21` and watch app version `1.5.3` identify this transport-fix build.

Status on 2026-06-26, typed-prompt target correction:

- The previous companion builds persisted `WatchIdentifier.toString()`, which produces `WatchIdentifier(value=...)` instead of the raw PebbleKit watch id. Typed prompts were likely sent to an invalid target even though normal watch-originated requests still worked.
- `PebbleWatchStore` now persists `watch.value` and migrates the old wrapped string automatically.
- If no valid watch id is known, the prompt sender uses PebbleKit's default all-watch target instead of refusing to send.
- The companion now displays actual PebbleKit launch/send results in the prompt status text so transport failures are visible.
- Watch-ready delivery now requires an explicit `WATCH_READY` packet instead of treating every promptless watch message as readiness.
- `WATCH_PROMPT` is registered in appinfo at key `10120`; `WATCH_READY` uses private key `10121`.
- Android companion version `0.1.23` and watch app version `1.5.5` identify this target-fix build.

Status on 2026-06-26, tool-intelligence pass:

- Tasks create/readback was succeeding but formatted through the list-task summary path. Create results now summarize the created task from the `task` payload instead of saying no tasks are open.
- Gmail draft creation now validates recipient arguments before writing MIME headers. `me`/`myself` resolves through Gmail `users/me/profile`; unresolved contact names are rejected with a short clarification-oriented error rather than becoming invalid `To:` headers.
- Added `show_map_directions`, which geocodes a destination, renders an OpenStreetMap tile to the existing Pebble image/map transport, and opens Google Maps navigation on the phone.
- Added `show_web_image_search`, which searches public Wikimedia Commons images and sends the selected image through the watch media transport. Open-web image requests are routed here instead of Drive.
- Automatic Android routing now recognizes plain commands like `navigate to Frankfurt`, not only prompts that also contain `maps` or `directions`.
- Photo subject/person/place requests now use a two-stage local search: metadata search first, then recent camera-roll candidates ranked by Gemini vision before the chosen photo is attached.
- The Android Gemini system prompt no longer claims generic Google Search grounding that is not actually present in the request body.
- PBW appinfo now declares both typed-prompt keys: `WATCH_PROMPT=10120` and `WATCH_READY=10121`.
- Android companion version `0.1.24` and watch app version `1.5.6` identify this build.

Status on 2026-06-26, media follow-up and Gmail send correction:

- Generic clarification answers now re-enter the companion agent with the original context and selected answer. This fixes non-calendar picker responses that previously stopped at `OK: <answer>`.
- Web image searches now store the last successful query locally. Follow-ups like `more zoomed in`, `closer`, or `another image` can search the prior subject with close-up/detail terms instead of losing context or falling back to a stale map.
- Gmail normal email requests now use `prepare_gmail_send`, not draft creation. The tool resolves `me/myself`, shows a watch confirmation card with To/Subject/Body preview, and sends only when the user selects `Send`.
- Gmail draft IDs and message IDs are no longer shown in user-facing summaries.
- Gmail OAuth now requests the send-capable Gmail scope. Existing testers may need to open Billy Companion and manage Google account access again.
- Semantic camera-roll search now scans a wider candidate window in batches, asks Gemini vision for confidence, and refuses low-confidence matches instead of showing a visibly wrong photo.
- Android companion version `0.1.25` identifies this companion-only fix build. Watch app remains `1.5.6`.

Status on 2026-06-27, local photo date-range correction:

- Root cause: Android companion photo display prompts containing `last` were routed through the fast latest-photo path before Gemini/tool routing. That made `last week`, `last month`, and `this day last year` behave like `latest photo`.
- Root cause: photo tools only accepted `taken_after_millis`, so even Gemini-routed date requests could only express "after this date" and newest-first ranking would still pick a newer photo.
- `AndroidPhotoTools` now supports an exclusive `taken_before_millis` upper bound for list/search/attach queries and applies a real local-time range in MediaStore.
- The MediaStore range filter handles both millisecond and second timestamp columns, using `DATE_ADDED` only when capture time is missing.
- `PhotoDateRangeParser` handles deterministic local ranges for today, yesterday, weekdays, last/this week, last/this month, last year, this day last year, named dates, numeric dates, and simple relative day/week/month phrases.
- `CompanionAgent` now routes date-specific photo display prompts before the latest-photo shortcut and preserves subject terms like dog, family, or little girl for semantic ranking within the bounded range.
- Photo tool schemas now expose `taken_before_millis`, and Gemini is instructed never to represent day/week/month photo requests with only a lower bound.
- Android companion version `0.1.26` identifies this companion-only fix build. Watch app remains `1.5.6`.

Status on 2026-06-27, conversation-context and picker-card correction:

- The companion now stores a compact previous-turn summary for short-lived follow-up prompts instead of treating every prompt as a fully isolated request.
- Photo requests now store the selected image metadata, source prompt, subject text, and date range. Follow-ups such as "that is from December, show me one from this time of year 2025" reuse the previous photo context even when the user says "one" instead of repeating "photo".
- `this time last year` and `this time of year 2025` now resolve to the current month in the target year, not the whole previous year. This avoids December results for "this time last year" while staying less brittle than exact-day-only matching.
- General Gemini context injection is gated to follow-up-shaped prompts (`this`, `that`, `one`, `again`, `instead`, `wrong`, `what about`, etc.) to reduce stale-context bleed into unrelated requests.
- Gemini is instructed not to offer "notes" as a clarification bucket unless the user explicitly asks for notes or Keep.
- The clarification picker now renders as a boxed Pebble-style card with padding, dividers, a bordered outline, and an inset cyan selection highlight.
- Font note: before this picker pass, both question and options used `fonts->small_font`. There is no Lago font resource in the repo; arbitrary text remains on the existing text-capable `small_font` path until a Lago-compatible font asset is added.
- Android companion version `0.1.27` and watch app version `1.5.7` identify this integrated build.

Status on 2026-06-27, picker-list and first Billy mascot pass:

- Clarification picker styling was revised away from the boxed card treatment and toward the Pebblegram chat-list pattern: white background, no option numbers, a top line, a line above the options, lines between options, a bottom line, and an inset cyan highlight for the selected answer.
- The watch root-screen mascot renderer now uses a bitmap resource for `ROOT_SCREEN_PONY`, allowing the Emery-specific mascot to be replaced by the new Billy artwork without requiring a PDC converter.
- `Billy_zoom.png` from `I:\My Drive\Billy Goat Iconography Source\New graphics` was converted into `app/resources/images/root_screen/pony~emery.png` and a generic `pony.png` fallback. No `Billy_zoom.svg` was present in the synced Drive tree, so this pass preserves the provided PNG rather than redrawing or vectorizing it.
- The root screen was smoke-tested in the Emery emulator. The bitmap draw path needed `GCompOpSet`; without it, transparent pixels rendered black.
- Watch app version `1.5.8` identifies this build.

Status on 2026-06-27, context-relative photo follow-up repair:

- Root cause: contextual prompts like "older than that by a month" did not have an explicit photo word or display verb, so automatic mode could route them away from the Android companion even though Android held the saved photo context.
- Android automatic routing now keeps contextual photo/date follow-ups in the companion when a recent photo context exists.
- Added a context-relative photo range resolver for prompts such as "a month older", "two weeks newer", "before that", and "after that". It shifts the previous selected photo range/date instead of interpreting every relative phrase against today.
- Gemini fallback prompts now include the structured previous photo summary when a prompt looks like a context follow-up.
- Added Android and JS model-output guards that convert clarification-looking text with 2-4 short bullet/number options into the real clarification picker card. This catches model failures where Gemini asks a question in plain text instead of calling `ask_clarifying_question`.
- Watch app version `1.5.9` and Android companion version `0.1.28` identify this build.

Status on 2026-06-27, Billy graphics migration pass:

- `Billy_rock.png` now replaces the Bobby fence/About-window image slot (`FENCE_PONY_BITMAP`) with transparent background so the branded Billy background extends behind and below the art.
- Later art task: color the Billy_rock mountains and rocks; this pass intentionally leaves them as transparent line art over the app background.
- `Billy_sleep.png` now replaces the sleeping pony asset as a bitmap resource and is used in empty alarm/timer/reminder states and the snoozed result window.
- `Billy_about.png` now replaces the About menu icon.
- Result windows now support bitmap artwork in addition to the existing PDC/vector artwork.
- Watch app version `1.5.10` identifies this build.

Status on 2026-06-27, Billy root mascot sizing pass:

- The main-screen `Billy_zoom` mascot resource was enlarged from `84x84` to `87x96` by cropping the approved transparent bitmap bounds and scaling it modestly; the art itself was not redrawn.
- Watch app version `1.5.11` identifies this build.

Status on 2026-06-27, Billy color and icon clarity pass:

- `Billy_rock` now renders as colored artwork: grey mountains, a light-brown rock, and white Billy goat fill, including explicit white underpaint for the open tail area while keeping the background transparent.
- `Billy_about` was regenerated larger, sharper, and with heavier linework for the 25x25 menu icon slot.
- `Billy_sleep` was regenerated with a sharper mask for the 50x50 empty-state asset without redrawing the source artwork.
- Watch app version `1.5.12` identifies this build.

Status on 2026-06-27, Billy launcher icon sharpness pass:

- `Billy_general.png` was regenerated into the Pebble launcher/menu icon resource with the same 25x25 line-width and sharpness settings used for the approved `Billy_about` menu icon.
- Watch app version `1.5.13` identifies this build.

Status on 2026-06-27, About credit and identity note:

- The About screen now credits Thomas Bolger above Katharine Berry under Programming.
- Earlier Billy builds kept Bobby's Pebble app UUID for drop-in testing, but the release build must use Billy's own UUID. The split was completed in the 2026-06-27 app identity pass below.
- Watch app version `1.5.14` identifies this build.

Status on 2026-06-27, feedback routing and iconography credit pass:

- Feedback now restores Bobby's phone-side behavior when configured: watch dictation/report requests send JSON via `XMLHttpRequest POST`, then return Sent/Error to the watch. The endpoint is `FEEDBACK_POST_URL` in Clay settings.
- If `FEEDBACK_POST_URL` is blank, Billy falls back to opening the configured prefilled GitHub issue URL. GitHub issue creation cannot be silent without shipping an auth token, so the POST endpoint is required for the exact Bobby-style no-browser flow.
- The About screen now credits Sarah Bolger above Stasia Michalska under Iconography.
- Watch app version `1.5.15` identifies this build.

Status on 2026-06-27, Billy app identity split:

- Billy now uses its own Pebble app UUID: `f74b42bb-3473-444f-9722-dd34136d9b02`.
- The Android companion bridge protocol was updated to the same UUID, so prompt injection, companion replies, cards, and media transfer target the Billy app rather than Bobby.
- This intentionally makes Billy a separate watch app. Existing Bobby Quick Launch assignment and Bobby persisted settings will not carry over automatically; users should assign Quick Launch to Billy and configure Billy settings/API keys as a distinct app.
- Watch app version `1.5.16` and Android companion version `0.1.29` identify this build.

Status on 2026-06-27, productivity API implementation pass:

- Google Tasks now has a real `complete_google_task` tool. It searches open tasks, patches the chosen task to `completed`, reads back the result, and uses the watch picker when multiple tasks match.
- Gmail token requests are now scoped per operation: read uses `gmail.readonly`, draft uses `gmail.compose`, and confirmed send uses `gmail.send`. The OAuth grant cache was reset to `billy_google_auth_v2` so older false-positive grants do not survive into this build.
- Drive/Docs/Sheets/Slides are now exposed as real companion tools: `search_google_drive`, `read_google_doc`, `create_google_doc`, `read_google_sheet`, and `read_google_slides`.
- Google Keep is now treated as unavailable for personal OAuth instead of poisoning the one-button grant flow. Keep requests get a short limitation message and should not create a substitute note without explicit user consent.
- Semantic local photo search now scans a wider recent camera-roll window in larger Gemini vision batches so common subjects like pets are less likely to be missed.
- Watch sample prompts now cover Gmail send confirmation, Docs creation/read, Sheets/Slides reads, task completion, and subject photo search; the broken Keep prompt was removed.
- Watch app version `1.5.17` and Android companion version `0.1.30` identify this build.

Status on 2026-06-27, strict fallback audit pass:

- Keep substitution was removed. Keep requests now state that Google restricts Keep API access for personal OAuth and Billy does not create a Google Doc, Task, email draft, or other substitute unless the user explicitly asks for that substitute.
- The Android open-Keep-draft intent tool was removed from the companion and the Keep package visibility query was removed from the manifest.
- Photo subject search now scans up to 240 camera-roll candidates from a MediaStore pool up to 1,200 rows, with smaller thumbnails and an early stop for high-confidence matches.
- Fallback behavior inventory for review:
  - Calendar read can fall back to Android Calendar Provider when Google Calendar API is unavailable. Calendar create does not fall back.
  - Calendar ghost cleanup runs both local Android cleanup and Google Calendar cleanup.
  - Email has an explicit draft tool, but normal send requests use Gmail API confirmation/send.
  - Drive has an explicit phone-open search tool in addition to API search.
  - Maps directions can open phone navigation while also producing the watch map card.
  - Feedback opens a GitHub issue only if no feedback POST endpoint is configured.
  - Gemini model/request fallback retries another model or API shape when the first Gemini call fails; this is technical reliability, not a substitute action.
- Watch app version `1.5.18` and Android companion version `0.1.31` identify this build.

Status on 2026-06-27, Google Photos API experiment:

- Added explicit Google Photos OAuth scopes for the supported public APIs: Photos Picker selected-media access and Photos Library app-created read access.
- Added a companion UI button to create and open a Google Photos Picker session. This stores the latest session so Billy can later show media the user selected in Google Photos.
- Added `search_google_photos_library`, `show_google_photos_picker_selection`, and `get_google_photos_picker_status` companion tools.
- Explicit Google Photos prompts bypass the local camera-roll shortcuts so test prompts exercise the Photos API path instead of the Android MediaStore path.
- Gemini is instructed to be honest that Google Photos Library API search is app-created-only under current Google rules, and that Picker only exposes explicitly selected media.
- Setup docs now list `photospicker.googleapis.com` and `photoslibrary.googleapis.com` as optional experimental APIs to enable.
- Watch app version `1.5.19` and Android companion version `0.1.32` identify this build.

Status on 2026-06-27, Android thread context and Google API roadmap:

- Root cause for bad follow-ups: Android companion ignored the watch `THREAD_ID` and kept only one global last-turn summary. Companionless JS had local thread history, but Android-backed requests did not.
- Android companion now reads/sends the same `THREAD_ID`, creates one for new Android-backed threads, and stores up to six recent Android-backed turns per thread for one hour.
- Android companion now injects recent thread context on every model request with an instruction to use it only when the prompt is a follow-up. This avoids brittle regex-only context gating.
- JS automatic routing now stands down for follow-up-shaped prompts on threads with no JS local history, so Android-backed photo/calendar/Gmail/Drive follow-ups are less likely to get answered by the companionless runtime.
- Google Photos shown media is now saved into photo context using `shown_photo`/`creation_time`, not only local MediaStore `photo` fields.
- Google API roadmap for Billy's goal:
  - High value next: People API for contacts/recipient resolution; Calendar freeBusy for scheduling; Maps Platform Places/Routes/Geocoding/Time Zone for better location, directions, and local search; Gmail labels/thread operations; Drive changes/search refinements.
  - Productivity expansion: Forms read/create basics; Sites read/search if useful; Meet conference/link creation through Calendar/Meet APIs; Chat only if user uses Google Chat.
  - Device/local complements: Android Contacts provider, notification listener, app intents, share targets, and local media/document providers can fill gaps where Google cloud APIs are unavailable.
  - Hard limits: Google Photos full-library semantic/person/place search is not exposed through the public Library API after the 2025 changes; Picker is explicit-selection only. Google Keep remains blocked for normal personal OAuth. Consumer Gemini app memories/Connected Apps context is not inherited by a Gemini API key.
- Watch app version `1.5.20` and Android companion version `0.1.33` identify this build.

Status on 2026-06-27, Google API expansion implementation pass:

- People API / Google Contacts is now implemented with `search_google_contacts` and `resolve_google_contact_email`.
- Gmail send preparation now tries Google Contacts for named recipients before asking for an address. Ambiguous matches use the watch picker, then still require the existing Send/Cancel confirmation card before Gmail sends.
- Calendar now has `query_calendar_freebusy` and `find_calendar_availability` tools backed by the Google Calendar freeBusy endpoint.
- Calendar event creation can request Google Meet conference data when the user asks for a Meet/video/call link, and the calendar picker preserves that intent.
- Drive gained `list_recent_google_drive_files`; Docs kept create/read; Sheets and Slides now have create tools as well as read tools.
- Forms read support is implemented with `read_google_form` for title, description, and question structure.
- The Android companion now has a separate optional Google Maps Platform API key field. It is stored locally and is not bundled into builds.
- Maps Platform tools now cover Places text search, Google route summaries, geocoding, and time-zone lookup. `show_map_directions` uses Google Static Maps when a Maps key is configured and keeps the pre-existing basic map path when no Maps key is present.
- Setup docs now include People API, Forms API, and optional Maps Platform APIs/key setup.
- Remaining high-value API work:
  - Gmail labels/thread/body operations are still shallow.
  - Drive changes and deeper content search/refinement are still shallow.
  - Google Chat is not implemented; it likely needs Workspace/app configuration and is not a simple consumer OAuth feature.
  - Full Google Photos semantic/person/place search is still blocked by public API limitations, not by local sorting.
- Watch app version `1.5.21` and Android companion version `0.1.34` identify this build.

Status on 2026-06-27, Maps error reporting fix:

- The direct map/navigation path no longer collapses Google Geocoding API failures into "No Google Maps result." It now reports `REQUEST_DENIED`, quota/billing, invalid request, disabled API, and other Geocoding statuses explicitly.
- Google Static Maps image failures now report the underlying HTTP/text error instead of only "map image could not be decoded."
- Direct navigation parsing now catches prompts like "navigation to London" as well as "navigate to London."
- Android companion version `0.1.35` identifies this build. Watch app remains `1.5.21`.

Status on 2026-06-27, broad navigation intent routing:

- Removed the pre-LLM direct navigation regex shortcut. Navigation is not a watch-owned deterministic Bobby function, so broad natural language should be parsed by Gemini and routed to map tools instead of being handled by a growing phrase list.
- The map tool descriptions and Gemini system instruction now describe the broad intent category: getting to a destination, directions, navigation, route guidance, travel-to-place help, and map cards.
- Explicit watch-owned actions such as alarms, timers, reminders, and clarification answers remain deterministic shortcuts.
- Android companion version `0.1.36` identifies this build. Watch app remains `1.5.21`.

Status on 2026-06-27, remote-Gemini routing cleanup:

- Removed Android pre-Gemini intent shortcuts for local photo display, dated photo display, contextual photo follow-ups, web image display, and Billy diagnostics.
- Removed Android and companionless plain-text bullet-list clarification parsers. Clarification cards should come from the explicit `ask_clarifying_question` tool, not post-hoc regex conversion.
- Removed automatic-mode prompt category regex routing. In automatic mode, prompts received by the Android companion are handled by the Android Gemini tool loop; companionless mode remains explicit.
- Removed companionless pre-Gemini weather regex handling. Weather and other non-watch intents should be selected by Gemini/tool paths rather than a local phrase list.
- Removed calendar create timing correction, calendar-list-to-create inference, photo display helper sanitizers, web image query cleaning, and Google Photos word-to-category mapping. The tools now validate structured arguments and rely on Gemini to choose the correct tool arguments.
- Watch-owned alarms, timers, reminders, formatting cleanup, and protocol parsing remain deterministic.
- Android companion thinking text is now `Thinking...`.
- Watch app version `1.5.22` and Android companion version `0.1.37` identify this build.

Status on 2026-06-28, locked-phone weather and picker reliability:

- Android companion now requests `ACCESS_BACKGROUND_LOCATION` and separates foreground location from all-time location in the Android access UI. On Android 11+, the companion opens app settings and instructs the user to choose Location > Allow all the time, because Android does not expose that grant in the normal runtime dialog.
- The weather tool now refuses local weather with a specific all-time-location message when foreground location is granted but background location is not, matching the real locked-phone watch use case.
- The watch conversation model now tracks the newest unanswered clarification card anywhere in the feed instead of only the last entry, so chained picker questions stay selectable even if another segment arrives.
- Clarification cards reserve a visible `Dictate...` row. Selecting it starts dictation and sends the transcript back through the same clarification-answer protocol as a tapped option.
- The picker selection color now uses Pebble yellow (`GColorYellow`) instead of the Celeste app accent.
- Gemini text fallback parsing was restored only for choice-looking final answers that contain a question plus bullet/number/letter options, including real bullet characters. This is a renderer recovery path for malformed Gemini output, not a pre-Gemini intent shortcut.
- Watch app version `1.5.25` and Android companion version `0.1.40` identify this build.

Status on 2026-06-28, no open-ended watch questions:

- Gemini system instructions now forbid user-facing open-ended questions in final text. Any needed user answer or decision must use `ask_clarifying_question` with 1-3 short options, while the watch adds `Dictate...` for answers not listed.
- The clarification tool declaration now documents that it is the required path for every follow-up question, including yes/no questions.
- Android clarification-card sending now supports a picker with only `Dictate...` if Gemini asks a question but gives no usable options.
- The malformed-question fallback now converts final text that is clearly a user-facing question into a picker even without bullet options, rather than showing it as chat text.
- Android companion version `0.1.41` identifies this build. Watch app remains `1.5.25`.

Status on 2026-06-28, map card route overview:

- `show_map_directions` now accepts `destination_latitude` and `destination_longitude` so Places/Geocoding results can carry exact coordinates into the map card instead of relying on a second fuzzy geocode.
- Google Static Maps output now renders an overview with a current-location marker (`Y`), destination marker (`D`), and route/path line when Android location and Routes API data are available.
- Destination-only Google map cards now use a less aggressive zoom to preserve more surrounding labels.
- Map summaries now include route distance, duration, and first step when Routes API succeeds; failures fall back to a map instead of blocking the card.
- Gemini map instructions now require passing selected Places coordinates to `show_map_directions`.
- Android companion version `0.1.42` identifies this build. Watch app remains `1.5.25`.

Status on 2026-06-28, phone navigation launch regression:

- Restored the pre-Maps-API phone launch behavior inside the map-card flow: Google Maps navigation now receives the user's destination text as the primary `google.navigation:q=...` target instead of only a raw lat/lon pair.
- The map card still uses exact Places/Geocoding coordinates for the watch-rendered route and static map, so card accuracy and phone launch behavior are separated.
- Added a Google Maps web directions fallback when the Maps app cannot handle the direct intent.
- Added Android notification permission to the companion and a high-priority "Open navigation" notification fallback for cases where Android accepts the request but does not surface Maps from the background.
- Android companion version `0.1.43` identifies this build. Watch app remains `1.5.25`.

Status on 2026-06-28, navigation proxy attempt:

- Direct service-to-Maps launch still did not open Maps on-device. The launch path now uses a Billy-owned no-display `NavigationLaunchActivity` as a proxy, then that Activity opens Google Maps or a web directions fallback.
- Navigation launch now uses `PendingIntent` sender and creator background-activity-start opt-ins required by modern Android/target SDK 36, instead of only calling `Context.startActivity()` from the Pebble listener service.
- `show_map_directions` and the older `open_maps_directions` tool now share the same `NavigationLauncher` implementation.
- The notification fallback now opens the same proxy Activity, so a notification tap exercises the same code path as the attempted direct launch.
- Android companion version `0.1.44` identifies this build. Watch app remains `1.5.25`.

Status on 2026-06-28, artifact layout and direct navigation routing:

- Restored the Drive artifact convention: new test builds should go under `I:\My Drive\Bobby Assistant Artifacts\<build-slug>\` with the PBW, APK, and update helper when applicable. The accidental flat `I:\My Drive\Billy Assistant Builds` folder remains as a reference but should not be the primary output path.
- Recovered the flat `Billy Assistant Builds` APK/PBW files into per-build folders named `20260627-recovered-billy-0.1.38-1.5.23` through `20260628-recovered-billy-0.1.44-1.5.25` under `I:\My Drive\Bobby Assistant Artifacts\`. The flat originals were left in place.
- Current test artifacts are in `I:\My Drive\Bobby Assistant Artifacts\20260628-billy-navigation-direct-open-map-card\`.
- Navigation routing now tells Gemini to call `open_maps_directions` first for phone navigation and then `show_map_directions` with `open_navigation=false` for the watch map card. This restores the older direct `google.navigation:q=<destination text>` opener as the primary phone-launch path instead of making the map-card renderer responsible for launching Maps.
- `show_map_directions` now defaults `open_navigation` to false.
- Android companion version `0.1.45` identifies this build. Watch app remains `1.5.25`.

Status on 2026-06-28, Static Maps card fallback:

- Phone navigation launch is now treated as separate from watch map-card rendering; the direct `open_maps_directions` path remains untouched because it is opening Maps again.
- Google Static Maps rendering now tries a route overview first, then a direct origin/destination path, then marker-only, then destination-only before surfacing an error.
- If Google Static Maps rejects every URL, Billy falls back to the existing OpenStreetMap tile renderer for the destination so the user still gets a watch map card when possible.
- Static Maps HTML error pages are stripped into readable text for debugging instead of dumping `<!DOCTYPE html>` onto the watch.
- Android companion version `0.1.46` identifies this build. Watch app remains `1.5.25`.

Status on 2026-06-28, no-fallback Maps correction:

- The `0.1.46` map-card fallback strategy is superseded. It hid real Maps failures and made it unclear which provider or launch path fired during testing.
- Nearby/local requests now have a dedicated `find_nearby_google_places` tool. It uses the Android phone's current location, a bounded Google Places Text Search `locationRestriction`, distance ranking, and an optional Google Places type such as `train_station`.
- Generic `search_google_places` is now documented as explicit-city/address search only. Gemini instructions require nearby/nearest/local phrasing to use the location-bounded nearby tool first.
- `show_map_directions` no longer launches phone navigation and no longer falls back to OpenStreetMap or synthetic map drawing. It either renders the Google Maps Platform card from the selected destination/route or returns the real Google error.
- Static Maps rendering now uses one route/map request instead of trying route, straight-line, marker-only, destination-only, and non-Google providers in sequence.
- The notification/proxy navigation fallback was removed. `open_maps_directions` now uses only the direct Google Maps navigation intent and surfaces launch exceptions instead of silently opening a fallback notification or web URL.
- Watch app remains `1.5.25`; Android companion version `0.1.47` identifies this maps-only correction build.

Status on 2026-06-28, OpenStreetMap no-key/companionless restoration:

- The no-fallback correction was too broad: OpenStreetMap is still required for companionless use and for users who have not pasted a Google Maps Platform key.
- Android `show_map_directions` now selects providers by configuration, not by failure recovery:
  - blank Maps key: render an OpenStreetMap card with Nominatim/tile data
  - nonblank Maps key: use Google Maps Platform only and surface Google errors instead of substituting OSM
- Companionless JS now exposes `show_openstreetmap_map`, which uses the existing image-transfer and map-widget path so no Android companion is required for basic map cards.
- Automatic runtime no longer assumes the Android companion exists. Android announces `ANDROID_COMPANION_READY`; the watch forwards that heartbeat to JS; automatic mode waits briefly for it and otherwise runs the companionless runtime.
- Watch app version `1.5.26` and Android companion version `0.1.48` identify this integrated build.

Status on 2026-06-29, Bobby/Billy feature parity review:

- Added the Bobby feature parity checklist above and marked each feature as implemented, partial, or open for companionless verification.
- Fixed manual prompt/heartbeat protocol drift: `WATCH_PROMPT=10121`, `WATCH_READY=10122`, and `ANDROID_COMPANION_READY=10123` now match the clean generated app keys after `FEEDBACK_POST_URL=10120`.
- Added companionless `get_weather` using Open-Meteo and Bobby's existing current-weather widget path.
- Restored companionless conversational settings changes through the existing `update_settings` action.
- Added Gemini, OSM, and weather request timeouts to avoid silent endless thinking states.
- Delayed companionless OSM map-widget enqueue until after image metadata is sent, reducing blank map races.
- Fixed message queue send-failure handling so an empty queue is not dequeued.
- Release notes now load the 1.5 changelog resource.
- Watch app version `1.5.27` and Android companion version `0.1.49` identify this review build.

Status on 2026-06-29, Billy profile and memory layer:

- Added a durable local Billy profile store in the Android companion. It stores
  Google profile basics, People API profile fields where available, and explicit
  Billy memories.
- Added `Load Google profile` to the companion UI. It uses OAuth identity
  profile data first, then enriches from the People API when granted.
- Added local `remember`, `forget`, and `what do you know about me` tools for
  both Android companion mode and companionless mode.
- Companion mode injects a compact Billy profile/memory block into each Gemini
  request before recent conversation context.
- Companionless mode adds a `Billy profile context` Clay setting and local
  storage-backed profile tools.
- This still does not grant consumer Gemini app memory, Gemini app chat history,
  or Gemini Connected Apps context. The Gemini API key only authenticates model
  API usage; Billy's personal context must come from Billy memory, explicit
  prompt context, OAuth-backed Google APIs, or current tool results.

Status on 2026-06-29, automatic profile identity hydration:

- Fixed the first-use gap where Billy could have Google OAuth access but still
  not know the user's name until `Load Google profile` was tapped manually.
- After Google OAuth succeeds, the companion now stores the lightweight Google
  identity profile immediately.
- Before each companion-mode Gemini request, Billy now refreshes missing/stale
  Google identity profile data if identity scopes are already granted.
- The automatic path uses only the lightweight OAuth userinfo profile to keep
  latency low; the manual `Load Google profile` button remains available for
  deeper People API enrichment.

Status on 2026-06-29, companionless schema/runtime fix:

- Fixed Gemini companionless tool schema failures by normalizing missing
  function parameters to an empty object schema and adding explicit empty
  schemas to zero-argument profile/alarm/timer/reminder tools.
- Increased automatic-mode Android companion wait from 1.2s to 5s so cold
  companion wakeups are less likely to race with companionless fallback and
  produce duplicate error/answer messages.

Status on 2026-07-01, watch AppMessage send retry:

- Fixed first-attempt `Sending to service failed` errors when a prompt launches
  the watch chat from the phone and the Pebble AppMessage outbox is still busy.
- Conversation prompt sends now retry internally before surfacing an error.
- Prompt outbox failure callbacks now retry the prompt instead of immediately
  adding a visible chat error.
