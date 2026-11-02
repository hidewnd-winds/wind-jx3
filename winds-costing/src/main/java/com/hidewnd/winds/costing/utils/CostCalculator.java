package com.hidewnd.winds.costing.utils;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 成本计算工具类
 * 提供利润计算、手续费计算等公共方法
 */
public class CostCalculator {

    /**
     * 默认交易行手续费率 5%
     */
    public static final BigDecimal DEFAULT_FEE_RATE = new BigDecimal("0.05");

    /**
     * 默认保管费（24小时） 8000铜钱
     */
    public static final long DEFAULT_CUSTODY_FEE = 8000L;

    /**
     * 计算交易行手续费
     *
     * @param tradingPrice 交易行总价
     * @param feeRate 手续费率
     * @return 手续费金额
     */
    public static long calculateTradingFee(long tradingPrice, BigDecimal feeRate) {
        if (tradingPrice <= 0) {
            return 0;
        }
        return new BigDecimal(tradingPrice)
                .multiply(feeRate)
                .setScale(0, RoundingMode.HALF_UP)
                .longValue();
    }

    /**
     * 计算交易行手续费（使用默认费率）
     *
     * @param tradingPrice 交易行总价
     * @return 手续费金额
     */
    public static long calculateTradingFee(long tradingPrice) {
        return calculateTradingFee(tradingPrice, DEFAULT_FEE_RATE);
    }

    /**
     * 计算实际利润
     *
     * @param tradingPrice 交易行总价
     * @param cost 成本价格
     * @param feeRate 手续费率
     * @param custodyFee 保管费
     * @return 实际利润
     */
    public static long calculateProfit(long tradingPrice, long cost, BigDecimal feeRate, long custodyFee) {
        if (tradingPrice <= 0) {
            return -cost - custodyFee;
        }
        long totalFees = calculateTradingFee(tradingPrice, feeRate);
        return tradingPrice - cost - totalFees - custodyFee;
    }

    /**
     * 计算实际利润（使用默认费率和保管费）
     *
     * @param tradingPrice 交易行总价
     * @param cost 成本价格
     * @return 实际利润
     */
    public static long calculateProfit(long tradingPrice, long cost) {
        return calculateProfit(tradingPrice, cost, DEFAULT_FEE_RATE, DEFAULT_CUSTODY_FEE);
    }

    /**
     * 计算实际利润（使用自定义费率，默认保管费）
     *
     * @param tradingPrice 交易行总价
     * @param cost 成本价格
     * @param feeRate 手续费率
     * @return 实际利润
     */
    public static long calculateProfit(long tradingPrice, long cost, BigDecimal feeRate) {
        return calculateProfit(tradingPrice, cost, feeRate, DEFAULT_CUSTODY_FEE);
    }

    /**
     * 计算实际利润（使用默认费率，自定义保管费）
     *
     * @param tradingPrice 交易行总价
     * @param cost 成本价格
     * @param custodyFee 保管费
     * @return 实际利润
     */
    public static long calculateProfit(long tradingPrice, long cost, long custodyFee) {
        return calculateProfit(tradingPrice, cost, DEFAULT_FEE_RATE, custodyFee);
    }

    /**
     * 批量计算总利润
     *
     * @param totalTradingPrice 交易行总价
     * @param totalCost 总成本
     * @param feeRate 手续费率
     * @param custodyFee 保管费
     * @return 总利润
     */
    public static long calculateTotalProfit(long totalTradingPrice, long totalCost,
                                           BigDecimal feeRate, long custodyFee) {
        return calculateProfit(totalTradingPrice, totalCost, feeRate, custodyFee);
    }

    /**
     * 验证费率是否合法（0-1之间）
     *
     * @param feeRate 手续费率
     * @return 是否合法
     */
    public static boolean isValidFeeRate(BigDecimal feeRate) {
        return feeRate != null &&
               feeRate.compareTo(BigDecimal.ZERO) >= 0 &&
               feeRate.compareTo(BigDecimal.ONE) <= 0;
    }
    /** 期望收益保留小数铜，合计并扣费后才取整。 */
    public static long calculateProfit(BigDecimal tradingPrice, long cost, BigDecimal feeRate, long custodyFee) {
        return tradingPrice.multiply(BigDecimal.ONE.subtract(feeRate)).subtract(BigDecimal.valueOf(cost))
                .subtract(BigDecimal.valueOf(custodyFee)).setScale(0, RoundingMode.HALF_UP).longValueExact();
    }
}