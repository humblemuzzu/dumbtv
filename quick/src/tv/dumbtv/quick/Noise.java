package tv.dumbtv.quick;

import android.content.Context;
import android.content.pm.PackageManager;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

/**
 * Live ambient noise sampling using AudioRecord.
 *
 * Samples only while the quick settings panel is open in the foreground; stops as soon as
 * the panel pauses or closes. Nothing is recorded, stored, or sent anywhere.
 *
 * Computes root-mean-square (RMS) amplitude and converts it to decibels for a live level bar.
 */
final class Noise {
    interface Listener {
        void onLevel(float fraction, int dB);
    }

    private static final int SAMPLE_RATE = 16000;
    private static final int CHANNEL = AudioFormat.CHANNEL_IN_MONO;
    private static final int ENCODING = AudioFormat.ENCODING_PCM_16BIT;
    private static final double DB_MIN = 30.0, DB_MAX = 90.0;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private AudioRecord recorder;
    private Thread worker;
    private volatile boolean running;

    static boolean hasPermission(Context c) {
        return c.checkCallingOrSelfPermission("android.permission.RECORD_AUDIO") == PackageManager.PERMISSION_GRANTED;
    }

    synchronized void start(Context c, Listener listener) {
        if (running) return;
        if (!hasPermission(c)) {
            Log.i(Tint.TAG, "noise: RECORD_AUDIO permission not granted");
            return;
        }

        int bufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL, ENCODING);
        if (bufferSize <= 0) return;

        try {
            recorder = new AudioRecord(MediaRecorder.AudioSource.MIC, SAMPLE_RATE, CHANNEL, ENCODING, bufferSize * 2);
            if (recorder.getState() != AudioRecord.STATE_INITIALIZED) {
                recorder.release();
                recorder = null;
                return;
            }
            recorder.startRecording();
        } catch (SecurityException | IllegalArgumentException e) {
            Log.w(Tint.TAG, "noise: could not start AudioRecord", e);
            if (recorder != null) {
                recorder.release();
                recorder = null;
            }
            return;
        }

        running = true;
        worker = new Thread(() -> {
            short[] buf = new short[1024];
            while (running) {
                AudioRecord rec = recorder;
                if (rec == null) break;
                int read = rec.read(buf, 0, buf.length);
                if (read > 0) {
                    long sum = 0;
                    for (int i = 0; i < read; i++) {
                        sum += (long) buf[i] * buf[i];
                    }
                    double rms = Math.sqrt((double) sum / read);
                    double db = rms > 1.0 ? 20.0 * Math.log10(rms) : 0.0;
                    float fraction = (float) Math.max(0.0, Math.min(1.0, (db - DB_MIN) / (DB_MAX - DB_MIN)));
                    int dbInt = (int) Math.round(db);
                    ui.post(() -> {
                        if (running) listener.onLevel(fraction, dbInt);
                    });
                }
                try {
                    Thread.sleep(100);
                } catch (InterruptedException ignored) {
                    break;
                }
            }
        }, "noise-sampler");
        worker.setDaemon(true);
        worker.start();
    }

    synchronized void stop() {
        running = false;
        if (worker != null) {
            worker.interrupt();
            worker = null;
        }
        if (recorder != null) {
            try {
                if (recorder.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) {
                    recorder.stop();
                }
                recorder.release();
            } catch (IllegalStateException ignored) {
            }
            recorder = null;
        }
    }
}
