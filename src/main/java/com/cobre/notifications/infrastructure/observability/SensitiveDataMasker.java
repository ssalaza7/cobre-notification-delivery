package com.cobre.notifications.infrastructure.observability;

import java.util.Set;

/**
 * Enmascara valores sensibles antes de que lleguen al log.
 *
 * <p>El servicio no pone secretos en la URL: el token se pide por POST y el secreto viaja
 * en el cuerpo. Pero el log de acceso registra lo que el <b>cliente</b> envio, y sobre eso
 * no hay control: basta que alguien integre mal y mande {@code ?access_token=...} para que
 * ese valor quede indexado en Elasticsearch, replicado en cada backup y visible para
 * cualquiera con acceso a Kibana.
 *
 * <p>Un secreto en un log es un secreto filtrado: no se puede borrar de los indices ya
 * replicados, y obliga a rotarlo. Enmascarar en el punto de escritura es la unica barrera
 * que no depende de que todos los integradores hagan las cosas bien.
 */
public final class SensitiveDataMasker {

    private static final String MASK = "***";

    /**
     * Nombres de parametro cuyo valor nunca debe quedar registrado.
     *
     * <p>Se compara en minusculas y por coincidencia parcial, de modo que {@code secret}
     * cubra tambien {@code client_secret} y {@code signing_secret}.
     */
    private static final Set<String> SENSITIVE = Set.of(
            "secret", "password", "token", "authorization", "signature", "api_key", "apikey", "key");

    private SensitiveDataMasker() {
    }

    /**
     * Devuelve la ruta con los valores sensibles de la query sustituidos.
     *
     * <p>Se conserva el <b>nombre</b> del parametro y se tapa solo el valor: saber que
     * alguien mando un {@code client_secret} por la URL es justamente lo que permite
     * avisarle de que corrija la integracion.
     */
    public static String maskQuery(String path, String query) {
        if (query == null || query.isBlank()) {
            return path;
        }
        StringBuilder masked = new StringBuilder(path).append('?');
        String[] params = query.split("&");
        for (int i = 0; i < params.length; i++) {
            if (i > 0) {
                masked.append('&');
            }
            masked.append(maskParam(params[i]));
        }
        return masked.toString();
    }

    private static String maskParam(String param) {
        int separator = param.indexOf('=');
        if (separator < 0) {
            return param;
        }
        String name = param.substring(0, separator);
        return isSensitive(name) ? name + "=" + MASK : param;
    }

    private static boolean isSensitive(String name) {
        String lower = name.toLowerCase(java.util.Locale.ROOT);
        return SENSITIVE.stream().anyMatch(lower::contains);
    }
}
