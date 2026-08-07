package com.cobre.notifications.infrastructure.adapter.in.web.dto;

import com.cobre.notifications.domain.model.PageResult;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.function.Function;

/**
 * Sobre de paginacion comun a los listados de la API.
 *
 * <p>Se pagina por cursor: para pedir la pagina siguiente se devuelve {@code next_cursor}
 * tal cual llego, en el parametro {@code cursor}. Cuando viene ausente, no queda nada mas.
 *
 * <p>No lleva total de elementos ni numero de paginas. Calcularlos exige recorrer todas
 * las notificaciones que cumplen el filtro, y ese recorrido cuesta mas que la propia
 * pagina que se esta sirviendo.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PagedResponse<T>(
        @JsonProperty("data") List<T> data,
        @JsonProperty("size") int size,
        @JsonProperty("next_cursor") String nextCursor,
        @JsonProperty("has_next") boolean hasNext) {

    public static <D, T> PagedResponse<T> from(PageResult<D> result, Function<D, T> mapper) {
        return new PagedResponse<>(
                result.items().stream().map(mapper).toList(),
                result.size(),
                result.nextCursor(),
                result.hasNext());
    }
}
