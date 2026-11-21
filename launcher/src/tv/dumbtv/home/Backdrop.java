package tv.dumbtv.home;

import android.content.Context;
import android.graphics.Matrix;
import android.graphics.SurfaceTexture;
import android.media.MediaPlayer;
import android.util.Log;
import android.view.Surface;
import android.view.TextureView;

import java.io.File;

/**
 * {@code background=video}: a muted, looping video behind the rows (Config.video()). The player
 * exists only while the home screen is in front, so the hardware decoder is free for whatever
 * opens next; it picks up where it stopped and fades in on its first frame.
 */
final class Backdrop extends TextureView implements TextureView.SurfaceTextureListener {
    private File file;
    private boolean wanted;
    private MediaPlayer player;
    private Surface surface;
    private int position;
    private int videoW, videoH;

    Backdrop(Context c) {
        super(c);
        setOpaque(false);
        setAlpha(0f);
        setSurfaceTextureListener(this);
    }

    /** Plays {@code f} while the screen is in front; null stops and forgets. */
    void play(File f) {
        if (f == null ? file != null : !f.equals(file)) {
            release();
            position = 0;
        }
        file = f;
        wanted = f != null;
        open();
    }

    /** Off screen: frees the decoder, keeps the position. */
    void pause() {
        wanted = false;
        release();
    }

    private void open() {
        if (!wanted || player != null || file == null || !isAvailable()) return;
        if (surface == null) surface = new Surface(getSurfaceTexture());
        MediaPlayer p = new MediaPlayer();
        player = p;
        try {
            p.setDataSource(file.getPath());
            p.setSurface(surface);
            p.setLooping(true);
            p.setVolume(0f, 0f);
            p.setOnPreparedListener(mp -> {
                if (mp != player) return;
                if (position > 0) mp.seekTo(position);
                mp.start();
            });
            p.setOnVideoSizeChangedListener((mp, w, h) -> {
                videoW = w;
                videoH = h;
                crop();
            });
            p.setOnInfoListener((mp, what, extra) -> {
                if (what == MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START && mp == player) {
                    animate().alpha(1f).setDuration(600).start();
                }
                return false;
            });
            p.setOnErrorListener((mp, what, extra) -> {
                Log.w("dumbtv", "background video error " + what + "/" + extra);
                release();
                return true;
            });
            p.prepareAsync();
        } catch (Exception e) {
            Log.w("dumbtv", "background video: " + file, e);
            release();
        }
    }

    private void release() {
        animate().cancel();
        setAlpha(0f);
        MediaPlayer p = player;
        player = null;
        if (p == null) return;
        try {
            if (p.isPlaying()) position = p.getCurrentPosition();
        } catch (IllegalStateException ignored) {
            // not prepared yet: keep the old position
        }
        p.release();
    }

    /** Centre crop, as the image background does. */
    private void crop() {
        int w = getWidth(), h = getHeight();
        if (videoW == 0 || videoH == 0 || w == 0 || h == 0) return;
        float scale = Math.max(w / (float) videoW, h / (float) videoH);
        Matrix m = new Matrix();
        m.setScale(videoW * scale / w, videoH * scale / h, w / 2f, h / 2f);
        setTransform(m);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        crop();
    }

    @Override
    public void onSurfaceTextureAvailable(SurfaceTexture st, int w, int h) {
        open();
    }

    @Override
    public void onSurfaceTextureSizeChanged(SurfaceTexture st, int w, int h) {
        crop();
    }

    @Override
    public boolean onSurfaceTextureDestroyed(SurfaceTexture st) {
        release();
        if (surface != null) surface.release();
        surface = null;
        return true;
    }

    @Override
    public void onSurfaceTextureUpdated(SurfaceTexture st) {}
}
