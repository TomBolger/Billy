/**
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

var LOGGING_ENABLED = false;

var actions = require('./actions');
var widgets = require('./widgets');
var messageQueue = require('./lib/message_queue').Queue;
var runtimeRouter = require('./agent/runtime_router');

function Session(prompt, threadId) {
    this.prompt = prompt;
    this.threadId = threadId;
    this.ws = undefined;
    this.hasOpenDialog = false;
}

Session.prototype.run = function() {
    if (LOGGING_ENABLED) {
        messageQueue.startLogging();
    }
    runtimeRouter.run(this);
}

Session.prototype.handleMessage = function(event) {
    var message = event.data;
    console.log(message);
    if (message[0] == 'c') {
        var widgetRegex = /<<!!WIDGET:(.+?)!!>>/;
        var content = message.substring(1);
        var match;
        while (content.length > 0) {
            match = widgetRegex.exec(content);
            if (!match) {
                break;
            }
            var widget = match[1];
            console.log("Widget found: " + widget);
            var start = match.index;
            if (start != 0) {
                this.enqueue({
                    CHAT: content.substring(0, start)
                });
            }
            this.processWidget(widget);
            this.hasOpenDialog = false;
            content = content.substring(match.index + match[0].length);
        }
        if (content.length > 0) {
            this.hasOpenDialog = true;
            this.enqueue({
                CHAT: content
            });
        }
    } else if (message[0] == 'f') {
        if (this.hasOpenDialog) {
            console.log('Received a thought while a dialog is open. Closing the dialog.');
            this.enqueue({
                CHAT_DONE: true
            });
            this.hasOpenDialog = false;
        }
        this.enqueue({
            FUNCTION: message.substring(1)
        });
    } else if (message[0] == 'd') {
        this.hasOpenDialog = false;
        this.enqueue({
            CHAT_DONE: true
        });
        if (LOGGING_ENABLED) {
            console.log(JSON.stringify(messageQueue.getLog()));
            messageQueue.stopLogging();
        }
    } else if (message[0] == 'a') {
        actions.handleAction(this, this.ws, message.substring(1));
    } else if (message[0] == 't') {
        this.enqueue({
            THREAD_ID: message.substring(1)
        });
    } else if (message[0] == 'w') {
        this.enqueue({
            WARNING: message.substring(1)
        });
    }
}

Session.prototype.processWidget = function(widgetData) {
    widgets.handleWidget(this, widgetData);
}

Session.prototype.enqueue = function(message) {
    messageQueue.enqueue(message);
}

Session.prototype.dequeue = function() {
    messageQueue.dequeue();
}

Session.prototype.handleClose = function(event) {
    console.log("Connection closed. Code: " + event.code + ". Reason: \"" + event.reason + "\". Was clean: " + event.wasClean);
    this.enqueue({
        CLOSE_CODE: event.code,
        CLOSE_REASON: event.reason,
        CLOSE_WAS_CLEAN: event.wasClean
    });
}

exports.Session = Session;
exports.userToken = null;
