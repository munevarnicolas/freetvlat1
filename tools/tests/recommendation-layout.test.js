'use strict';

var assert = require('assert');
var layout = require('../../web/card-layout.js');

for (var selected = 0; selected < 3; selected++) {
  var cards = layout.forSelection(selected);
  assert.strictEqual(cards.length, 3);
  var right = 80;
  for (var i = 0; i < cards.length; i++) {
    var width = i === selected ? 1040 : 320;
    assert.strictEqual(cards[i].left, right);
    assert.strictEqual(cards[i].width, width);
    right += width + 40;
  }
  assert.strictEqual(right - 40, 1840);
}

assert.strictEqual(layout.forSelection(-1)[0].width, 1040);
assert.strictEqual(layout.forSelection(3)[2].width, 1040);
console.log('recommendation-layout.test.js passed');
