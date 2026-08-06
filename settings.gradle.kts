rootProject.name = "notification-delivery-service"

// Monorepo: una libreria compartida y tres ejecutables independientes.
// Cada ejecutable declara solo las dependencias que usa, de modo que la API no
// arrastra el cliente de Kafka ni el worker la cadena de seguridad web.
include("common", "event-consumer", "delivery-worker", "monitoring-api")
