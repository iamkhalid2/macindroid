package xyz.iamkhalid.macindroid;

import android.Manifest;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.NotificationManager;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Insets;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.net.Uri;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * The view kit for every Macindroid screen, drawn in the app icon's own language rather than Material: a graphite
 * tile, the Mac half's off-white strokes, the Android half's green with a soft glow, and the lightning seam.
 * Monospace caps label the sections, the way a device's spec plate does.
 */
final class Ui {
    static final Typeface MEDIUM = Typeface.create(Typeface.DEFAULT, 500, false);
    static final Typeface BOLD = Typeface.create(Typeface.DEFAULT, 700, false);
    static final Typeface MONO = Typeface.create("monospace", Typeface.NORMAL);

    final Activity a;
    final float dp;
    final int bg, card, raised, line, on, onVar, primary, onPrimary, primaryContainer, onPrimaryContainer, good, warn, bad;

    Ui(Activity a) {
        this.a = a;
        dp = a.getResources().getDisplayMetrics().density;
        bg = a.getColor(R.color.bg);
        card = a.getColor(R.color.card);
        raised = a.getColor(R.color.raised);
        line = a.getColor(R.color.line);
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
        scroll.setBackgroundColor(bg);
        scroll.addView(column);
        scroll.setOnApplyWindowInsetsListener((v, insets) -> {
            Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            column.setPadding(px(18) + bars.left, bars.top + px(24), px(18) + bars.right, bars.bottom + px(24));
            return WindowInsets.CONSUMED;
        });
        return scroll;
    }

    /** Light status-bar icons: every screen is dark, like the icon. Call after setContentView. */
    void systemBars() {
        int light = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
        a.getWindow().getInsetsController().setSystemBarsAppearance(0, light);
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

    /** A graphite tile with a hairline edge, the icon's own shape. */
    LinearLayout card() {
        LinearLayout c = column();
        c.setBackground(outlined(card, line, 24));
        c.setPadding(px(18), px(16), px(18), px(16));
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
        r.addView(appIcon(48));
        LinearLayout texts = column();
        texts.setPadding(px(14), 0, 0, 0);
        texts.addView(text(title, 24, on, BOLD));
        TextView sub = mono(subtitle, onVar);
        sub.setPadding(0, px(3), 0, 0);
        texts.addView(sub);
        r.addView(texts);
        return r;
    }

    /** Monospace caps, letter-spaced: the section names. */
    TextView mono(String s, int color) {
        TextView t = text(s.toUpperCase(), 11, color, MONO);
        t.setLetterSpacing(0.14f);
        return t;
    }

    TextView sectionLabel(String s) {
        TextView t = mono(s, onVar);
        t.setPadding(px(6), 0, 0, 0);
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
        return button(label, fill, Color.TRANSPARENT, ink, sp);
    }

    private TextView button(String label, int fill, int stroke, int ink, float sp) {
        TextView b = text(label, sp, ink, BOLD);
        b.setSingleLine(true);
        b.setGravity(Gravity.CENTER);
        b.setClickable(true);
        b.setFocusable(true);
        b.setPadding(px(12), 0, px(12), 0);
        GradientDrawable mask = new GradientDrawable();
        mask.setCornerRadius(px(16));
        mask.setColor(Color.WHITE);
        Drawable content = fill == Color.TRANSPARENT && stroke == Color.TRANSPARENT ? null : outlined(fill, stroke, 16);
        b.setBackground(new RippleDrawable(ColorStateList.valueOf((ink & 0x00FFFFFF) | 0x33000000), content, mask));
        return b;
    }

    /** The green button, glowing like the icon's Android half. */
    TextView primaryButton(String label) {
        TextView b = button(label, primary, Color.TRANSPARENT, onPrimary, 15);
        b.setElevation(px(6));
        b.setOutlineSpotShadowColor(primary);
        b.setOutlineAmbientShadowColor(primary);
        return b;
    }

    /** An off-white outline, drawn like the icon's Mac half. */
    TextView tonalButton(String label) {
        return button(label, Color.TRANSPARENT, (on & 0x00FFFFFF) | 0x66000000, on, 15);
    }

    /** A small inline action: green text in a faint green capsule. */
    TextView chip(String label, int ink) {
        TextView c = button(label, (ink & 0x00FFFFFF) | 0x1F000000, Color.TRANSPARENT, ink, 13);
        c.setPadding(px(12), 0, px(12), 0);
        return c;
    }

    GradientDrawable rounded(int color, int radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(px(radiusDp));
        return d;
    }

    GradientDrawable outlined(int fill, int stroke, int radiusDp) {
        GradientDrawable d = rounded(fill, radiusDp);
        if (stroke != Color.TRANSPARENT) d.setStroke(Math.max(1, px(1.25f)), stroke);
        return d;
    }

    GradientDrawable circle(int color) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.OVAL);
        d.setColor(color);
        return d;
    }

    /** A soft round glow in `color`, fading to nothing at the edge. */
    GradientDrawable glow(int color) {
        GradientDrawable d = new GradientDrawable();
        d.setGradientType(GradientDrawable.RADIAL_GRADIENT);
        d.setColors(new int[] {(color & 0x00FFFFFF) | 0x55000000, (color & 0x00FFFFFF) | 0x14000000, color & 0x00FFFFFF});
        d.setGradientRadius(px(46)); // fades out before the edge of a 96 dp box
        return d;
    }

    /** A feature's glyph in a raised square tile; green when the feature is working. */
    ImageView glyph(int drawable, boolean lit) {
        ImageView g = new ImageView(a);
        g.setImageResource(drawable);
        g.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        g.setPadding(px(10), px(10), px(10), px(10));
        g.setImageTintList(ColorStateList.valueOf(lit ? primary : onVar));
        g.setBackground(outlined(lit ? primaryContainer : raised, lit ? (primary & 0x00FFFFFF) | 0x55000000 : line, 13));
        g.setLayoutParams(new LinearLayout.LayoutParams(px(44), px(44)));
        return g;
    }

    View hairline() {
        View v = new View(a);
        v.setBackgroundColor(line);
        return v;
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

    LinearLayout.LayoutParams wrap(int heightDp) {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, px(heightDp));
    }

    int px(float value) {
        return Math.round(value * dp);
    }

    /** A title/detail row with an optional button on the right, or a check mark when done. */
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
            TextView b = chip(action, primary);
            b.setOnClickListener(v -> onClick.run());
            LinearLayout.LayoutParams lp = wrap(36);
            lp.setMarginStart(px(12));
            r.addView(b, lp);
        }
        return r;
    }

    // ------------------------------------------------------------------ the switch

    /**
     * An on/off switch in the icon's style: off is an off-white outline with a grey knob; on fills with the
     * Android half's green and glows. Reads as a switch to TalkBack.
     */
    static final class Toggle extends View {
        interface Listener {
            void changed(boolean on);
        }

        private final Ui ui;
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), edge = new Paint(Paint.ANTI_ALIAS_FLAG),
                knob = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF track = new RectF();
        private boolean checked;
        private float t; // 0 off … 1 on, animated
        private ValueAnimator anim;
        Listener listener;

        Toggle(Ui ui, boolean checked) {
            super(ui.a);
            this.ui = ui;
            this.checked = checked;
            t = checked ? 1 : 0;
            setLayerType(LAYER_TYPE_SOFTWARE, null); // for the glow
            edge.setStyle(Paint.Style.STROKE);
            edge.setStrokeWidth(ui.dp * 1.75f);
            setClickable(true);
            setFocusable(true);
            setOnClickListener(v -> setChecked(!this.checked, true));
            setLayoutParams(new LinearLayout.LayoutParams(ui.px(60), ui.px(40)));
        }

        boolean isChecked() {
            return checked;
        }

        void setChecked(boolean value, boolean fromUser) {
            if (value == checked) return;
            checked = value;
            if (anim != null) anim.cancel();
            anim = ValueAnimator.ofFloat(t, value ? 1 : 0);
            anim.setDuration(180);
            anim.addUpdateListener(x -> {
                t = (float) x.getAnimatedValue();
                invalidate();
            });
            anim.start();
            if (fromUser) {
                performHapticFeedback(value ? HapticFeedbackConstants.TOGGLE_ON : HapticFeedbackConstants.TOGGLE_OFF);
                sendAccessibilityEvent(AccessibilityEvent.TYPE_VIEW_CLICKED);
                if (listener != null) listener.changed(value);
            }
        }

        @Override
        protected void onDraw(Canvas c) {
            float d = ui.dp, w = 46 * d, h = 26 * d;
            float left = (getWidth() - w) / 2, top = (getHeight() - h) / 2;
            track.set(left, top, left + w, top + h);
            float r = h / 2;
            int green = ui.primary;
            if (t > 0) {
                fill.setColor(green);
                fill.setAlpha(Math.round(255 * t));
                fill.setShadowLayer(9 * d * t, 0, 0, (green & 0x00FFFFFF) | (Math.round(0xAA * t) << 24));
                c.drawRoundRect(track, r, r, fill);
            }
            if (t < 1) {
                edge.setColor(ui.onVar);
                edge.setAlpha(Math.round(255 * (1 - t)));
                RectF inset = new RectF(track);
                inset.inset(edge.getStrokeWidth() / 2, edge.getStrokeWidth() / 2);
                c.drawRoundRect(inset, r, r, edge);
            }
            float kr = r - 5 * d;
            float cx = track.left + r + (w - 2 * r) * t;
            knob.setColor(blend(ui.onVar, ui.onPrimary, t));
            c.drawCircle(cx, track.centerY(), kr + t * d, knob);
        }

        private static int blend(int from, int to, float t) {
            int a = Math.round(Color.alpha(from) + (Color.alpha(to) - Color.alpha(from)) * t);
            int r = Math.round(Color.red(from) + (Color.red(to) - Color.red(from)) * t);
            int g = Math.round(Color.green(from) + (Color.green(to) - Color.green(from)) * t);
            int b = Math.round(Color.blue(from) + (Color.blue(to) - Color.blue(from)) * t);
            return Color.argb(a, r, g, b);
        }

        @Override
        public CharSequence getAccessibilityClassName() {
            return Switch.class.getName();
        }

        @Override
        public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
            super.onInitializeAccessibilityNodeInfo(info);
            info.setCheckable(true);
            info.setChecked(checked);
        }
    }

    // ------------------------------------------------------------------ permissions, shared by onboarding and home

    static final String P_NOTIFY = "notify", P_PHOTOS = "photos", P_CODES = "codes", P_LISTENER = "listener",
            P_OVERLAY = "overlay", P_BACKGROUND = "background", P_USB = "usb";

    static final class Permission {
        final String key, title, detail, action;
        final boolean granted;
        final Runnable fix;

        Permission(String key, String title, String detail, boolean granted, String action, Runnable fix) {
            this.key = key;
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
        list.add(new Permission(P_NOTIFY, "Notifications", "Transfer progress, links from your Mac and Find My Phone.",
                a.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED, "Allow",
                () -> a.requestPermissions(new String[] {Manifest.permission.POST_NOTIFICATIONS}, 1)));
        list.add(new Permission(P_PHOTOS, "Photos", "Shows new photos and screenshots on your Mac, ready to copy.",
                a.checkSelfPermission(Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED, "Allow",
                () -> a.requestPermissions(new String[] {Manifest.permission.READ_MEDIA_IMAGES}, 2)));
        ComponentName listener = new ComponentName(a, NotifListener.class);
        list.add(new Permission(P_CODES, "One-time codes", "Puts sign-in codes on your Mac's clipboard. Android asks you to pick your "
                + "Mac: open Bluetooth in System Settings on the Mac so it shows up.",
                CodeAccess.granted(a), "Allow", () -> CodeAccess.request(a)));
        list.add(new Permission(P_LISTENER, "Notification access", "Shows this phone's notifications on your Mac, with Reply.",
                a.getSystemService(NotificationManager.class).isNotificationListenerAccessGranted(listener), "Allow",
                () -> a.startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
                        .putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, listener.flattenToString()))));
        list.add(new Permission(P_OVERLAY, "Display over other apps", "Sends copies without anything flashing, and opens links from your Mac.",
                Settings.canDrawOverlays(a), "Allow",
                () -> a.startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + pkg)))));
        list.add(new Permission(P_BACKGROUND, "Run in the background", "Keeps the link to your Mac open while the screen is off.",
                a.getSystemService(PowerManager.class).isIgnoringBatteryOptimizations(pkg), "Allow",
                () -> a.startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + pkg)))));
        boolean power = PowerSetup.done(a);
        list.add(new Permission(P_USB, "Power features",
                power ? "Copies reach your Mac by themselves, and mirroring works over Wi-Fi."
                        : "Optional: copies reach your Mac by themselves, and mirroring works over Wi-Fi. Set up once from your Mac, no cable.",
                power, "Set up", () -> PowerSetup.start(a)));
        return list;
    }
}
