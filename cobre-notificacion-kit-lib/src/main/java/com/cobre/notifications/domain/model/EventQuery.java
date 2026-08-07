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
 *
 * <p>La paginacion es por cursor y no por numero de pagina. Un numero de pagina obliga
 * a saltar las anteriores para llegar a la pedida, de modo que la pagina 50 cuesta
 * cincuenta veces la primera; el cursor apunta directamente a donde se quedo la
 * anterior y todas cuestan lo mismo. A cambio no se puede saltar a una pagina
 * arbitraria ni conocer el total sin recorrerlo todo.
 *
 * @param cursor punto donde continuar, tal como lo devolvio la consulta anterior.
 *               Vacio en la primera pagina. Es opaco: su formato es asunto del
 *               adaptador de persistencia y el cliente solo lo devuelve como llego
 */
public record EventQuery(
        String clientId,
        Instant createdFrom,
        Instant createdTo,
        DeliveryStatus deliveryStatus,
        String cursor,
        int size) {

    public static final int MAX_PAGE_SIZE = 100;
    public static final int DEFAULT_PAGE_SIZE = 20;

    public EventQuery {
        if (clientId == null || clientId.isBlank()) {
            throw new InvalidQueryException("client_id es obligatorio");
        }
        if (size < 1 || size > MAX_PAGE_SIZE) {
            throw new InvalidQueryException("size debe estar entre 1 y " + MAX_PAGE_SIZE);
        }
        if (createdFrom != null && createdTo != null && createdFrom.isAfter(createdTo)) {
            throw new InvalidQueryException("created_from no puede ser posterior a created_to");
        }
        cursor = cursor == null || cursor.isBlank() ? null : cursor;
    }

    public boolean isFirstPage() {
        return cursor == null;
    }
}
