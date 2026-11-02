package com.hidewnd.winds.costing.utils;

import cn.hutool.core.util.StrUtil;

import java.util.ArrayList;
import java.util.List;

public class BoxUtils {

    public static String computePrice(String price) {
        if (StrUtil.isEmpty(price)) {
            return "";
        }
        return computePrice(Long.parseLong(price));
    }

    public static String computePrice(Long value) {
        if (value == null) {
            return "";
        }
        if (value == 0) return "0铜";
        String prefix = "";
        if (value < 0) {
            prefix = "负";
            value = Math.abs(value);
        }
        StringBuilder builder = new StringBuilder("{}");
        List<Object> params = new ArrayList<>();
        params.add(prefix);
        value = computePrice(value, 100000000, builder, "{}砖", params);
        value = computePrice(value, 10000, builder, "{}金", params);
        value = computePrice(value, 100, builder, "{}银", params);
        if (value > 0) {
            builder.append("{}铜");
            params.add(value);
        }
        return StrUtil.format(builder.toString(), params.toArray());
    }

    public static long computePrice(long value, long threshold, StringBuilder builder, String format,
                                    List<Object> params) {
        if (value >= threshold) {
            long result = value / threshold;
            if (value % threshold >= 0) {
                value -= result * threshold;
            }
            builder.append(format);
            params.add(result);
        }
        return value;
    }


}
