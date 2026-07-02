# Billy Profile Pack Template

This document is designed to be given directly to Gemini, then imported into
Billy after the user reviews the result.

## Instructions for the AI filling this document

You are helping the user create a personal context file for Billy, a private
smartwatch assistant. Your job is to fill the YAML profile pack below using
only information you can access in the current user-approved context: explicit
user statements, Gemini app memory, attached exports, connected Google apps, or
documents the user provides.

Return a completed profile pack in the exact structure below. Keep the boundary
markers:

- `BEGIN_BILLY_PROFILE_PACK`
- `END_BILLY_PROFILE_PACK`

Do not return explanatory prose outside those markers unless the user asks for
it. Do not invent facts. If a field is unknown, use `null`, `unknown`, or an
empty list. If a fact is likely but not certain, include it in
`uncertain_facts` or set `confidence: low`.

Billy is a watch assistant, so favor facts that help answer short voice
requests correctly:

- who the user is
- names and relationships of important people
- homes, workplaces, frequent places, and travel patterns
- hobbies, interests, projects, and recurring responsibilities
- preferences for maps, weather, calendar, email, photos, reminders, tasks,
  food, shopping, entertainment, tone, and response style
- aliases and phrases the user commonly uses
- private data that should require confirmation before use

Preserve privacy. Do not include passwords, API keys, access tokens, full
payment card numbers, government ID numbers, or secrets. For medical, legal,
financial, or safety-sensitive information, include only facts the user has
explicitly approved or that are necessary for safe assistance, and mark them
`sensitive: true`.

For every important fact, provide metadata:

- `confidence`: `high`, `medium`, or `low`
- `source_hint`: short explanation such as `user stated`, `Gemini memory`,
  `calendar pattern`, `email pattern`, `profile`, `attached document`, or
  `inferred from repeated context`
- `last_confirmed`: ISO date if known, otherwise `unknown`
- `sensitive`: `true` or `false`
- `use_for`: list of Billy features that may use the fact, such as `maps`,
  `calendar`, `gmail`, `photos`, `tasks`, `reminders`, `weather`, `shopping`,
  `conversation`, `projects`, or `preferences`

Use concise values. Prefer lists of structured items over paragraphs. If a
section has no known data, leave an empty list and do not explain.

When filling people:

- include full names when known
- include nicknames, aliases, relationship labels, and likely contact hints
- note who should not be confused with whom
- include pets as pets, not people, unless the schema asks for household
  members broadly

When filling places:

- include exact addresses only if the user has clearly approved storing them
- otherwise use neighborhoods, cities, landmarks, or labels
- set `safe_for_navigation` to `true` only when Billy can safely use that
  place for maps/directions without asking again

When filling photos context:

- include names of people, pets, places, albums, and recurring photo subjects
- include visual hints that help identify them
- do not claim Billy can access private face labels unless the source actually
  provides them

When filling email/calendar/task context:

- capture recurring senders, event types, calendar names, task lists, and user
  preferences
- do not copy long email bodies or full private messages
- summarize patterns and labels instead

When filling projects:

- include software, hardware, writing, art, home, family, travel, work,
  business, hobby, and community projects
- include goals, status, next actions, relevant people, repos/docs, and aliases

When filling assistant behavior:

- include how Billy should answer on a watch
- include when Billy should ask a picker question
- include things Billy must never silently fake
- include preferred defaults and confirmation rules

## Instructions for the user

1. Give this document to Gemini in the Gemini app.
2. Ask Gemini to fill it from what it knows about you. A short prompt is enough:
   `Fill out this Billy Profile Pack for my smartwatch assistant.`
3. Review the filled result. Delete anything you do not want Billy to know.
4. Import the reviewed pack into Billy Companion when the importer exists.

## Fillable Profile Pack

```yaml
BEGIN_BILLY_PROFILE_PACK
billy_profile_pack_version: 1
pack_metadata:
  generated_at: null
  generated_by: Gemini
  generated_for_assistant: Billy
  owner_review_required: true
  owner_reviewed_at: null
  privacy_notes: []
  source_summary:
    user_statements: []
    gemini_memory: []
    connected_apps_used: []
    attached_exports_used: []
    limitations: []

identity:
  preferred_name:
    value: null
    aliases: []
    confidence: low
    source_hint: unknown
    last_confirmed: unknown
    sensitive: false
    use_for: [conversation]
  full_name:
    value: null
    aliases: []
    confidence: low
    source_hint: unknown
    last_confirmed: unknown
    sensitive: false
    use_for: [conversation, gmail, calendar]
  display_initials:
    value: null
    confidence: low
    source_hint: unknown
    last_confirmed: unknown
    sensitive: false
    use_for: [conversation]
  pronouns:
    value: null
    confidence: low
    source_hint: unknown
    last_confirmed: unknown
    sensitive: false
    use_for: [conversation]
  birthday_or_age_notes:
    value: null
    confidence: low
    source_hint: unknown
    last_confirmed: unknown
    sensitive: true
    use_for: [conversation, calendar]
  languages:
    - value: null
      fluency: unknown
      confidence: low
      source_hint: unknown
      last_confirmed: unknown
      sensitive: false
      use_for: [conversation]
  home_timezone:
    value: null
    confidence: low
    source_hint: unknown
    last_confirmed: unknown
    sensitive: false
    use_for: [calendar, reminders, weather]
  current_or_travel_timezone_notes:
    value: null
    confidence: low
    source_hint: unknown
    last_confirmed: unknown
    sensitive: false
    use_for: [calendar, reminders, maps]
  communication_style:
    value: null
    examples: []
    confidence: low
    source_hint: unknown
    last_confirmed: unknown
    sensitive: false
    use_for: [conversation]

household:
  partner_spouse: []
  children: []
  pets: []
  roommates: []
  important_family_members: []
  household_preferences: []
  emergency_contacts: []

people:
  family: []
  friends: []
  coworkers: []
  collaborators: []
  neighbors: []
  service_providers: []
  doctors_or_health_contacts: []
  school_or_childcare_contacts: []
  contact_aliases:
    - alias: null
      person: null
      relationship: null
      confidence: low
      source_hint: unknown
      last_confirmed: unknown
      sensitive: false
      use_for: [gmail, calendar, reminders]
  people_to_prioritize: []
  people_to_avoid_confusing:
    - person_a: null
      person_b: null
      reason: null
      confidence: low
      source_hint: unknown
      last_confirmed: unknown
      sensitive: false
      use_for: [gmail, calendar, photos]

places:
  home:
    label: home
    address_or_area: null
    aliases: []
    safe_for_navigation: false
    ask_before_using_exact_address: true
    confidence: low
    source_hint: unknown
    last_confirmed: unknown
    sensitive: true
    use_for: [maps, weather, calendar]
  work: []
  family_homes: []
  frequent_places: []
  favorite_places: []
  schools_or_childcare: []
  medical_places: []
  travel_bases: []
  airports_and_stations: []
  hotels_or_regular_stays: []
  places_to_avoid: []
  navigation_preferences:
    default_mode: null
    walking_preferences: []
    driving_preferences: []
    cycling_preferences: []
    transit_preferences: []
    accessibility_or_safety_notes: []
    ask_before_opening_phone_navigation: null

routines:
  daily_routine: []
  weekly_routine: []
  work_schedule: []
  school_or_childcare_schedule: []
  sleep_schedule: []
  commute_patterns: []
  recurring_errands: []
  recurring_chores: []
  meal_patterns: []
  fitness_patterns: []
  travel_patterns: []
  time_sensitive_habits: []

calendar_context:
  calendar_names_and_meanings: []
  default_calendar_for_new_events:
    value: null
    confidence: low
    source_hint: unknown
    last_confirmed: unknown
    sensitive: false
    use_for: [calendar]
  calendars_never_to_modify: []
  recurring_events: []
  important_event_types: []
  scheduling_preferences:
    default_meeting_lengths: []
    buffer_preferences: []
    preferred_times: []
    blocked_times: []
    timezone_rules: []
    invite_rules: []
  availability_rules: []
  birthdays_anniversaries_and_important_dates: []
  common_calendar_phrases:
    - user_phrase: null
      intended_calendar_action: null
      confidence: low
      source_hint: unknown
      last_confirmed: unknown
      sensitive: false
      use_for: [calendar]

gmail_context:
  important_senders: []
  important_recipients: []
  email_aliases: []
  threads_or_topics_to_prioritize: []
  bills_and_account_email_patterns: []
  travel_email_patterns: []
  shopping_email_patterns: []
  newsletters_to_ignore: []
  email_tone_preferences: []
  send_email_rules:
    always_confirm_before_sending: true
    allowed_auto_send_cases: []
    signature_preferences: []
    sensitive_topics_requiring_confirmation: []
  common_email_requests: []

tasks_and_reminders_context:
  task_lists_and_meanings: []
  default_task_list:
    value: null
    confidence: low
    source_hint: unknown
    last_confirmed: unknown
    sensitive: false
    use_for: [tasks]
  reminder_preferences:
    prefer_watch_timeline_reminders_for_timed_reminders: true
    prefer_tasks_for_untimed_todos: true
    default_reminder_offsets: []
    recurring_reminder_patterns: []
  common_todos: []
  errands: []
  task_completion_rules: []

projects:
  active_projects: []
  paused_projects: []
  completed_projects: []
  software_projects: []
  hardware_projects: []
  home_projects: []
  creative_projects: []
  business_projects: []
  family_projects: []
  travel_projects: []
  research_or_learning_projects: []
  community_or_social_projects: []
  project_aliases:
    - alias: null
      project: null
      confidence: low
      source_hint: unknown
      last_confirmed: unknown
      sensitive: false
      use_for: [projects, drive, gmail, calendar]
  repos_docs_and_folders: []
  next_actions: []

work:
  roles: []
  employers_clients_or_businesses: []
  teams: []
  coworkers_and_stakeholders: []
  tools_and_systems: []
  recurring_responsibilities: []
  jargon_and_acronyms: []
  important_docs_or_folders: []
  work_preferences: []
  work_boundaries: []
  work_travel: []
  work_sensitive_topics: []

hobbies_and_interests:
  hobbies: []
  sports: []
  games: []
  books: []
  music: []
  movies_tv_and_video: []
  podcasts_and_channels: []
  tech_interests: []
  collecting_interests: []
  outdoor_interests: []
  creative_interests: []
  vehicles_transport_or_mobility: []
  food_drink_and_cooking: []
  travel_interests: []
  learning_goals: []
  communities_or_clubs: []
  favorite_topics_to_discuss: []
  topics_to_avoid_or_handle_carefully: []

preferences:
  watch_response_style:
    preferred_length: short
    allow_rich_cards: true
    use_bullets_when_helpful: true
    avoid_duplicate_card_text: true
    confidence: medium
    source_hint: template_default
    last_confirmed: unknown
    sensitive: false
    use_for: [conversation]
  units:
    temperature: null
    distance: null
    speed: null
    weight: null
    time_format: null
  weather_preferences: []
  maps_preferences: []
  food_and_restaurant_preferences: []
  coffee_preferences: []
  shopping_preferences: []
  brand_preferences: []
  entertainment_preferences: []
  news_preferences: []
  privacy_preferences: []
  notification_preferences: []
  accessibility_preferences: []
  humor_and_tone_preferences: []

health_and_lifestyle:
  fitness_goals: []
  dietary_constraints: []
  allergies: []
  medications_or_health_reminders: []
  medical_preferences: []
  stressors_or_support_preferences: []
  sleep_preferences: []
  safety_notes: []
  include_only_if_user_approved: true

photos_context:
  important_people_to_recognize: []
  pets_to_recognize: []
  frequent_photo_locations: []
  albums_or_collections: []
  recurring_events_or_occasions: []
  visual_search_hints:
    - subject: null
      visual_description: null
      aliases: []
      confidence: low
      source_hint: unknown
      last_confirmed: unknown
      sensitive: false
      use_for: [photos]
  things_user_often_photographs: []
  screenshots_context: []
  photo_privacy_rules: []

drive_docs_context:
  important_drive_folders: []
  docs_projects: []
  sheets_trackers: []
  slides_or_presentations: []
  forms: []
  file_naming_patterns: []
  files_to_prioritize: []
  files_to_ignore: []
  document_aliases: []
  common_drive_requests: []

search_and_web_context:
  recurring_search_topics: []
  favorite_sources: []
  sources_to_avoid: []
  shopping_research_patterns: []
  travel_research_patterns: []
  tech_research_patterns: []
  local_search_preferences: []
  fact_checking_preferences: []

assistant_behavior:
  always_do: []
  never_do:
    - value: Do not pretend an unavailable service worked.
      confidence: high
      source_hint: Billy project rule
      last_confirmed: unknown
      sensitive: false
      use_for: [conversation, calendar, gmail, tasks, photos, maps]
    - value: Ask before using fallback behavior that changes where data is stored.
      confidence: high
      source_hint: Billy project rule
      last_confirmed: unknown
      sensitive: false
      use_for: [conversation, calendar, gmail, tasks, photos, maps]
  ask_before: []
  safe_defaults: []
  confirmation_rules:
    before_sending_email: true
    before_deleting_or_modifying_calendar_events: true
    before_using_exact_home_address: true
    before_sharing_sensitive_data: true
    before_opening_phone_navigation: null
  clarification_style:
    use_picker_cards_for_questions: true
    include_dictate_option_when_possible: true
    avoid_open_ended_watch_questions: true
    max_picker_options: 4
  preferred_error_style:
    be_brief: true
    state_real_blocker: true
    suggest_next_action: true
    do_not_show_internal_ids_unless_needed: true
  proactive_help_rules: []

common_phrases_and_intents:
  dictation_misrecognitions:
    - heard_phrase: null
      likely_intent: null
      confidence: low
      source_hint: unknown
      last_confirmed: unknown
      sensitive: false
      use_for: [conversation, reminders, calendar]
  user_shortcuts:
    - user_phrase: null
      intended_action: null
      confidence: low
      source_hint: unknown
      last_confirmed: unknown
      sensitive: false
      use_for: [conversation]
  ambiguous_phrases:
    - phrase: null
      possible_meanings: []
      preferred_interpretation: null
      when_to_ask: null
      confidence: low
      source_hint: unknown
      last_confirmed: unknown
      sensitive: false
      use_for: [conversation]

examples:
  good_answers: []
  bad_answers: []
  good_picker_questions: []
  bad_picker_questions: []
  useful_watch_cards: []
  requests_billy_should_handle_well: []

memory_management:
  facts_to_remember: []
  facts_to_forget: []
  uncertain_facts:
    - fact: null
      why_uncertain: null
      possible_values: []
      suggested_question_to_user: null
      confidence: low
      source_hint: unknown
      last_confirmed: unknown
      sensitive: false
      use_for: [conversation]
  stale_facts: []
  sensitive_facts_requiring_confirmation: []
  private_do_not_store: []

raw_user_approved_notes:
  # Use this only for short user-approved notes that do not fit above.
  - value: null
    confidence: low
    source_hint: unknown
    last_confirmed: unknown
    sensitive: false
    use_for: [conversation]
END_BILLY_PROFILE_PACK
```
