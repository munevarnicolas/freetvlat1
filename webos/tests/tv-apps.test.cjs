const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const test = require('node:test');
const vm = require('node:vm');

const source = fs.readFileSync(path.join(__dirname, '../../web/tv.js'), 'utf8');

function tvWith(respond, inventory) {
  const timers = new Map();
  let nextTimer = 0;
  const context = {
    window: { CONFIG: { webosApps: inventory } },
    setTimeout(callback) { const id = ++nextTimer; timers.set(id, callback); return id; },
    clearTimeout(id) { timers.delete(id); }
  };
  context.window.PalmServiceBridge = function () {
    this.call = (uri, payload) => {
      const reply = respond(uri, JSON.parse(payload));
      if (reply !== undefined) this.onservicecallback(JSON.stringify(reply));
    };
    this.cancel = () => {};
  };
  vm.runInNewContext(source, context);
  return {
    tv: context.TV,
    expire() {
      for (const [id, callback] of [...timers]) {
        timers.delete(id);
        callback();
      }
    }
  };
}

const inventory = [
  { id: 'netflix', title: 'Netflix', color: '#E50914' },
  { id: 'amazon', title: 'Prime Video', color: '#1A98FF' }
];

test('installed streaming apps remain available when LG denies enumeration', () => {
  const { tv } = tvWith(uri => uri.endsWith('/listLaunchPoints')
    ? { returnValue: false, errorText: 'Denied method call' }
    : { returnValue: true, exist: true }, inventory);
  let apps;
  tv.apps(value => { apps = value; });
  assert.deepEqual(Array.from(apps, app => app.id), ['netflix', 'amazon']);
  assert.equal(apps[1].title, 'Prime Video');
});

test('unanswered enumeration and checks finish once with the paired inventory', () => {
  const { tv, expire } = tvWith(() => undefined, inventory);
  const replies = [];
  tv.apps(value => replies.push(Array.from(value, app => app.id)));
  expire();
  expire();
  assert.deepEqual(replies, [['netflix', 'amazon']]);
  expire();
  assert.equal(replies.length, 1);
});

test('confirmed removals disappear while unavailable checks preserve other apps', () => {
  const { tv } = tvWith((uri, payload) => {
    if (uri.endsWith('/listLaunchPoints')) return { returnValue: false };
    return payload.appId === 'netflix'
      ? { returnValue: true, exist: false }
      : { returnValue: false, errorText: 'Service unavailable' };
  }, inventory);
  let apps;
  tv.apps(value => { apps = value; });
  assert.deepEqual(Array.from(apps, app => app.id), ['amazon']);
});

test('successful native enumeration takes precedence over the saved inventory', () => {
  const { tv } = tvWith(uri => {
    assert.ok(uri.endsWith('/listLaunchPoints'));
    return { returnValue: true, launchPoints: [
      { id: 'new.stream', title: 'New Stream', icon: 'native.png' },
      { id: 'io.freemytv', title: 'Free My TV' }
    ] };
  }, inventory);
  let apps;
  tv.apps(value => { apps = value; });
  assert.deepEqual(Array.from(apps, app => app.id), ['new.stream']);
  assert.equal(apps[0].icon, 'native.png');
});

test('inventory does not add the launcher, inputs, or duplicate app entries', () => {
  const { tv } = tvWith(uri => uri.endsWith('/listLaunchPoints')
    ? { returnValue: false } : { returnValue: true, exist: true }, [
    ...inventory, inventory[0], null,
    { id: 'io.freemytv' }, { id: 'com.webos.app.hdmi1' }, { id: 'com.webos.app.home' }
  ]);
  let apps;
  tv.apps(value => { apps = value; });
  assert.deepEqual(Array.from(apps, app => app.id), ['netflix', 'amazon']);
});

test('a generic build without an inventory still returns a usable empty list', () => {
  const { tv } = tvWith(() => ({ returnValue: false }));
  let apps;
  tv.apps(value => { apps = value; });
  assert.equal(apps.length, 0);
});

test('launching an inventoried app keeps the public LG launch contract', () => {
  const requests = [];
  const { tv } = tvWith((uri, payload) => {
    requests.push({ uri, payload });
    return { returnValue: true };
  }, inventory);
  let launched;
  tv.launch('amazon', null, value => { launched = value; });
  assert.equal(launched, true);
  assert.deepEqual(requests, [{
    uri: 'luna://com.webos.applicationManager/launch', payload: { id: 'amazon' }
  }]);
});

test('desktop preview and Samsung app listing do not consume LG inventory', () => {
  const browser = { window: { CONFIG: { webosApps: inventory } } };
  vm.runInNewContext(source, browser);
  let apps;
  browser.TV.apps(value => { apps = value; });
  assert.ok(apps.some(app => app.id === 'youtube.leanback.v4'));
  const samsung = { window: { CONFIG: { webosApps: inventory }, tizen: { application: {
    getCurrentApplication() { return { appInfo: { id: 'FreeMyTVio.FreeMyTV' } }; },
    getAppsInfo(done) { done([{ id: 'org.tizen.netflix', name: 'Netflix' }]); }
  } } } };
  vm.runInNewContext(source, samsung);
  samsung.TV.apps(value => { apps = value; });
  assert.deepEqual(Array.from(apps, app => app.id), ['org.tizen.netflix']);
});
