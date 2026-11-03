package org.sqlcj.runtime;

import org.jspecify.annotations.Nullable;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.function.Function;

/**
 * Reads one column whose value the schema sqlcj generated from states
 * {@code NOT NULL}.
 *
 * <p>Generated code reads every component and single-column value the model
 * states non-null through this class, and every nullable one through
 * {@link SqlArray} or the JDBC {@link ResultSet} itself. The read therefore
 * never contradicts the nullness the generated API declares: where the
 * database returns SQL {@code NULL} all the same, which means the database and
 * the schema sqlcj read disagree, the read itself fails.
 *
 * <p>The failure is a {@link SQLException} without a SQLSTATE, because no
 * database reported it, whose message names the column's one-based position
 * and its label in the result. {@link JdbcQueryExecutor} translates it the way
 * it translates every other {@code SQLException} a row mapper raises: into a
 * plain {@link QueryExecutionException} naming the query and the repository,
 * with this exception as its cause.
 *
 * <p>A list this class reads is non-null, while its elements stay nullable,
 * because a {@code NOT NULL} array column may hold {@code NULL} elements.
 */
public final class NotNullColumn {

    private NotNullColumn() {
    }

    /**
     * Reads the column at the one-based {@code position} as {@code type}, the
     * way {@link ResultSet#getObject(int, Class)} reads it, and fails when it
     * is SQL {@code NULL}.
     */
    public static <T> T getObject(
        ResultSet resultSet,
        int position,
        Class<T> type
    ) throws SQLException {
        return requireValue(resultSet.getObject(position, type), resultSet, position);
    }

    /**
     * Reads the column at the one-based {@code position} as text, the way
     * {@link ResultSet#getString(int)} reads it, and fails when it is SQL
     * {@code NULL}.
     */
    public static String getString(ResultSet resultSet, int position) throws SQLException {
        return requireValue(resultSet.getString(position), resultSet, position);
    }

    /**
     * Reads the column at the one-based {@code position} as text the same way
     * and converts it with {@code converter}, such as a label into a generated
     * enum constant or PostgreSQL's interval text into a
     * {@link SqlInterval}. The read fails when the column is SQL {@code NULL},
     * and also when {@code converter} returns {@code null} for its text,
     * because the value the component holds would be {@code null} either way.
     */
    public static <T> T getString(
        ResultSet resultSet,
        int position,
        Function<? super String, ? extends @Nullable T> converter
    ) throws SQLException {
        return requireValue(
            converter.apply(getString(resultSet, position)),
            resultSet,
            position
        );
    }

    /**
     * Reads the array column at the one-based {@code position} as a list of
     * {@code elementType} values, the way
     * {@link SqlArray#getList(ResultSet, int, Class)} reads it, and fails when
     * the column is SQL {@code NULL}. A {@code NULL} element stays a
     * {@code null} element.
     */
    public static <T> List<@Nullable T> getList(
        ResultSet resultSet,
        int position,
        Class<T> elementType
    ) throws SQLException {
        return requireValue(
            SqlArray.getList(resultSet, position, elementType),
            resultSet,
            position
        );
    }

    /**
     * Reads the array column at the one-based {@code position} the same way and
     * converts each element that is not {@code null} with {@code converter},
     * the way {@link SqlArray#getList(ResultSet, int, Class, Function)} reads
     * it, and fails when the column is SQL {@code NULL}.
     */
    public static <E, T> List<@Nullable T> getList(
        ResultSet resultSet,
        int position,
        Class<E> elementType,
        Function<? super E, ? extends @Nullable T> converter
    ) throws SQLException {
        return requireValue(
            SqlArray.getList(resultSet, position, elementType, converter),
            resultSet,
            position
        );
    }

    /**
     * The value itself, or a failure naming the column it was read from when it
     * is absent.
     */
    private static <T> T requireValue(
        @Nullable T value,
        ResultSet resultSet,
        int position
    ) throws SQLException {
        if (value == null) {
            throw new SQLException(
                "Column %d (%s) is NULL, but the schema sqlcj generated from declares it NOT NULL"
                    .formatted(position, resultSet.getMetaData().getColumnLabel(position))
            );
        }

        return value;
    }
}
