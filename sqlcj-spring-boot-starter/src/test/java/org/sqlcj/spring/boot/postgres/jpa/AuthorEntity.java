package org.sqlcj.spring.boot.postgres.jpa;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * The JPA view of the table a generated repository writes, in the
 * application's own package, so that Spring Boot's entity scan finds it.
 *
 * <p>Its key is assigned, so Hibernate defers its insert until a flush.
 */
@Entity
@Table(name = "authors")
public class AuthorEntity {

    @Id
    private Long id;

    private String name;

    public AuthorEntity() {
    }

    AuthorEntity(Long id, String name) {
        this.id = id;
        this.name = name;
    }
}
