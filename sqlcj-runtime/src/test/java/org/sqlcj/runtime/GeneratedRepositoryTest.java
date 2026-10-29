package org.sqlcj.runtime;

import org.junit.jupiter.api.Test;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers {@link GeneratedRepository}, whose retention, target, and absence of
 * members are what a reader of generated repositories relies on.
 */
class GeneratedRepositoryTest {

    @Test
    void shouldBeReadableAtRunTime() {
        assertTrue(GeneratedRepository.class.isAnnotation());

        assertEquals(
            RetentionPolicy.RUNTIME,
            GeneratedRepository.class.getAnnotation(Retention.class).value()
        );
    }

    @Test
    void shouldTargetTypesOnly() {
        assertArrayEquals(
            new ElementType[] { ElementType.TYPE },
            GeneratedRepository.class.getAnnotation(Target.class).value()
        );
    }

    @Test
    void shouldDeclareNoMembers() {
        assertEquals(0, GeneratedRepository.class.getDeclaredMethods().length);
    }
}
