package io.freemytv;

import android.content.Context;

/**
 * A widget: what the home screen shows at the right end of the tab row (or under the clock), as the drawer's second
 * card and on the screensaver. One is chosen in Home Settings (`Widgets.chosen()`); only that one loads, and only while
 * the home screen or the screensaver is visible. HomeData runs it: `load()` on its background thread, again every
 * `refreshEvery()` and whenever the widget reports a change.
 *
 * A widget gives a small, immutable `Model` and the screens draw it, measuring its text once per model. Adding one:
 * a subclass, an entry in `Widgets.ALL`, its strings (pt/en here, es/de/ru in Spanish/German/Russian.java) and a line
 * in the README (what it reads, which hosts). See CLAUDE.md.
 */
abstract class Widget {
    /** Colour of the status dot or of a row's value: the screens pick the shade for day and night. */
    static final int PLAIN = 0, GOOD = 1, IDLE = 2, BAD = 3;

    /** One line of the drawer card: a label on the left, a value on the right (with a dot in `tone`, if not PLAIN). */
    static final class Row {
        final String label, value;
        final int tone;

        Row(String label, String value, int tone) {
            this.label = label;
            this.value = value;
            this.tone = tone;
        }
    }

    /** What a widget shows. Replaced as a whole (never changed), so the screens cache their measurements per model. */
    static final class Model {
        private static int count;

        /** Different for every model: the screens' caches compare it. */
        final int serial;
        /** One short line for the tab row and the screensaver, after a dot in `dot` (none with PLAIN). */
        final String line;
        final int dot;
        /** The drawer card: its title, a small note at the right of the title ("" for none) and up to 4 rows. */
        final String title, note;
        final Row[] rows;

        Model(String line, int dot, String title, String note, Row... rows) {
            synchronized (Model.class) {
                serial = ++count;
            }
            this.line = line;
            this.dot = dot;
            this.title = title;
            this.note = note;
            this.rows = rows;
        }

        /** The card as TalkBack reads it: the title, the note, then each row. */
        String spoken() {
            StringBuilder s = new StringBuilder(title);
            if (!note.isEmpty()) s.append(", ").append(note);
            s.append('.');
            for (Row r : rows) s.append(' ').append(r.label).append(' ').append(r.value).append('.');
            return s.toString();
        }
    }

    static final Model EMPTY = new Model("", PLAIN, "", "");

    private volatile Model model = EMPTY;

    /** Saved in the settings: never change it once released. */
    abstract String id();

    /** Its name in Home Settings. */
    abstract String name();

    /** What it shows and what it reads, in a sentence (with the hosts it talks to, if any). */
    abstract String description();

    /** The hosts it talks to (none: nothing leaves the TV). */
    String[] hosts() { return new String[0]; }

    /** Whether it can be chosen on this TV. */
    boolean available(Context c) { return true; }

    /** Whether it has anything to show now (the crypto widget with every coin off doesn't). */
    boolean visible() { return true; }

    /** Builds the model. Runs on HomeData's background thread; null keeps the one there is. */
    abstract Model load(Context c);

    /** How often to load again while it's on screen (ms); 0 = only when it reports a change. */
    long refreshEvery() { return 0; }

    /** Starts following changes while it's on screen (call `changed` on any thread to load again). */
    void watch(Context c, Runnable changed) {}

    /** Stops following changes: no screen is showing it, or another widget was chosen. */
    void unwatch() {}

    /** Its own Home Settings rows, shown after "Widget" while it's the chosen one. */
    String[] settingLabels() { return new String[0]; }

    String settingValue(int i) { return ""; }

    /** OK or an arrow on its row `i` (dir 1 or -1). */
    void adjust(int i, int dir) {}

    final Model model() { return model; }

    final void setModel(Model m) { model = m; }
}
