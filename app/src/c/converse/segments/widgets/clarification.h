/*
 * Copyright 2025 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 */

#ifndef CLARIFICATION_WIDGET_H
#define CLARIFICATION_WIDGET_H

#include "../../conversation.h"
#include <pebble.h>

typedef Layer ClarificationWidget;

ClarificationWidget* clarification_widget_create(GRect rect, ConversationEntry* entry);
void clarification_widget_destroy(ClarificationWidget* layer);
void clarification_widget_update(ClarificationWidget* layer);

#endif
