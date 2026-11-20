package tv.dumbtv.quick;

/** DispService log lines as logged on an AFTHY7ABBA (Fire OS 8), in the order they came. */
public final class VideoTest {
    private static void check(boolean ok, String name) {
        if (!ok) throw new AssertionError(name);
    }

    private static final String P = "I/DispService(  600): [Mediator][commitInfoChange] ";

    static String fmt(int str, int srcType, String size, int fps, String hdr, String tail) {
        String f = "[" + size + ", fps(" + fps + ", 0), interlace 0, Fmt 15, Hdr " + hdr + "]";
        return P + "srcT[0]: str " + str + ", srcType " + srcType + ", oriInFmt" + f + ", realInFmt" + f
                + ", usedDsclRatio 1, srcMode 1, renderMode 1, share 0, vrr 0, Allm 0," + tail;
    }

    static String act(int str, String what) {
        return P + "additionInfo[0]: str " + str + ", actId 0, act " + ("CREATE".equals(what) ? 101 : 102)
                + " (" + what + "_STREAM), generation 0";
    }

    public static void main(String[] args) {
        Video v = new Video();
        check(v.current(false) == null, "nothing live at first");

        // An HDMI input opens: format line, then CREATE.
        String hdmi = fmt(54, 0, "1920x1080", 60000, "SDR", " lowLatency 0, signalStat 1 realHdmiFps 59940");
        check(!v.feed(hdmi), "a format line alone does not make a stream live");
        check(v.feed(act(54, "CREATE")), "CREATE makes it live");
        Video.Format f = v.current(false);
        check(f.srcType == Video.SRC_HDMI && f.width == 1920 && f.height == 1080, "HDMI 1920x1080");
        check(f.resolution().equals("1080p60"), "1080p60 from realHdmiFps 59.94: " + f.resolution());
        check(f.range().equals("SDR"), "SDR");

        // HOME: the input's stream is destroyed, its format is logged once more, then the home
        // screen's video background starts.
        check(v.feed(hdmi), "a format line for a live stream updates it");
        check(v.feed(act(54, "DESTROY")), "DESTROY ends it");
        check(!v.feed(hdmi), "the trailing format line after DESTROY does not bring it back");
        check(v.current(true) == null, "no HDMI live");
        check(!v.feed(fmt(55, 6, "1920x1080", 30000, "SDR", "")), "app format line");
        check(v.feed(act(55, "CREATE")), "app stream live");
        check(v.current(false).srcType == 6 && v.current(false).resolution().equals("1080p30"), "app 1080p30");
        check(v.current(true) == null, "hdmiOnly skips the app stream");
        check(!v.feed(act(54, "DESTROY")), "destroying a gone stream changes nothing");

        // A 4K HDR app stream.
        v.feed(fmt(60, 6, "3840x2160", 23976, "HDR10", ""));
        v.feed(act(60, "CREATE"));
        check(v.current(false).resolution().equals("4K24"), "4K24: " + v.current(false).resolution());
        check(v.current(false).range().equals("HDR10"), "HDR10");

        // DESTROY lines lost to logd pruning: leftovers are capped, the newest stream still wins.
        for (int id = 100; id < 120; id++) {
            v.feed(fmt(id, 0, "1280x720", 50000, "SDR", ""));
            v.feed(act(id, "CREATE"));
        }
        check(v.current(false).resolution().equals("720p50"), "newest after many leftovers");

        check(!v.feed(P.replace("[Mediator][commitInfoChange] ", "[logoThread] no logo info")), "unrelated lines ignored");
        check(!v.feed(null), "null ignored");
        System.out.println("VideoTest ok");
    }
}
