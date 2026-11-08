package xyz.iamkhalid.macindroid;

import android.content.Context;
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
 * Opening an app from the background. Android 14+ allows it only while the app has a window on screen; holding
 * "Display over other apps" is no longer enough by itself. So this shows an invisible 1×1 overlay, opens once the
 * overlay is on screen, and removes it again a moment later.
 */
final class Foreground {
    private Foreground() {}

    /** Main thread. */
    static void run(Context context, Runnable open) {
        if (!Settings.canDrawOverlays(context)) {
            open.run();
            return;
        }
        Display display = context.getSystemService(DisplayManager.class).getDisplay(Display.DEFAULT_DISPLAY);
        Context windowContext = context.createWindowContext(display, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null);
        WindowManager wm = windowContext.getSystemService(WindowManager.class);
        View dot = new View(windowContext);
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(1, 1,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        try {
            wm.addView(dot, lp);
        } catch (RuntimeException e) {
            open.run();
            return;
        }
        Handler main = new Handler(Looper.getMainLooper());
        boolean[] opened = {false};
        Runnable go = () -> {
            if (opened[0]) return;
            opened[0] = true;
            try {
                open.run();
            } finally {
                main.postDelayed(() -> {
                    try {
                        wm.removeView(dot);
                    } catch (RuntimeException ignored) {
                    }
                }, 1500);
            }
        };
        dot.getViewTreeObserver().registerFrameCommitCallback(() -> main.postDelayed(go, 50)); // on screen now
        main.postDelayed(go, 500); // the screen may be off, in which case no frame ever comes
    }
}
