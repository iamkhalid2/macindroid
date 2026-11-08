package xyz.iamkhalid.macindroid;

import android.app.Activity;
import android.content.ClipboardManager;
import android.widget.Toast;

/** Invisible activity used by the tile, the notification button and as a fallback: it gains focus, reads, closes. */
public class ReadClipActivity extends Activity {
    private boolean handled;

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (!hasFocus || handled) return;
        handled = true;
        LinkService service = LinkService.instance;
        if (service == null) {
            LinkService.start(this);
            Toast.makeText(this, "Macindroid was not running; started it. Try again.", Toast.LENGTH_SHORT).show();
        } else {
            boolean fromUser = !getIntent().getBooleanExtra("auto", false);
            service.sendClip(getSystemService(ClipboardManager.class).getPrimaryClip(), fromUser);
        }
        finish();
        overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, 0, 0);
    }
}
