package tv.dumbtv.home;

import android.service.dreams.DreamService;
import android.view.KeyEvent;

/**
 * Art mode as the TV's screensaver. Fire OS has no menu for a third-party screensaver, so it is
 * chosen over adb: settings put secure screensaver_components tv.dumbtv.home/tv.dumbtv.home.ArtDream
 * (launcher/README.md, Art mode). Left/right change the painting; any other key wakes the TV.
 */
public final class ArtDream extends DreamService {
    /** A screensaver turns over a little faster than the Art button's five minutes. */
    private static final long EVERY = 3 * 60_000L;
    private ArtView art;

    @Override
    public void onAttachedToWindow() {
        super.onAttachedToWindow();
        setInteractive(true);
        setFullscreen(true);
        ArtView.translucent(getWindow());
        art = new ArtView(this, EVERY);
        setContentView(art);
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        int code = event.getKeyCode();
        if (code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT) {
            if (event.getAction() == KeyEvent.ACTION_DOWN) art.step(code == KeyEvent.KEYCODE_DPAD_LEFT ? -1 : 1);
            return true;
        }
        finish();
        return true;
    }
}
