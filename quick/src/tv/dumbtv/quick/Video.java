package tv.dumbtv.quick;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The format of the video on screen, read from the log of MediaTek's display service
 * (DispService in the hwcomposer process). Android's TV API reports no video track for an HDMI
 * input on these TVs (TvView.getTracks returns none), but DispService logs every video stream it
 * creates or destroys, HDMI and app playback alike:
 *
 * <pre>
 * [Mediator][commitInfoChange] srcT[0]: str 28, srcType 0, oriInFmt[...], realInFmt[1920x1080,
 *     fps(60000, 0), interlace 0, Fmt 15, Hdr SDR], ... realHdmiFps 59940
 * [Mediator][commitInfoChange] additionInfo[0]: str 30, actId 0, act 102 (DESTROY_STREAM), ...
 * </pre>
 *
 * srcType 0 is an HDMI input; 6 is a decoder in an app. Reading another process's log needs
 * READ_LOGS, which quick/install.sh grants over adb. Pure Java: quick/tests/VideoTest.java.
 */
final class Video {
    static final int SRC_HDMI = 0;

    /** One live video stream. */
    static final class Format {
        final int width, height, fpsMilli, hdmiFpsMilli, srcType;
        final boolean interlaced;
        final String hdr;

        Format(int width, int height, int fpsMilli, boolean interlaced, String hdr, int srcType, int hdmiFpsMilli) {
            this.width = width;
            this.height = height;
            this.fpsMilli = fpsMilli;
            this.interlaced = interlaced;
            this.hdr = hdr;
            this.srcType = srcType;
            this.hdmiFpsMilli = hdmiFpsMilli;
        }

        /** "1080p60", "4K24", "1080i60", "720p50". */
        String resolution() {
            int fps = Math.round((hdmiFpsMilli > 0 ? hdmiFpsMilli : fpsMilli) / 1000f);
            String size = height >= 4000 ? "8K" : height >= 2100 ? "4K"
                    : height >= 470 ? height + (interlaced ? "i" : "p") : width + "x" + height;
            return fps > 0 ? size + fps : size;
        }

        /** "SDR", "HDR10", "HLG", "Dolby Vision": the name DispService logs, tidied. */
        String range() {
            if (hdr == null || hdr.isEmpty()) return "SDR";
            String h = hdr.toUpperCase(java.util.Locale.ROOT);
            if (h.contains("DOLBY") || h.equals("DV") || h.contains("DOVI")) return "Dolby Vision";
            if (h.contains("HLG")) return "HLG";
            if (h.contains("PLUS") || h.contains("10+")) return "HDR10+";
            if (h.contains("HDR")) return "HDR10";
            return hdr;
        }
    }

    private static final Pattern STREAM = Pattern.compile(
            "srcT\\[\\d+\\]: str (\\d+), srcType (\\d+),.*?realInFmt\\[(\\d+)x(\\d+), fps\\((\\d+)[^\\]]*?"
                    + "interlace (\\d+)[^\\]]*?Hdr ([^\\],]+)\\](?:.*?realHdmiFps (\\d+))?");
    private static final Pattern ACTION = Pattern.compile("str (\\d+), actId \\d+, act \\d+ \\((CREATE|DESTROY)_STREAM\\)");

    private static final int MAX = 6;

    /** Live streams by DispService's stream id, oldest first. */
    private final Map<Integer, Format> live = new LinkedHashMap<>();
    /** The last format logged for each recent stream id, live or not. */
    private final Map<Integer, Format> seen = new LinkedHashMap<>();

    /**
     * Feeds one log line; true if it changed what is live. A stream goes live on its
     * CREATE_STREAM line (its format line comes just before it) and leaves on DESTROY_STREAM.
     * Format lines alone never make a stream live: DispService logs a destroyed stream's format
     * once more when it clears the layer away.
     */
    synchronized boolean feed(String line) {
        if (line == null) return false;
        if (line.contains("_STREAM)")) {
            Matcher a = ACTION.matcher(line);
            if (!a.find()) return false;
            Integer id = Integer.valueOf(a.group(1));
            if ("DESTROY".equals(a.group(2))) {
                seen.remove(id);
                return live.remove(id) != null;
            }
            Format f = seen.get(id);
            if (f == null) return false;
            live.remove(id);
            live.put(id, f);
            // A DESTROY_STREAM line can be lost (logd prunes DispService's lines first), so only
            // the newest few streams are kept.
            trim(live);
            return true;
        }
        if (!line.contains("realInFmt")) return false;
        Matcher m = STREAM.matcher(line);
        if (!m.find()) return false;
        Integer id = Integer.valueOf(m.group(1));
        Format f = new Format(Integer.parseInt(m.group(3)), Integer.parseInt(m.group(4)),
                Integer.parseInt(m.group(5)), !"0".equals(m.group(6)), m.group(7).trim(),
                Integer.parseInt(m.group(2)), m.group(8) == null ? 0 : Integer.parseInt(m.group(8)));
        seen.remove(id);
        seen.put(id, f);
        trim(seen);
        if (!live.containsKey(id)) return false;
        live.put(id, f); // a format change of a live stream, such as an HDMI source switching modes
        return true;
    }

    private static void trim(Map<Integer, Format> m) {
        while (m.size() > MAX) m.remove(m.keySet().iterator().next());
    }

    /** The stream most recently started, or null. {@code hdmiOnly} skips app playback. */
    synchronized Format current(boolean hdmiOnly) {
        List<Format> all = new ArrayList<>(live.values());
        for (int i = all.size() - 1; i >= 0; i--) {
            Format f = all.get(i);
            if (!hdmiOnly || f.srcType == SRC_HDMI) return f;
        }
        return null;
    }

    synchronized void clear() {
        live.clear();
        seen.clear();
    }
}
