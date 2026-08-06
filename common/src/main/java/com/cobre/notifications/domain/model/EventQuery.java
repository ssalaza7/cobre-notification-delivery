package com.cobre.notifications.domain.model;

import com.cobre.notifications.domain.exception.InvalidQueryException;

import java.time.Instant;

/**
 * Criterios de consulta de la API self-service.
 *
 * <p>{@code clientId} no es opcional por diseno: es el tenant dueno de los datos y
 * se toma siempre del token, nunca de la peticion. Modelarlo como campo obligatorio
 * hace imposible construir una consulta sin acotar por cliente, que es la falla de
 * autorizacion a nivel de objeto (OWASP A01) mas comun en este tipo de API.
 */
public record EventQuery(
        String clientId,
        Instant createdFrom,
        Instant createdTo,
        DeliveryStatus deliveryStatus,
        int page,
        int size) {

    public static final int MAX_PAGE_SIZE = 100;
    public static final int DEFAULT_PAGE_SIZE = 20;

    public EventQuery {
        if (clientId == null || clientId.isBlank()) {
            throw new InvalidQueryException("client_id es obligatorio");
        }
        if (page < 0) {
            throw new InvalidQueryException("page no puede ser negativo");
        }
        if (size < 1 || size > MAX_PAGE_SIZE) {
            throw new InvalidQueryException("size debe estar entre 1 y " + MAX_PAGE_SIZE);
        }
        if (createdFrom != null && createdTo != null && createdFrom.isAfter(createdTo)) {
            throw new InvalidQueryException("created_from no puede ser posterior a created_to");
        }
    }

    public long offset() {
        return (long) page * size;
    }
}
