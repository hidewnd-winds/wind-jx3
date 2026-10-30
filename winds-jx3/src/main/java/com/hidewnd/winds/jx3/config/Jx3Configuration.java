package com.hidewnd.winds.jx3.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hidewnd.winds.jx3.client.Jx3ApiWebSocketClient;
import com.hidewnd.winds.jx3.client.CeicEarthquakeClient;
import com.hidewnd.winds.jx3.repository.EarthquakeRepository;
import com.hidewnd.winds.jx3.service.EarthquakeService;
import org.springframework.beans.factory.ObjectProvider;
import com.hidewnd.winds.jx3.client.OfficialClient;
import com.hidewnd.winds.jx3.client.TcpServerProbe;
import com.hidewnd.winds.jx3.repository.Jx3RecordRepository;
import com.hidewnd.winds.jx3.scheduler.Jx3PollingScheduler;
import com.hidewnd.winds.jx3.service.ArticleMonitorService;
import com.hidewnd.winds.jx3.service.PatchMonitorService;
import com.hidewnd.winds.jx3.service.ServerMonitorService;
import com.hidewnd.winds.jx3.service.impl.ArticleMonitorServiceImpl;
import com.hidewnd.winds.jx3.service.impl.PatchMonitorServiceImpl;
import com.hidewnd.winds.jx3.service.impl.ServerMonitorServiceImpl;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.scheduling.TaskScheduler;

import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;

/** 只负责条件启用和依赖装配；监听业务由各服务负责。 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "winds.jx3", name = "enabled", havingValue = "true")
@EnableConfigurationProperties({Jx3Properties.class, Jx3ApiSocketProperties.class, EarthquakeProperties.class})
public class Jx3Configuration {
    @Bean(destroyMethod = "shutdownNow")
    @ConditionalOnMissingBean(name = "jx3ApiHttpClient")
    public HttpClient jx3ApiHttpClient(@Qualifier("asyncTaskExecutor") AsyncTaskExecutor executor) {
        return HttpClient.newBuilder().executor(executor).connectTimeout(Duration.ofSeconds(10)).build();
    }

    @Bean
    @ConditionalOnProperty(prefix = "winds.jx3.socket", name = "enabled", havingValue = "true", matchIfMissing = true)
    public Jx3ApiWebSocketClient jx3ApiWebSocketClient(
            @Qualifier("jx3ApiHttpClient") HttpClient http,
            ObjectMapper mapper, ServerMonitorService monitor, EarthquakeService earthquakes,
            @Qualifier("taskScheduler") TaskScheduler scheduler, Jx3ApiSocketProperties properties,
            @Qualifier("asyncTaskExecutor") AsyncTaskExecutor executor) {
        return new Jx3ApiWebSocketClient(http, mapper, monitor, earthquakes, scheduler, Clock.systemUTC(), properties, executor);
    }

    @Bean
    public EarthquakeRepository earthquakeRepository(MongoTemplate mongo) {
        return new EarthquakeRepository(mongo);
    }

    @Bean
    public EarthquakeService earthquakeService(EarthquakeRepository repository, ApplicationEventPublisher publisher) {
        return new EarthquakeService(repository, publisher, Clock.systemUTC());
    }

    @Bean
    @ConditionalOnProperty(prefix = "winds.jx3.earthquake", name = "enabled", havingValue = "true", matchIfMissing = true)
    public CeicEarthquakeClient ceicEarthquakeClient(
            @Qualifier("jx3ApiHttpClient") HttpClient http, ObjectMapper mapper, EarthquakeProperties properties) {
        return new CeicEarthquakeClient(http, mapper, properties);
    }

    @Bean
    public OfficialClient jx3OfficialClient(ObjectMapper mapper) {
        return new OfficialClient(mapper);
    }

    @Bean
    public Jx3RecordRepository jx3RecordRepository(MongoTemplate mongo, ObjectMapper mapper) {
        return new Jx3RecordRepository(mongo, mapper);
    }

    @Bean
    public ArticleMonitorService jx3NewsMonitor(
            OfficialClient client,
            Jx3RecordRepository repository,
            ApplicationEventPublisher publisher,
            ObjectMapper mapper) {
        return new ArticleMonitorServiceImpl(
                false, client, repository, publisher, mapper, Clock.systemUTC());
    }

    @Bean
    public ArticleMonitorService jx3MaintenanceMonitor(
            OfficialClient client,
            Jx3RecordRepository repository,
            ApplicationEventPublisher publisher,
            ObjectMapper mapper) {
        return new ArticleMonitorServiceImpl(
                true, client, repository, publisher, mapper, Clock.systemUTC());
    }

    @Bean
    public PatchMonitorService jx3PatchMonitor(
            OfficialClient client,
            Jx3RecordRepository repository,
            ApplicationEventPublisher publisher,
            ObjectMapper mapper) {
        return new PatchMonitorServiceImpl(
                client, repository, publisher, mapper, Clock.systemUTC());
    }

    @Bean
    public TcpServerProbe jx3TcpServerProbe(Jx3Properties properties) {
        return new TcpServerProbe(properties.serverConnectTimeout());
    }

    @Bean
    public ServerMonitorService jx3ServerMonitor(
            OfficialClient client,
            Jx3RecordRepository repository,
            ApplicationEventPublisher publisher,
            ObjectMapper mapper,
            TcpServerProbe probe,
            @Qualifier("asyncTaskExecutor") AsyncTaskExecutor executor) {
        return new ServerMonitorServiceImpl(
                client, repository, publisher, mapper, Clock.systemUTC(), probe, executor);
    }

    @Bean
    public Jx3PollingScheduler jx3Polling(
            ObjectProvider<CeicEarthquakeClient> ceic, EarthquakeService earthquakes, EarthquakeProperties earthquakeProperties,
            @Qualifier("jx3NewsMonitor") ArticleMonitorService news,
            @Qualifier("jx3MaintenanceMonitor") ArticleMonitorService maintenance,
            PatchMonitorService patches,
            ServerMonitorService servers,
            Jx3Properties properties,
            @Qualifier("asyncTaskExecutor") AsyncTaskExecutor executor,
            @Qualifier("taskScheduler") TaskScheduler scheduler) {
        CeicEarthquakeClient client = ceic.getIfAvailable();
        return new Jx3PollingScheduler(news, maintenance, patches, servers, properties, executor, scheduler,
                client == null ? null : () -> earthquakes.poll(client.fetch()), earthquakeProperties.interval());
    }
}
