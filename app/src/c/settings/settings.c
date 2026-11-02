/*
 * Copyright 2025 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

#include "settings.h"
#include <pebble.h>
#include <string.h>
#include <pebble-events/pebble-events.h>

#include "../util/persist_keys.h"

static EventHandle s_event_handle;
static char s_assistant_runtime[16];
static char s_gemini_model[40];
static char s_gemini_thinking[12];
static AppTimer *s_sync_timer;
static int s_sync_attempts;

static void prv_app_message_handler(DictionaryIterator *iter, void *context);

void settings_init() {
  s_event_handle = events_app_message_register_inbox_received(prv_app_message_handler, NULL);
}

void settings_deinit() {
  events_app_message_unsubscribe(s_event_handle);
  if (s_sync_timer) {
    app_timer_cancel(s_sync_timer);
    s_sync_timer = NULL;
  }
}

QuickLaunchBehaviour settings_get_quick_launch_behaviour() {
  int result = persist_read_int(PERSIST_KEY_QUICK_LAUNCH_BEHAVIOUR);
  if (result == 0) {
    return QuickLaunchBehaviourConverseWithTimeout;
  }
  return result;
}

VibePatternSetting settings_get_alarm_vibe_pattern() {
  int result = persist_read_int(PERSIST_KEY_ALARM_VIBE_PATTERN);
  if (result == 0) {
    return VibePatternSettingStandard;
  }
  return result;
}

VibePatternSetting settings_get_timer_vibe_pattern() {
  int result = persist_read_int(PERSIST_KEY_TIMER_VIBE_PATTERN);
  if (result == 0) {
    return VibePatternSettingStandard;
  }
  return result;
}

bool settings_get_should_confirm_transcripts() {
  // the default is false, so we don't have to check whether it exists.
  return persist_read_bool(PERSIST_KEY_CONFIRM_TRANSCRIPTS);
}

const char* settings_get_assistant_runtime() {
  if (persist_exists(PERSIST_KEY_ASSISTANT_RUNTIME)) {
    persist_read_string(PERSIST_KEY_ASSISTANT_RUNTIME, s_assistant_runtime, sizeof(s_assistant_runtime));
    s_assistant_runtime[sizeof(s_assistant_runtime) - 1] = '\0';
    return s_assistant_runtime;
  }
  return "automatic";
}

const char* settings_get_gemini_model() {
  if (persist_exists(PERSIST_KEY_GEMINI_MODEL)) {
    persist_read_string(PERSIST_KEY_GEMINI_MODEL, s_gemini_model, sizeof(s_gemini_model));
    s_gemini_model[sizeof(s_gemini_model) - 1] = '\0';
    return s_gemini_model;
  }
  return "";
}

const char* settings_get_gemini_thinking() {
  if (persist_exists(PERSIST_KEY_GEMINI_THINKING)) {
    persist_read_string(PERSIST_KEY_GEMINI_THINKING, s_gemini_thinking, sizeof(s_gemini_thinking));
    s_gemini_thinking[sizeof(s_gemini_thinking) - 1] = '\0';
    if (s_gemini_thinking[0] != '\0') {
      return s_gemini_thinking;
    }
  }
  return "low";
}

// Sends the current model and thinking level to the phone. The outbox may be
// busy with a conversation, so retry a few times.
static void prv_sync_to_phone(void *context) {
  s_sync_timer = NULL;
  DictionaryIterator *iter;
  if (app_message_outbox_begin(&iter) != APP_MSG_OK) {
    if (++s_sync_attempts < 10) {
      s_sync_timer = app_timer_register(500, prv_sync_to_phone, NULL);
    }
    return;
  }
  const char *model = settings_get_gemini_model();
  if (model[0] != '\0') {
    dict_write_cstring(iter, MESSAGE_KEY_GEMINI_MODEL, model);
  }
  dict_write_cstring(iter, MESSAGE_KEY_GEMINI_THINKING, settings_get_gemini_thinking());
  app_message_outbox_send();
}

static void prv_schedule_sync(void) {
  s_sync_attempts = 0;
  if (s_sync_timer) {
    app_timer_cancel(s_sync_timer);
  }
  s_sync_timer = app_timer_register(50, prv_sync_to_phone, NULL);
}

void settings_set_gemini_model(const char* model) {
  if (!model || strlen(model) >= sizeof(s_gemini_model)) {
    return;
  }
  persist_write_string(PERSIST_KEY_GEMINI_MODEL, model);
  prv_schedule_sync();
}

void settings_set_gemini_thinking(const char* level) {
  if (!level || strlen(level) >= sizeof(s_gemini_thinking)) {
    return;
  }
  persist_write_string(PERSIST_KEY_GEMINI_THINKING, level);
  prv_schedule_sync();
}

static void prv_app_message_handler(DictionaryIterator *iter, void *context) {
  for (Tuple *tuple = dict_read_first(iter); tuple; tuple = dict_read_next(iter)) {
    if (tuple->key == MESSAGE_KEY_QUICK_LAUNCH_BEHAVIOUR) {
      int value = atoi(tuple->value->cstring);
      persist_write_int(PERSIST_KEY_QUICK_LAUNCH_BEHAVIOUR, value);
    } else if (tuple->key == MESSAGE_KEY_ALARM_VIBE_PATTERN) {
      persist_write_int(PERSIST_KEY_ALARM_VIBE_PATTERN, atoi(tuple->value->cstring));
    } else if (tuple->key == MESSAGE_KEY_TIMER_VIBE_PATTERN) {
      persist_write_int(PERSIST_KEY_TIMER_VIBE_PATTERN, atoi(tuple->value->cstring));
    } else if (tuple->key == MESSAGE_KEY_CONFIRM_TRANSCRIPTS) {
      persist_write_bool(PERSIST_KEY_CONFIRM_TRANSCRIPTS, tuple->value->int8);
    } else if (tuple->key == MESSAGE_KEY_ASSISTANT_RUNTIME) {
      persist_write_string(PERSIST_KEY_ASSISTANT_RUNTIME, tuple->value->cstring);
    } else if (tuple->key == MESSAGE_KEY_GEMINI_MODEL && tuple->type == TUPLE_CSTRING &&
               strlen(tuple->value->cstring) < sizeof(s_gemini_model)) {
      persist_write_string(PERSIST_KEY_GEMINI_MODEL, tuple->value->cstring);
    } else if (tuple->key == MESSAGE_KEY_GEMINI_THINKING && tuple->type == TUPLE_CSTRING &&
               strlen(tuple->value->cstring) < sizeof(s_gemini_thinking)) {
      persist_write_string(PERSIST_KEY_GEMINI_THINKING, tuple->value->cstring);
    }
  }
}
