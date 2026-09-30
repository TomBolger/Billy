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

#include "root_window.h"
#include "release_notes.h"
#include "consent/consent.h"
#include "converse/session_window.h"
#include "converse/conversation_manager.h"
#include "image_manager/image_manager.h"
#include "alarms/manager.h"
#include "version/version.h"
#include "settings/settings.h"
#include "util/memory/malloc.h"

#include <pebble.h>
#include <string.h>
#include <pebble-events/pebble-events.h>

#include "util/fonts.h"
#include "util/logging.h"
#include "util/memory/pressure.h"


#define QUICK_LAUNCH_TIMEOUT_MS 60000
#define BILLY_MESSAGE_KEY_WATCH_PROMPT 10122
#define BILLY_MESSAGE_KEY_ANDROID_COMPANION_READY 10124
#define BILLY_MESSAGE_KEY_ANDROID_REQUEST_ID 10125
#define ANDROID_HEARTBEAT_RETRY_DELAY_MS 250
#define ANDROID_HEARTBEAT_MAX_ATTEMPTS 6

static RootWindow* s_root_window = NULL;
static EventHandle s_prompt_inbox_handle = NULL;
static AppTimer *s_android_heartbeat_retry_timer = NULL;
static int s_android_heartbeat_attempts = 0;
static uint32_t s_android_heartbeat_request_id = 0;

static bool prv_send_android_companion_ready_to_phone(void);
static void prv_retry_android_companion_ready(void *context);

// Tool relay between the Android companion and the phone JS. Neither can
// message the other directly, so the watch bounces JS_TOOL_REQUEST (from
// Android) and JS_TOOL_RESULT (from phone JS) back out to the phone, where the
// other side picks it up. Small FIFO with retries because the outbox is shared
// with the conversation traffic.
#define RELAY_QUEUE_SIZE 4
#define RELAY_RETRY_DELAY_MS 120
#define RELAY_MAX_ATTEMPTS 25

typedef struct {
  uint32_t key;
  char *payload;
} RelayItem;

static RelayItem s_relay_queue[RELAY_QUEUE_SIZE];
static int s_relay_count = 0;
static int s_relay_attempts = 0;
static AppTimer *s_relay_timer = NULL;

static void prv_relay_pump(void *context);

static void prv_relay_drop_head(void) {
  if (s_relay_count == 0) {
    return;
  }
  free(s_relay_queue[0].payload);
  for (int i = 1; i < s_relay_count; ++i) {
    s_relay_queue[i - 1] = s_relay_queue[i];
  }
  s_relay_count--;
  s_relay_attempts = 0;
}

static void prv_relay_schedule(uint32_t delay_ms) {
  if (s_relay_timer || s_relay_count == 0) {
    return;
  }
  s_relay_timer = app_timer_register(delay_ms, prv_relay_pump, NULL);
}

static void prv_relay_pump(void *context) {
  s_relay_timer = NULL;
  while (s_relay_count > 0) {
    DictionaryIterator *out;
    AppMessageResult result = app_message_outbox_begin(&out);
    if (result == APP_MSG_OK && out) {
      dict_write_cstring(out, s_relay_queue[0].key, s_relay_queue[0].payload);
      result = app_message_outbox_send();
    }
    if (result != APP_MSG_OK) {
      if (++s_relay_attempts >= RELAY_MAX_ATTEMPTS) {
        BOBBY_LOG(APP_LOG_LEVEL_WARNING, "Dropping relay message after %d attempts.", s_relay_attempts);
        prv_relay_drop_head();
        continue;
      }
      prv_relay_schedule(RELAY_RETRY_DELAY_MS);
      return;
    }
    prv_relay_drop_head();
    // One message per outbox slot; give the outbox time to drain.
    prv_relay_schedule(RELAY_RETRY_DELAY_MS);
    return;
  }
}

static void prv_relay_enqueue(uint32_t key, const char *payload) {
  if (!payload || payload[0] == '\0') {
    return;
  }
  if (s_relay_count >= RELAY_QUEUE_SIZE) {
    BOBBY_LOG(APP_LOG_LEVEL_WARNING, "Relay queue full; dropping oldest.");
    prv_relay_drop_head();
  }
  char *copy = bmalloc(strlen(payload) + 1);
  strcpy(copy, payload);
  s_relay_queue[s_relay_count].key = key;
  s_relay_queue[s_relay_count].payload = copy;
  s_relay_count++;
  if (!s_relay_timer) {
    prv_relay_pump(NULL);
  }
}

static bool prv_send_android_companion_ready_to_phone(void) {
  DictionaryIterator *out;
  AppMessageResult result = app_message_outbox_begin(&out);
  if (result != APP_MSG_OK || !out) {
    BOBBY_LOG(APP_LOG_LEVEL_WARNING, "Could not forward Android companion heartbeat: %d.", result);
    return false;
  }
  dict_write_uint8(out, BILLY_MESSAGE_KEY_ANDROID_COMPANION_READY, 1);
  if (s_android_heartbeat_request_id != 0) {
    dict_write_uint32(out, BILLY_MESSAGE_KEY_ANDROID_REQUEST_ID, s_android_heartbeat_request_id);
  }
  result = app_message_outbox_send();
  if (result != APP_MSG_OK) {
    BOBBY_LOG(APP_LOG_LEVEL_WARNING, "Could not send Android companion heartbeat: %d.", result);
    return false;
  }
  return true;
}

static void prv_schedule_android_companion_ready_retry(void) {
  if (s_android_heartbeat_attempts >= ANDROID_HEARTBEAT_MAX_ATTEMPTS) {
    BOBBY_LOG(APP_LOG_LEVEL_WARNING, "Giving up forwarding Android companion heartbeat.");
    return;
  }
  if (s_android_heartbeat_retry_timer) {
    app_timer_cancel(s_android_heartbeat_retry_timer);
  }
  s_android_heartbeat_retry_timer = app_timer_register(ANDROID_HEARTBEAT_RETRY_DELAY_MS, prv_retry_android_companion_ready, NULL);
}

static void prv_retry_android_companion_ready(void *context) {
  s_android_heartbeat_retry_timer = NULL;
  s_android_heartbeat_attempts++;
  if (prv_send_android_companion_ready_to_phone()) {
    s_android_heartbeat_attempts = 0;
    return;
  }
  prv_schedule_android_companion_ready_retry();
}

static void prv_forward_android_companion_ready(uint32_t request_id) {
  s_android_heartbeat_request_id = request_id;
  s_android_heartbeat_attempts = 1;
  if (prv_send_android_companion_ready_to_phone()) {
    s_android_heartbeat_attempts = 0;
    return;
  }
  prv_schedule_android_companion_ready_retry();
}

static void prv_prompt_inbox_received(DictionaryIterator *iter, void *context) {
  Tuple *relay_tuple = dict_find(iter, MESSAGE_KEY_JS_TOOL_REQUEST);
  if (relay_tuple && relay_tuple->type == TUPLE_CSTRING) {
    prv_relay_enqueue(MESSAGE_KEY_JS_TOOL_REQUEST, relay_tuple->value->cstring);
    return;
  }
  relay_tuple = dict_find(iter, MESSAGE_KEY_JS_TOOL_RESULT);
  if (relay_tuple && relay_tuple->type == TUPLE_CSTRING) {
    prv_relay_enqueue(MESSAGE_KEY_JS_TOOL_RESULT, relay_tuple->value->cstring);
    return;
  }
  Tuple *android_ready_tuple = dict_find(iter, BILLY_MESSAGE_KEY_ANDROID_COMPANION_READY);
  if (android_ready_tuple) {
    Tuple *request_tuple = dict_find(iter, BILLY_MESSAGE_KEY_ANDROID_REQUEST_ID);
    uint32_t request_id = request_tuple ? request_tuple->value->uint32 : 0;
    prv_forward_android_companion_ready(request_id);
    return;
  }
  Tuple *prompt_tuple = dict_find(iter, BILLY_MESSAGE_KEY_WATCH_PROMPT);
  if (!prompt_tuple) {
    prompt_tuple = dict_find(iter, MESSAGE_KEY_PROMPT);
  }
  if (!prompt_tuple || prompt_tuple->length <= 1) {
    return;
  }
  BOBBY_LOG(APP_LOG_LEVEL_INFO, "Launching session from typed phone prompt.");
  session_window_push(0, prompt_tuple->value->cstring);
}

static void prv_init(void) {
  memory_pressure_init();
  version_init();
  consent_migrate();
  settings_init();
  conversation_manager_init();
#if ENABLE_FEATURE_IMAGE_MANAGER
  image_manager_init();
#endif
  events_app_message_open();
  s_prompt_inbox_handle = events_app_message_register_inbox_received(prv_prompt_inbox_received, NULL);
  alarm_manager_init();
  fonts_load();
}

static void prv_deinit(void) {
  if (s_prompt_inbox_handle) {
    events_app_message_unsubscribe(s_prompt_inbox_handle);
    s_prompt_inbox_handle = NULL;
  }
  if (s_android_heartbeat_retry_timer) {
    app_timer_cancel(s_android_heartbeat_retry_timer);
    s_android_heartbeat_retry_timer = NULL;
  }
  if (s_relay_timer) {
    app_timer_cancel(s_relay_timer);
    s_relay_timer = NULL;
  }
  while (s_relay_count > 0) {
    prv_relay_drop_head();
  }
  if (s_root_window) {
    root_window_destroy(s_root_window);
  }
#ifdef ENABLE_FEATURE_IMAGE_MANAGER
  image_manager_deinit();
#endif
  fonts_unload();
}

int main(void) {
  VersionInfo version_info = version_get_current();
  BOBBY_LOG(APP_LOG_LEVEL_INFO, "Billy %d.%d", version_info.major, version_info.minor);
  prv_init();
  
  if (alarm_manager_maybe_alarm()) {
    // don't actually have anything to do here - the alarm manager already did it.
  } else {
    if (must_present_consent()) {
      consent_window_push();
    } else {
      if (launch_reason() == APP_LAUNCH_QUICK_LAUNCH) {
        QuickLaunchBehaviour quick_launch_behaviour = settings_get_quick_launch_behaviour();
        if (quick_launch_behaviour != QuickLaunchBehaviourHomeScreen) {
          session_window_push(quick_launch_behaviour == QuickLaunchBehaviourConverseWithTimeout ? QUICK_LAUNCH_TIMEOUT_MS : 0, NULL);
        } else {
          s_root_window = root_window_create();
          root_window_push(s_root_window);
        }
      } else {
        s_root_window = root_window_create();
        root_window_push(s_root_window);
      }
      release_notes_maybe_push();
    }
  }


  app_event_loop();
  prv_deinit();
}
