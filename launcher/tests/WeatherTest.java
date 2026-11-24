package tv.dumbtv.home;

/** Host-side checks for the weather words, symbols and request address. */
public final class WeatherTest {
    private static void check(boolean ok, String name) {
        if (!ok) throw new AssertionError(name);
    }

    public static void main(String[] args) {
        check("Clear".equals(Weather.describe(0)), "0 is clear");
        check("Partly cloudy".equals(Weather.describe(2)), "2 is partly cloudy");
        check("Rain".equals(Weather.describe(63)), "63 is rain");
        check("Showers".equals(Weather.describe(81)), "81 is showers");
        check("Thunderstorm".equals(Weather.describe(95)), "95 is a thunderstorm");
        check("".equals(Weather.describe(4)), "unknown code has no words");

        check(!Weather.symbol(0, true).equals(Weather.symbol(0, false)), "clear night differs from day");
        check(Weather.symbol(3, true).equals(Weather.symbol(3, false)), "overcast is the same at night");
        check(!Weather.symbol(4, true).isEmpty(), "unknown code still has a symbol");

        String url = Weather.url("12.3400", "56.7800");
        check(url != null && url.startsWith("https://api.open-meteo.com/v1/forecast?"), "open-meteo address");
        check(url.contains("latitude=12.3400") && url.contains("longitude=56.7800"), "coordinates in the address");
        check(Weather.url(null, "56.78") == null, "no latitude, no request");
        check(Weather.url("91", "0") == null, "latitude out of range");
        check(Weather.url("12.3", "0&x=1") == null, "not a number");
        check(Weather.url("NaN", "0") == null, "NaN refused");
        System.out.println("WeatherTest passed");
    }
}
