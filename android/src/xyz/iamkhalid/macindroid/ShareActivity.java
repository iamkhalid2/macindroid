package xyz.iamkhalid.macindroid;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.util.Patterns;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;

/** Share → Macindroid: files land in the Mac's Downloads, a link opens in the Mac's browser, other text is copied. */
public class ShareActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        LinkService service = LinkService.instance;
        if (service == null) {
            LinkService.start(this);
            Toast.makeText(this, "Macindroid is starting. Share again in a moment.", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }
        Intent intent = getIntent();
        List<Uri> uris = new ArrayList<>();
        if (Intent.ACTION_SEND_MULTIPLE.equals(intent.getAction())) {
            ArrayList<Uri> list = intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri.class);
            if (list != null) uris.addAll(list);
        } else {
            Uri uri = intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri.class);
            if (uri != null) uris.add(uri);
        }
        if (!uris.isEmpty()) {
            // Hand the read grant to the service, which outlives this activity, and stream from there.
            String what = uris.size() == 1 ? "file" : uris.size() + " files";
            Toast.makeText(this, LinkService.sendFiles(this, uris) ? "Sending " + what + " to " + Config.macName(this)
                    : "That app didn't let Macindroid read the " + what + ".", Toast.LENGTH_SHORT).show();
        } else {
            String text = intent.getStringExtra(Intent.EXTRA_TEXT);
            if (text != null && !text.trim().isEmpty()) {
                String url = onlyLink(text);
                if (url != null) service.sendLink(url);
                else service.sendText(text, true);
            }
        }
        finish();
    }

    /** The shared text's link when it holds exactly one web link (what browsers and most apps share). */
    static String onlyLink(String text) {
        Matcher m = Patterns.WEB_URL.matcher(text);
        String found = null;
        while (m.find()) {
            String candidate = m.group();
            if (!candidate.startsWith("http://") && !candidate.startsWith("https://")) continue;
            if (found != null) return null;
            found = candidate;
        }
        return found;
    }
}
