package com.hidewnd.winds.costing.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import java.io.Serializable;

@Data
@Schema(description = "材料购买报价；缺价时value为null")
public class PriceQuote implements Serializable {
    public enum Status { MARKET, EXTRAPOLATED, VENDOR, MISSING, ERROR, TIMEOUT }
    private Status status;
    private Long value;
    @Schema(description = "当前最低有效挂单单价，供产物参考估值使用")
    private Long unitPrice;
    private int estimatedNumber;
    private Long estimatedUnitPrice;
}
