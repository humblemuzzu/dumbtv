package tv.dumbtv.home;

/** Darkened brand accents for verified installed app identities. */
final class BrandColors {
    private BrandColors() {}
    static int[] forPackage(String id) {
        switch (id) {
            case "com.amcplus.amcfiretv": return new int[] {0xFF274348, 0xFF008A85, 0xFF5E9487};
            case "com.crunchyroll.crunchyroid": return new int[] {0xFF943700, 0xFF943700};
            case "com.disney.disneyplus": return new int[] {0xFF004046, 0xFF004046};
            case "com.hbo.hbonow": return new int[] {0xFF313131, 0xFF313131};
            case "com.hulu.plus": return new int[] {0xFF10864C, 0xFF10864C};
            case "com.netflix.ninja": return new int[] {0xFF85050C, 0xFF85050C};
            case "com.pandora.android.gtv": return new int[] {0xFF391E75, 0xFF7B0C5D, 0xFF8C3A34};
            case "com.cbs.ott":
            case "com.cbs.ca": return new int[] {0xFF003A94, 0xFF003A94};
            case "com.peacock.peacockfiretv": return new int[] {0xFF92760A, 0xFF94420A, 0xFF8B0C26, 0xFF61187A, 0xFF035B82, 0xFF036425};
            case "com.plexapp.android": return new int[] {0xFF886600, 0xFF886600};
            case "com.amazon.firebat": return new int[] {0xFF006182, 0xFF006182};
            case "com.starz.starzplay.firetv": return new int[] {0xFF948C2C, 0xFF5C8F92, 0xFF008790};
            case "com.amazon.firetv.youtube": return new int[] {0xFF94001E, 0xFF941754};
            default: return null;
        }
    }
}
