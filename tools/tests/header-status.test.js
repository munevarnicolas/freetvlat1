'use strict';

var assert = require('assert');
var header = require('../../web/header-status.js');

var at = new Date(2026, 9, 21, 13, 4, 0).getTime();
var us = header.format(at, 'en-US', 'en-US', function () { return 'fallback'; });
var de = header.format(at, 'de-DE', 'de-DE', function () { return 'fallback'; });
assert.ok(/Wednesday/i.test(us.date));
assert.ok(/October 21/i.test(us.date));
assert.ok(/1:04\s*PM/i.test(us.time));
assert.ok(/Mittwoch/i.test(de.date));
assert.ok(/21\.\s*Oktober/i.test(de.date));
assert.ok(/13:04/.test(de.time));
assert.ok(!/:\d{2}:\d{2}/.test(us.time));

var fallback = header.format(at, 'not_a_valid_locale_x', 'not_a_valid_locale_x', function () { return '1:04 PM'; });
assert.strictEqual(fallback.time, '1:04 PM');

var box = header.layout(420, 1596);
assert.strictEqual(box.right, 1572);
assert.strictEqual(box.width, 448);
assert.ok(box.left > 420);
var longTitle = header.layout(1100, 1596);
assert.strictEqual(longTitle.left, 1148);
assert.ok(longTitle.left >= 1100 + 48);
assert.ok(longTitle.right <= 1596 - 24);

var oldZone = process.env.TZ;
process.env.TZ = 'UTC';
var leapDates = [
  header.format(Date.UTC(2024, 1, 28, 23, 59), 'en-US', 'en-US', function () { return ''; }).date,
  header.format(Date.UTC(2024, 1, 29, 0, 0), 'en-US', 'en-US', function () { return ''; }).date,
  header.format(Date.UTC(2024, 2, 1, 0, 0), 'en-US', 'en-US', function () { return ''; }).date
];
assert.ok(/February 28/.test(leapDates[0]));
assert.ok(/February 29/.test(leapDates[1]));
assert.ok(/March 1/.test(leapDates[2]));

process.env.TZ = 'America/New_York';
var beforeDst = header.format(Date.UTC(2026, 2, 8, 6, 59), 'en-US', 'en-US', function () { return ''; }).time;
var afterDst = header.format(Date.UTC(2026, 2, 8, 7, 0), 'en-US', 'en-US', function () { return ''; }).time;
var rollback = header.format(Date.UTC(2026, 10, 1, 6, 0), 'en-US', 'en-US', function () { return ''; }).time;
assert.ok(/1:59\s*AM/i.test(beforeDst));
assert.ok(/3:00\s*AM/i.test(afterDst));
assert.ok(/1:00\s*AM/i.test(rollback));
if (oldZone === undefined) delete process.env.TZ;
else process.env.TZ = oldZone;
console.log('header-status.test.js passed');
