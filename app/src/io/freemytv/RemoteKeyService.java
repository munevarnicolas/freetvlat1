package io.freemytv;

import android.accessibilityservice.AccessibilityService;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;

/**
 * Sees the remote's keys before any app does, so the buttons work from inside Netflix, Disney and the rest. The
 * system waits for it on every key (up to half a second), so it runs in a process of its own and does next to nothing.
 */
public class RemoteKeyService extends AccessibilityService {
    @Override protected void onServiceConnected() {
        super.onServiceConnected();
        RemoteKeys.prepare(this);   // what each button opens, looked up before the first press
    }

    @Override protected boolean onKeyEvent(KeyEvent event) {
        return RemoteKeys.onKey(this, event, true);
    }

    /** Which app is on screen (only its package: no window content). */
    @Override public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event.getEventType() == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED && event.getPackageName() != null) {
            RemoteKeys.windowChanged(this, event.getPackageName().toString());
        }
    }

    @Override public void onInterrupt() {}
}
