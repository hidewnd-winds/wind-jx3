package com.hidewnd.winds.jx3.service;

import com.hidewnd.winds.jx3.event.Jx3EventFactory;
import com.hidewnd.winds.jx3.model.EarthquakeReport;
import com.hidewnd.winds.jx3.repository.EarthquakeRepository;
import com.hidewnd.winds.jx3.support.Jx3Time;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;

public class EarthquakeService {
    private final EarthquakeRepository repository;
    private final ApplicationEventPublisher publisher;
    private final Clock clock;

    public EarthquakeService(EarthquakeRepository repository, ApplicationEventPublisher publisher, Clock clock) {
        this.repository = repository;
        this.publisher = publisher;
        this.clock = clock;
    }

    public void accept(EarthquakeReport report) {
        Instant now = clock.instant();
        repository.archive(report, false, now);
        if (repository.claimPush(report, now)) {
            publisher.publishEvent(Jx3EventFactory.createEarthquakeEvent(report));
        }
    }

    public void poll(List<EarthquakeReport> reports) {
        if (reports.isEmpty()) {
            throw new IllegalArgumentException("空地震列表不能建立基线");
        }
        boolean baseline = !repository.baselineReady();
        Instant now = clock.instant();
        Instant today = Jx3Time.startOfDay(now);
        for (var report : reports.stream().sorted(Comparator.comparing(EarthquakeReport::time)).toList()) {
            boolean eligible = report.domestic() && !report.time().isBefore(today)
                    && report.time().isBefore(today.plus(Duration.ofDays(1)));
            repository.archive(report, baseline || !eligible, now);
            if (!baseline && eligible && repository.claimPush(report, now)) {
                publisher.publishEvent(Jx3EventFactory.createEarthquakeEvent(report));
            }
        }
        if (baseline) {
            // 全批存档成功后才登记基线；中途失败重试时不会将剩余历史记录误推送。
            repository.finishBaseline(now);
        }
    }
}
