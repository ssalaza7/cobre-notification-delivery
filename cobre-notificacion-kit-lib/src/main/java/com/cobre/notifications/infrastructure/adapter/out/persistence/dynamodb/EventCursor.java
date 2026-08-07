package com.cobre.notifications.infrastructure.adapter.out.persistence.dynamodb;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

import com.cobre.notifications.domain.exception.InvalidQueryException;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

/**
 * Traduce entre la clave de continuacion de DynamoDB y el cursor que viaja por la API.
 *
 * <p>Se codifica en Base64 para que sea opaco: un cursor legible invita a fabricarlo a
 * mano, y entonces su formato pasa a ser contrato publico y ya no se puede cambiar.
 *
 * <p>Al decodificar se comprueba que el cursor pertenezca al cliente que consulta. Sin
 * esa comprobacion, pasar el cursor de otro tenant seria una via para leer sus
 * notificaciones (OWASP A01), que es justo lo que el resto del diseno impide.
 */
final class EventCursor {

    private static final String PAIR_SEPARATOR = "\n";
    private static final String VALUE_SEPARATOR = "\t";

    private EventCursor() {
    }

    static String encode(Map<String, AttributeValue> lastEvaluatedKey) {
        if (lastEvaluatedKey == null || lastEvaluatedKey.isEmpty()) {
            return null;
        }
        StringBuilder plain = new StringBuilder();
        for (Map.Entry<String, AttributeValue> entry : lastEvaluatedKey.entrySet()) {
            if (!plain.isEmpty()) {
                plain.append(PAIR_SEPARATOR);
            }
            plain.append(entry.getKey()).append(VALUE_SEPARATOR).append(entry.getValue().s());
        }
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(plain.toString().getBytes(StandardCharsets.UTF_8));
    }

    /**
     * @param expectedClientPk particion que el cliente autenticado tiene permitido leer
     * @throws InvalidQueryException si el cursor esta corrupto o es de otro cliente
     */
    static Map<String, AttributeValue> decode(String cursor, String expectedClientPk) {
        Map<String, AttributeValue> key = new LinkedHashMap<>();
        try {
            String plain = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
            for (String pair : plain.split(PAIR_SEPARATOR, -1)) {
                String[] parts = pair.split(VALUE_SEPARATOR, 2);
                if (parts.length != 2) {
                    throw new IllegalArgumentException("par incompleto");
                }
                key.put(parts[0], NotificationTable.s(parts[1]));
            }
        } catch (RuntimeException e) {
            throw new InvalidQueryException("cursor no valido");
        }
        if (key.isEmpty()) {
            throw new InvalidQueryException("cursor no valido");
        }
        AttributeValue owner = key.get(NotificationTable.GSI_PK);
        if (owner == null || !expectedClientPk.equals(owner.s())) {
            throw new InvalidQueryException("cursor no valido");
        }
        return key;
    }
}
