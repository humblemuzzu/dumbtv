package tv.dumbtv.home;

/**
 * Which of dumbtv home's own window events tell Guard what is in front. Two do not: the cover,
 * an overlay drawn over Amazon's home while ours starts, and a Home event delivered after Home
 * has already lost the screen again. Read as "the user left Amazon's home", either one dropped
 * the pending takeover and left Amazon's home in front (#153).
 */
final class OwnWindow {
    private OwnWindow() {}

    static boolean showsWhatIsInFront(CharSequence cls, String coverClass, String homeClass,
            boolean homeInFront) {
        if (cls == null) return true;
        if (coverClass.contentEquals(cls)) return false;
        return homeInFront || !homeClass.contentEquals(cls);
    }
}
