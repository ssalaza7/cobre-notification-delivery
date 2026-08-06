package com.cobre.notifications.domain.model;

/** Conteo de notificaciones por estado; alimenta los gauges de observabilidad. */
public record StatusCount(DeliveryStatus status, long count) {
}
