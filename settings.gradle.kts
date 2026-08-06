rootProject.name = "notification-delivery-service"

// Dos librerias y tres ejecutables.
//
//   domain    modelo, reglas y puertos. Sin framework y sin dependencias.
//   kit       plomeria transversal que implementa esos puertos: persistencia, cola,
//             metricas y utilidades de log. No sabe que es una notificacion.
//
// Cada ejecutable trae su propia capa de aplicacion y sus adaptadores.
include("domain", "kit", "consumer", "worker", "api")
