package com.hidewnd.winds.costing.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import java.io.Serializable;
import java.math.BigDecimal;

@Data
@Schema(description = "配方单次可能产物，概率不含技艺等级修正")
public class FormulaOutput implements Serializable {
    private Integer slot;
    private String id;
    private String name;
    private Integer quality;
    private Integer bindType;
    private String desc;
    private Integer createMin;
    private Integer createMax;
    private Integer probabilityWeight;
    @Schema(description = "配方表概率，0至1；未知为null")
    private BigDecimal probability;
}
