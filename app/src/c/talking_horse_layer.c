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

#include "talking_horse_layer.h"
#include "util/perimeter.h"
#include <pebble.h>

#include "util/memory/sdk.h"

typedef struct {
  GPerimeter perimeter;
  Layer *layer;
  const char* text;
  GBitmap *mascot;
  GSize text_size;
  GFont font;
  bool font_is_custom;
  GTextAttributes *text_attributes;
} TalkingHorseLayerData;

const int SPEECH_BUBBLE_BASELINE = 59;
static const int16_t SPEECH_BUBBLE_MARGIN_X = 8;
static const int16_t SPEECH_BUBBLE_TOP = 1;
static const int16_t SPEECH_BUBBLE_CORNER_OFFSET = 6;
static const int16_t SPEECH_BUBBLE_MIN_WIDTH = 52;
static const int16_t SPEECH_BUBBLE_TEXT_EXTRA_HEIGHT = 5;
static const int16_t SPEECH_BUBBLE_BOTTOM_MARGIN = 5;
#if PBL_DISPLAY_WIDTH >= 200
static const int16_t SPEECH_BUBBLE_WIDE_FONT_MIN_WIDTH = 188;
static const int16_t SPEECH_BUBBLE_WIDE_FONT_MIN_HEIGHT = 170;
#endif

static void prv_update_layer(Layer *layer, GContext *ctx);
static GTextAttributes *prv_create_text_attributes(TalkingHorseLayer *layer);
static GRangeHorizontal prv_perimeter_callback(const GPerimeter *perimeter, const GSize *ctx_size, GRangeVertical vertical_range, uint16_t inset);
static GFont prv_load_font_for_bounds(GRect bounds, bool *is_custom);
static int16_t prv_clamp_int16(int16_t value, int16_t min, int16_t max);
static int16_t prv_get_max_text_width(GRect bounds);
static int16_t prv_get_max_text_height(GRect bounds);
static int16_t prv_get_tail_anchor_x(GSize mascot_bounds);
static int16_t prv_get_tail_backset(GSize mascot_bounds);


TalkingHorseLayer *talking_horse_layer_create(GRect frame) {
  Layer *layer = blayer_create_with_data(frame, sizeof(TalkingHorseLayerData));
  TalkingHorseLayerData *data = layer_get_data(layer);
  data->perimeter = (GPerimeter) { .callback = prv_perimeter_callback };
  data->layer = layer;
  data->text = NULL;
  data->text_size = GSizeZero;
  data->font = prv_load_font_for_bounds(layer_get_bounds(layer), &data->font_is_custom);
  data->mascot = bgbitmap_create_with_resource(RESOURCE_ID_ROOT_SCREEN_PONY);
  data->text_attributes = prv_create_text_attributes(layer);
  layer_set_update_proc(layer, prv_update_layer);
  return layer;
}

void talking_horse_layer_destroy(TalkingHorseLayer *layer) {
  TalkingHorseLayerData *data = layer_get_data(layer);
  gbitmap_destroy(data->mascot);
  graphics_text_attributes_destroy(data->text_attributes);
  if (data->font_is_custom) {
    fonts_unload_custom_font(data->font);
  }
  layer_destroy(layer);
}

void talking_horse_layer_set_text(TalkingHorseLayer *layer, const char *text) {
  TalkingHorseLayerData *data = layer_get_data(layer);
  data->text = text;
  GRect bounds = layer_get_bounds(layer);
  const int16_t max_text_width = prv_get_max_text_width(bounds);
  const int16_t max_text_height = prv_get_max_text_height(bounds);
  data->text_size = graphics_text_layout_get_content_size_with_attributes(text, data->font, GRect(0, SPEECH_BUBBLE_TOP, max_text_width, max_text_height), GTextOverflowModeWordWrap, GTextAlignmentLeft, data->text_attributes);
  data->text_size.w = prv_clamp_int16(data->text_size.w, 1, max_text_width);
  data->text_size.h = prv_clamp_int16(data->text_size.h, 1, max_text_height);
  layer_mark_dirty(layer);
}

static void prv_update_layer(Layer *layer, GContext *ctx) {
  TalkingHorseLayerData *data = layer_get_data(layer);
  GRect bounds = layer_get_bounds(layer);
  GSize size = bounds.size;
  GSize mascot_bounds = gbitmap_get_bounds(data->mascot).size;

  const int16_t text_height = data->text_size.h + SPEECH_BUBBLE_TEXT_EXTRA_HEIGHT;
  const int16_t max_bubble_width = size.w - SPEECH_BUBBLE_MARGIN_X * 2;
  const int16_t min_bubble_width = max_bubble_width < SPEECH_BUBBLE_MIN_WIDTH ? max_bubble_width : SPEECH_BUBBLE_MIN_WIDTH;
  const int16_t bubble_width = prv_clamp_int16(data->text_size.w + SPEECH_BUBBLE_CORNER_OFFSET * 2, min_bubble_width, max_bubble_width);
  const int16_t bubble_left = size.w - SPEECH_BUBBLE_MARGIN_X - bubble_width;
  const int16_t tail_anchor_x = prv_get_tail_anchor_x(mascot_bounds);
  const int16_t tail_backset = prv_get_tail_backset(mascot_bounds);

  GPoint tail_origin = GPoint(tail_anchor_x - bubble_left, size.h - 30 - SPEECH_BUBBLE_TOP);
  // When the text is three lines long, the tail runs into the bubble, so we need to move it.
  if (tail_origin.y < text_height + SPEECH_BUBBLE_CORNER_OFFSET) {
    tail_origin = GPoint(tail_anchor_x - bubble_left, size.h - 20 - SPEECH_BUBBLE_TOP);
  }
  tail_origin.x = prv_clamp_int16(tail_origin.x, SPEECH_BUBBLE_MARGIN_X - bubble_left, size.w - SPEECH_BUBBLE_MARGIN_X - bubble_left);
  tail_origin.y = prv_clamp_int16(tail_origin.y, text_height + SPEECH_BUBBLE_CORNER_OFFSET, size.h - SPEECH_BUBBLE_TOP - SPEECH_BUBBLE_BOTTOM_MARGIN);

  GPath bubble_path = {
    .num_points = 11,
    .offset = GPoint(bubble_left, SPEECH_BUBBLE_TOP),
    .rotation = 0,
    .points = (GPoint[]) {
      // top left
      {0, SPEECH_BUBBLE_CORNER_OFFSET},
      {SPEECH_BUBBLE_CORNER_OFFSET, 0},
      // top right
      {bubble_width - SPEECH_BUBBLE_CORNER_OFFSET, 0},
      {bubble_width, SPEECH_BUBBLE_CORNER_OFFSET},
      // bottom right
      {bubble_width, text_height},
      {bubble_width - SPEECH_BUBBLE_CORNER_OFFSET, text_height + SPEECH_BUBBLE_CORNER_OFFSET},
      // tail
      {bubble_width - 20, text_height + SPEECH_BUBBLE_CORNER_OFFSET},
      tail_origin,
      {bubble_width - tail_backset, text_height + SPEECH_BUBBLE_CORNER_OFFSET},
      // bottom left
      {SPEECH_BUBBLE_CORNER_OFFSET, text_height + SPEECH_BUBBLE_CORNER_OFFSET},
      {0, text_height},
    }
  };
  graphics_context_set_fill_color(ctx, GColorWhite);
  gpath_draw_filled(ctx, &bubble_path);
  graphics_context_set_stroke_color(ctx, GColorBlack);
  graphics_context_set_stroke_width(ctx, 3);
  gpath_draw_outline(ctx, &bubble_path);

  graphics_context_set_text_color(ctx, GColorBlack);
  GRect text_bounds = GRect(bubble_left + SPEECH_BUBBLE_CORNER_OFFSET, SPEECH_BUBBLE_TOP + SPEECH_BUBBLE_CORNER_OFFSET - 5, bubble_width - SPEECH_BUBBLE_CORNER_OFFSET * 2, data->text_size.h);
  graphics_draw_text(ctx, data->text, data->font, text_bounds, GTextOverflowModeWordWrap, GTextAlignmentLeft, data->text_attributes);
  graphics_context_set_compositing_mode(ctx, GCompOpSet);
  graphics_draw_bitmap_in_rect(ctx, data->mascot, GRect(0, size.h - mascot_bounds.h, mascot_bounds.w, mascot_bounds.h));
}

static GFont prv_load_font_for_bounds(GRect bounds, bool *is_custom) {
  *is_custom = false;
#if PBL_DISPLAY_WIDTH >= 200
  if (bounds.size.w >= SPEECH_BUBBLE_WIDE_FONT_MIN_WIDTH && bounds.size.h >= SPEECH_BUBBLE_WIDE_FONT_MIN_HEIGHT) {
    *is_custom = true;
    return fonts_load_custom_font(resource_get_handle(RESOURCE_ID_FONT_GOTHIC_36_BOLD));
  }
  return fonts_get_system_font(FONT_KEY_GOTHIC_28_BOLD);
#else
  return fonts_get_system_font(FONT_KEY_GOTHIC_24_BOLD);
#endif
}

static int16_t prv_clamp_int16(int16_t value, int16_t min, int16_t max) {
  if (value < min) {
    return min;
  }
  if (value > max) {
    return max;
  }
  return value;
}

static int16_t prv_get_max_text_width(GRect bounds) {
  const int16_t max_bubble_width = bounds.size.w - SPEECH_BUBBLE_MARGIN_X * 2;
  const int16_t max_text_width = max_bubble_width - SPEECH_BUBBLE_CORNER_OFFSET * 2;
  return max_text_width > 1 ? max_text_width : 1;
}

static int16_t prv_get_max_text_height(GRect bounds) {
  const int16_t max_text_height = bounds.size.h - SPEECH_BUBBLE_TOP - SPEECH_BUBBLE_TEXT_EXTRA_HEIGHT - SPEECH_BUBBLE_CORNER_OFFSET - SPEECH_BUBBLE_BOTTOM_MARGIN;
  return max_text_height > 1 ? max_text_height : 1;
}

static int16_t prv_get_tail_anchor_x(GSize mascot_bounds) {
  return mascot_bounds.w + (mascot_bounds.w >= 80 ? 9 : 6);
}

static int16_t prv_get_tail_backset(GSize mascot_bounds) {
  return mascot_bounds.w >= 80 ? 40 : 30;
}

static GTextAttributes* prv_create_text_attributes(TalkingHorseLayer *layer) {
  TalkingHorseLayerData *data = layer_get_data(layer);
  GTextAttributes *attributes = graphics_text_attributes_create();
  attributes->flow_data.perimeter.impl = &data->perimeter;
  attributes->flow_data.perimeter.inset = 0;
  return attributes;
}


static GRangeHorizontal prv_perimeter_callback(const GPerimeter *perimeter, const GSize *ctx_size, GRangeVertical vertical_range, uint16_t inset) {
  // We don't get a reference to the original layer, but we do get this perimeter pointer. By putting the perimeter at
  // the top of the struct, we can make this cast and get away with it.
  TalkingHorseLayerData *data = (TalkingHorseLayerData*)perimeter;
  Layer *layer = data->layer;
  // The top right of the mascot is this far from the bottom of the layer, and we need it in screen space.
  GSize mascot_bounds = gbitmap_get_bounds(data->mascot).size;
  const int16_t mascot_size = mascot_bounds.h;
  GRect bounds = layer_get_bounds(layer);
  GPoint wrap_point = layer_convert_point_to_screen(layer, GPoint(mascot_size, bounds.size.h - mascot_size));
  // We know the mascot is at the bottom of our layer, so we don't bother worrying about text being rendered past it.
  if (vertical_range.origin_y + vertical_range.size_h < wrap_point.y) {
    // nothing to do here - implement the inset while we're here, though.
    return (GRangeHorizontal) { .origin_x = inset, .size_w = ctx_size->w - inset * 2 };
  } else {
    // The pony is in the way, so we need to indent the text on the left.
    return (GRangeHorizontal) { .origin_x = wrap_point.x + inset, .size_w = ctx_size->w - wrap_point.x - inset * 2 };
  }
}
