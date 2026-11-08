package xyz.iamkhalid.macindroid;

import android.app.PendingIntent;
import android.content.Intent;
import android.service.quicksettings.Tile;

/** The Macindroid Quick Settings tile: the icon and name, lit while the Mac is connected. Tapping opens the app. */
public class TileService extends android.service.quicksettings.TileService {
    @Override
    public void onTileAdded() {
        Config.setTileAdded(this, true);
    }

    @Override
    public void onTileRemoved() {
        Config.setTileAdded(this, false);
    }

    @Override
    public void onStartListening() {
        Config.setTileAdded(this, true); // it's listening, so it's in the panel (covers tiles added before this was tracked)
        if (LinkService.instance == null && Config.paired(this)) LinkService.start(this);
        Tile tile = getQsTile();
        if (tile == null) return;
        tile.setState(LinkService.connected() ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
        tile.setSubtitle(null);
        tile.setStateDescription(!Config.paired(this) ? "Not set up" : LinkService.connected() ? "Connected" : "Not connected");
        tile.updateTile();
    }

    @Override
    public void onClick() {
        Class<?> target = Config.paired(this) && Config.onboarded(this) ? HomeActivity.class : OnboardingActivity.class;
        Intent intent = new Intent(this, target).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivityAndCollapse(PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE));
    }
}
