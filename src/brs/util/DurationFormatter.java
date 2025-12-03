package brs.util;

import java.util.concurrent.TimeUnit;

public final class DurationFormatter {

    public enum Unit {
        MILLISECOND(0, "ms"),
        SECOND(TimeUnit.SECONDS.toSeconds(1), "s"),
        MINUTE(TimeUnit.MINUTES.toSeconds(1), "m"),
        HOUR(TimeUnit.HOURS.toSeconds(1), "h"),
        DAY(TimeUnit.DAYS.toSeconds(1), "d"),
        YEAR(TimeUnit.DAYS.toSeconds(365), "y");

        private final long seconds;
        private final String symbol;

        Unit(long seconds, String symbol) {
            this.seconds = seconds;
            this.symbol = symbol;
        }

        public long getSeconds() {
            return seconds;
        }
    }

    private DurationFormatter() {
    } // Static utility class

    /**
     * Formats a duration in milliseconds into a human-readable string with
     * configurable resolution.
     *
     * @param millis  The duration in milliseconds.
     * @param minUnit The smallest time unit to display.
     * @param maxUnit The largest time unit to display.
     * @return A formatted string like "1d:2h:3m:4s". // Javadoc-ot nem módosítom,
     *         mert a kérés a logikára vonatkozott
     */
    public static String format(long millis, Unit maxUnit, Unit minUnit) {
        if (millis < 0) {
            return "0" + minUnit.symbol;
        }

        if (minUnit.ordinal() > maxUnit.ordinal()) {
            throw new IllegalArgumentException("minUnit cannot be greater than maxUnit");
        }

        if (millis == 0) {
            return "0" + minUnit.symbol;
        }

        StringBuilder sb = new StringBuilder();
        long remainingMillis = millis;

        for (int i = maxUnit.ordinal(); i >= minUnit.ordinal(); i--) {
            Unit unit = Unit.values()[i];

            if (unit == Unit.MILLISECOND) {
                if (remainingMillis > 0 || sb.length() == 0) {
                    if (sb.length() > 0) {
                        sb.append(":");
                    }
                    sb.append(String.format("%03d", remainingMillis)).append(unit.symbol);
                }
                break; // Milliseconds is the last unit
            }

            long secondsPerUnit = unit.getSeconds();
            long millisPerUnit = secondsPerUnit * 1000;

            String formatString;
            if (unit == Unit.DAY) {
                formatString = "%03d";
            } else if (unit == Unit.HOUR || unit == Unit.MINUTE || unit == Unit.SECOND) {
                formatString = "%02d";
            } else {
                formatString = "%d"; // Year
            }

            long value = remainingMillis / millisPerUnit;
            if (value > 0 || sb.length() > 0) {
                if (sb.length() > 0) {
                    sb.append(":");
                }
                sb.append(String.format(formatString, value)).append(unit.symbol);
                remainingMillis %= millisPerUnit;
            }
        }

        String result = sb.toString(); // No trim needed
        return result.isEmpty() ? "0" + minUnit.symbol : result;
    }

    /**
     * A simplified format method that shows all units from years down to
     * milliseconds.
     * 
     * @param millis The duration in milliseconds.
     * @return A formatted string.
     */
    public static String format(long millis) {
        return format(millis, Unit.YEAR, Unit.MILLISECOND);
    }
}