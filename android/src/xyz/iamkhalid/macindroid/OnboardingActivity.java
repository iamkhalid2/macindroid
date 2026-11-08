package xyz.iamkhalid.macindroid;

import android.app.Activity;
import android.app.StatusBarManager;
import android.content.ComponentName;
import android.content.Intent;
import android.graphics.drawable.Icon;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * First run: welcome → pair with the Mac → permissions → done. Afterwards the launcher icon and the Quick Settings
 * tile both open the home screen.
 */
public class OnboardingActivity extends Activity {
    private static final int WELCOME = 0, PAIR = 1, PERMISSIONS = 2, DONE = 3;

    private Ui ui;
    private FrameLayout host;
    private int step;
    private Discovery discovery;
    private Pairing pairing;
    private final Map<String, Discovery.Mac> macs = new LinkedHashMap<>();
    private LinearLayout macList;
    private TextView pairStatus;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (Config.onboarded(this) && Config.paired(this)) {
            startActivity(new Intent(this, HomeActivity.class));
            finish();
            return;
        }
        ui = new Ui(this);
        host = new FrameLayout(this);
        setContentView(host);
        ui.systemBars();
        show(Config.paired(this) ? PERMISSIONS : WELCOME);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (ui == null) return;
        if (step == PERMISSIONS && walking) {
            host.postDelayed(() -> { if (walking && !isFinishing()) walk(); }, 350); // the next one, after a beat
            show(PERMISSIONS);
        } else if (step == PERMISSIONS) show(PERMISSIONS); // returning from a Settings screen
        else if (step < PERMISSIONS && pairing == null && Config.paired(this)) show(PERMISSIONS);
    }

    /** Back steps through the pages instead of closing the app halfway through setup. */
    @Override
    public void onBackPressed() {
        if (pairing != null) {
            pairing.cancel();
            pairing = null;
            show(PAIR);
        } else if (step == PAIR) {
            show(WELCOME);
        } else if (step == DONE) {
            show(PERMISSIONS);
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onDestroy() {
        stopSearching();
        if (pairing != null) pairing.cancel();
        super.onDestroy();
    }

    private void show(int next) {
        step = next;
        if (next != PAIR) stopSearching();
        LinearLayout column = ui.column();
        switch (next) {
            case WELCOME: welcome(column); break;
            case PAIR: pair(column); break;
            case PERMISSIONS: permissions(column); break;
            default: done(column);
        }
        host.removeAllViews();
        host.addView(ui.page(column));
        host.requestApplyInsets();
    }

    // ------------------------------------------------------------------ pages

    private void welcome(LinearLayout c) {
        c.setGravity(Gravity.CENTER_HORIZONTAL);
        c.addView(ui.appIcon(96));
        TextView title = ui.text("Macindroid", 32, ui.on, Ui.BOLD);
        title.setGravity(Gravity.CENTER);
        title.setPadding(0, ui.px(20), 0, 0);
        c.addView(title);
        TextView sub = ui.text("Your phone and your Mac, working as one.", 16, ui.onVar, null);
        sub.setGravity(Gravity.CENTER);
        sub.setPadding(0, ui.px(6), 0, 0);
        c.addView(sub);

        LinearLayout card = ui.card();
        feature(card, R.drawable.ic_f_clipboard, "Universal clipboard", "Copy on one, paste on the other.");
        feature(card, R.drawable.ic_f_bell, "Notifications on your Mac", "Read and reply without picking up your phone.");
        feature(card, R.drawable.ic_f_key, "One-time codes", "Sign-in codes land on your Mac's clipboard.");
        feature(card, R.drawable.ic_f_photo, "Photos and files", "New shots pop up on your Mac; share anything across.");
        feature(card, R.drawable.ic_f_mirror, "Mirror and find", "Use your phone from the Mac, or make it ring.");
        c.addView(card, ui.spaced(32));
        c.addView(new View(this), new LinearLayout.LayoutParams(1, 0, 1)); // push the button down
        TextView go = ui.primaryButton("Get started");
        go.setOnClickListener(v -> show(PAIR));
        c.addView(go, ui.height(54, 28));
    }

    private void feature(LinearLayout card, int glyph, String title, String detail) {
        LinearLayout r = ui.row();
        r.addView(ui.glyph(glyph, true));
        LinearLayout texts = ui.column();
        texts.setPadding(ui.px(14), 0, 0, 0);
        texts.addView(ui.text(title, 16, ui.on, Ui.MEDIUM));
        texts.addView(ui.text(detail, 13, ui.onVar, null));
        r.addView(texts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        card.addView(r, card.getChildCount() == 0 ? ui.full() : ui.spaced(16));
    }

    private void pair(LinearLayout c) {
        c.addView(ui.text("Pair with your Mac", 28, ui.on, Ui.BOLD));
        TextView how = ui.text("Your Mac shows a Set Up Your Phone window. (If it doesn't, click the Macindroid icon in its menu bar "
                + "and choose Pair a Phone….) Then pick your Mac here.", 15, ui.onVar, null);
        how.setPadding(0, ui.px(8), 0, 0);
        c.addView(how);

        LinearLayout card = ui.card();
        macList = ui.column();
        card.addView(macList);
        LinearLayout searching = ui.row();
        ProgressBar spinner = new ProgressBar(this);
        searching.addView(spinner, new LinearLayout.LayoutParams(ui.px(20), ui.px(20)));
        pairStatus = ui.text("Looking for Macs on this Wi-Fi…", 14, ui.onVar, null);
        pairStatus.setPadding(ui.px(12), 0, 0, 0);
        searching.addView(pairStatus);
        card.addView(searching);
        c.addView(card, ui.spaced(24));

        for (Discovery.Mac mac : macs.values()) addMac(mac);
        startSearching();
    }

    private void startSearching() {
        if (discovery != null) return;
        discovery = new Discovery(this);
        discovery.start(mac -> runOnUiThread(() -> {
            if (step != PAIR || macs.containsKey(mac.name)) return;
            macs.put(mac.name, mac);
            addMac(mac);
        }));
    }

    private void stopSearching() {
        if (discovery != null) discovery.stop();
        discovery = null;
    }

    private void addMac(Discovery.Mac mac) {
        LinearLayout r = ui.row();
        r.setClickable(true);
        r.setBackground(ui.rounded(ui.primaryContainer, 18));
        r.setPadding(ui.px(16), ui.px(14), ui.px(16), ui.px(14));
        TextView name = ui.text(mac.name, 16, ui.onPrimaryContainer, Ui.MEDIUM);
        r.addView(name, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        r.addView(ui.text("Pair", 15, ui.onPrimaryContainer, Ui.BOLD));
        r.setOnClickListener(v -> startPairing(mac));
        if (macList.getChildCount() == 0) macList.setPadding(0, 0, 0, ui.px(16));
        macList.addView(r, macList.getChildCount() == 0 ? ui.full() : ui.spaced(10));
    }

    private void startPairing(Discovery.Mac mac) {
        stopSearching();
        LinearLayout c = ui.column();
        c.addView(ui.text("Pairing with " + mac.name, 26, ui.on, Ui.BOLD));
        LinearLayout card = ui.card();
        card.setGravity(Gravity.CENTER_HORIZONTAL);
        card.setPadding(ui.px(20), ui.px(28), ui.px(20), ui.px(28));
        TextView code = ui.text("······", 44, ui.on, Ui.BOLD);
        code.setLetterSpacing(0.08f);
        code.setGravity(Gravity.CENTER);
        card.addView(code);
        TextView hint = ui.text("Connecting…", 15, ui.onVar, null);
        hint.setGravity(Gravity.CENTER);
        hint.setPadding(0, ui.px(12), 0, 0);
        card.addView(hint);
        c.addView(card, ui.spaced(24));
        c.addView(new View(this), new LinearLayout.LayoutParams(1, 0, 1));
        TextView cancel = ui.tonalButton("Cancel");
        cancel.setOnClickListener(v -> {
            if (pairing != null) pairing.cancel();
            pairing = null;
            show(PAIR);
        });
        c.addView(cancel, ui.height(52, 24));
        host.removeAllViews();
        host.addView(ui.page(c));
        host.requestApplyInsets();

        pairing = Pairing.start(this, mac.address, new Pairing.Listener() {
            @Override
            public void onCode(String digits, String macName) {
                code.setText(digits);
                hint.setText("Check that " + macName + " shows the same code, then click Pair on the Mac.");
            }

            @Override
            public void onFinished(boolean paired, String message) {
                pairing = null;
                if (isFinishing()) return;
                if (paired) {
                    LinkService.start(OnboardingActivity.this);
                    LinkService s = LinkService.instance;
                    if (s != null) s.reconnect();
                    show(PERMISSIONS);
                } else {
                    show(PAIR);
                    pairStatus.setText(message);
                }
            }
        });
    }

    private void permissions(LinearLayout c) {
        c.addView(ui.text("Allow Macindroid", 28, ui.on, Ui.BOLD));
        TextView why = ui.text("Each of these turns on part of the experience. Nothing leaves your Wi-Fi, and everything is encrypted.",
                15, ui.onVar, null);
        why.setPadding(0, ui.px(8), 0, 0);
        c.addView(why);
        LinearLayout card = ui.card();
        for (Ui.Permission p : ui.permissions()) {
            card.addView(ui.settingRow(p.title, p.detail, p.granted, p.action, p.fix),
                    card.getChildCount() == 0 ? ui.full() : ui.spaced(18));
        }
        c.addView(card, ui.spaced(24));
        c.addView(new View(this), new LinearLayout.LayoutParams(1, 0, 1));
        boolean missing = !walkable().isEmpty();
        TextView next = ui.primaryButton(missing ? "Allow all" : "Continue");
        next.setOnClickListener(v -> {
            if (missing) {
                walking = true;
                tried.clear();
                walk();
            } else {
                show(DONE);
            }
        });
        c.addView(next, ui.height(54, 24));
        if (missing) {
            TextView later = ui.button("Not now", android.graphics.Color.TRANSPARENT, ui.onVar, 15);
            later.setOnClickListener(v -> show(DONE));
            c.addView(later, ui.height(48, 6));
        }
    }

    // "Allow all": opens each of Android's screens in turn and comes back after each one. Code access goes last
    // because it needs the Mac nearby; power features are set up from the Mac, so they aren't in the walk.
    private boolean walking;
    private final java.util.Set<String> tried = new java.util.HashSet<>();

    private java.util.List<Ui.Permission> walkable() {
        java.util.List<Ui.Permission> list = new java.util.ArrayList<>();
        Ui.Permission codes = null;
        for (Ui.Permission p : ui.permissions()) {
            if (p.granted || p.fix == null || Ui.P_USB.equals(p.key) || tried.contains(p.key)) continue;
            if (Ui.P_CODES.equals(p.key)) codes = p;
            else list.add(p);
        }
        if (codes != null) list.add(codes);
        return list;
    }

    private void walk() {
        java.util.List<Ui.Permission> next = walkable();
        if (next.isEmpty()) {
            walking = false;
            show(PERMISSIONS);
            return;
        }
        Ui.Permission p = next.get(0);
        tried.add(p.key);
        p.fix.run();
    }

    private void done(LinearLayout c) {
        c.setGravity(Gravity.CENTER_HORIZONTAL);
        c.addView(ui.appIcon(80));
        TextView title = ui.text("You're all set", 30, ui.on, Ui.BOLD);
        title.setPadding(0, ui.px(20), 0, 0);
        c.addView(title);
        TextView body = ui.text("Everything is on. Open Macindroid from your app drawer, or add its Quick Settings tile to reach it "
                + "from anywhere. Switch features off on the home screen.", 15, ui.onVar, null);
        body.setGravity(Gravity.CENTER);
        body.setPadding(0, ui.px(10), 0, 0);
        c.addView(body);
        c.addView(new View(this), new LinearLayout.LayoutParams(1, 0, 1));
        TextView tile = ui.tonalButton("Add the Quick Settings tile");
        tile.setOnClickListener(v -> getSystemService(StatusBarManager.class).requestAddTileService(
                new ComponentName(this, TileService.class), "Macindroid", Icon.createWithResource(this, R.drawable.ic_glyph),
                getMainExecutor(), result -> {
                    if (result == StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED
                            || result == StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED) {
                        Config.setTileAdded(this, true);
                        tile.setText("Tile added ✓");
                    }
                }));
        c.addView(tile, ui.height(52, 24));
        TextView finish = ui.primaryButton("Finish");
        finish.setOnClickListener(v -> {
            Config.setOnboarded(this);
            startActivity(new Intent(this, HomeActivity.class));
            finish();
        });
        c.addView(finish, ui.height(54, 12));
    }
}
