package xyz.iamkhalid.macindroid;

import android.Manifest;
import android.app.Activity;
import android.app.NotificationManager;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Insets;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.net.Uri;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/** Small view kit shared by the onboarding and home screens: Material You colours, cards, pill buttons. */
final class Ui {
    static final Typeface MEDIUM = Typeface.create(Typeface.DEFAULT, 500, false);
    static final Typeface BOLD = Typeface.create(Typeface.DEFAULT, 600, false);

    final Activity a;
    final float dp;
    final int card, on, onVar, primary, onPrimary, primaryContainer, onPrimaryContainer, good, warn, bad;

    Ui(Activity a) {
        this.a = a;
        dp = a.getResources().getDisplayMetrics().density;
        card = a.getColor(R.color.card);
        on = a.getColor(R.color.on);
        onVar = a.getColor(R.color.onVar);
        primary = a.getColor(R.color.primary);
        onPrimary = a.getColor(R.color.onPrimary);
        primaryContainer = a.getColor(R.color.primaryContainer);
        onPrimaryContainer = a.getColor(R.color.onPrimaryContainer);
        good = a.getColor(R.color.good);
        warn = a.getColor(R.color.warn);
        bad = a.getColor(R.color.bad);
    }

    /** Edge-to-edge scrolling page; `column` gets padding for the system bars. */
    ScrollView page(LinearLayout column) {
        Window window = a.getWindow();
        window.setDecorFitsSystemWindows(false);
        ScrollView scroll = new ScrollView(a);
        scroll.setClipToPadding(false);
        scroll.setFillViewport(true);
        scroll.addView(column);
        scroll.setOnApplyWindowInsetsListener((v, insets) -> {
            Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            column.setPadding(px(20) + bars.left, bars.top + px(28), px(20) + bars.right, bars.bottom + px(24));
            return WindowInsets.CONSUMED;
        });
        return scroll;
    }

    /** Dark status-bar icons on the light theme. Call after setContentView. */
    void systemBars() {
        boolean night = (a.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        int light = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
        a.getWindow().getInsetsController().setSystemBarsAppearance(night ? 0 : light, light);
    }

    LinearLayout column() {
        LinearLayout c = new LinearLayout(a);
        c.setOrientation(LinearLayout.VERTICAL);
        return c;
    }

    LinearLayout row() {
        LinearLayout r = new LinearLayout(a);
        r.setGravity(Gravity.CENTER_VERTICAL);
        return r;
    }

    LinearLayout card() {
        LinearLayout c = column();
        c.setBackground(rounded(card, 28));
        c.setPadding(px(20), px(18), px(20), px(18));
        return c;
    }

    ImageView appIcon(int sizeDp) {
        ImageView icon = new ImageView(a);
        icon.setImageResource(R.mipmap.ic_launcher);
        icon.setLayoutParams(new LinearLayout.LayoutParams(px(sizeDp), px(sizeDp)));
        return icon;
    }

    View header(String title, String subtitle) {
        LinearLayout r = row();
        r.addView(appIcon(52));
        LinearLayout texts = column();
        texts.setPadding(px(16), 0, 0, 0);
        texts.addView(text(title, 26, on, BOLD));
        texts.addView(text(subtitle, 14, onVar, null));
        r.addView(texts);
        return r;
    }

    TextView sectionLabel(String s) {
        TextView t = text(s, 14, primary, MEDIUM);
        t.setPadding(px(8), 0, 0, 0);
        return t;
    }

    TextView text(String s, float sp, int color, Typeface face) {
        TextView t = new TextView(a);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        if (face != null) t.setTypeface(face);
        return t;
    }

    TextView button(String label, int fill, int ink, float sp) {
        TextView b = text(label, sp, ink, MEDIUM);
        b.setGravity(Gravity.CENTER);
        b.setClickable(true);
        b.setFocusable(true);
        b.setPadding(px(20), 0, px(20), 0);
        GradientDrawable mask = new GradientDrawable();
        mask.setCornerRadius(px(100));
        mask.setColor(Color.WHITE);
        Drawable content = fill == Color.TRANSPARENT ? null : rounded(fill, 100);
        b.setBackground(new RippleDrawable(ColorStateList.valueOf((ink & 0x00FFFFFF) | 0x33000000), content, mask));
        return b;
    }

    TextView primaryButton(String label) {
        return button(label, primary, onPrimary, 16);
    }

    TextView tonalButton(String label) {
        return button(label, primaryContainer, onPrimaryContainer, 15);
    }

    GradientDrawable rounded(int color, int radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(px(radiusDp));
        return d;
    }

    GradientDrawable circle(int color) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.OVAL);
        d.setColor(color);
        return d;
    }

    LinearLayout.LayoutParams full() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    LinearLayout.LayoutParams spaced(int topDp) {
        LinearLayout.LayoutParams lp = full();
        lp.topMargin = px(topDp);
        return lp;
    }

    LinearLayout.LayoutParams height(int heightDp, int topDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(heightDp));
        lp.topMargin = px(topDp);
        return lp;
    }

    LinearLayout.LayoutParams weight(int heightDp) {
        return new LinearLayout.LayoutParams(0, px(heightDp), 1);
    }

    int px(float value) {
        return Math.round(value * dp);
    }

    /** A title/detail row with an optional pill button on the right, or a check mark when done. */
    View settingRow(String title, String detail, boolean done, String action, Runnable onClick) {
        LinearLayout r = row();
        LinearLayout texts = column();
        texts.addView(text(title, 16, on, MEDIUM));
        TextView sub = text(detail, 13, onVar, null);
        sub.setPadding(0, px(2), 0, 0);
        texts.addView(sub);
        r.addView(texts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        if (done) {
            TextView check = text("✓", 18, good, BOLD);
            check.setPadding(px(12), 0, px(4), 0);
            r.addView(check);
        } else if (action != null) {
            TextView b = tonalButton(action);
            b.setTextSize(14);
            b.setPadding(px(18), 0, px(18), 0);
            b.setOnClickListener(v -> onClick.run());
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, px(40));
            lp.setMarginStart(px(12));
            r.addView(b, lp);
        }
        return r;
    }

    // ------------------------------------------------------------------ permissions, shared by onboarding and home

    static final class Permission {
        final String title, detail, action;
        final boolean granted;
        final Runnable fix;

        Permission(String title, String detail, boolean granted, String action, Runnable fix) {
            this.title = title;
            this.detail = detail;
            this.granted = granted;
            this.action = action;
            this.fix = fix;
        }
    }

    List<Permission> permissions() {
        String pkg = a.getPackageName();
        List<Permission> list = new ArrayList<>();
        list.add(new Permission("Notifications", "Transfer progress, links from your Mac and Find My Phone.",
                a.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED, "Allow",
                () -> a.requestPermissions(new String[] {Manifest.permission.POST_NOTIFICATIONS}, 1)));
        list.add(new Permission("Photos", "Shows new photos and screenshots on your Mac, ready to copy.",
                a.checkSelfPermission(Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED, "Allow",
                () -> a.requestPermissions(new String[] {Manifest.permission.READ_MEDIA_IMAGES}, 2)));
        ComponentName listener = new ComponentName(a, NotifListener.class);
        list.add(new Permission("Notification access", "Shows this phone's notifications on your Mac, with Reply.",
                a.getSystemService(NotificationManager.class).isNotificationListenerAccessGranted(listener), "Allow",
                () -> a.startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
                        .putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, listener.flattenToString()))));
        list.add(new Permission("Display over other apps", "Sends copies without anything flashing, and opens links from your Mac.",
                Settings.canDrawOverlays(a), "Allow",
                () -> a.startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + pkg)))));
        list.add(new Permission("Run in the background", "Keeps the link to your Mac open while the screen is off.",
                a.getSystemService(PowerManager.class).isIgnoringBatteryOptimizations(pkg), "Allow",
                () -> a.startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + pkg)))));
        boolean usb = a.checkSelfPermission(Manifest.permission.READ_LOGS) == PackageManager.PERMISSION_GRANTED
                && a.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED;
        list.add(new Permission("Automatic clipboard and wireless mirroring",
                usb ? "Granted over USB." : "One-time step: plug the phone into your Mac and run setup-phone.sh.",
                usb, null, null));
        return list;
    }
}
