package tv.dumbtv.home;

import android.app.Activity;
import android.os.Bundle;
import android.view.KeyEvent;

/** Art mode from the home screen's Art button: left/right change the painting, any other key goes back. */
public final class ArtScreen extends Activity {
    private ArtView art;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        ArtView.translucent(getWindow());
        art = new ArtView(this, 5 * 60_000L);
        setContentView(art);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) art.step(-1);
        else if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) art.step(1);
        else finish();
        return true;
    }
}
