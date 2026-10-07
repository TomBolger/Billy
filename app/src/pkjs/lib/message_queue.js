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

var MAX_BYTES_IN_FLIGHT = 400;

function MessageQueue() {
    this.queue = [];
    this.log = null;
    this.messagesInFlight = 0;
    this.bytesInFlight = 0;
    this.retryTimer = null;
    this.nextSequence = (Date.now() % 1000000000) + 1;
}

function countBytes(message) {
    var bytes = 0;
    for (var key in message) {
        if (message.hasOwnProperty(key)) {
            var value = message[key];
            if (typeof value === 'string') {
                bytes += value.length;
            } else if (typeof value === 'number') {
                bytes += 4; // 4 bytes for numbers
            } else if (typeof value == 'boolean') {
                bytes += 1; // 1 byte for boolean
            } else if (Array.isArray(value)) {
                bytes += value.length; // 1 byte per array element
            } else if (value instanceof Uint8Array) {
                bytes += value.length; // 1 byte per array element
            }
            bytes += 12; // space for some overhead for the key.
        }
    }
    return bytes;
}

MessageQueue.prototype.startLogging = function() {
    this.log = [];
};

MessageQueue.prototype.stopLogging = function() {
    this.log = null;
}

MessageQueue.prototype.getLog = function() {
    return this.log || [];
}

MessageQueue.prototype.enqueue = function(message) {
    var packet = {};
    Object.keys(message).forEach(function(key) { packet[key] = message[key]; });
    packet.TRANSPORT_SEQUENCE = this.nextSequence++;
    if (this.nextSequence >= 2147483647) { this.nextSequence = 1; }
    if (this.log) { this.log.push(packet); }
    this.queue.push({message: packet, attempts: 0});
    this.dequeue();
};

MessageQueue.prototype.dequeue = function() {
    if (this.messagesInFlight || this.retryTimer || !this.queue.length) { return; }
    var item = this.queue[0];
    var self = this;
    this.messagesInFlight = 1;
    this.bytesInFlight = countBytes(item.message);
    item.attempts++;
    Pebble.sendAppMessage(item.message, function() {
        self.messagesInFlight = 0;
        self.bytesInFlight = 0;
        self.queue.shift();
        self.dequeue();
    }, function() {
        self.messagesInFlight = 0;
        self.bytesInFlight = 0;
        if (item.attempts < 4) {
            self.retryTimer = setTimeout(function() {
                self.retryTimer = null;
                self.dequeue();
            }, 100 * item.attempts);
            return;
        }
        self.queue.shift();
        console.log('Watch packet failed after four attempts.');
        var request = item.message.RESPONSE_REQUEST_ID;
        if (request) {
            // Do not complete an answer with a missing fragment or image chunk.
            self.queue = self.queue.filter(function(next) { return next.message.RESPONSE_REQUEST_ID !== request; });
            if (!item.message.WARNING) {
                self.enqueue({RESPONSE_REQUEST_ID: request, WARNING: 'Watch connection interrupted. Please ask again.'});
                self.enqueue({RESPONSE_REQUEST_ID: request, CHAT_DONE: true});
            }
        }
        self.dequeue();
    });
};

exports.Queue = new MessageQueue();
