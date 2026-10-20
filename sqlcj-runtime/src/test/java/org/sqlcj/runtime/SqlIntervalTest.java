package org.sqlcj.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.DateTimeException;
import java.time.Duration;
import java.time.Period;
import java.time.temporal.ChronoUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the two directions of {@link SqlInterval}: the text PostgreSQL writes
 * under its default {@code postgres} {@code IntervalStyle}, which the record
 * reads, and the text the record writes, which PostgreSQL reads under every
 * style. The {@code java.time} conversions are covered beside them, each with
 * the component and the magnitude it cannot hold.
 */
class SqlIntervalTest {

    /**
     * Every form PostgreSQL 16 writes under {@code IntervalStyle postgres} is
     * read into the three counts it states: a zero value, the signed fields of
     * a mixed value, years and months alone, a month and a day, a negative
     * value with a time part, a sub-microsecond-free fraction of one digit, an
     * hour count beyond a day, the greatest day count, and the greatest and
     * least time parts the record holds.
     */
    @ParameterizedTest
    @CsvSource(
        delimiter = '|',
        value = {
            "00:00:00|0|0|0",
            "1 year 2 mons -3 days +01:02:03.000004|14|-3|3723000004",
            "-1 years -2 mons|-14|0|0",
            "1 mon -2 days|1|-2|0",
            "-1 mons +2 days -03:00:00|-1|2|-10800000000",
            "-00:00:00.000001|0|0|-1",
            "00:00:01.5|0|0|1500000",
            "100000:00:00|0|0|360000000000000",
            "2147483647 days|0|2147483647|0",
            "2562047788:00:54.775807|0|0|9223372036854775807",
            "-2562047788:00:54.775808|0|0|-9223372036854775808"
        }
    )
    void shouldParsePostgresStyleText(
        String text,
        int months,
        int days,
        long microseconds
    ) {
        assertEquals(
            new SqlInterval(months, days, microseconds),
            SqlInterval.parse(text)
        );
    }

    /**
     * PostgreSQL writes a field of one in the singular and every other field in
     * the plural, so both spellings of each field are read alike.
     */
    @ParameterizedTest
    @CsvSource(
        delimiter = '|',
        value = {
            "1 year 1 mon 1 day|13|1",
            "1 years 1 mons 1 days|13|1",
            "2 year 2 mon 2 day|26|2",
            "2 years 2 mons 2 days|26|2"
        }
    )
    void shouldParseSingularAndPluralFieldNames(String text, int months, int days) {
        assertEquals(
            new SqlInterval(months, days, 0),
            SqlInterval.parse(text)
        );
    }

    /** A SQL {@code NULL} column reads as no interval at all. */
    @Test
    void shouldParseNullTextAsNull() {
        assertNull(SqlInterval.parse(null));
    }

    /**
     * Text of any other {@code IntervalStyle} is rejected naming the text and
     * the one style that is read, because the counts such text states cannot be
     * told apart from the {@code postgres} form: a leading {@code -} of
     * {@code sql_standard} applies to every unsigned field after it.
     */
    @ParameterizedTest
    @ValueSource(
        strings = {
            "+1-2 -3 +1:02:03.000004",
            "-1-2",
            "P1Y2M-3DT1H2M3.000004S",
            "PT0S",
            "@ 1 year 2 mons -3 days +1 hour 2 mins 3.000004 secs",
            "@ 1 min ago",
            "",
            "1 year 2 months",
            "1 year 2 mons -3 days +01:02:03.000004 extra"
        }
    )
    void shouldRejectTextOfAnotherIntervalStyle(String text) {
        IllegalArgumentException exception = assertThrows(
            IllegalArgumentException.class,
            () -> SqlInterval.parse(text)
        );

        assertEquals(
            "Cannot read interval text '" + text + "';"
                + " sqlcj reads only the text of PostgreSQL's postgres IntervalStyle,"
                + " such as 1 year 2 mons -3 days +01:02:03.000004",
            exception.getMessage()
        );

        assertTrue(exception.getMessage().contains("IntervalStyle"));
    }

    /**
     * The text written states the three counts with an explicit sign on every
     * field, the time part as microseconds, so that PostgreSQL reads it alike
     * under every style and the least time part is spellable.
     */
    @ParameterizedTest
    @CsvSource(
        delimiter = '|',
        value = {
            "0|0|0|+0 mons +0 days +0 microseconds",
            "14|-3|3723000004|+14 mons -3 days +3723000004 microseconds",
            "-1|2|-10800000000|-1 mons +2 days -10800000000 microseconds",
            "0|0|-9223372036854775808|+0 mons +0 days -9223372036854775808 microseconds"
        }
    )
    void shouldFormatEveryFieldWithItsOwnSign(
        int months,
        int days,
        long microseconds,
        String text
    ) {
        assertEquals(
            text,
            new SqlInterval(months, days, microseconds).format()
        );
    }

    /**
     * A {@link Period} becomes its total months, its years counted as twelve
     * months each, with its own days and no time part.
     */
    @Test
    void shouldConvertFromPeriod() {
        assertEquals(
            new SqlInterval(14, -3, 0),
            SqlInterval.of(Period.of(1, 2, -3))
        );

        assertEquals(
            new SqlInterval(0, 0, 0),
            SqlInterval.of(Period.ZERO)
        );
    }

    /** A {@link Period} whose total months overflow a month count is rejected. */
    @Test
    void shouldRejectAPeriodWhoseMonthsOverflow() {
        assertThrows(
            ArithmeticException.class,
            () -> SqlInterval.of(Period.of(Integer.MAX_VALUE, 1, 0))
        );
    }

    /**
     * A {@link Duration} becomes microseconds alone, with no month and no day,
     * because a {@code Duration} day is exactly 24 hours and PostgreSQL's is
     * not. The whole microsecond range is converted, its least value included.
     */
    @Test
    void shouldConvertFromDuration() {
        assertEquals(
            new SqlInterval(0, 0, 90_000_004),
            SqlInterval.of(Duration.ofMinutes(1).plusSeconds(30).plusNanos(4_000))
        );

        assertEquals(
            new SqlInterval(0, 0, 86_400_000_000L),
            SqlInterval.of(Duration.ofDays(1))
        );

        assertEquals(
            new SqlInterval(0, 0, -1),
            SqlInterval.of(Duration.ofNanos(-1_000))
        );

        assertEquals(
            new SqlInterval(0, 0, Long.MIN_VALUE),
            SqlInterval.of(Duration.of(Long.MIN_VALUE, ChronoUnit.MICROS))
        );
    }

    /** A {@link Duration} holding a nanosecond part is rejected. */
    @Test
    void shouldRejectADurationHoldingASubMicrosecondPart() {
        assertThrows(
            DateTimeException.class,
            () -> SqlInterval.of(Duration.ofNanos(1))
        );

        assertThrows(
            DateTimeException.class,
            () -> SqlInterval.of(Duration.ofNanos(-1))
        );
    }

    /** A {@link Duration} longer than a microsecond count is rejected. */
    @Test
    void shouldRejectADurationWhoseMicrosecondsOverflow() {
        assertThrows(
            ArithmeticException.class,
            () -> SqlInterval.of(Duration.ofSeconds(Long.MAX_VALUE / 1_000_000 + 1))
        );
    }

    /**
     * An interval of months and days alone becomes a {@link Period} whose years
     * are taken from the month count, as PostgreSQL writes them.
     */
    @Test
    void shouldConvertToPeriod() {
        assertEquals(
            Period.of(1, 2, -3),
            new SqlInterval(14, -3, 0).toPeriod()
        );

        assertEquals(
            Period.of(-1, -2, 0),
            new SqlInterval(-14, 0, 0).toPeriod()
        );

        assertEquals(
            Period.ZERO,
            new SqlInterval(0, 0, 0).toPeriod()
        );
    }

    /** An interval holding a time part has no {@link Period}. */
    @Test
    void shouldRejectAPeriodOfAnIntervalHoldingATimePart() {
        DateTimeException exception = assertThrows(
            DateTimeException.class,
            () -> new SqlInterval(14, -3, 1).toPeriod()
        );

        assertEquals(
            "Interval +14 mons -3 days +1 microseconds"
                + " holds a time part, which a Period cannot hold",
            exception.getMessage()
        );
    }

    /**
     * An interval of days and a time part becomes a {@link Duration} counting
     * each day as 24 hours, as PostgreSQL compares two intervals.
     */
    @Test
    void shouldConvertToDuration() {
        assertEquals(
            Duration.ofDays(2).plusSeconds(3).plusNanos(4_000),
            new SqlInterval(0, 2, 3_000_004).toDuration()
        );

        assertEquals(
            Duration.ofDays(-2).minusSeconds(3),
            new SqlInterval(0, -2, -3_000_000).toDuration()
        );

        assertEquals(
            Duration.ZERO,
            new SqlInterval(0, 0, 0).toDuration()
        );
    }

    /** An interval holding a month has no {@link Duration}. */
    @Test
    void shouldRejectADurationOfAnIntervalHoldingAMonth() {
        DateTimeException exception = assertThrows(
            DateTimeException.class,
            () -> new SqlInterval(1, 0, 0).toDuration()
        );

        assertEquals(
            "Interval +1 mons +0 days +0 microseconds"
                + " holds a month, which a Duration cannot hold",
            exception.getMessage()
        );
    }
}
