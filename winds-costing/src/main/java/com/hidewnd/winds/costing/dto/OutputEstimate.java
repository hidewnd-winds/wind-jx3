package com.hidewnd.winds.costing.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import java.io.Serializable;
import java.math.BigDecimal;

@Data
@Schema(description = "按物品ID合并的产物预测；已消耗的中间产物不参与估值")
public class OutputEstimate implements Serializable {
    private String id;
    private String name;
    @Schema(type = "integer", description = "期望产量，响应时向下取整；计算使用未取整值")
    private BigDecimal expectedNumber;
    private Integer actualNumber;
    private BigDecimal consumedNumber = BigDecimal.ZERO;
    @Schema(type = "integer", description = "扣除内部消耗后的余量，响应时向下取整；估值使用未取整值")
    private BigDecimal availableNumber;
    private PriceQuote.Status priceStatus;
    private Long unitPrice;
    private Long value;
    private String valueString;
}
