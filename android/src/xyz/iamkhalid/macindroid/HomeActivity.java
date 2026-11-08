package xyz.iamkhalid.macindroid;

import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
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
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/** Macindroid's home: connection, quick actions, anything left to set up, settings and recent activity. */
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
    private View statusDot;
    private ObjectAnimator pulse;
    private TextView statusTitle, statusDetail, modeChip, setupLabel;
    private LinearLayout setupCard, historyList;
    private final List<TextView> historyTimes = new ArrayList<>();
    private final List<Long> historyStamps = new ArrayList<>();
    private String setupSignature = "";
    private int shownHistory = -1;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (!Config.paired(this)) {
            startActivity(new Intent(this, OnboardingActivity.class));
            finish();
            return;
        }
        LinkService.start(this);
        ui = new Ui(this);

        LinearLayout column = ui.column();
        column.addView(ui.header("Macindroid", "Linked with " + Config.macName(this)));
        column.addView(statusCard(), ui.spaced(20));

        setupLabel = ui.sectionLabel("Finish setup");
        column.addView(setupLabel, ui.spaced(28));
        setupCard = ui.card();
        column.addView(setupCard, ui.spaced(10));

        column.addView(ui.sectionLabel("Settings"), ui.spaced(28));
        column.addView(settingsCard(), ui.spaced(10));

        column.addView(ui.sectionLabel("Recent"), ui.spaced(28));
        LinearLayout historyCard = ui.card();
        historyList = ui.column();
        historyCard.addView(historyList);
        column.addView(historyCard, ui.spaced(10));

        column.addView(footer(), ui.spaced(24));
        setContentView(ui.page(column));
        ui.systemBars();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (ui == null) return;
        setupSignature = ""; // permissions may have changed in Settings
        handler.post(refresh);
    }

    @Override
    protected void onPause() {
        handler.removeCallbacks(refresh);
        super.onPause();
    }

    // ------------------------------------------------------------------ sections

    private View statusCard() {
        LinearLayout card = ui.card();
        card.setPadding(ui.px(22), ui.px(22), ui.px(22), ui.px(22));

        LinearLayout titleRow = ui.row();
        statusDot = new View(this);
        statusDot.setBackground(ui.circle(ui.good));
        LinearLayout.LayoutParams dotLp = new LinearLayout.LayoutParams(ui.px(12), ui.px(12));
        dotLp.setMarginEnd(ui.px(12));
        titleRow.addView(statusDot, dotLp);
        statusTitle = ui.text("", 22, ui.on, Ui.BOLD);
        titleRow.addView(statusTitle);
        card.addView(titleRow);

        statusDetail = ui.text("", 14, ui.onVar, null);
        statusDetail.setPadding(ui.px(24), ui.px(4), 0, 0);
        card.addView(statusDetail);

        modeChip = ui.text("", 13, ui.onPrimaryContainer, Ui.MEDIUM);
        modeChip.setBackground(ui.rounded(ui.primaryContainer, 100));
        modeChip.setPadding(ui.px(12), ui.px(6), ui.px(12), ui.px(6));
        modeChip.setOnClickListener(v -> {
            LinkService s = LinkService.instance;
            if (s != null && LinkService.logDeclined) s.retryLogAccessIfDeclined(true);
        });
        LinearLayout.LayoutParams chipLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        chipLp.topMargin = ui.px(16);
        card.addView(modeChip, chipLp);

        LinearLayout actions = ui.row();
        TextView clip = ui.primaryButton("Send clipboard");
        clip.setOnClickListener(v -> {
            LinkService s = LinkService.instance;
            if (s != null) s.sendClip(getSystemService(ClipboardManager.class).getPrimaryClip(), true);
        });
        TextView files = ui.tonalButton("Send files");
        files.setOnClickListener(v -> startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE).setType("*/*").putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true), PICK_FILES));
        actions.addView(clip, ui.weight(52));
        LinearLayout.LayoutParams filesLp = ui.weight(52);
        filesLp.setMarginStart(ui.px(10));
        actions.addView(files, filesLp);
        LinearLayout.LayoutParams actionsLp = ui.full();
        actionsLp.topMargin = ui.px(20);
        card.addView(actions, actionsLp);

        pulse = ObjectAnimator.ofFloat(statusDot, View.ALPHA, 1f, 0.25f);
        pulse.setDuration(800);
        pulse.setRepeatCount(ValueAnimator.INFINITE);
        pulse.setRepeatMode(ValueAnimator.REVERSE);
        return card;
    }

    private View settingsCard() {
        LinearLayout card = ui.card();
        card.addView(switchRow("Show notifications on your Mac", "Only while you're not using this phone.",
                Config.mirrorNotifications(this), on -> Config.setMirrorNotifications(this, on)));
        ComponentName alias = new ComponentName(getPackageName(), LAUNCHER_ALIAS);
        boolean inDrawer = getPackageManager().getComponentEnabledSetting(alias) != PackageManager.COMPONENT_ENABLED_STATE_DISABLED;
        card.addView(switchRow("Show in app drawer", "Otherwise open Macindroid by long-pressing its Quick Settings tile.",
                inDrawer, on -> getPackageManager().setComponentEnabledSetting(alias,
                        on ? PackageManager.COMPONENT_ENABLED_STATE_ENABLED : PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                        PackageManager.DONT_KILL_APP)), ui.spaced(18));
        return card;
    }

    interface Toggle {
        void set(boolean on);
    }

    private View switchRow(String title, String detail, boolean checked, Toggle toggle) {
        LinearLayout r = ui.row();
        LinearLayout texts = ui.column();
        texts.addView(ui.text(title, 16, ui.on, Ui.MEDIUM));
        TextView sub = ui.text(detail, 13, ui.onVar, null);
        sub.setPadding(0, ui.px(2), 0, 0);
        texts.addView(sub);
        r.addView(texts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        Switch s = new Switch(this);
        s.setChecked(checked);
        s.setOnCheckedChangeListener((b, on) -> toggle.set(on));
        s.setPadding(ui.px(12), 0, 0, 0);
        r.addView(s);
        r.setOnClickListener(v -> s.toggle());
        return r;
    }

    private View footer() {
        LinearLayout r = ui.row();
        r.setGravity(Gravity.CENTER);
        TextView reconnect = ui.button("Reconnect", Color.TRANSPARENT, ui.primary, 15);
        reconnect.setOnClickListener(v -> {
            LinkService s = LinkService.instance;
            if (s != null) s.reconnect();
        });
        TextView unpair = ui.button("Unpair", Color.TRANSPARENT, ui.bad, 15);
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
        renderSetup();
        renderHistory();
    }

    private void renderStatus() {
        int state = LinkService.instance == null ? -1 : LinkService.state;
        String mac = Config.macName(this);
        int color;
        boolean waiting = false;
        switch (state) {
            case LinkService.CONNECTED:
                statusTitle.setText("Connected");
                statusDetail.setText(mac + " · " + LinkService.peer);
                color = ui.good;
                break;
            case LinkService.SEARCHING:
                statusTitle.setText("Looking for " + mac);
                statusDetail.setText("Both need to be on the same Wi-Fi");
                color = ui.warn;
                waiting = true;
                break;
            case LinkService.RETRYING:
                statusTitle.setText("Reconnecting");
                statusDetail.setText(mac + " isn't answering yet");
                color = ui.warn;
                waiting = true;
                break;
            case LinkService.KEY_MISMATCH:
                statusTitle.setText("Pairing doesn't match");
                statusDetail.setText("Unpair below and pair again");
                color = ui.bad;
                break;
            case -1:
                statusTitle.setText("Stopped");
                statusDetail.setText("Reopen this screen to start again");
                color = ui.onVar;
                break;
            default:
                statusTitle.setText("Starting");
                statusDetail.setText(" ");
                color = ui.warn;
                waiting = true;
        }
        ((GradientDrawable) statusDot.getBackground()).setColor(color);
        if (waiting && !pulse.isStarted()) pulse.start();
        if (!waiting && pulse.isStarted()) {
            pulse.cancel();
            statusDot.setAlpha(1f);
        }
        if (LinkService.autoSend && android.provider.Settings.canDrawOverlays(this)) modeChip.setText("Clipboard syncs automatically both ways");
        else if (LinkService.autoSend) modeChip.setText("Clipboard syncs automatically, with a brief flash");
        else if (LinkService.logDeclined) modeChip.setText("Phone → Mac paused · tap to allow");
        else modeChip.setText("Mac → phone automatic · phone → Mac with the tile");
    }

    private void renderSetup() {
        List<Ui.Permission> missing = new ArrayList<>();
        StringBuilder signature = new StringBuilder();
        for (Ui.Permission p : ui.permissions()) {
            signature.append(p.granted ? '1' : '0');
            if (!p.granted) missing.add(p);
        }
        if (signature.toString().equals(setupSignature)) return;
        setupSignature = signature.toString();
        setupCard.removeAllViews();
        for (Ui.Permission p : missing) {
            setupCard.addView(ui.settingRow(p.title, p.detail, false, p.action, p.fix),
                    setupCard.getChildCount() == 0 ? ui.full() : ui.spaced(18));
        }
        int visibility = missing.isEmpty() ? View.GONE : View.VISIBLE;
        setupLabel.setVisibility(visibility);
        setupCard.setVisibility(visibility);
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
            for (LinkService.Event e : events) historyList.addView(historyRow(e), historyList.getChildCount() == 0 ? ui.full() : ui.spaced(16));
        }
        for (int i = 0; i < historyTimes.size(); i++) historyTimes.get(i).setText(ago(historyStamps.get(i)));
    }

    private View historyRow(LinkService.Event e) {
        LinearLayout r = ui.row();
        TextView arrow = ui.text(e.toMac ? "↑" : "↓", 18, ui.onPrimaryContainer, Ui.BOLD);
        arrow.setGravity(Gravity.CENTER);
        arrow.setBackground(ui.circle(ui.primaryContainer));
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
        main.setEllipsize(TextUtils.TruncateAt.END);
        texts.addView(main);
        String size = e.bytes > 0 ? " · " + Formatter.formatShortFileSize(this, e.bytes) : "";
        texts.addView(ui.text(kind + (e.toMac ? " to Mac" : " from Mac") + size, 13, ui.onVar, null));
        r.addView(texts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        TextView time = ui.text(ago(e.at), 13, ui.onVar, null);
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
        if (s < 10) return "now";
        if (s < 60) return s + "s";
        if (s < 3600) return (s / 60) + "m";
        if (s < 86400) return (s / 3600) + "h";
        return DateFormat.getDateFormat(this).format(at);
    }
}
