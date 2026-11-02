package com.hidewnd.winds.costing.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;
import com.hidewnd.winds.costing.costant.CalculationMode;
import java.util.List;
import java.util.Map;

@Data
@Schema(description = "成本结果集基类")
public class CostResult implements Serializable {

    @Schema(description = "服务器")
    private String server;

    @Schema(description = "本次使用的技艺等级")
    private Integer professionLevel;

    @Schema(description = "本次幸运概率累计加成，0至1；旧数量模式为0")
    private BigDecimal luckyOutputAddRate;

    @Schema(description = "本次特殊产物概率累计加成，0至1；旧数量模式为0")
    private BigDecimal specialItemAddRate;

    @Schema(description = "预计总消耗精力")
    private Integer energies;

    @Schema(description = "合计成本价")
    private Long cost;

    @Schema(description = "合计成本价格式化")
    private String costString;

    @Schema(description = "合计交易行价")
    private Long value;

    @Schema(description = "合计交易行价格式化")
    private String valueString;

    @Schema(description = "预期利润")
    private Long actualProfit;

    @Schema(description = "预期利润格式化")
    private String actualProfitString;

    @Schema(description = "合计所需材料数量")
    private Map<String, Material> requiredMap;

    @Schema(description = "制作明细")
    private List<CostDetailDto> makeDetail;
    private CalculationMode calculationMode;
    private boolean estimateComplete;
    private boolean costComplete;
    private boolean valueComplete;
    private Long knownCost;
    private String knownCostString;
    private Long knownValue;
    private String knownValueString;
    private BigDecimal feeRate;
    private Long custodyFee;
    private List<OutputEstimate> outputs;
    private List<String> notes;
}
