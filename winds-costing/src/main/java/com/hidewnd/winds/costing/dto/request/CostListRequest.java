package com.hidewnd.winds.costing.dto.request;

import com.hidewnd.winds.costing.dto.validate.RequestModel;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

import java.io.Serializable;
import com.hidewnd.winds.costing.costant.CalculationMode;
import java.util.List;

@Data
@Schema(description = "技艺成本清单查询体")
public class CostListRequest implements Serializable {

    @Schema(description = "服务器", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
    private String server;

    @Schema(description = "旧参数：true随机、false最低产量；两个模式参数均省略时使用期望值")
    private Boolean rangeCreate;

    @Schema(description = "清单明细列表")
    @Valid
    @NotEmpty(message = "清单明细列表不能为空", groups = RequestModel.class)
    private List<@NotNull(groups = RequestModel.class) CostItem> items;

    @Schema(description = "EXPECTED期望值、RANDOM随机、MINIMUM最低产量；优先于rangeCreate")
    private CalculationMode calculationMode;
}
