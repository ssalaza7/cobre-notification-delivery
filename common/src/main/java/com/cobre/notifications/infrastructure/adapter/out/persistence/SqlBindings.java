package com.cobre.notifications.infrastructure.adapter.out.persistence;

import org.springframework.r2dbc.core.DatabaseClient;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;

/**
 * Utilidades de enlace de parametros compartidas por los adaptadores de persistencia.
 *
 * <p>Todo valor variable viaja como parametro enlazado, nunca concatenado al SQL. Los
 * unicos fragmentos que se concatenan son nombres de columna fijos escritos en el
 * codigo, jamas entrada del usuario: asi la construccion dinamica de filtros no abre
 * la puerta a inyeccion SQL (OWASP A03).
 */
final class SqlBindings {

    private SqlBindings() {
    }

    /** Postgres devuelve TIMESTAMPTZ como OffsetDateTime; el dominio trabaja en Instant. */
    static OffsetDateTime toDb(Instant instant) {
        return instant == null ? null : instant.atOffset(ZoneOffset.UTC);
    }

    static Instant toDomain(OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }

    /**
     * Aplica los parametros al spec distinguiendo nulos, que en R2DBC requieren
     * {@code bindNull} con el tipo declarado.
     */
    static DatabaseClient.GenericExecuteSpec bindAll(
            DatabaseClient.GenericExecuteSpec spec, Map<String, TypedValue> params) {
        DatabaseClient.GenericExecuteSpec bound = spec;
        for (Map.Entry<String, TypedValue> entry : params.entrySet()) {
            TypedValue typed = entry.getValue();
            bound = typed.value() == null
                    ? bound.bindNull(entry.getKey(), typed.type())
                    : bound.bind(entry.getKey(), typed.value());
        }
        return bound;
    }

    /** Valor con su tipo declarado, necesario para poder enlazar nulos. */
    record TypedValue(Object value, Class<?> type) {

        static TypedValue of(String value) {
            return new TypedValue(value, String.class);
        }

        static TypedValue of(Integer value) {
            return new TypedValue(value, Integer.class);
        }

        static TypedValue of(Long value) {
            return new TypedValue(value, Long.class);
        }

        static TypedValue timestamp(Instant value) {
            return new TypedValue(toDb(value), OffsetDateTime.class);
        }
    }
}
