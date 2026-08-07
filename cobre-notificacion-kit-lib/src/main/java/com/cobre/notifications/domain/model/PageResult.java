package com.cobre.notifications.domain.model;

import java.util.List;

/** Pagina de resultados con el total, para que el cliente pueda paginar sin adivinar. */
public record PageResult<T>(List<T> items, int page, int size, long totalElements) {

    public PageResult {
        items = List.copyOf(items);
    }

    public int totalPages() {
        return size == 0 ? 0 : (int) Math.ceil((double) totalElements / size);
    }

    public boolean hasNext() {
        return (long) (page + 1) * size < totalElements;
    }
}
