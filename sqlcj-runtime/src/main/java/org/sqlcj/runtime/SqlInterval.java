package org.sqlcj.runtime;

import java.math.BigInteger;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Period;
import java.time.temporal.ChronoUnit;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * One PostgreSQL {@code interval} value, held exactly as PostgreSQL holds it:
 * a month count, a day count, and a microsecond count, each signed on its own.
 *
 * <p>PostgreSQL keeps the three apart because none of them converts into
 * another without a calendar: a month is 28 to 31 days, and a day is 23, 24, or
 * 25 hours across a daylight-saving change. {@code java.time} has no type of
 * that shape — a {@link Period} holds no time part and a {@link Duration} holds
 * no month — so the runtime carries the value itself and converts to either on
 * request, failing when the target cannot hold a component.
 *
 * <p>The value is exchanged with the database as text, which needs no driver of
 * its own. {@link #format()} writes the three counts with an explicit sign on
 * every field, which PostgreSQL reads the same way under every
 * {@code IntervalStyle}, and {@link #parse} reads the text PostgreSQL writes
 * under its default {@code postgres} style.
 *
 * @param months       the month count, years included as 12 months each
 * @param days         the day count
 * @param microseconds the time part, in microseconds, which is PostgreSQL's
 *                     own resolution
 */
public record SqlInterval(
    int months,
    int days,
    long microseconds
) {

    /** Months per year, which PostgreSQL stores a year as. */
    private static final int MONTHS_PER_YEAR = 12;

    private static final long MICROS_PER_SECOND = 1_000_000L;

    private static final long MICROS_PER_MINUTE = 60 * MICROS_PER_SECOND;

    private static final long MICROS_PER_HOUR = 60 * MICROS_PER_MINUTE;

    private static final int NANOS_PER_MICRO = 1_000;

    /** The number of fractional-second digits PostgreSQL writes at most. */
    private static final int MICRO_DIGITS = 6;

    /**
     * The text PostgreSQL writes under its default {@code postgres}
     * {@code IntervalStyle}: an optional year, month, and day field, each
     * signed on its own, followed by an optional {@code HH:MM:SS} time part
     * with its own sign and up to six fractional digits. PostgreSQL writes the
     * fields in this order and writes only the ones the value holds, so every
     * field is optional and at least one must be present.
     */
    private static final Pattern POSTGRES_TEXT = Pattern.compile(
        "(?=.)"
            + "(?:([+-]?\\d+) years?)?"
            + "(?: ?([+-]?\\d+) mons?)?"
            + "(?: ?([+-]?\\d+) days?)?"
            + "(?: ?([+-])?(\\d+):(\\d{2}):(\\d{2})(?:\\.(\\d{1,6}))?)?"
    );

    /**
     * Reads one interval from the text PostgreSQL writes under its default
     * {@code postgres} {@code IntervalStyle}, such as
     * {@code 1 year 2 mons -3 days +01:02:03.000004}. A year counts as twelve
     * months, so the years and months of the text are one month count, as
     * PostgreSQL stores them.
     *
     * @param text the text to read, or {@code null}
     * @return the interval the text states, or {@code null} when {@code text}
     *         is {@code null}
     * @throws IllegalArgumentException when the text is not
     *                                  {@code postgres}-style interval text
     * @throws ArithmeticException      when a count the text states does not
     *                                  fit the value
     */
    public static SqlInterval parse(String text) {
        if (text == null) {
            return null;
        }

        Matcher matcher = POSTGRES_TEXT.matcher(text);

        if (!matcher.matches()) {
            throw new IllegalArgumentException(unreadableText(text));
        }

        int years = intField(matcher.group(1));
        int monthsField = intField(matcher.group(2));

        return new SqlInterval(
            Math.addExact(Math.multiplyExact(years, MONTHS_PER_YEAR), monthsField),
            intField(matcher.group(3)),
            timeMicroseconds(matcher)
        );
    }

    /**
     * The text this interval is bound as: the three counts, each with an
     * explicit sign, as in {@code +14 mons -3 days +3723000004 microseconds}.
     *
     * <p>Every field carries its own sign, so PostgreSQL reads the text alike
     * under every {@code IntervalStyle}, and the time part is written as a
     * microsecond count rather than as {@code HH:MM:SS}, which cannot spell the
     * least microsecond count this record holds.
     */
    public String format() {
        return String.format(
            Locale.ROOT,
            "%+d mons %+d days %+d microseconds",
            months,
            days,
            microseconds
        );
    }

    /**
     * The interval of one {@link Period}: its total months, which take its
     * years as twelve months each, its own days, and no time part.
     *
     * @throws ArithmeticException when the period's total months do not fit a
     *                             month count
     */
    public static SqlInterval of(Period period) {
        return new SqlInterval(
            Math.toIntExact(period.toTotalMonths()),
            period.getDays(),
            0
        );
    }

    /**
     * The interval of one {@link Duration}: its whole length as microseconds,
     * with no month and no day, because a {@code Duration} day is exactly 24
     * hours and PostgreSQL's is not.
     *
     * @throws DateTimeException   when the duration holds a part smaller than
     *                             a microsecond
     * @throws ArithmeticException when the duration does not fit a microsecond
     *                             count
     */
    public static SqlInterval of(Duration duration) {
        int nanoOfSecond = duration.getNano();

        if (nanoOfSecond % NANOS_PER_MICRO != 0) {
            throw new DateTimeException(
                "Duration " + duration + " holds a part smaller than a microsecond,"
                    + " which an interval cannot hold"
            );
        }

        BigInteger microseconds = BigInteger.valueOf(duration.getSeconds())
            .multiply(BigInteger.valueOf(MICROS_PER_SECOND))
            .add(BigInteger.valueOf(nanoOfSecond / NANOS_PER_MICRO));

        return new SqlInterval(0, 0, microseconds.longValueExact());
    }

    /**
     * This interval as a {@link Period} of years, months, and days, the years
     * taken from the month count as PostgreSQL writes them.
     *
     * @throws DateTimeException when this interval holds a time part, which a
     *                           {@code Period} cannot hold
     */
    public Period toPeriod() {
        if (microseconds != 0) {
            throw new DateTimeException(
                "Interval " + format() + " holds a time part, which a Period cannot hold"
            );
        }

        return Period.of(months / MONTHS_PER_YEAR, months % MONTHS_PER_YEAR, days);
    }

    /**
     * This interval as a {@link Duration} of its days and its time part, each
     * day counting as 24 hours, as {@link Duration#ofDays} does and as
     * PostgreSQL compares two intervals.
     *
     * @throws DateTimeException when this interval holds a month, which a
     *                           {@code Duration} cannot hold
     */
    public Duration toDuration() {
        if (months != 0) {
            throw new DateTimeException(
                "Interval " + format() + " holds a month, which a Duration cannot hold"
            );
        }

        return Duration.ofDays(days).plus(microseconds, ChronoUnit.MICROS);
    }

    /** One signed field of the text, and {@code 0} where the text omits it. */
    private static int intField(String field) {
        return field == null
            ? 0
            : Integer.parseInt(field);
    }

    /**
     * The time part of the text as microseconds, and {@code 0} where the text
     * omits it.
     *
     * <p>The magnitude is accumulated as a negative count, which reaches one
     * further than a positive one does, so that the least time part PostgreSQL
     * writes, {@code -2562047788:00:54.775808}, is read exactly.
     */
    private static long timeMicroseconds(Matcher matcher) {
        String hours = matcher.group(5);

        if (hours == null) {
            return 0;
        }

        long negated = 0;

        negated = subtract(negated, Long.parseLong(hours), MICROS_PER_HOUR);
        negated = subtract(negated, Long.parseLong(matcher.group(6)), MICROS_PER_MINUTE);
        negated = subtract(negated, Long.parseLong(matcher.group(7)), MICROS_PER_SECOND);
        negated = subtract(negated, fractionMicroseconds(matcher.group(8)), 1);

        return "-".equals(matcher.group(4))
            ? negated
            : Math.negateExact(negated);
    }

    /** The fractional-second digits of the text as microseconds. */
    private static long fractionMicroseconds(String fraction) {
        if (fraction == null) {
            return 0;
        }

        long micros = Long.parseLong(fraction);

        for (int digit = fraction.length(); digit < MICRO_DIGITS; digit++) {
            micros *= 10;
        }

        return micros;
    }

    private static long subtract(long negated, long count, long microsPerUnit) {
        return Math.subtractExact(negated, Math.multiplyExact(count, microsPerUnit));
    }

    private static String unreadableText(String text) {
        return "Cannot read interval text '" + text + "';"
            + " sqlcj reads only the text of PostgreSQL's postgres IntervalStyle,"
            + " such as 1 year 2 mons -3 days +01:02:03.000004";
    }
}
