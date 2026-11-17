package org.sqlcj.analysis;

import org.sqlcj.schema.ColumnType;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The fixed catalogue of built-in PostgreSQL functions whose projected result
 * sqlcj types without a cast, and the type and nullness each returns for the
 * arguments it is called with.
 *
 * <p>The catalogue is syntax-free and schema-free: it reads one call's name,
 * its form, and the facts its caller analyzed for each argument — a type or
 * none, and whether the argument reads no {@code NULL} — and reports the result
 * type and nullness, or none for a call it types no result of. Resolving a
 * column, a nested call, or a placeholder against the query's sources belongs to
 * the analyzer that calls it.
 *
 * <p>A result type is either fixed, as {@code now()} and {@code row_number()}
 * are, or the common type of the arguments the result depends on, as
 * {@code coalesce} and {@code lag} are. Every type the catalogue reports is one
 * PostgreSQL 16 returns for the accepted arguments, so a call whose arguments do
 * not state it is left untyped rather than guessed.
 *
 * <p>Nullness follows PostgreSQL's own guarantee: a function that never returns
 * {@code NULL} is non-null, a strict function is non-null when every argument
 * reads no {@code NULL}, {@code coalesce}, {@code greatest}, and {@code least}
 * are non-null when at least one argument does, and a window function that
 * reads another row is always nullable. An argument with no type is read as
 * nullable, because nothing states what it returns.
 *
 * <p>{@link #holds} reports whether one name is a catalogue call at all, so
 * that the arguments of every other call are left to the rules of the
 * projection item they stand in.
 */
final class FunctionCatalogue {

    /** The text types {@code lower}, {@code upper}, and {@code trim} take. */
    private static final Set<ColumnType> TEXT_TYPES = Set.of(
        ColumnType.TEXT,
        ColumnType.VARCHAR,
        ColumnType.CITEXT
    );

    /** The key types the one-argument advisory lock takes. */
    private static final Set<ColumnType> LOCK_KEY_TYPES = Set.of(
        ColumnType.SMALLINT,
        ColumnType.INTEGER,
        ColumnType.BIGINT
    );

    /**
     * The key types the two-argument advisory lock takes, which is also the
     * type set of a {@code lag} or {@code lead} offset.
     */
    private static final Set<ColumnType> LOCK_KEY_PAIR_TYPES = Set.of(
        ColumnType.SMALLINT,
        ColumnType.INTEGER
    );

    /** The value types {@code date_trunc} truncates. */
    private static final Set<ColumnType> TRUNCATED_TYPES = Set.of(
        ColumnType.TIMESTAMP,
        ColumnType.TIMESTAMP_WITH_TIME_ZONE,
        ColumnType.DATE,
        ColumnType.INTERVAL
    );

    /**
     * The scalar numeric types a common type widens through, widest last, which
     * is the order PostgreSQL 16 resolves two numeric arguments in.
     */
    private static final List<ColumnType> NUMERIC_WIDENING = List.of(
        ColumnType.SMALLINT,
        ColumnType.INTEGER,
        ColumnType.BIGINT,
        ColumnType.DECIMAL,
        ColumnType.REAL,
        ColumnType.DOUBLE_PRECISION
    );

    private static final ResultType TEXT = new ResultType(ColumnType.TEXT);

    private static final ResultType BIGINT = new ResultType(ColumnType.BIGINT);

    private static final ResultType BOOLEAN = new ResultType(ColumnType.BOOLEAN);

    private static final ResultType UUID = new ResultType(ColumnType.UUID);

    private static final ResultType TIMESTAMP = new ResultType(ColumnType.TIMESTAMP);

    private static final ResultType TIMESTAMP_WITH_TIME_ZONE = new ResultType(
        ColumnType.TIMESTAMP_WITH_TIME_ZONE
    );

    private static final ResultType INTERVAL = new ResultType(ColumnType.INTERVAL);

    /** The rule of each plain call of the catalogue, by its own name. */
    private final Map<String, Rule> plainCalls = Map.ofEntries(
        Map.entry("now", arguments -> fixed(arguments, TIMESTAMP_WITH_TIME_ZONE)),
        Map.entry("gen_random_uuid", arguments -> fixed(arguments, UUID)),
        Map.entry("nextval", this::advanced),
        Map.entry("pg_try_advisory_xact_lock", this::locked),
        Map.entry("lower", this::cased),
        Map.entry("upper", this::cased),
        Map.entry("trim", this::trimmed),
        Map.entry("date_trunc", this::truncated),
        Map.entry("coalesce", this::chosen),
        Map.entry("greatest", this::orderedChoice),
        Map.entry("least", this::orderedChoice)
    );

    /**
     * The rule of each window call of the catalogue, by its own name.
     * {@code lag}, {@code lead}, {@code first_value}, and {@code last_value}
     * read a row of the window frame, which may be absent, so each is nullable
     * however its arguments read.
     */
    private final Map<String, Rule> windowCalls = Map.of(
        "row_number", arguments -> fixed(arguments, BIGINT),
        "rank", arguments -> fixed(arguments, BIGINT),
        "dense_rank", arguments -> fixed(arguments, BIGINT),
        "lag", this::shifted,
        "lead", this::shifted,
        "first_value", this::valued,
        "last_value", this::valued
    );

    /**
     * Reports whether the catalogue holds one called name in one form, which
     * its caller reads before it analyzes any argument, so that a call of every
     * other name keeps the rules it has without this catalogue.
     */
    boolean holds(String name, Form form) {
        return rules(form).containsKey(name.toLowerCase(Locale.ROOT));
    }

    /**
     * The result of one catalogue call, and {@code null} for a call this
     * catalogue types no result of, which its caller leaves to a cast.
     *
     * @param name      the called name, which is matched case-insensitively
     * @param form      the form the query writes the call in
     * @param arguments the analyzed arguments, in the order the call writes them
     */
    Result result(String name, Form form, List<Argument> arguments) {
        Rule rule = rules(form).get(name.toLowerCase(Locale.ROOT));

        return rule == null
            ? null
            : rule.result(arguments);
    }

    /**
     * The type one uncast placeholder has at one argument position of a
     * catalogue call, and {@code null} at every position that states none: a
     * position of another call, of another number of arguments, or whose type
     * PostgreSQL 16 takes from the arguments rather than from the position
     * itself, such as a {@code coalesce} argument or the value {@code lag}
     * reads.
     *
     * <p>These are the types {@link #accepted} reads such a placeholder as, so
     * a call of a placeholder typed here states the result it states for a
     * typed argument of the same type.
     *
     * @param name     the called name, which is matched case-insensitively
     * @param form     the form the query writes the call in
     * @param arity    the number of arguments the call writes
     * @param position the zero-based position of the argument among them
     */
    ColumnType placeholderType(String name, Form form, int arity, int position) {
        if (position < 0 || position >= arity) {
            return null;
        }

        String called = name.toLowerCase(Locale.ROOT);

        if (form == Form.WINDOW) {
            return isShifting(called) && arity <= 3 && position == 1
                ? ColumnType.INTEGER
                : null;
        }

        return switch (called) {
            case "lower", "upper" -> arity == 1
                ? ColumnType.TEXT
                : null;
            case "trim" -> arity <= 2
                ? ColumnType.TEXT
                : null;
            case "date_trunc" -> arity >= 2 && arity <= 3 && position != 1
                ? ColumnType.TEXT
                : null;
            case "pg_try_advisory_xact_lock" -> switch (arity) {
                case 1 -> ColumnType.BIGINT;
                case 2 -> ColumnType.INTEGER;
                default -> null;
            };
            default -> null;
        };
    }

    /** Reports whether one name is a window call that reads a shifted row. */
    private boolean isShifting(String name) {
        return "lag".equals(name) || "lead".equals(name);
    }

    private Map<String, Rule> rules(Form form) {
        return form == Form.WINDOW
            ? windowCalls
            : plainCalls;
    }

    /**
     * The result of one call that takes no argument and never returns
     * {@code NULL}, and {@code null} where the query writes an argument.
     */
    private Result fixed(List<Argument> arguments, ResultType type) {
        return arguments.isEmpty()
            ? new Result(type, true)
            : null;
    }

    /**
     * The result of {@code nextval}, which advances the sequence its argument
     * names. The name reaches the database as written, so no type is read from
     * it and a typed argument, which names no sequence, leaves the call
     * untyped.
     */
    private Result advanced(List<Argument> arguments) {
        return strict(BIGINT, accepted(arguments, 1, Set.of(), null));
    }

    /**
     * The result of {@code pg_try_advisory_xact_lock}, which takes one
     * {@code bigint} key or two {@code integer} ones.
     */
    private Result locked(List<Argument> arguments) {
        List<Argument> keys = switch (arguments.size()) {
            case 1 -> accepted(arguments, 1, LOCK_KEY_TYPES, ColumnType.BIGINT);
            case 2 -> accepted(arguments, 2, LOCK_KEY_PAIR_TYPES, ColumnType.INTEGER);
            default -> null;
        };

        return strict(BOOLEAN, keys);
    }

    /** The result of {@code lower} and {@code upper}, which is a text of a text. */
    private Result cased(List<Argument> arguments) {
        return strict(TEXT, accepted(arguments, 1, TEXT_TYPES, ColumnType.TEXT));
    }

    /**
     * The result of {@code first_value} and {@code last_value}, which is the
     * type of the one value they read.
     */
    private Result valued(List<Argument> arguments) {
        return arguments.size() == 1
            ? nullable(commonType(arguments, null))
            : null;
    }

    /**
     * The result of {@code trim}, whose characters and string are each accepted
     * as {@code lower}'s argument is, in either of the spellings the parser
     * reports as one or two arguments.
     */
    private Result trimmed(List<Argument> arguments) {
        if (arguments.isEmpty() || arguments.size() > 2) {
            return null;
        }

        return strict(
            TEXT,
            accepted(arguments, arguments.size(), TEXT_TYPES, ColumnType.TEXT)
        );
    }

    /**
     * The result of {@code date_trunc}, whose unit and time zone are accepted as
     * {@code lower}'s argument is, and whose result type PostgreSQL 16 takes
     * from the truncated value and from whether a time zone is written: a
     * {@code timestamp} value alone stays a {@code timestamp}, an
     * {@code interval} stays an {@code interval} and takes no time zone, and
     * every other accepted value, a time zone included, is a
     * {@code timestamptz}.
     */
    private Result truncated(List<Argument> arguments) {
        if (arguments.size() < 2 || arguments.size() > 3) {
            return null;
        }

        List<Argument> units = new ArrayList<>();

        units.add(arguments.get(0));

        if (arguments.size() == 3) {
            units.add(arguments.get(2));
        }

        List<Argument> accepted = accepted(units, units.size(), TEXT_TYPES, ColumnType.TEXT);

        if (accepted == null) {
            return null;
        }

        ResultType truncated = truncatedType(arguments.get(1), arguments.size() == 3);

        if (truncated == null) {
            return null;
        }

        List<Argument> read = new ArrayList<>(accepted);

        read.add(arguments.get(1));

        return strict(truncated, read);
    }

    /**
     * The type one truncated value states, and {@code null} for a value
     * {@code date_trunc} states no result type for: one of another type, one of
     * no type, and an {@code interval} written beside a time zone, which
     * PostgreSQL 16 defines no {@code date_trunc} for.
     */
    private ResultType truncatedType(Argument value, boolean zoned) {
        ColumnType type = scalarType(value);

        if (type == null || !TRUNCATED_TYPES.contains(type)) {
            return null;
        }

        if (type == ColumnType.INTERVAL) {
            return zoned
                ? null
                : INTERVAL;
        }

        return type == ColumnType.TIMESTAMP && !zoned
            ? TIMESTAMP
            : TIMESTAMP_WITH_TIME_ZONE;
    }

    /**
     * The result of {@code coalesce}, which is the common type of its arguments
     * and reads no {@code NULL} as soon as one argument does.
     */
    private Result chosen(List<Argument> arguments) {
        if (arguments.isEmpty()) {
            return null;
        }

        ResultType type = commonType(arguments, TEXT);

        return type == null
            ? null
            : new Result(type, arguments.stream().anyMatch(Argument::nonNull));
    }

    /**
     * The result of {@code greatest} and {@code least}, which are
     * {@code coalesce}'s except that PostgreSQL 16 orders no {@code json}
     * value, so a {@code json} argument leaves the call untyped.
     */
    private Result orderedChoice(List<Argument> arguments) {
        boolean json = arguments.stream()
            .map(Argument::type)
            .anyMatch(type -> type != null && type.type() == ColumnType.JSON);

        return json
            ? null
            : chosen(arguments);
    }

    /**
     * The result of {@code lag} and {@code lead}: the common type of the read
     * value and the default beside it, with the offset between them accepted as
     * a {@code smallint} or an {@code integer}, which PostgreSQL 16 requires.
     */
    private Result shifted(List<Argument> arguments) {
        if (arguments.isEmpty() || arguments.size() > 3) {
            return null;
        }

        if (
            arguments.size() > 1
                && accepted(
                    arguments.subList(1, 2),
                    1,
                    LOCK_KEY_PAIR_TYPES,
                    ColumnType.INTEGER
                ) == null
        ) {
            return null;
        }

        List<Argument> valued = new ArrayList<>();

        valued.add(arguments.get(0));

        if (arguments.size() == 3) {
            valued.add(arguments.get(2));
        }

        return nullable(commonType(valued, null));
    }

    /**
     * The arguments of one call whose result does not depend on them, each read
     * at a position that accepts {@code types} and states
     * {@code placeholderType} for a placeholder, and {@code null} where the
     * argument list is not that many arguments or an argument is one such a
     * position does not accept.
     *
     * <p>A typed argument of another type leaves the call untyped, because
     * PostgreSQL 16 defines no such call. An untyped literal is accepted, as
     * PostgreSQL resolves one at these positions, and so is an argument of no
     * type, which reaches the database as written and is read as nullable; an
     * uncast placeholder takes the type the position states.
     */
    private List<Argument> accepted(
        List<Argument> arguments,
        int count,
        Set<ColumnType> types,
        ColumnType placeholderType
    ) {
        if (arguments.size() != count) {
            return null;
        }

        List<Argument> accepted = new ArrayList<>();

        for (Argument argument : arguments) {
            switch (argument.kind()) {
                case TYPED -> {
                    ColumnType type = scalarType(argument);

                    if (type == null || !types.contains(type)) {
                        return null;
                    }

                    accepted.add(argument);
                }
                case PLACEHOLDER -> accepted.add(
                    placeholderType == null
                        ? Argument.untyped()
                        : Argument.typed(new ResultType(placeholderType), argument.nonNull())
                );
                default -> accepted.add(argument);
            }
        }

        return accepted;
    }

    /**
     * The common type of the arguments one result depends on, {@code none} where
     * no argument is typed, and {@code null} where they resolve to no single
     * type.
     *
     * <p>An untyped literal takes no part, because PostgreSQL resolves it to the
     * type the other arguments state, while an argument of no type, an uncast
     * placeholder at a position that states none included, leaves the call
     * untyped. Arguments of one type, their enum type, array spelling, and
     * blank-padded spelling included, have that type; scalar numeric arguments
     * of different types widen to the widest of them, which is PostgreSQL's own
     * resolution; every other mix is left untyped, because PostgreSQL resolves
     * it by argument order, which no single type states.
     */
    private ResultType commonType(List<Argument> arguments, ResultType none) {
        List<ResultType> typed = new ArrayList<>();

        for (Argument argument : arguments) {
            switch (argument.kind()) {
                case TYPED -> typed.add(argument.type());
                case LITERAL -> {
                }
                default -> {
                    return null;
                }
            }
        }

        if (typed.isEmpty()) {
            return none;
        }

        ResultType first = typed.get(0);

        if (typed.stream().allMatch(first::equals)) {
            return first;
        }

        int widest = -1;

        for (ResultType type : typed) {
            int rank = type.array() || type.type() == ColumnType.ENUM
                ? -1
                : NUMERIC_WIDENING.indexOf(type.type());

            if (rank < 0) {
                return null;
            }

            widest = Math.max(widest, rank);
        }

        return new ResultType(NUMERIC_WIDENING.get(widest));
    }

    /**
     * The result of one strict function of the given arguments, which reads no
     * {@code NULL} exactly where every argument does, and {@code null} where the
     * arguments are not the ones it accepts.
     */
    private Result strict(ResultType type, List<Argument> arguments) {
        return arguments == null
            ? null
            : new Result(type, arguments.stream().allMatch(Argument::nonNull));
    }

    /** One nullable result of the given type, and none where it has none. */
    private Result nullable(ResultType type) {
        return type == null
            ? null
            : new Result(type, false);
    }

    /**
     * The scalar type one argument states, and {@code null} for an argument of
     * no type and for one whose type is an array or an enum type, which no
     * position of a fixed type set accepts.
     */
    private ColumnType scalarType(Argument argument) {
        ResultType type = argument.type();

        return type == null || type.array() || type.type() == ColumnType.ENUM
            ? null
            : type.type();
    }

    /** The rule one catalogue call states its result by. */
    private interface Rule {

        /**
         * The result of this call for the given arguments, and {@code null}
         * where they are not the arguments it accepts.
         */
        Result result(List<Argument> arguments);
    }

    /** The form one call is written in, which decides the names it may carry. */
    enum Form {

        /** A call written as a plain function call, such as {@code lower(s)}. */
        PLAIN,

        /** A call written with {@code OVER}, such as {@code rank() OVER ()}. */
        WINDOW
    }

    /** What one analyzed argument states about its type. */
    enum Kind {

        /** An argument of a known type, such as a column of a mapped type. */
        TYPED,

        /**
         * An untyped literal — a string literal or {@code NULL} — which takes
         * the type its position or the other arguments state.
         */
        LITERAL,

        /**
         * An uncast placeholder, which takes the type its position states and
         * has none at a position that states one for no placeholder.
         */
        PLACEHOLDER,

        /** An argument of no type, which reaches the database as written. */
        UNTYPED
    }

    /**
     * One type a catalogue call reports, written as a result column carries it:
     * the column type, the declared enum type it names, whether it is an array
     * of that type, and whether its declared spelling is blank padded.
     */
    record ResultType(ColumnType type, String enumType, boolean array, boolean blankPadded) {

        /** One scalar type of no enum type and no blank-padded spelling. */
        ResultType(ColumnType type) {
            this(type, null, false, false);
        }
    }

    /**
     * The result of one catalogue call: its type and whether it reads no
     * {@code NULL}.
     */
    record Result(ResultType type, boolean nonNull) {
    }

    /**
     * One analyzed argument of a catalogue call: what it states about its type,
     * that type where it states one, and whether it reads no {@code NULL}.
     */
    record Argument(Kind kind, ResultType type, boolean nonNull) {

        /** One argument of a known type. */
        static Argument typed(ResultType type, boolean nonNull) {
            return new Argument(Kind.TYPED, type, nonNull);
        }

        /**
         * One untyped literal, which is non-null unless it is the {@code NULL}
         * literal itself.
         */
        static Argument literal(boolean nonNull) {
            return new Argument(Kind.LITERAL, null, nonNull);
        }

        /** One uncast placeholder, typed by the position it stands at. */
        static Argument placeholder(boolean nonNull) {
            return new Argument(Kind.PLACEHOLDER, null, nonNull);
        }

        /** One argument of no type, which is read as nullable. */
        static Argument untyped() {
            return new Argument(Kind.UNTYPED, null, false);
        }
    }
}
