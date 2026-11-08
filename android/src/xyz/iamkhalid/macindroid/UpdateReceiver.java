package xyz.iamkhalid.macindroid;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.util.Log;

/** Android's installer reports here: it needs you to confirm (show its dialog), it failed, or it's done. */
public class UpdateReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            Intent confirm = intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent.class);
            if (confirm != null) {
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(confirm);
            }
            Updater.progress = "Waiting for you to confirm";
            LinkService.historyVersion++;
        } else if (status == PackageInstaller.STATUS_SUCCESS) {
            Updater.finished(); // usually never seen: the update replaces this process
        } else {
            String message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
            Log.w("Macindroid", "update not installed: " + status + " " + message);
            Updater.fail(context, status == PackageInstaller.STATUS_FAILURE_ABORTED ? "cancelled" : "Android didn't install it");
        }
    }
}
