var RecommendationLayout = (function () {
  'use strict';

  function forSelection(selected) {
    var wide = 1040, narrow = 320, gap = 40, x = 80, out = [];
    selected = Math.max(0, Math.min(2, selected | 0));
    for (var i = 0; i < 3; i++) {
      var width = i === selected ? wide : narrow;
      out.push({left: x, width: width});
      x += width + gap;
    }
    return out;
  }

  return {forSelection: forSelection};
})();

if (typeof module !== 'undefined' && module.exports) module.exports = RecommendationLayout;
