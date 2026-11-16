package tv.dumbtv.home;

import android.content.Context;
import android.content.Intent;
import android.hardware.ConsumerIrManager;
import android.os.Build;
import android.util.Log;
import android.widget.Toast;

import java.util.List;

/** Offline Samsung S90C input selection, ported from the owner's FireCubeIRTest v0.6. */
final class SamsungIr {
    private static final String PREFIX = "samsung-ir-hdmi-";
    private static final int CARRIER_HZ = 38000;
    // AFTGAZL Fire OS workaround proven by the owner's test app, not Android microseconds.
    private static final int FIRE_OS_TIMING_DIVISOR = 10;

    private SamsungIr() {}

    private static ConsumerIrManager emitter(Context c) {
        return (ConsumerIrManager) c.getSystemService(Context.CONSUMER_IR_SERVICE);
    }

    static void append(Context c, List<Item> out) {
        // Do not apply this nonstandard timing scale to other TV/Cube hardware.
        if (!"Amazon".equalsIgnoreCase(Build.MANUFACTURER) || !"AFTGAZL".equals(Build.MODEL)) return;
        try {
            ConsumerIrManager ir = emitter(c);
            if (ir == null || !ir.hasIrEmitter()) return;
        } catch (RuntimeException e) {
            Log.w("dumbtv", "Samsung IR unavailable", e);
            return;
        }
        for (int port = 1; port <= 4; port++) {
            out.add(new Item(PREFIX + port, "Samsung HDMI " + port, new Intent()));
        }
    }

    /** Consumes only Samsung tiles. Never opens TvView or changes Cube input routing. */
    static boolean isSamsungInput(Item item) {
        return item.id.startsWith(PREFIX);
    }

    static boolean open(Context c, Item item, int errorMessage) {
        if (!isSamsungInput(item)) return false;
        try {
            int port = Integer.parseInt(item.id.substring(PREFIX.length()));
            String[] codes = {PRONTO_HDMI1, PRONTO_HDMI2, PRONTO_HDMI3, PRONTO_HDMI4};
            ConsumerIrManager ir = emitter(c);
            if (port < 1 || port > codes.length || ir == null || !ir.hasIrEmitter())
                throw new IllegalStateException("IR unavailable");
            // Exactly one transmission per click, as in the successful test app.
            ir.transmit(CARRIER_HZ, prontoRaw(codes[port - 1]));
        } catch (RuntimeException e) {
            Log.w("dumbtv", "Samsung HDMI transmission failed", e);
            Toast.makeText(c, errorMessage, Toast.LENGTH_SHORT).show();
        }
        return true;
    }
    private static final String PRONTO_HDMI1 =
            "0000 006C 0022 0000 00AD 00AD 0015 0041 0015 0041 0015 0041 " +
            "0015 0015 0015 0015 0015 0015 0015 0015 0015 0015 0015 0041 " +
            "0015 0041 0015 0041 0015 0015 0015 0015 0015 0015 0015 0015 " +
            "0015 0015 0015 0041 0015 0015 0015 0015 0015 0041 0015 0015 " +
            "0015 0041 0015 0041 0015 0041 0015 0015 0015 0041 0015 0041 " +
            "0015 0015 0015 0041 0015 0015 0015 0015 0015 0015 0015 0728";
    private static final String PRONTO_HDMI2 =
            "0000 006C 0022 0000 00AD 00AD 0015 0041 0015 0041 0015 0041 " +
            "0015 0015 0015 0015 0015 0015 0015 0015 0015 0015 0015 0041 " +
            "0015 0041 0015 0041 0015 0015 0015 0015 0015 0015 0015 0015 " +
            "0015 0015 0015 0015 0015 0041 0015 0041 0015 0041 0015 0041 " +
            "0015 0041 0015 0015 0015 0041 0015 0041 0015 0015 0015 0015 " +
            "0015 0015 0015 0015 0015 0015 0015 0041 0015 0015 0015 0728";
    private static final String PRONTO_HDMI3 =
            "0000 006C 0022 0000 00AD 00AD 0015 0041 0015 0041 0015 0041 " +
            "0015 0015 0015 0015 0015 0015 0015 0015 0015 0015 0015 0041 " +
            "0015 0041 0015 0041 0015 0015 0015 0015 0015 0015 0015 0015 " +
            "0015 0015 0015 0015 0015 0041 0015 0015 0015 0015 0015 0015 " +
            "0015 0015 0015 0041 0015 0041 0015 0041 0015 0015 0015 0041 " +
            "0015 0041 0015 0041 0015 0041 0015 0015 0015 0015 0015 0728";
    private static final String PRONTO_HDMI4 =
            "0000 006D 0000 0022 00AC 00AB 0015 0041 0015 0041 0015 0041 " +
            "0015 0016 0015 0016 0015 0016 0015 0016 0015 0016 0015 0041 " +
            "0015 0041 0015 0041 0015 0016 0015 0016 0015 0016 0015 0016 " +
            "0015 0016 0015 0041 0015 0016 0015 0041 0015 0016 0015 0016 " +
            "0015 0016 0015 0041 0015 0041 0015 0016 0015 0041 0015 0016 " +
            "0015 0041 0015 0041 0015 0041 0015 0016 0015 0016 0015 0689";

    private static int[] prontoRaw(String hex) {
        String[] words = hex.trim().split("\\s+");
        int[] w = new int[words.length];
        for (int i = 0; i < words.length; i++) w[i] = Integer.parseInt(words[i], 16);
        if (w.length < 6 || w[0] != 0 || w[1] == 0 || w.length != 4 + 2*(w[2]+w[3]))
            throw new IllegalArgumentException("Ungueltiger Pronto-Code");
        // Frequency for Pronto 0000: MHz / (0.241246 * frequencyWord)
        double usPerCycle = 0.241246 * w[1];
        int count = w[2] > 0 ? w[2]*2 : w[3]*2;
        int[] pattern = new int[count];
        for (int i = 0; i < count; i++) {
            double micros = w[4+i]*usPerCycle;
            pattern[i] = Math.max(1, (int)Math.round(micros / FIRE_OS_TIMING_DIVISOR));
        }
        return pattern;
    }
}
