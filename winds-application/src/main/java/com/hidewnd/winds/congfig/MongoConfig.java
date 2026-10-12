package com.hidewnd.winds.congfig;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.mongo.MongoClientSettingsBuilderCustomizer;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.data.mongodb.MongoDatabaseFactory;
import org.springframework.data.mongodb.core.convert.DbRefResolver;
import org.springframework.data.mongodb.core.convert.DefaultDbRefResolver;
import org.springframework.data.mongodb.core.convert.DefaultMongoTypeMapper;
import org.springframework.data.mongodb.core.convert.MappingMongoConverter;
import org.springframework.data.mongodb.core.convert.MongoCustomConversions;
import org.springframework.data.mongodb.core.convert.NoOpDbRefResolver;
import org.springframework.data.mongodb.core.mapping.MongoMappingContext;

import java.util.concurrent.TimeUnit;

/**
 * MongoDB 共享客户端连接池及统一序列化配置。
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(MongoConnectionProperties.class)
public class MongoConfig {

    @Bean
    @Order(100)
    public MongoClientSettingsBuilderCustomizer mongoConnectionSettings(MongoConnectionProperties properties) {
        // 在 Boot 的地址/认证配置之后应用连接策略，仍由 Boot 创建和关闭唯一 MongoClient。
        // 仅调整已有 builder，保留驱动监听器、指标和其他连接设置。
        return builder -> {
            builder.applyToConnectionPoolSettings(pool -> pool
                    .minSize(properties.minPoolSize())
                    .maxSize(properties.maxPoolSize())
                    .maxConnecting(properties.maxConnecting())
                    .maxWaitTime(properties.maxWaitTime().toMillis(), TimeUnit.MILLISECONDS)
                    .maxConnectionIdleTime(properties.maxConnectionIdleTime().toMillis(), TimeUnit.MILLISECONDS)
                    .maintenanceFrequency(properties.maintenanceFrequency().toMillis(), TimeUnit.MILLISECONDS));
            builder.applyToClusterSettings(cluster -> cluster
                    .serverSelectionTimeout(properties.serverSelectionTimeout().toMillis(), TimeUnit.MILLISECONDS));
            builder.applyToSocketSettings(socket -> socket
                    .connectTimeout((int) properties.connectTimeout().toMillis(), TimeUnit.MILLISECONDS)
                    .readTimeout((int) properties.readTimeout().toMillis(), TimeUnit.MILLISECONDS));
        };
    }

    @Bean
    public MappingMongoConverter mappingMongoConverter(
            ObjectProvider<MongoDatabaseFactory> factory,
            MongoMappingContext mappingContext,
            MongoCustomConversions customConversions) {
        MongoDatabaseFactory databaseFactory = factory.getIfAvailable();
        DbRefResolver dbRefResolver = databaseFactory == null
                ? NoOpDbRefResolver.INSTANCE
                : new DefaultDbRefResolver(databaseFactory);
        MappingMongoConverter converter = new MappingMongoConverter(dbRefResolver, mappingContext);
        converter.setCustomConversions(customConversions);
        // 不持久化 Java 类型信息，Mongo 文档只保留稳定的业务字段。
        converter.setTypeMapper(new DefaultMongoTypeMapper(null));
        return converter;
    }
}
