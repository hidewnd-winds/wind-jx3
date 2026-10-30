package com.hidewnd.winds.jx3.scheduler;

import com.hidewnd.winds.jx3.config.Jx3Properties;
import com.hidewnd.winds.jx3.service.ArticleMonitorService;
import com.hidewnd.winds.jx3.service.PatchMonitorService;
import com.hidewnd.winds.jx3.service.ServerMonitorService;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;

import lombok.extern.slf4j.Slf4j;

import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.scheduling.TaskScheduler;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.ArrayList;
import java.util.concurrent.FutureTask;
import java.util.concurrent.ScheduledFuture;

/** 共用应用调度器和异步执行器；每个来源独立串行轮询、退避和取消。 */
@Slf4j
public class Jx3PollingScheduler {
    private final AsyncTaskExecutor executor;
    private final TaskScheduler scheduler;
    private final List<PollingTask> tasks;

    public Jx3PollingScheduler(
            ArticleMonitorService news,
            ArticleMonitorService maintenance,
            PatchMonitorService patches,
            ServerMonitorService servers,
            Jx3Properties properties,
            AsyncTaskExecutor executor,
            TaskScheduler scheduler, Runnable earthquakes, Duration earthquakeInterval) {
        this.executor = executor;
        this.scheduler = scheduler;
        this.tasks = new ArrayList<>(List.of(
                new PollingTask("news", news::poll, properties.newsInterval(), true),
                new PollingTask("maintenance", maintenance::poll, properties.maintenanceInterval(), true),
                new PollingTask("server", servers::poll, properties.serverInterval(), true),
                new PollingTask("patch", patches::poll, properties.patchInterval(), true)));
        if (earthquakes != null) {
            tasks.add(new PollingTask("earthquake", earthquakes, earthquakeInterval, false));
        }
    }

    @PostConstruct
    public void start() {
        tasks.forEach(task -> task.schedule(Duration.ofSeconds(10)));
    }

    @PreDestroy
    public void close() {
        // 只取消本模块持有的任务；共享线程资源由应用配置管理。
        tasks.forEach(PollingTask::cancel);
    }

    /** 单一来源的轮询状态归任务自身所有；同步只保护提交、重排和取消，不覆盖网络 I/O。 */
    private final class PollingTask implements Runnable {
        private final String name;
        private final Runnable action;
        private final Duration interval;
        private final boolean backoff;
        private Instant lastSuccess;
        private int failures;
        private ScheduledFuture<?> scheduled;
        private FutureTask<Void> running;
        private boolean stopped;

        private PollingTask(String name, Runnable action, Duration interval, boolean backoff) {
            this.name = name;
            this.action = action;
            this.interval = interval;
            this.backoff = backoff;
        }

        private synchronized void schedule(Duration delay) {
            if (!stopped) {
                scheduled = scheduler.schedule(this, Instant.now().plus(delay));
            }
        }

        @Override
        public synchronized void run() {
            if (stopped) {
                return;
            }
            running = new FutureTask<>(() -> {
                Duration delay = interval;
                try {
                    action.run();
                    lastSuccess = Instant.now();
                    if (failures != 0) {
                        log.info("剑三采集恢复，来源={}", name);
                    }
                    failures = 0;
                } catch (RuntimeException exception) {
                    delay = retryAfter(exception);
                } finally {
                    // 异步任务实际完成后才计算下一轮，避免定时触发堆积或同一来源重叠执行。
                    schedule(delay);
                }
                return null;
            });
            try {
                executor.execute(running);
            } catch (RuntimeException exception) {
                running.cancel(false);
                schedule(retryAfter(exception));
            }
        }

        private Duration retryAfter(RuntimeException exception) {
            failures = Math.min(6, failures + 1);
            long delayMillis = backoff ? Math.min(600_000, interval.toMillis() * (1L << (failures - 1)))
                    : interval.toMillis();
            log.warn("剑三采集失败，来源={}，最近成功={}，原因={}",
                    name, lastSuccess, exception.getMessage(), exception);
            return Duration.ofMillis(delayMillis);
        }

        private synchronized void cancel() {
            stopped = true;
            if (scheduled != null) {
                scheduled.cancel(false);
            }
            if (running != null) {
                running.cancel(true);
            }
        }
    }
}
