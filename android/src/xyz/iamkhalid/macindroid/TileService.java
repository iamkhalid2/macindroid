package xyz.iamkhalid.macindroid;

import android.app.PendingIntent;
import android.content.Intent;
import android.service.quicksettings.Tile;

/** The Macindroid Quick Settings tile: tap sends the clipboard to the Mac; long-press opens Macindroid. */
public class TileService extends android.service.quicksettings.TileService {
    @Override
    public void onStartListening() {
        if (LinkService.instance == null && Config.paired(this)) LinkService.start(this);
        Tile tile = getQsTile();
        if (tile == null) return;
        boolean on = LinkService.connected();
        tile.setState(on ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
        tile.setSubtitle(!Config.paired(this) ? "Not set up" : on ? "Send clipboard" : "Not connected");
        tile.updateTile();
    }

    @Override
    public void onClick() {
        Class<?> target = Config.paired(this) ? ReadClipActivity.class : OnboardingActivity.class;
        Intent intent = new Intent(this, target).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivityAndCollapse(PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE));
    }
}
