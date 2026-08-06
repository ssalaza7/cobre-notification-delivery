rootProject.name = "notification-delivery-service"

// Tres servicios desplegables y dos librerias que comparten.
//
// El sufijo -service marca lo que se despliega: cada uno produce su propio jar
// ejecutable y su propio contenedor. Lo que no lo lleva viaja dentro de los tres.
include(
    "cobre-notificacion-consumer-service",
    "cobre-notificacion-worker-service",
    "cobre-notificacion-api-service",
    "cobre-notificacion-domain",
    "cobre-notificacion-kit",
)
