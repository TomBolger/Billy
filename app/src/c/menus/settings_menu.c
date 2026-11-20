#include "settings_menu.h"
#include "../settings/settings.h"
#include "../util/style.h"
#include "../util/memory/malloc.h"
#include "../util/memory/sdk.h"
#include <pebble.h>
#include <string.h>

// Keep in step with GEMINI_MODEL in app/src/pkjs/config.json.
typedef struct {
  const char *value;
  const char *title;
  const char *subtitle;
} Choice;

static const Choice s_models[] = {
  {"gemini-3.8-flash", "3.8 Flash", "Recommended"},
  {"gemini-3.6-flash", "3.6 Flash", NULL},
  {"gemini-3.5-flash-lite", "3.5 Flash-Lite", "Bigger free allowance"},
  {"gemini-3.1-flash-lite", "3.1 Flash-Lite", "Cheapest"},
  {"gemini-3.1-pro-preview", "3.1 Pro", "Needs billing, slow"},
};
#define MODEL_COUNT (sizeof(s_models) / sizeof(s_models[0]))
#define DEFAULT_MODEL_INDEX 0

static const Choice s_thinking[] = {
  {"minimal", "Minimal", "Fastest"},
  {"low", "Low", "Fast (default)"},
  {"medium", "Medium", "Balanced"},
  {"high", "High", "Most careful, slow"},
};
#define THINKING_COUNT (sizeof(s_thinking) / sizeof(s_thinking[0]))
#define DEFAULT_THINKING_INDEX 1
#define MAX_CHOICES 6

typedef struct {
  SimpleMenuLayer *menu_layer;
  StatusBarLayer *status_bar;
  SimpleMenuSection section;
  SimpleMenuItem items[MAX_CHOICES];
  bool is_model;  // which list a choice window shows; unused by the top menu
} MenuData;

static void prv_push_choices(bool is_model);

static int prv_index_of(const Choice *choices, int count, const char *value, int fallback_index) {
  for (int i = 0; i < count; i++) {
    if (strcmp(choices[i].value, value) == 0) {
      return i;
    }
  }
  return fallback_index;
}

static const char *prv_model_title(void) {
  const char *model = settings_get_gemini_model();
  int index = prv_index_of(s_models, MODEL_COUNT, model, -1);
  if (index >= 0) {
    return s_models[index].title;
  }
  // A model set on the phone that isn't in this list: show its id.
  return model[0] ? model : s_models[DEFAULT_MODEL_INDEX].title;
}

static const char *prv_thinking_title(void) {
  return s_thinking[prv_index_of(s_thinking, THINKING_COUNT, settings_get_gemini_thinking(), DEFAULT_THINKING_INDEX)].title;
}

static void prv_build_window(Window *window, MenuData *data, int selected) {
  Layer *root_layer = window_get_root_layer(window);
  GRect bounds = layer_get_frame(root_layer);
  data->status_bar = bstatus_bar_layer_create();
  bobby_status_bar_config(data->status_bar);
  layer_add_child(root_layer, status_bar_layer_get_layer(data->status_bar));
  data->menu_layer = bsimple_menu_layer_create(
      GRect(0, STATUS_BAR_LAYER_HEIGHT, bounds.size.w, bounds.size.h - STATUS_BAR_LAYER_HEIGHT),
      window, &data->section, 1, window);
  menu_layer_set_highlight_colors(simple_menu_layer_get_menu_layer(data->menu_layer),
                                  SELECTION_HIGHLIGHT_COLOUR, gcolor_legible_over(SELECTION_HIGHLIGHT_COLOUR));
  simple_menu_layer_set_selected_index(data->menu_layer, selected, false);
  layer_add_child(root_layer, simple_menu_layer_get_layer(data->menu_layer));
}

static void prv_window_unload(Window *window) {
  MenuData *data = window_get_user_data(window);
  simple_menu_layer_destroy(data->menu_layer);
  status_bar_layer_destroy(data->status_bar);
  free(data);
  window_destroy(window);
}

static Window *prv_create(MenuData **out) {
  Window *window = bwindow_create();
  MenuData *data = bmalloc(sizeof(MenuData));
  memset(data, 0, sizeof(MenuData));
  window_set_user_data(window, data);
  *out = data;
  return window;
}

// ---- top menu: Model / Thinking ----

static void prv_open_model(int index, void *context) {
  prv_push_choices(true);
}

static void prv_open_thinking(int index, void *context) {
  prv_push_choices(false);
}

static void prv_top_fill(MenuData *data) {
  data->items[0] = (SimpleMenuItem) {.title = "Model", .subtitle = prv_model_title(), .callback = prv_open_model};
  data->items[1] = (SimpleMenuItem) {.title = "Thinking", .subtitle = prv_thinking_title(), .callback = prv_open_thinking};
  data->section = (SimpleMenuSection) {.title = NULL, .items = data->items, .num_items = 2};
}

static void prv_top_load(Window *window) {
  MenuData *data = window_get_user_data(window);
  prv_top_fill(data);
  prv_build_window(window, data, 0);
}

static void prv_top_appear(Window *window) {
  // Refresh the subtitles after a choice was made.
  MenuData *data = window_get_user_data(window);
  if (data->menu_layer) {
    prv_top_fill(data);
    layer_mark_dirty(simple_menu_layer_get_layer(data->menu_layer));
  }
}

void settings_menu_push(void) {
  MenuData *data;
  Window *window = prv_create(&data);
  window_set_window_handlers(window, (WindowHandlers) {
    .load = prv_top_load,
    .appear = prv_top_appear,
    .unload = prv_window_unload,
  });
  window_stack_push(window, true);
}

// ---- choice list ----

static void prv_chosen(int index, void *context) {
  Window *window = context;
  MenuData *data = window_get_user_data(window);
  if (data->is_model) {
    settings_set_gemini_model(s_models[index].value);
  } else {
    settings_set_gemini_thinking(s_thinking[index].value);
  }
  window_stack_remove(window, true);
}

static void prv_choices_load(Window *window) {
  MenuData *data = window_get_user_data(window);
  const Choice *choices = data->is_model ? s_models : s_thinking;
  int count = data->is_model ? (int)MODEL_COUNT : (int)THINKING_COUNT;
  int current = data->is_model
      ? prv_index_of(s_models, MODEL_COUNT, settings_get_gemini_model(), DEFAULT_MODEL_INDEX)
      : prv_index_of(s_thinking, THINKING_COUNT, settings_get_gemini_thinking(), DEFAULT_THINKING_INDEX);
  for (int i = 0; i < count; i++) {
    data->items[i] = (SimpleMenuItem) {
      .title = choices[i].title,
      .subtitle = i == current ? "Selected" : choices[i].subtitle,
      .callback = prv_chosen,
    };
  }
  data->section = (SimpleMenuSection) {.title = NULL, .items = data->items, .num_items = count};
  prv_build_window(window, data, current);
}

static void prv_push_choices(bool is_model) {
  MenuData *data;
  Window *window = prv_create(&data);
  data->is_model = is_model;
  window_set_window_handlers(window, (WindowHandlers) {
    .load = prv_choices_load,
    .unload = prv_window_unload,
  });
  window_stack_push(window, true);
}
