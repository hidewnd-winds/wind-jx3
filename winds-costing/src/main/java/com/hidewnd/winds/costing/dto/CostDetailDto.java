package com.hidewnd.winds.costing.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import java.io.Serializable;
import java.math.BigDecimal;

@Data
@Schema(description = "按配方汇总的制作明细，包含中间材料制作")
public class CostDetailDto implements Serializable {
    private Integer no;
    private String name;
    @Schema(description = "旧模式实际产量；期望模式为null")
    private Integer makeNumber;
    private Integer times;
    @Schema(type = "integer", description = "期望产量，响应时向下取整；计算使用未取整值")
    private BigDecimal expectedNumber;
    private Formulas formula;
}
