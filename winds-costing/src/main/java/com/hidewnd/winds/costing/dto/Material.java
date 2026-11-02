package com.hidewnd.winds.costing.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;

@Data
public class Material implements Serializable {

    @Schema(description = "材料ID")
    private String id;

    @Schema(description = "材料名称")
    private String name;

    private Integer quality;
    private Integer bindType;

    @Schema(description = "所需材料数量")
    private Integer number;

    @Schema(description = "材料价格")
    private Long value;

    @Schema(description = "材料价格格式化")
    private String valueString;

    @Schema(description = "本次材料报价和缺货补估信息")
    private PriceQuote quote;

    @JsonIgnore
    private String uiId;

    @JsonIgnore
    private String sourceId;

    @JsonIgnore
    private String iconId;

    @JsonIgnore
    @Schema(description = "材料的描述信息")
    private String desc;

    @JsonIgnore
    @Schema(description = "材料的关联链接")
    private String link;

}
