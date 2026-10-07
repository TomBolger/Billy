'use strict';
const assert = require('assert');
const fs = require('fs');
const vm = require('vm');
const path = require('path');
const PKJS = path.resolve(__dirname, '../../app/src/pkjs');
function evaluate(file, globals, dependencies) {
    const module = {exports: {}};
    const context = Object.assign({module, exports: module.exports, console: {log() {}}, setTimeout, clearTimeout,
        require: name => dependencies[name]}, globals);
    vm.runInNewContext(fs.readFileSync(path.join(PKJS, file), 'utf8'), context, {filename: file});
    return context.module.exports;
}
async function queueTest() {
    const sent = [];
    let failures = 2;
    const queue = evaluate('lib/message_queue.js', {Pebble: {
        sendAppMessage(packet, ok, fail) {
            sent.push(JSON.parse(JSON.stringify(packet)));
            setTimeout(() => failures-- > 0 ? fail() : ok(), 1);
        }
    }}, {}).Queue;
    queue.enqueue({CHAT: 'first', RESPONSE_REQUEST_ID: 17});
    queue.enqueue({CHAT: 'second', RESPONSE_REQUEST_ID: 17});
    await new Promise(resolve => setTimeout(resolve, 400));
    assert.deepStrictEqual(sent.map(p => p.CHAT), ['first', 'first', 'first', 'second']);
    assert.strictEqual(sent[0].TRANSPORT_SEQUENCE, sent[2].TRANSPORT_SEQUENCE);
    assert.notStrictEqual(sent[2].TRANSPORT_SEQUENCE, sent[3].TRANSPORT_SEQUENCE);
    assert.strictEqual(queue.queue.length, 0);
}
function startupTest() {
    const listeners = {};
    const setup = [{type: 'heading', defaultValue: 'Billy'}];
    evaluate('index.js', {Pebble: {
        addEventListener: (event, fn) => { listeners[event] = fn; },
        sendAppMessage() {},
        getTimelineToken: (_, fail) => fail('offline'),
        showSimpleNotificationOnPebble() {}
    }, window: {}}, {
        './location': {update() {}}, './session': {},
        './quota': {fetchQuota: cb => cb({hasSubscription: true})},
        '@rebble/clay': function(config) { assert.strictEqual(config[0].defaultValue, 'Billy 0.5'); }, './config.json': setup, './custom_config': {},
        './config': {getGeminiModel: () => 'gemini-test'}, './reminders': {},
        './lib/feedback': {}, 'package.json': {version: '0.5'}, './agent/runtime_router': {},
        './agent/relay': {}, message_keys: {}
    });
    listeners.ready({});
    assert.strictEqual(typeof listeners.appmessage, 'function', 'AI must start without a Timeline token');
}
function reminderTest() {
    let requests = [], data = {};
    const globals = {
        localStorage: {getItem: k => data[k] || null, setItem: (k, v) => { data[k] = v; }},
        Pebble: {getTimelineToken: ok => ok('test-token')},
        XMLHttpRequest: function() {
            this.open = () => {}; this.setRequestHeader = () => {};
            this.send = () => requests.push(this); this.abort = () => {};
        }
    };
    const timeline = evaluate('actions/timeline.js', globals, {});
    const reminders = evaluate('lib/reminders.js', globals, {'../actions/timeline': timeline});
    const time = new Date(Date.now() + 7200000).toISOString();
    let result;
    reminders.addReminder('Test', time, (error, id) => { result = {error, id}; });
    assert.strictEqual(result, undefined, 'must wait for HTTP confirmation');
    assert.strictEqual(reminders.getAllReminders().length, 0);
    requests[0].status = 403; requests[0].onload();
    assert(result.error);
    assert.strictEqual(reminders.getAllReminders().length, 0);
    reminders.addReminder('Test', time, (error, id) => { result = {error, id}; });
    requests[1].status = 201; requests[1].onload();
    assert.strictEqual(result.error, null);
    assert.strictEqual(reminders.getAllReminders().length, 1);
    const id = result.id;
    reminders.deleteReminder(id, error => { result = {error}; });
    requests[2].status = 500; requests[2].onload();
    assert(result.error);
    assert.strictEqual(reminders.getAllReminders().length, 1, 'failed deletion must retain the reminder');
    reminders.deleteReminder(id, error => { result = {error}; });
    requests[3].status = 204; requests[3].onload();
    assert.strictEqual(reminders.getAllReminders().length, 0);
    let callbacks = 0;
    globals.Pebble.getTimelineToken = (_, fail) => fail('offline');
    reminders.addReminder('Test', time, error => { assert(error); callbacks++; });
    assert.strictEqual(callbacks, 1);
}
function responseTest() {
    const packets = [];
    const Session = evaluate('session.js', {}, {
        './actions': {}, './widgets': {}, './agent/runtime_router': {},
        './lib/message_queue': {Queue: {enqueue: p => packets.push(p)}}
    }).Session;
    const session = new Session('hello', 'thread', 42);
    session.enqueue({CHAT: 'answer'});
    assert.strictEqual(packets[0].RESPONSE_REQUEST_ID, 42);
    session.obsolete = true;
    session.enqueue({CHAT: 'old answer'});
    assert.strictEqual(packets.length, 1);
}
(async () => {
    startupTest(); reminderTest(); responseTest(); await queueTest();
    console.log('4/4 delivery regression tests passed');
})().catch(error => { console.error(error); process.exitCode = 1; });
