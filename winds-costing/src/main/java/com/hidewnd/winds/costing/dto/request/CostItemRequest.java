package com.hidewnd.winds.costing.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.io.Serializable;
import com.hidewnd.winds.costing.costant.CalculationMode;

@Data
@Schema(description = "技艺成本查询体")
@EqualsAndHashCode(callSuper = true)
public class CostItemRequest extends CostItem implements Serializable {

    @Schema(description = "服务器", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
    private String server;


    @Schema(description = "旧参数：true随机、false最低产量；两个模式参数均省略时使用期望值")
    private Boolean rangeCreate;

    @Schema(description = "EXPECTED期望值、RANDOM随机、MINIMUM最低产量；优先于rangeCreate")
    private CalculationMode calculationMode;
}
