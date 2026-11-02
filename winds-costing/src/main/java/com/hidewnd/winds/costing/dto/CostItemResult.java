package com.hidewnd.winds.costing.dto;

import com.hidewnd.winds.costing.costant.FormulasEnum;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.io.Serializable;
import java.math.BigDecimal;

@Data
@EqualsAndHashCode(callSuper = true)
public class CostItemResult extends CostResult implements Serializable {

    @Schema(description = "技艺类型")
    private FormulasEnum type;

    @Schema(description = "技艺制品名称")
    private String formulaName;

    @Schema(description = "材料ID")
    private String materialId;

    @Schema(description = "所需数量")
    private Integer number;

    @Schema(description = "实际产出数量")
    private Integer actualNumber;

    @Schema(type = "integer", description = "期望产量，响应时向下取整；计算使用未取整值")
    private BigDecimal expectedNumber;
    private Integer times;
    private Formulas formula;
}
