/*
 * Copyright 2025 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 */

#include "clarification.h"
#include "../../../util/fonts.h"
#include "../../../util/memory/sdk.h"
#include "../../../util/style.h"

#include <string.h>

typedef struct {
  ConversationEntry *entry;
} ClarificationWidgetData;

#define CLARIFICATION_CONTENT_INSET_X 5
#define CLARIFICATION_TEXT_PADDING_X 6
#define CLARIFICATION_QUESTION_PADDING_TOP 3
#define CLARIFICATION_QUESTION_PADDING_BOTTOM 5
#define CLARIFICATION_OPTION_TEXT_PADDING_X 6
#define CLARIFICATION_SELECTION_INSET_X 2
#define CLARIFICATION_SELECTION_INSET_Y 2
#define CLARIFICATION_MAX_QUESTION_HEIGHT 10000
#define CLARIFICATION_LINE_COLOUR COLOR_FALLBACK(GColorLightGray, GColorBlack)
#define CLARIFICATION_SELECTION_COLOUR COLOR_FALLBACK(GColorYellow, GColorBlack)
#if defined(PBL_PLATFORM_EMERY)
#define CLARIFICATION_OPTION_DISPLAY_CHARS 28
#elif defined(PBL_PLATFORM_GABBRO)
#define CLARIFICATION_OPTION_DISPLAY_CHARS 24
#elif defined(PBL_PLATFORM_BASALT)
#define CLARIFICATION_OPTION_DISPLAY_CHARS 20
#elif defined(PBL_PLATFORM_CHALK)
#define CLARIFICATION_OPTION_DISPLAY_CHARS 18
#else
#define CLARIFICATION_OPTION_DISPLAY_CHARS 18
#endif

static void prv_layer_update(Layer *layer, GContext *ctx);
static int16_t prv_measure_height(GRect rect, ConversationWidgetClarification *widget);
static int16_t prv_row_height(const FontsConfig *fonts);
static void prv_draw_horizontal_line(GContext *ctx, int16_t x, int16_t y, int16_t w);
static void prv_option_display_text(const char *option, char *buffer, size_t buffer_size);

ClarificationWidget* clarification_widget_create(GRect rect, ConversationEntry* entry) {
  ConversationWidgetClarification *widget = &conversation_entry_get_widget(entry)->widget.clarification;
  int16_t layer_height = prv_measure_height(rect, widget);
  Layer *layer = blayer_create_with_data(GRect(rect.origin.x, rect.origin.y, rect.size.w, layer_height), sizeof(ClarificationWidgetData));
  ClarificationWidgetData *data = layer_get_data(layer);
  data->entry = entry;
  layer_set_update_proc(layer, prv_layer_update);
  return layer;
}

void clarification_widget_destroy(ClarificationWidget* layer) {
  layer_destroy(layer);
}

void clarification_widget_update(ClarificationWidget* layer) {
  ConversationWidgetClarification *widget = &conversation_entry_get_widget(((ClarificationWidgetData*)layer_get_data(layer))->entry)->widget.clarification;
  GRect frame = layer_get_frame(layer);
  frame.size.h = prv_measure_height(frame, widget);
  layer_set_frame(layer, frame);
  layer_mark_dirty(layer);
}

static int16_t prv_measure_height(GRect rect, ConversationWidgetClarification *widget) {
  const FontsConfig *fonts = fonts_get_config();
  int16_t width = rect.size.w - (CLARIFICATION_TEXT_PADDING_X * 2);
  GSize question_size = graphics_text_layout_get_content_size(
      widget->question,
      fonts->small_font,
      GRect(0, 0, width, CLARIFICATION_MAX_QUESTION_HEIGHT),
      GTextOverflowModeWordWrap,
      GTextAlignmentLeft);
  return CLARIFICATION_QUESTION_PADDING_TOP +
      question_size.h +
      CLARIFICATION_QUESTION_PADDING_BOTTOM +
      (prv_row_height(fonts) * widget->option_count) +
      1;
}

static void prv_layer_update(Layer *layer, GContext *ctx) {
  ClarificationWidgetData *data = layer_get_data(layer);
  ConversationWidgetClarification *widget = &conversation_entry_get_widget(data->entry)->widget.clarification;
  GRect bounds = layer_get_bounds(layer);
  const FontsConfig *fonts = fonts_get_config();

  graphics_context_set_fill_color(ctx, GColorWhite);
  graphics_fill_rect(ctx, bounds, 0, GCornerNone);

  int16_t content_x = CLARIFICATION_TEXT_PADDING_X;
  int16_t content_width = bounds.size.w - (CLARIFICATION_TEXT_PADDING_X * 2);
  int16_t line_x = CLARIFICATION_CONTENT_INSET_X;
  int16_t line_width = bounds.size.w - (CLARIFICATION_CONTENT_INSET_X * 2);
  int16_t question_y = CLARIFICATION_QUESTION_PADDING_TOP;
  GRect question_rect = GRect(content_x, question_y, content_width, bounds.size.h);
  GSize question_size = graphics_text_layout_get_content_size(
      widget->question,
      fonts->small_font,
      GRect(0, 0, question_rect.size.w, CLARIFICATION_MAX_QUESTION_HEIGHT),
      GTextOverflowModeWordWrap,
      GTextAlignmentLeft);

  int16_t row_height = prv_row_height(fonts);
  int16_t options_top_line_y = question_y + question_size.h + CLARIFICATION_QUESTION_PADDING_BOTTOM;
  int16_t options_left = line_x;
  int16_t options_width = line_width;

  for (int i = 0; i < widget->option_count; ++i) {
    int16_t row_top_line_y = options_top_line_y + (row_height * i);
    GRect row = GRect(options_left, row_top_line_y + 1, options_width, row_height - 1);
    if (i == widget->selected_index && !widget->answered) {
      GRect highlight = GRect(
          row.origin.x + CLARIFICATION_SELECTION_INSET_X,
          row.origin.y + CLARIFICATION_SELECTION_INSET_Y,
          row.size.w - (CLARIFICATION_SELECTION_INSET_X * 2),
          row.size.h - (CLARIFICATION_SELECTION_INSET_Y * 2));
      graphics_context_set_fill_color(ctx, CLARIFICATION_SELECTION_COLOUR);
      graphics_fill_rect(ctx, highlight, 2, GCornersAll);
    }
  }

  graphics_context_set_text_color(ctx, GColorBlack);
  graphics_draw_text(
      ctx,
      widget->question,
      fonts->small_font,
      GRect(question_rect.origin.x, question_rect.origin.y, question_rect.size.w, question_size.h),
      GTextOverflowModeWordWrap,
      GTextAlignmentLeft,
      NULL);

  for (int i = 0; i < widget->option_count; ++i) {
    char display_option[CLARIFICATION_OPTION_DISPLAY_CHARS + 4];
    prv_option_display_text(widget->options[i], display_option, sizeof(display_option));
    int16_t row_top_line_y = options_top_line_y + (row_height * i);
    GRect row = GRect(options_left, row_top_line_y + 1, options_width, row_height - 1);
    GRect text_rect = GRect(
        row.origin.x + CLARIFICATION_OPTION_TEXT_PADDING_X,
        row.origin.y - 1,
        row.size.w - (CLARIFICATION_OPTION_TEXT_PADDING_X * 2),
        row.size.h + 2);
    graphics_context_set_text_color(
        ctx,
        (i == widget->selected_index && !widget->answered) ?
            gcolor_legible_over(CLARIFICATION_SELECTION_COLOUR) :
            GColorBlack);
    graphics_draw_text(
        ctx,
        display_option,
        fonts->small_font,
        text_rect,
        GTextOverflowModeTrailingEllipsis,
        GTextAlignmentLeft,
        NULL);
  }

  graphics_context_set_stroke_color(ctx, CLARIFICATION_LINE_COLOUR);
  prv_draw_horizontal_line(ctx, line_x, 0, line_width);
  for (int i = 0; i <= widget->option_count; ++i) {
    prv_draw_horizontal_line(ctx, line_x, options_top_line_y + (row_height * i), line_width);
  }
}

static int16_t prv_row_height(const FontsConfig *fonts) {
  return fonts->small_font_cap * 2;
}

static void prv_draw_horizontal_line(GContext *ctx, int16_t x, int16_t y, int16_t w) {
  graphics_draw_line(ctx, GPoint(x, y), GPoint(x + w - 1, y));
}

static void prv_option_display_text(const char *option, char *buffer, size_t buffer_size) {
  if (!buffer || buffer_size == 0) {
    return;
  }
  buffer[0] = 0;
  if (!option) {
    return;
  }
  size_t visible_len = 0;
  while (option[visible_len] != 0) {
    if (option[visible_len] == '|' || (
        option[visible_len] == ' ' &&
        option[visible_len + 1] == '|' &&
        option[visible_len + 2] == ' ')) {
      break;
    }
    visible_len++;
  }
  while (visible_len > 0 && option[visible_len - 1] == ' ') {
    visible_len--;
  }

  size_t max_chars = CLARIFICATION_OPTION_DISPLAY_CHARS;
  bool truncated = visible_len > max_chars;
  if (truncated && max_chars > 3) {
    max_chars -= 3;
  }
  size_t copy_len = visible_len < max_chars ? visible_len : max_chars;
  if (copy_len >= buffer_size) {
    copy_len = buffer_size - 1;
  }
  memcpy(buffer, option, copy_len);
  buffer[copy_len] = 0;
  while (copy_len > 0 && buffer[copy_len - 1] == ' ') {
    buffer[--copy_len] = 0;
  }
  if (truncated && copy_len + 4 <= buffer_size) {
    strcat(buffer, "...");
  }
}
