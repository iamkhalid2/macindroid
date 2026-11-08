package xyz.iamkhalid.macindroid;

import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Display;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;

/**
 * Android only lets the focused app read the clipboard. This adds an invisible 1x1 overlay window that takes focus
 * for a moment, reads the clipboard, then removes itself. Falls back to a transparent activity.
 */
final class ClipReader {
    private static boolean busy; // main thread only

    private ClipReader() {}

    static void readWithFocus(LinkService service) {
        if (busy) return;
        if (!Settings.canDrawOverlays(service)) {
            launchActivity(service);
            return;
        }
        busy = true;
        Display display = service.getSystemService(DisplayManager.class).getDisplay(Display.DEFAULT_DISPLAY);
        Context windowContext = service.createWindowContext(display, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null);
        WindowManager wm = windowContext.getSystemService(WindowManager.class);
        Handler handler = new Handler(Looper.getMainLooper());
        Probe probe = new Probe(windowContext, service, wm);
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(1, 1,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        try {
            wm.addView(probe, lp);
        } catch (RuntimeException e) {
            busy = false;
            launchActivity(service);
            return;
        }
        handler.postDelayed(() -> {
            if (!probe.done) {
                probe.remove();
                launchActivity(service);
            }
        }, 1200);
    }

    private static void launchActivity(Context context) {
        Intent intent = new Intent(context, ReadClipActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION);
        try {
            context.startActivity(intent);
        } catch (RuntimeException ignored) {
        }
    }

    private static final class Probe extends View {
        private final LinkService service;
        private final WindowManager wm;
        boolean done;

        Probe(Context context, LinkService service, WindowManager wm) {
            super(context);
            this.service = service;
            this.wm = wm;
        }

        @Override
        public void onWindowFocusChanged(boolean hasWindowFocus) {
            super.onWindowFocusChanged(hasWindowFocus);
            if (!hasWindowFocus || done) return;
            done = true;
            ClipboardManager cm = service.getSystemService(ClipboardManager.class);
            service.sendClip(cm.getPrimaryClip(), false);
            remove();
        }

        void remove() {
            done = true;
            try {
                wm.removeView(this);
            } catch (RuntimeException ignored) {
            }
            busy = false;
        }
    }
}
