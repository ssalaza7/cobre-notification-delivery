rootProject.name = "notification-delivery-service"

// Tres servicios desplegables y una libreria que comparten.
//
// El sufijo -service marca lo que se despliega: cada uno produce su propio jar
// ejecutable y su propio contenedor. La libreria viaja dentro de los tres.
include(
    "cobre-notificacion-consumer-service",
    "cobre-notificacion-worker-service",
    "cobre-notificacion-api-service",
    "cobre-notificacion-kit",
)
