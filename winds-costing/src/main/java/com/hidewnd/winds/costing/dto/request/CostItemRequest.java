package com.hidewnd.winds.costing.dto.request;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.hidewnd.winds.costing.dto.validate.ProfessionLevelDeserializer;
import com.hidewnd.winds.costing.dto.validate.RequestModel;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
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

    @NotNull(message = "技艺等级不能为空", groups = RequestModel.class)
    @Min(value = 0, message = "技艺等级不能小于0", groups = RequestModel.class)
    @Max(value = 50, message = "技艺等级不能大于50", groups = RequestModel.class)
    @JsonDeserialize(using = ProfessionLevelDeserializer.class)
    @Schema(description = "技艺等级，省略时按50级；仅EXPECTED计入等级加成", minimum = "0", maximum = "50", defaultValue = "50")
    private Integer professionLevel = 50;


    @Schema(description = "旧参数：true随机、false最低产量；两个模式参数均省略时使用期望值")
    private Boolean rangeCreate;

    @Schema(description = "EXPECTED期望值、RANDOM随机、MINIMUM最低产量；优先于rangeCreate")
    private CalculationMode calculationMode;
}
