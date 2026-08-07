package com.cobre.notifications.infrastructure.adapter.in.web.dto;

import com.cobre.notifications.domain.model.PageResult;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.function.Function;

/** Sobre de paginacion comun a los listados de la API. */
public record PagedResponse<T>(
        @JsonProperty("data") List<T> data,
        @JsonProperty("page") int page,
        @JsonProperty("size") int size,
        @JsonProperty("total_elements") long totalElements,
        @JsonProperty("total_pages") int totalPages,
        @JsonProperty("has_next") boolean hasNext) {

    public static <D, T> PagedResponse<T> from(PageResult<D> result, Function<D, T> mapper) {
        return new PagedResponse<>(
                result.items().stream().map(mapper).toList(),
                result.page(),
                result.size(),
                result.totalElements(),
                result.totalPages(),
                result.hasNext());
    }
}
