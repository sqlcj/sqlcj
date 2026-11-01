package org.sqlcj.runtime;

import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

import java.lang.reflect.AnnotatedParameterizedType;
import java.lang.reflect.AnnotatedType;
import java.lang.reflect.AnnotatedWildcardType;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.TypeVariable;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The runtime states its nullness with JSpecify annotations, which a null
 * checker, an IDE, and a Kotlin caller read from the class files, so every
 * placement is read back by reflection exactly as they read it.
 */
class NullnessAnnotationsTest {

    /** One package mark covers every type of the runtime. */
    @Test
    void shouldMarkTheRuntimePackageAsNullMarked() {
        assertTrue(QueryExecutor.class.getPackage().isAnnotationPresent(NullMarked.class));
    }

    /**
     * Every argument list an executor binds may hold a null argument, and a
     * batch is a list of such lists.
     */
    @Test
    void shouldAcceptNullArgumentsInEveryExecutorOperation() {
        for (String operation : List.of("queryOne", "queryOptional", "queryMany", "execute")) {
            assertNullableElement(argumentListType(QueryExecutor.class, operation));
        }

        AnnotatedType batch = argumentListType(QueryExecutor.class, "executeBatch");

        assertNullableElement(elementType(batch));
    }

    /** The implementation states the same nullness as the interface. */
    @Test
    void shouldAcceptNullArgumentsInEveryJdbcExecutorOperation() {
        for (String operation : List.of("queryOne", "queryOptional", "queryMany", "execute")) {
            assertNullableElement(argumentListType(JdbcQueryExecutor.class, operation));
        }

        assertNullableElement(
            elementType(argumentListType(JdbcQueryExecutor.class, "executeBatch"))
        );
    }

    /** A row a query returns is never null, in the mapper and in the result. */
    @Test
    void shouldKeepRowsAndMappedValuesNonNull() {
        assertFalse(isNullable(method(RowMapper.class, "map").getAnnotatedReturnType()));
        assertFalse(isNullable(method(QueryExecutor.class, "queryOne").getAnnotatedReturnType()));
        assertFalse(
            isNullable(method(QueryExecutor.class, "queryOptional").getAnnotatedReturnType())
        );
        assertFalse(isNullable(method(QueryExecutor.class, "queryMany").getAnnotatedReturnType()));
    }

    /** A unit of work may return null, so its value type is nullable. */
    @Test
    void shouldAllowANullTransactionResult() {
        assertTrue(isNullable(typeParameterBound(TransactionCallback.class.getTypeParameters()[0])));

        Method inTransaction = method(JdbcTransactions.class, "inTransaction");

        assertTrue(isNullable(typeParameterBound(inTransaction.getTypeParameters()[0])));
    }

    /** A cardinality failure has no cause, so a cause may be null. */
    @Test
    void shouldAllowAQueryExecutionExceptionWithoutACause() throws Exception {
        Constructor<QueryExecutionException> constructor = QueryExecutionException.class
            .getConstructor(String.class, Throwable.class);

        assertTrue(isNullable(constructor.getAnnotatedParameterTypes()[1]));
    }

    /** An untyped text argument may be a SQL {@code NULL}. */
    @Test
    void shouldAllowANullUntypedTextValue() {
        assertTrue(isNullable(recordComponent(UntypedText.class, "value").getAnnotatedType()));
    }

    /**
     * An array value may be null and may hold null elements, in both
     * directions.
     */
    @Test
    void shouldAllowANullArrayAndNullArrayElements() {
        AnnotatedType elements = recordComponent(SqlArray.class, "elements").getAnnotatedType();

        assertTrue(isNullable(elements));
        assertNullableElement(elements);

        Method of = method(SqlArray.class, "of");

        assertTrue(isNullable(of.getAnnotatedParameterTypes()[1]));
        assertNullableElement(of.getAnnotatedParameterTypes()[1]);
        assertTrue(isNullable(typeArgument(of.getAnnotatedParameterTypes()[2], 1)));

        for (Method getList : methods(SqlArray.class, "getList")) {
            assertTrue(isNullable(getList.getAnnotatedReturnType()));
            assertNullableElement(getList.getAnnotatedReturnType());
        }
    }

    /** Interval text is read back from a column that may be {@code NULL}. */
    @Test
    void shouldReadAndWriteANullInterval() {
        Method parse = method(SqlInterval.class, "parse");

        assertTrue(isNullable(parse.getAnnotatedReturnType()));
        assertTrue(isNullable(parse.getAnnotatedParameterTypes()[0]));
    }

    /** An executor holds exactly one of a data source and a connection. */
    @Test
    void shouldStateThatOneExecutorSourceIsAlwaysNull() throws Exception {
        for (String name : List.of("dataSource", "connection")) {
            Field field = JdbcQueryExecutor.class.getDeclaredField(name);

            assertTrue(isNullable(field.getAnnotatedType()), name);
        }
    }

    /**
     * The argument list one executor operation binds, which is the fourth
     * parameter of every operation, after the repository, the query, and the
     * SQL.
     */
    private AnnotatedType argumentListType(Class<?> type, String operation) {
        return method(type, operation).getAnnotatedParameterTypes()[3];
    }

    private Method method(Class<?> type, String name) {
        return methods(type, name).get(0);
    }

    private List<Method> methods(Class<?> type, String name) {
        List<Method> found = Arrays.stream(type.getDeclaredMethods())
            .filter(method -> method.getName().equals(name))
            .toList();

        assertFalse(found.isEmpty(), name);

        return found;
    }

    private RecordComponent recordComponent(Class<?> type, String name) {
        for (RecordComponent component : type.getRecordComponents()) {
            if (component.getName().equals(name)) {
                return component;
            }
        }

        throw new IllegalArgumentException("No record component " + name + " in " + type);
    }

    /** The one type argument of a parameterized type, such as a list's. */
    private AnnotatedType elementType(AnnotatedType type) {
        return typeArgument(type, 0);
    }

    /**
     * One type argument of a parameterized type, read through a wildcard as
     * the bound the nullness annotation sits on.
     */
    private AnnotatedType typeArgument(AnnotatedType type, int index) {
        AnnotatedParameterizedType parameterized = (AnnotatedParameterizedType) bound(type);

        return bound(parameterized.getAnnotatedActualTypeArguments()[index]);
    }

    /**
     * Asserts that the element a parameterized type holds may be null, which a
     * wildcard states on its upper bound.
     */
    private void assertNullableElement(AnnotatedType type) {
        assertTrue(isNullable(elementType(type)), type.getType().toString());
    }

    /** The bound a nullness annotation sits on, for a wildcard its upper one. */
    private AnnotatedType bound(AnnotatedType type) {
        return type instanceof AnnotatedWildcardType wildcard
            ? wildcard.getAnnotatedUpperBounds()[0]
            : type;
    }

    private AnnotatedType typeParameterBound(TypeVariable<?> typeParameter) {
        return typeParameter.getAnnotatedBounds()[0];
    }

    private boolean isNullable(AnnotatedType type) {
        return type.getAnnotation(Nullable.class) != null;
    }
}
