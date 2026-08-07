package com.cobre.notifications.domain.model;

import java.util.List;

/**
 * Pagina de resultados con el punto donde continuar.
 *
 * <p>No lleva total de elementos: contar cuantas notificaciones cumplen un filtro exige
 * recorrerlas todas, y ese recorrido costaria mas que la propia pagina. Para saber si
 * queda algo mas basta con {@link #hasNext()}.
 *
 * @param nextCursor valor que hay que devolver para pedir la pagina siguiente, o
 *                   {@code null} si esta era la ultima
 */
public record PageResult<T>(List<T> items, int size, String nextCursor) {

    public PageResult {
        items = List.copyOf(items);
    }

    public boolean hasNext() {
        return nextCursor != null;
    }
}
