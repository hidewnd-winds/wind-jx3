package com.hidewnd.winds.congfig;

import cn.hutool.core.util.StrUtil;
import cn.hutool.crypto.digest.DigestUtil;
import com.alibaba.fastjson2.JSONArray;
import com.hidewnd.winds.costing.dto.request.CostItemRequest;
import com.hidewnd.winds.costing.costant.CalculationMode;
import com.hidewnd.winds.costing.handler.FormulaBuilder;
import com.hidewnd.winds.costing.dto.request.CostListRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.interceptor.KeyGenerator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.lang.NonNull;

import java.lang.reflect.Method;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;


/**
 * Spring Cache 配置类
 */
@EnableCaching
@Configuration
public class CacheConfig {

    @Value("${spring.cache.live-time:180000}")
    private long liveTime;

    @Value("${box.default.server:剑胆琴心}")
    private String defaultServer;

    @Bean
    public RedisCacheManager redisCacheManager(RedisConnectionFactory factory) {
        RedisCacheConfiguration config = RedisCacheConfiguration.defaultCacheConfig()
                .entryTtl(Duration.ofMillis(liveTime))
                .serializeValuesWith(RedisSerializationContext.SerializationPair
                        .fromSerializer(new GenericJackson2JsonRedisSerializer()))
                .prefixCacheNameWith("costing:");  // 自定义缓存键前缀
        return RedisCacheManager.builder(factory)
                .cacheDefaults(config)
                .build();
    }

    /**
     * 成本计算缓存Key生成器： [查询服务器]_[请求参数SHA-1摘要值]
     *
     *
     */
    @Bean
    public KeyGenerator costingKeyGenerator() {
        return new KeyGenerator() {

            @NonNull
            @Override
            public Object generate(@NonNull Object target, @NonNull Method method, @NonNull Object... params) {
                String key = DigestUtil.sha1Hex(JSONArray.toJSONString(params));
                if (params[0] instanceof CostItemRequest request) {
                    String formulaName = FormulaBuilder.normalizeFormulaName(request.getFormulaName());
                    String server = StrUtil.emptyToDefault(request.getServer(), defaultServer);
                    int number = request.getNumber() == null ? 1 : request.getNumber();
                    CalculationMode mode = CalculationMode.resolve(request.getCalculationMode(), request.getRangeCreate());
                    Map<String, Object> map = new HashMap<>();
                    map.put("formulaName", formulaName);
                    map.put("number", number);
                    map.put("professionLevel", request.getProfessionLevel());
                    map.put("calculationMode", mode);
                    key = StrUtil.format("{}_{}", server, DigestUtil.sha1Hex(JSONArray.toJSONString(map)));
                }
                if (params[0] instanceof CostListRequest request) {
                    String server = StrUtil.emptyToDefault(request.getServer(), defaultServer);
                    CalculationMode mode = CalculationMode.resolve(request.getCalculationMode(), request.getRangeCreate());
                    Map<String, Object> map = new HashMap<>();
                    map.put("items", request.getItems());
                    map.put("professionLevel", request.getProfessionLevel());
                    map.put("calculationMode", mode);
                    key = StrUtil.format("{}_{}", server, DigestUtil.sha1Hex(JSONArray.toJSONString(map)));
                }
                return "v4:" + method.getName() + ":" + key;
            }
        };
    }
}
