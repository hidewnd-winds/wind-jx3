package com.hidewnd.winds.costing.controller;

import cn.hutool.core.util.StrUtil;
import com.hidewnd.winds.common.base.response.R;
import com.hidewnd.winds.costing.dto.request.CostItemRequest;
import com.hidewnd.winds.costing.dto.CostItemResult;
import com.hidewnd.winds.costing.dto.request.CostListRequest;
import com.hidewnd.winds.costing.dto.CostListResult;
import com.hidewnd.winds.costing.dto.validate.RequestModel;
import com.hidewnd.winds.costing.service.CostingService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.CacheConfig;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Slf4j
@RestController
@Tag(name = "技艺成本计算接口")
@RequestMapping("/costing")
@CacheConfig(cacheNames = "cost")
public class CostingController {

    @Value("${server.port}")
    private String port;

    private CostingService costingService;

    @Autowired
    public void setCostingService(CostingService costingService) {
        this.costingService = costingService;
    }

    @PostMapping("/one")
    @Operation(summary = "查询单个技艺制品成本")
    @Cacheable(cacheNames = {"item"}, keyGenerator = "costingKeyGenerator",
            unless = "#result.obj == null || !#result.obj.estimateComplete || !#result.obj.costComplete || !#result.obj.valueComplete")
    public R<CostItemResult> costValue(@Validated(RequestModel.class) @RequestBody CostItemRequest request) {
        return costingService.queryCosting(request);
    }

    @PostMapping("/list")
    @Operation(summary = "查询多个技艺制品成本")
    @Cacheable(cacheNames = {"item"}, keyGenerator = "costingKeyGenerator",
            unless = "#result.obj == null || !#result.obj.estimateComplete || !#result.obj.costComplete || !#result.obj.valueComplete")
    public R<CostListResult> costValueList(@Validated(RequestModel.class) @RequestBody CostListRequest request) {
        return costingService.queryCostingList(request);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public R<String> invalidRequest() {
        return R.error(R.CODE_PARAM_ERROR, "成本请求参数格式无效，技艺等级须为0至50的整数");
    }


    @PostConstruct
    public void afterServerStart() {
        log.info(StrUtil.format("接口文档地址：http://localhost:{}/doc.html", port));
    }
}
