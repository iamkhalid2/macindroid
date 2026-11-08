package xyz.iamkhalid.macindroid;

import android.app.Activity;
import android.app.AppOpsManager;
import android.companion.AssociationInfo;
import android.companion.AssociationRequest;
import android.companion.BluetoothDeviceFilter;
import android.companion.CompanionDeviceManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.IntentSender;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.service.notification.NotificationListenerService;
import android.util.Log;
import android.widget.Toast;

/**
 * One-time codes for the Mac. Android 15 and later hide codes in notifications from listeners it doesn't trust.
 * It trusts a companion app: one you've linked to a device of yours in Android's own pairing dialog, as a watch
 * app is. Allow here opens that dialog and you pick your Mac. Android only checks when the listener connects, so
 * the listener is reconnected afterwards. (On phones that let adb set it, setup-phone.sh's app op does the same.)
 */
final class CodeAccess {
    static final int REQUEST = 3;

    private CodeAccess() {}

    static boolean granted(Context c) {
        try {
            if (!c.getSystemService(CompanionDeviceManager.class).getMyAssociations().isEmpty()) return true;
        } catch (RuntimeException ignored) { // no companion device support
        }
        try {
            return c.getSystemService(AppOpsManager.class).unsafeCheckOpNoThrow("android:receive_sensitive_notifications",
                    Process.myUid(), c.getPackageName()) == AppOpsManager.MODE_ALLOWED;
        } catch (RuntimeException e) { // an Android without the op, which doesn't hide codes either
            return true;
        }
    }

    /** Opens Android's pairing dialog, listing nearby Bluetooth devices; you pick your Mac. */
    static void request(Activity a) {
        CompanionDeviceManager cdm = a.getSystemService(CompanionDeviceManager.class);
        AssociationRequest request = new AssociationRequest.Builder()
                .addDeviceFilter(new BluetoothDeviceFilter.Builder().build())
                .setSingleDevice(false)
                .build();
        cdm.associate(request, a.getMainExecutor(), new CompanionDeviceManager.Callback() {
            @Override
            public void onAssociationPending(IntentSender chooser) {
                try {
                    a.startIntentSenderForResult(chooser, REQUEST, null, 0, 0, 0);
                } catch (IntentSender.SendIntentException e) {
                    Log.w("Macindroid", "could not open the pairing dialog", e);
                }
            }

            @Override
            public void onAssociationCreated(AssociationInfo info) {
                Log.i("Macindroid", "linked to " + info.getDisplayName() + " as a companion device");
                reconnectListener(a.getApplicationContext());
            }

            @Override
            public void onFailure(CharSequence error) {
                Log.i("Macindroid", "companion pairing didn't finish: " + error);
                if (error != null && !error.toString().toLowerCase().contains("cancel")) {
                    Toast.makeText(a, "Couldn't link your Mac: " + error, Toast.LENGTH_LONG).show();
                }
            }
        });
    }

    /** Android decides whether to hide codes from a listener when it connects, so connect it again. */
    static void reconnectListener(Context c) {
        NotifListener listener = NotifListener.instance;
        if (listener != null) listener.requestUnbind();
        new Handler(Looper.getMainLooper()).postDelayed(
                () -> NotificationListenerService.requestRebind(new ComponentName(c, NotifListener.class)), 1000);
    }
}
