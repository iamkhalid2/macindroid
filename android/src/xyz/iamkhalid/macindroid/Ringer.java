package xyz.iamkhalid.macindroid;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.VibrationAttributes;
import android.os.VibrationEffect;
import android.os.Vibrator;

/**
 * Find My Phone: plays the alarm sound at full alarm volume (so it rings on silent), vibrates, and stops after
 * 30 seconds, on Stop, or as soon as the phone is unlocked. The alarm volume is put back afterwards.
 */
final class Ringer {
    private static final int NOTIFICATION_ID = 2;
    private final LinkService service;
    private final AudioManager audio;
    private MediaPlayer player;
    private int savedVolume = -1;
    private final Runnable timeout = () -> stop(true);
    private final BroadcastReceiver unlocked = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            stop(true);
        }
    };

    Ringer(LinkService service) {
        this.service = service;
        this.audio = service.getSystemService(AudioManager.class);
    }

    /** Main thread. */
    void start() {
        if (player != null) return;
        Uri tone = RingtoneManager.getActualDefaultRingtoneUri(service, RingtoneManager.TYPE_ALARM);
        if (tone == null) tone = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE);
        savedVolume = audio.getStreamVolume(AudioManager.STREAM_ALARM);
        audio.setStreamVolume(AudioManager.STREAM_ALARM, audio.getStreamMaxVolume(AudioManager.STREAM_ALARM), 0);
        try {
            player = new MediaPlayer();
            player.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build());
            player.setDataSource(service, tone);
            player.setLooping(true);
            player.prepare();
            player.start();
        } catch (Exception e) {
            if (player != null) player.release();
            player = null;
            restoreVolume();
            return;
        }
        service.getSystemService(Vibrator.class).vibrate(VibrationEffect.createWaveform(new long[] {0, 700, 500}, 0),
                VibrationAttributes.createForUsage(VibrationAttributes.USAGE_ALARM));
        service.registerReceiver(unlocked, new IntentFilter(Intent.ACTION_USER_PRESENT));
        PendingIntent stop = PendingIntent.getService(service, 4,
                new Intent(service, LinkService.class).setAction(LinkService.ACTION_STOP_RING), PendingIntent.FLAG_IMMUTABLE);
        service.notify(NOTIFICATION_ID, new Notification.Builder(service, LinkService.CH_RING)
                .setSmallIcon(R.drawable.ic_glyph)
                .setContentTitle(Config.macName(service) + " is looking for this phone")
                .setContentText("Tap to stop")
                .setCategory(Notification.CATEGORY_ALARM)
                .setContentIntent(stop)
                .setDeleteIntent(stop)
                .addAction(new Notification.Action.Builder(null, "Stop", stop).build())
                .build());
        service.main.postDelayed(timeout, 30_000);
    }

    /** Main thread. `tellMac` updates the Mac's menu when the phone stops ringing on its own. */
    void stop(boolean tellMac) {
        if (player == null) return;
        service.main.removeCallbacks(timeout);
        try {
            player.stop();
        } catch (Exception ignored) {
        }
        player.release();
        player = null;
        restoreVolume();
        service.getSystemService(Vibrator.class).cancel();
        try {
            service.unregisterReceiver(unlocked);
        } catch (Exception ignored) {
        }
        service.getSystemService(NotificationManager.class).cancel(NOTIFICATION_ID);
        if (tellMac) service.sendAsync(Proto.RING_STOP, new byte[0]);
    }

    private void restoreVolume() {
        if (savedVolume >= 0) audio.setStreamVolume(AudioManager.STREAM_ALARM, savedVolume, 0);
        savedVolume = -1;
    }
}
