package xyz.iamkhalid.macindroid;

import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.StatusBarManager;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.Icon;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.text.format.DateFormat;
import android.text.format.Formatter;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Macindroid's home, in the icon's language: the link to the Mac (the mark glows green while connected), every
 * feature with its own switch (all on until switched off) and anything it still needs, how to reach the app,
 * and recent activity.
 */
public class HomeActivity extends Activity {
    private static final int PICK_FILES = 7;
    static final String LAUNCHER_ALIAS = "xyz.iamkhalid.macindroid.Launcher";

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable refresh = new Runnable() {
        @Override
        public void run() {
            render();
            LinkService s = LinkService.instance;
            if (s != null) s.retryLogAccessIfDeclined(false); // Android only shows its log-access prompt while we're on screen
            handler.postDelayed(this, 1000);
        }
    };

    private Ui ui;
    private ImageView mark;
    private View glow;
    private ObjectAnimator pulse;
    private TextView statusTitle, statusMac, statusDetail, featureCount;
    private LinearLayout accessCard, historyList;
    private final List<FeatureRow> features = new ArrayList<>();
    private final List<TextView> historyTimes = new ArrayList<>();
    private final List<Long> historyStamps = new ArrayList<>();
    private String needsSignature = "";
    private int shownHistory = -1;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (!Config.paired(this)) {
            startActivity(new Intent(this, OnboardingActivity.class));
            finish();
            return;
        }
        restoreLauncherIcon();
        LinkService.start(this);
        ui = new Ui(this);

        LinearLayout column = ui.column();
        column.addView(ui.header("Macindroid", "Linked with " + Config.macName(this)));
        column.addView(hero(), ui.spaced(20));

        LinearLayout featuresHead = ui.row();
        featuresHead.addView(ui.sectionLabel("Features"), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        featureCount = ui.mono("", ui.primary);
        featureCount.setPadding(0, 0, ui.px(6), 0);
        featuresHead.addView(featureCount);
        column.addView(featuresHead, ui.spaced(28));
        column.addView(featuresCard(), ui.spaced(10));

        column.addView(ui.sectionLabel("Reach Macindroid"), ui.spaced(28));
        accessCard = ui.card();
        column.addView(accessCard, ui.spaced(10));

        column.addView(ui.sectionLabel("Recent"), ui.spaced(28));
        LinearLayout historyCard = ui.card();
        historyList = ui.column();
        historyCard.addView(historyList);
        column.addView(historyCard, ui.spaced(10));

        column.addView(footer(), ui.spaced(20));
        setContentView(ui.page(column));
        ui.systemBars();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (ui == null) return;
        needsSignature = ""; // permissions may have changed in Settings
        handler.post(refresh);
    }

    @Override
    protected void onPause() {
        handler.removeCallbacks(refresh);
        super.onPause();
    }

    /**
     * Android can't take an app that asks for permissions out of the app drawer: it puts an icon there that opens
     * Settings › Apps instead. So the real icon always stays, opening this screen. (Older versions hid it.)
     */
    private void restoreLauncherIcon() {
        ComponentName alias = new ComponentName(getPackageName(), LAUNCHER_ALIAS);
        if (getPackageManager().getComponentEnabledSetting(alias) == PackageManager.COMPONENT_ENABLED_STATE_DISABLED) {
            getPackageManager().setComponentEnabledSetting(alias, PackageManager.COMPONENT_ENABLED_STATE_DEFAULT, PackageManager.DONT_KILL_APP);
        }
    }

    // ------------------------------------------------------------------ the link

    private View hero() {
        LinearLayout card = ui.card();
        card.setPadding(ui.px(18), ui.px(18), ui.px(18), ui.px(18));

        LinearLayout top = ui.row();
        FrameLayout markBox = new FrameLayout(this);
        glow = new View(this);
        glow.setBackground(ui.glow(ui.primary));
        markBox.addView(glow, new FrameLayout.LayoutParams(ui.px(96), ui.px(96), Gravity.CENTER));
        mark = new ImageView(this);
        mark.setImageResource(R.drawable.ic_launcher_foreground); // the split face itself: Mac half, seam, Android half
        markBox.addView(mark, new FrameLayout.LayoutParams(ui.px(112), ui.px(112), Gravity.CENTER));
        LinearLayout.LayoutParams markLp = new LinearLayout.LayoutParams(ui.px(96), ui.px(96));
        markLp.setMarginStart(-ui.px(8));
        top.addView(markBox, markLp);
        markBox.setClipChildren(false);

        LinearLayout texts = ui.column();
        texts.setPadding(ui.px(8), 0, 0, 0);
        statusTitle = ui.text("", 26, ui.on, Ui.BOLD);
        texts.addView(statusTitle);
        statusMac = ui.text("", 15, ui.on, null);
        statusMac.setSingleLine(true);
        statusMac.setEllipsize(TextUtils.TruncateAt.END);
        texts.addView(statusMac);
        statusDetail = ui.mono("", ui.onVar);
        statusDetail.setPadding(0, ui.px(6), 0, 0);
        texts.addView(statusDetail);
        top.addView(texts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        card.addView(top);

        LinearLayout actions = ui.row();
        TextView clip = ui.primaryButton("Send clipboard");
        clip.setOnClickListener(v -> {
            LinkService s = LinkService.instance;
            if (s != null) s.sendClip(getSystemService(ClipboardManager.class).getPrimaryClip(), true);
        });
        TextView files = ui.tonalButton("Send files");
        files.setOnClickListener(v -> pickFiles());
        actions.addView(clip, ui.weight(50));
        LinearLayout.LayoutParams filesLp = ui.weight(50);
        filesLp.setMarginStart(ui.px(10));
        actions.addView(files, filesLp);
        card.addView(actions, ui.spaced(16));

        pulse = ObjectAnimator.ofFloat(mark, View.ALPHA, 0.9f, 0.35f);
        pulse.setDuration(900);
        pulse.setRepeatCount(ValueAnimator.INFINITE);
        pulse.setRepeatMode(ValueAnimator.REVERSE);
        return card;
    }

    private void pickFiles() {
        startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE).setType("*/*").putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true), PICK_FILES);
    }

    // ------------------------------------------------------------------ features

    /** One feature: its switch (null for Files, which only runs when you send something) and what it needs. */
    private final class FeatureRow {
        final String key;
        final String[] needs;
        ImageView glyph;
        TextView detail;
        LinearLayout needsBox;
        Ui.Toggle toggle;

        FeatureRow(String key, String... needs) {
            this.key = key;
            this.needs = needs;
        }

        boolean on() {
            if (key == null) return true;
            return key.equals("notifications") ? Config.mirrorNotifications(HomeActivity.this) : Config.on(HomeActivity.this, key);
        }

        void set(boolean on) {
            if (key.equals("notifications")) Config.setMirrorNotifications(HomeActivity.this, on);
            else Config.set(HomeActivity.this, key, on);
        }
    }

    private View featuresCard() {
        LinearLayout card = ui.card();
        card.setPadding(ui.px(16), ui.px(6), ui.px(16), ui.px(6));
        addFeature(card, new FeatureRow(Config.CLIPBOARD, Ui.P_USB), R.drawable.ic_f_clipboard, "Clipboard",
                "Copy on one, paste on the other.");
        addFeature(card, new FeatureRow("notifications", Ui.P_LISTENER), R.drawable.ic_f_bell, "Notifications on your Mac",
                "While you're away from this phone, with Reply.");
        addFeature(card, new FeatureRow(Config.CODES, Ui.P_LISTENER, Ui.P_CODES), R.drawable.ic_f_key, "One-time codes",
                "Sign-in codes land on your Mac's clipboard.");
        addFeature(card, new FeatureRow(Config.PHOTOS, Ui.P_PHOTOS), R.drawable.ic_f_photo, "Photos & screenshots",
                "New ones pop up on your Mac, ready to copy or drag.");
        addFeature(card, new FeatureRow(Config.LINKS, Ui.P_OVERLAY), R.drawable.ic_f_link, "Open links from your Mac",
                "Straight away while you're using the phone. Off: they wait in a notification.");
        addFeature(card, new FeatureRow(Config.RING, Ui.P_NOTIFY), R.drawable.ic_f_ring, "Find my phone",
                "Your Mac can ring this phone, even on silent.");
        addFeature(card, new FeatureRow(Config.MIRROR, Ui.P_USB), R.drawable.ic_f_mirror, "Phone mirroring",
                "Your Mac can show and control this screen over Wi-Fi.");
        addFeature(card, new FeatureRow(null), R.drawable.ic_f_file, "Files",
                "Share to Macindroid, or drop on the menu-bar icon on your Mac.");
        return card;
    }

    private void addFeature(LinearLayout card, FeatureRow f, int glyph, String title, String detail) {
        if (!features.isEmpty()) card.addView(ui.hairline(), new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1));
        features.add(f);
        LinearLayout r = ui.row();
        r.setPadding(0, ui.px(12), 0, ui.px(12));
        r.setGravity(Gravity.TOP);
        f.glyph = ui.glyph(glyph, f.on());
        r.addView(f.glyph);

        LinearLayout texts = ui.column();
        texts.setPadding(ui.px(14), ui.px(1), ui.px(8), 0);
        texts.addView(ui.text(title, 16, ui.on, Ui.MEDIUM));
        f.detail = ui.text(detail, 13, ui.onVar, null);
        f.detail.setPadding(0, ui.px(2), 0, 0);
        texts.addView(f.detail);
        f.needsBox = ui.column();
        texts.addView(f.needsBox);
        r.addView(texts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        if (f.key != null) {
            f.toggle = new Ui.Toggle(ui, f.on());
            f.toggle.setContentDescription(title);
            f.toggle.listener = on -> {
                f.set(on);
                bindFeature(f, permissionMap());
                countFeatures();
            };
            r.addView(f.toggle);
            r.setOnClickListener(v -> f.toggle.performClick());
        } else {
            TextView send = ui.chip("Send", ui.primary);
            send.setOnClickListener(v -> pickFiles());
            r.addView(send, ui.wrap(36));
        }
        card.addView(r);
    }

    private Map<String, Ui.Permission> permissionMap() {
        Map<String, Ui.Permission> map = new HashMap<>();
        for (Ui.Permission p : ui.permissions()) map.put(p.key, p);
        return map;
    }

    /** Lights the glyph when the feature is on and has what it needs; otherwise lists what's missing. */
    private void bindFeature(FeatureRow f, Map<String, Ui.Permission> perms) {
        boolean on = f.on();
        boolean ready = on;
        f.needsBox.removeAllViews();
        if (on) {
            for (String need : f.needs) {
                Ui.Permission p = perms.get(need);
                if (p == null || p.granted) continue;
                if (Config.CLIPBOARD.equals(f.key) && Ui.P_USB.equals(need)) continue; // Mac → phone works without it
                ready = false;
                addNeed(f, p);
            }
            if (Config.CLIPBOARD.equals(f.key)) clipboardNeeds(f, perms);
        }
        f.glyph.setImageTintList(ColorStateList.valueOf(ready ? ui.primary : on ? ui.on : ui.onVar));
        f.glyph.setBackground(ui.outlined(ready ? ui.primaryContainer : ui.raised,
                ready ? (ui.primary & 0x00FFFFFF) | 0x55000000 : ui.line, 13));
    }

    private void addNeed(FeatureRow f, Ui.Permission p) {
        String label = Ui.P_USB.equals(p.key) ? "Set up power features  ›"
                : "Allow " + p.title.toLowerCase().replace("one-time codes", "code access") + "  ›";
        TextView allow = ui.chip(label, ui.warn);
        allow.setOnClickListener(v -> p.fix.run());
        LinearLayout.LayoutParams lp = ui.wrap(32);
        lp.topMargin = ui.px(8);
        f.needsBox.addView(allow, lp);
    }

    /** The clipboard's state is richer than on/off: which directions are automatic depends on the power features. */
    private void clipboardNeeds(FeatureRow f, Map<String, Ui.Permission> perms) {
        boolean overlay = perms.get(Ui.P_OVERLAY).granted;
        if (LinkService.autoSend) {
            f.detail.setText(overlay ? "Copy on one, paste on the other. Automatic both ways."
                    : "Automatic both ways, with a brief flash when this phone sends.");
            if (!overlay) addNeed(f, perms.get(Ui.P_OVERLAY));
        } else if (LinkService.logDeclined) {
            f.detail.setText("Mac → phone is automatic. Phone → Mac is paused.");
            TextView allow = ui.chip("Allow phone → Mac  ›", ui.warn);
            allow.setOnClickListener(v -> {
                LinkService s = LinkService.instance;
                if (s != null) s.retryLogAccessIfDeclined(true);
            });
            LinearLayout.LayoutParams lp = ui.wrap(32);
            lp.topMargin = ui.px(8);
            f.needsBox.addView(allow, lp);
        } else {
            f.detail.setText("Mac → phone is automatic. Phone → Mac with Send clipboard, or by itself with power features.");
            if (!perms.get(Ui.P_USB).granted) addNeed(f, perms.get(Ui.P_USB));
        }
    }

    private void countFeatures() {
        int on = 0, total = 0;
        for (FeatureRow f : features) {
            if (f.key == null) continue;
            total++;
            if (f.on()) on++;
        }
        featureCount.setText((on == total ? "All on" : on + " of " + total + " on").toUpperCase());
    }

    // ------------------------------------------------------------------ reaching the app

    private void renderAccess(Map<String, Ui.Permission> perms) {
        accessCard.removeAllViews();
        accessCard.setPadding(ui.px(16), ui.px(6), ui.px(16), ui.px(6));
        boolean tile = Config.tileAdded(this);
        accessRow(R.drawable.ic_f_tiles, "Quick Settings tile", tile ? "In your Quick Settings. Tap it to open Macindroid."
                : "Open Macindroid from anywhere, without the app drawer.", tile, "Add", this::requestTile);
        Ui.Permission bg = perms.get(Ui.P_BACKGROUND);
        accessRow(R.drawable.ic_f_bolt, "Run in the background", "Keeps the link to your Mac open while the screen is off.",
                bg.granted, "Allow", bg.fix);
        Ui.Permission notify = perms.get(Ui.P_NOTIFY);
        accessRow(R.drawable.ic_f_bell, "Macindroid's notifications", "Transfer progress, links and Find My Phone.",
                notify.granted, "Allow", notify.fix);
    }

    private void accessRow(int glyph, String title, String detail, boolean done, String action, Runnable fix) {
        if (accessCard.getChildCount() > 0) accessCard.addView(ui.hairline(), new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1));
        LinearLayout r = ui.row();
        r.setPadding(0, ui.px(12), 0, ui.px(12));
        r.addView(ui.glyph(glyph, done));
        LinearLayout texts = ui.column();
        texts.setPadding(ui.px(14), 0, ui.px(8), 0);
        texts.addView(ui.text(title, 16, ui.on, Ui.MEDIUM));
        TextView sub = ui.text(detail, 13, ui.onVar, null);
        sub.setPadding(0, ui.px(2), 0, 0);
        texts.addView(sub);
        r.addView(texts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        if (done) {
            r.addView(ui.mono("✓ Done", ui.primary));
        } else {
            TextView b = ui.chip(action, ui.primary);
            b.setOnClickListener(v -> fix.run());
            r.addView(b, ui.wrap(36));
        }
        accessCard.addView(r);
    }

    /** Android's own "Add tile?" prompt, so the tile lands in Quick Settings without editing the panel. */
    private void requestTile() {
        getSystemService(StatusBarManager.class).requestAddTileService(new ComponentName(this, TileService.class), "Macindroid",
                Icon.createWithResource(this, R.drawable.ic_glyph), getMainExecutor(), result -> {
                    if (result == StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED
                            || result == StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED) {
                        Config.setTileAdded(this, true);
                        needsSignature = "";
                    }
                });
    }

    private View footer() {
        LinearLayout r = ui.row();
        r.setGravity(Gravity.CENTER);
        TextView reconnect = ui.button("RECONNECT", Color.TRANSPARENT, ui.primary, 13);
        reconnect.setTypeface(Ui.MONO);
        reconnect.setLetterSpacing(0.12f);
        reconnect.setOnClickListener(v -> {
            LinkService s = LinkService.instance;
            if (s != null) s.reconnect();
        });
        TextView unpair = ui.button("UNPAIR", Color.TRANSPARENT, ui.bad, 13);
        unpair.setTypeface(Ui.MONO);
        unpair.setLetterSpacing(0.12f);
        unpair.setOnClickListener(v -> new AlertDialog.Builder(this)
                .setTitle("Unpair from " + Config.macName(this) + "?")
                .setMessage("You can pair again any time.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Unpair", (d, w) -> {
                    LinkService s = LinkService.instance;
                    if (s != null) s.unpair();
                    else Config.unpair(this);
                    startActivity(new Intent(this, OnboardingActivity.class));
                    finish();
                })
                .show());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ui.px(44));
        r.addView(reconnect, lp);
        r.addView(unpair, lp);
        return r;
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != PICK_FILES || resultCode != RESULT_OK || data == null) return;
        List<Uri> uris = new ArrayList<>();
        if (data.getClipData() != null) for (int i = 0; i < data.getClipData().getItemCount(); i++) uris.add(data.getClipData().getItemAt(i).getUri());
        else if (data.getData() != null) uris.add(data.getData());
        LinkService.sendFiles(this, uris);
    }

    // ------------------------------------------------------------------ live state

    private void render() {
        renderStatus();
        renderNeeds();
        renderHistory();
    }

    private void renderStatus() {
        int state = LinkService.instance == null ? -1 : LinkService.state;
        String mac = Config.macName(this);
        boolean connected = false, waiting = false;
        switch (state) {
            case LinkService.CONNECTED:
                statusTitle.setText("Connected");
                statusMac.setText(mac);
                statusDetail.setText(LinkService.peer == null ? "Same Wi-Fi" : LinkService.peer);
                connected = true;
                break;
            case LinkService.SEARCHING:
                statusTitle.setText("Looking");
                statusMac.setText("for " + mac);
                statusDetail.setText("Both on the same Wi-Fi");
                waiting = true;
                break;
            case LinkService.RETRYING:
                statusTitle.setText("Reconnecting");
                statusMac.setText(mac + " isn't answering yet");
                statusDetail.setText("Retrying");
                waiting = true;
                break;
            case LinkService.KEY_MISMATCH:
                statusTitle.setText("Not matching");
                statusMac.setText("The pairing with " + mac + " changed");
                statusDetail.setText("Unpair below, then pair again");
                break;
            case -1:
                statusTitle.setText("Stopped");
                statusMac.setText(mac);
                statusDetail.setText("Reopen this screen to start");
                break;
            default:
                statusTitle.setText("Starting");
                statusMac.setText(mac);
                statusDetail.setText(" ");
                waiting = true;
        }
        statusDetail.setTextColor(connected ? ui.primary : state == LinkService.KEY_MISMATCH ? ui.bad : waiting ? ui.warn : ui.onVar);
        glow.setVisibility(connected ? View.VISIBLE : View.INVISIBLE);
        if (waiting && !pulse.isStarted()) pulse.start();
        if (!waiting && pulse.isStarted()) pulse.cancel();
        if (!waiting) mark.setAlpha(connected ? 1f : 0.45f);
    }

    private void renderNeeds() {
        Map<String, Ui.Permission> perms = permissionMap();
        StringBuilder signature = new StringBuilder();
        for (Ui.Permission p : perms.values()) signature.append(p.key).append(p.granted ? '1' : '0');
        signature.append(LinkService.autoSend).append(LinkService.logDeclined).append(Config.tileAdded(this));
        if (signature.toString().equals(needsSignature)) return;
        needsSignature = signature.toString();
        for (FeatureRow f : features) bindFeature(f, perms);
        countFeatures();
        renderAccess(perms);
    }

    private void renderHistory() {
        int version = LinkService.historyVersion;
        if (version != shownHistory) {
            shownHistory = version;
            historyList.removeAllViews();
            historyTimes.clear();
            historyStamps.clear();
            List<LinkService.Event> events = LinkService.recent();
            if (events.isEmpty()) {
                TextView empty = ui.text("Nothing yet. Copy something, share a file to Macindroid, or drop one on the menu-bar icon on your Mac.",
                        14, ui.onVar, null);
                empty.setPadding(0, ui.px(4), 0, ui.px(4));
                historyList.addView(empty);
            }
            for (LinkService.Event e : events) historyList.addView(historyRow(e), historyList.getChildCount() == 0 ? ui.full() : ui.spaced(14));
        }
        for (int i = 0; i < historyTimes.size(); i++) historyTimes.get(i).setText(ago(historyStamps.get(i)));
    }

    /** Toward the Mac in the Mac half's off-white; toward the phone in the Android half's green. */
    private View historyRow(LinkService.Event e) {
        LinearLayout r = ui.row();
        int tint = e.toMac ? ui.on : ui.primary;
        TextView arrow = ui.text(e.toMac ? "↑" : "↓", 18, tint, Ui.BOLD);
        arrow.setGravity(Gravity.CENTER);
        arrow.setBackground(ui.outlined(ui.raised, (tint & 0x00FFFFFF) | 0x40000000, 12));
        r.addView(arrow, new LinearLayout.LayoutParams(ui.px(40), ui.px(40)));

        LinearLayout texts = ui.column();
        texts.setPadding(ui.px(14), 0, ui.px(12), 0);
        String title;
        String kind;
        switch (e.type) {
            case LinkService.EV_IMAGE: title = "Image"; kind = "Clipboard"; break;
            case LinkService.EV_FILE: title = e.title; kind = "File"; break;
            case LinkService.EV_LINK: title = e.uri != null && e.uri.getHost() != null ? e.uri.getHost() : e.title; kind = "Link"; break;
            default: title = e.title == null || e.title.isEmpty() ? "Blank text" : e.title; kind = "Clipboard";
        }
        TextView main = ui.text(title, 15, ui.on, e.type == LinkService.EV_TEXT ? null : Ui.MEDIUM);
        main.setSingleLine(true);
        main.setEllipsize(e.type == LinkService.EV_FILE ? TextUtils.TruncateAt.MIDDLE : TextUtils.TruncateAt.END);
        texts.addView(main);
        String size = e.bytes > 0 ? " · " + Formatter.formatShortFileSize(this, e.bytes) : "";
        texts.addView(ui.text(kind + (e.toMac ? " to Mac" : " from Mac") + size, 13, ui.onVar, null));
        r.addView(texts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        TextView time = ui.mono(ago(e.at), ui.onVar);
        r.addView(time);
        historyTimes.add(time);
        historyStamps.add(e.at);

        if (e.uri != null && (e.type == LinkService.EV_LINK || !e.toMac)) {
            r.setOnClickListener(v -> {
                Intent view = new Intent(Intent.ACTION_VIEW);
                if (e.mime != null) view.setDataAndType(e.uri, e.mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                else view.setData(e.uri);
                try {
                    startActivity(view);
                } catch (RuntimeException ignored) {
                }
            });
        }
        return r;
    }

    private String ago(long at) {
        long s = (System.currentTimeMillis() - at) / 1000;
        if (s < 10) return "NOW";
        if (s < 60) return s + "S";
        if (s < 3600) return (s / 60) + "M";
        if (s < 86400) return (s / 3600) + "H";
        return DateFormat.getDateFormat(this).format(at);
    }
}
