package tv.dumbtv.quick;

import android.app.Activity;
import android.os.Bundle;

/**
 * Trampoline activity for external callers of tv.dumbtv.quick.PANEL.
 * Passes the intent to {@link Tint} and finishes immediately without UI animation.
 */
public final class PanelActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        overridePendingTransition(0, 0);
        Tint.showFromIntent(this, getIntent());
        finish();
        overridePendingTransition(0, 0);
    }
}
