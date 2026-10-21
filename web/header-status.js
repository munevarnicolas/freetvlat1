var HeaderStatus = (function () {
  'use strict';

  var MAX_WIDTH = 448, BUTTON_GAP = 24, TITLE_GAP = 48;

  function format(at, dateLocale, timeLocale, fallbackTime) {
    var date = new Date(at), dateText, timeText;
    try {
      dateText = date.toLocaleDateString(dateLocale, {weekday: 'long', day: 'numeric', month: 'long'});
    } catch (e) {
      dateText = date.toDateString();
    }
    try {
      timeText = date.toLocaleTimeString(timeLocale, {hour: 'numeric', minute: '2-digit'});
      if (/\d{1,2}[:.]\d{2}[:.]\d{2}/.test(timeText)) timeText = fallbackTime(at);
    } catch (e) {
      timeText = fallbackTime(at);
    }
    return {date: dateText, time: timeText};
  }

  function layout(titleRight, buttonLeft) {
    var right = buttonLeft - BUTTON_GAP, left = Math.max(titleRight + TITLE_GAP, right - MAX_WIDTH);
    return {left: left, right: right, width: right - left};
  }

  return {format: format, layout: layout};
})();

if (typeof module !== 'undefined' && module.exports) module.exports = HeaderStatus;
