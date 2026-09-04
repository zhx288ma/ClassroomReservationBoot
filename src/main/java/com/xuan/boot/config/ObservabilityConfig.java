package com.xuan.boot.config;

import com.xuan.boot.mapper.AuditLogMapper;
import com.xuan.boot.mapper.NotificationOutboxMapper;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ObservabilityConfig {
    @Bean
    public MeterBinder classroomReservationMetrics(NotificationOutboxMapper outboxMapper,
                                                   AuditLogMapper auditLogMapper) {
        return registry -> {
            Gauge.builder("crs.outbox.pending", outboxMapper, mapper -> mapper.countByStatus(0))
                    .description("Pending notification outbox events")
                    .register(registry);
            Gauge.builder("crs.outbox.retrying", outboxMapper, mapper -> mapper.countByStatus(2))
                    .description("Retrying notification outbox events")
                    .register(registry);
            Gauge.builder("crs.outbox.dead", outboxMapper, mapper -> mapper.countByStatus(3))
                    .description("Dead notification outbox events")
                    .register(registry);
            Gauge.builder("crs.audit.failures", auditLogMapper, AuditLogMapper::countFailures)
                    .description("Total failed audited API requests")
                    .register(registry);
            Gauge.builder("crs.audit.recent.requests", auditLogMapper, AuditLogMapper::countRecentRequests)
                    .description("API requests in the last five minutes")
                    .register(registry);
            Gauge.builder("crs.audit.avg.latency.last5m", auditLogMapper, AuditLogMapper::avgLatencyLastFiveMinutes)
                    .description("Average API latency in the last five minutes")
                    .register(registry);
        };
    }
}
