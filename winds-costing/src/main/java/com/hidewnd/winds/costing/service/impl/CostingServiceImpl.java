package com.hidewnd.winds.costing.service.impl;

import cn.hutool.core.util.StrUtil;
import com.hidewnd.winds.common.base.response.R;
import com.hidewnd.winds.costing.costant.CalculationMode;
import com.hidewnd.winds.costing.dto.*;
import com.hidewnd.winds.costing.dto.request.*;
import com.hidewnd.winds.costing.handler.FormulaBuilder;
import com.hidewnd.winds.costing.handler.FormulaParseAdapter;
import com.hidewnd.winds.costing.service.CostingService;
import com.hidewnd.winds.costing.service.Jx3BoxRemote;
import com.hidewnd.winds.costing.utils.BoxUtils;
import com.hidewnd.winds.costing.utils.CostCalculator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;
import java.util.concurrent.*;

@Slf4j
@Service("costingService")
public class CostingServiceImpl implements CostingService {
    @Value("${box.default.server:剑胆琴心}")
    private String defaultServer;
    @Value("${box.default.fee-rate:0.05}")
    private String feeRate;
    private Jx3BoxRemote jx3BoxRemote;
    private AsyncTaskExecutor asyncTaskExecutor;
    private FormulaParseAdapter formulaParseAdapter;

    @Autowired
    public void setJx3BoxRemote(Jx3BoxRemote jx3BoxRemote) { this.jx3BoxRemote = jx3BoxRemote; }
    @Autowired
    @Qualifier("asyncTaskExecutor")
    public void setAsyncTaskExecutor(AsyncTaskExecutor executor) { this.asyncTaskExecutor = executor; }
    @Autowired
    public void setFormulaParseAdapter(FormulaParseAdapter adapter) { this.formulaParseAdapter = adapter; }

    @Override
    public R<CostItemResult> queryCosting(CostItemRequest request) {
        FormulaBuilder builder = FormulaBuilder.create(CalculationMode.resolve(request.getCalculationMode(), request.getRangeCreate()), request.getProfessionLevel())
                .addFormula(request.getFormulaName(), request.getNumber()).parseFormulas(formulaParseAdapter).analysisMaterial();
        CostItemResult result = new CostItemResult();
        Formulas formula = builder.getByName(request.getFormulaName());
        CostDetailDto detail = builder.getDetail(formula);
        result.setFormulaName(formula.getFormulaName());
        result.setMaterialId(formula.getMaterialId());
        result.setType(formula.getType());
        result.setNumber(request.getNumber());
        result.setFormula(formula);
        result.setTimes(detail.getTimes());
        result.setActualNumber(detail.getMakeNumber());
        result.setExpectedNumber(detail.getExpectedNumber());
        result.setServer(StrUtil.emptyToDefault(request.getServer(), defaultServer));
        computeCost(result, builder);
        floorResponseQuantities(result);
        return R.successByObj(result);
    }

    @Override
    public R<CostListResult> queryCostingList(CostListRequest request) {
        FormulaBuilder builder = FormulaBuilder.create(CalculationMode.resolve(request.getCalculationMode(), request.getRangeCreate()), request.getProfessionLevel());
        for (CostItem item : request.getItems()) builder.addFormula(item.getFormulaName(), item.getNumber());
        builder.parseFormulas(formulaParseAdapter).analysisMaterial();
        CostListResult result = new CostListResult();
        Map<String, CostResultItem> formulas = new LinkedHashMap<>();
        builder.getNumberMap().forEach((name, number) -> {
            Formulas formula = builder.getByName(name);
            CostDetailDto detail = builder.getDetail(formula);
            CostResultItem item = new CostResultItem();
            item.setFormulaName(formula.getFormulaName());
            item.setType(formula.getType());
            item.setMaterialId(formula.getMaterialId());
            item.setNumber(number);
            item.setFormula(formula);
            item.setTimes(detail.getTimes());
            item.setActualNumber(detail.getMakeNumber());
            item.setExpectedNumber(detail.getExpectedNumber());
            formulas.put(name, item);
        });
        result.setFormulas(formulas);
        result.setServer(StrUtil.emptyToDefault(request.getServer(), defaultServer));
        computeCost(result, builder);
        floorResponseQuantities(result);
        return R.successByObj(result);
    }

    private void floorResponseQuantities(CostResult result) {
        // 只在全部计算完成后截去数量小数；制作次数、内部消耗及收益仍使用完整期望值。
        if (result instanceof CostItemResult item && item.getExpectedNumber() != null) {
            item.setExpectedNumber(item.getExpectedNumber().setScale(0, RoundingMode.FLOOR));
        } else if (result instanceof CostListResult list) {
            list.getFormulas().values().forEach(item -> {
                if (item.getExpectedNumber() != null) item.setExpectedNumber(item.getExpectedNumber().setScale(0, RoundingMode.FLOOR));
            });
        }
        for (CostDetailDto detail : result.getMakeDetail()) {
            if (detail.getExpectedNumber() != null) detail.setExpectedNumber(detail.getExpectedNumber().setScale(0, RoundingMode.FLOOR));
        }
        for (OutputEstimate output : result.getOutputs()) {
            if (output.getExpectedNumber() != null) output.setExpectedNumber(output.getExpectedNumber().setScale(0, RoundingMode.FLOOR));
            if (output.getAvailableNumber() != null) output.setAvailableNumber(output.getAvailableNumber().setScale(0, RoundingMode.FLOOR));
        }
    }

    private void computeCost(CostResult result, FormulaBuilder builder) {
        result.setCalculationMode(builder.getMode());
        result.setProfessionLevel(builder.getProfessionLevel());
        result.setLuckyOutputAddRate(builder.getLuckyOutputAddRate());
        result.setSpecialItemAddRate(builder.getSpecialItemAddRate());
        result.setEstimateComplete(builder.isEstimateComplete());
        result.setRequiredMap(builder.getMaterialMap());
        result.setMakeDetail(builder.getMakeList());
        result.setEnergies(builder.getTotalEnergies());
        result.setOutputs(new ArrayList<>(builder.getOutputs().values()));
        List<String> notes = new ArrayList<>(builder.getNotes());
        notes.add(builder.getMode() == CalculationMode.EXPECTED
                ? "等级加成在原概率上累加；本结果采用约定的估算模型。幸运加成提高数量区间均值，特殊产物按原概率比例分配总加成，不扣减主产物概率，也不重新归一化。期望达到目标不代表保证产出。产物数量向下取整返回，制作次数与收益使用取整前期望值。"
                : "旧数量模式只计算普通主产物，不将特殊产物计入收益，技艺等级加成未参与计算。使用EXPECTED可计算各产物期望值。");
        notes.add("收益使用当前最低有效挂牌单价，仅为参考估值；精力未折算金币，拆解回收未抵扣成本。");
        if (builder.getMakeList().stream().anyMatch(d -> d.getName().startsWith("玄元"))) {
            notes.add("玄元相关帽鞋涉及境华、地脉玄元与拆解回收；使用不可交易地脉玄元制作的成品不可交易，本估值仅适用于可交易成品。");
        }
        result.setNotes(notes);
        BigDecimal fee = new BigDecimal(feeRate);
        if (!CostCalculator.isValidFeeRate(fee)) throw new IllegalStateException("交易手续费率必须在0至1之间");
        result.setFeeRate(fee);
        result.setCustodyFee(CostCalculator.DEFAULT_CUSTODY_FEE);
        if (!builder.isEstimateComplete()) return;

        Map<String, Integer> required = new LinkedHashMap<>();
        builder.getMaterialMap().forEach((id, material) -> required.put(id, material.getNumber()));
        Map<String, PriceQuote> materialPrices = queryQuotes(result.getServer(), required, true);
        long knownCost = 0;
        boolean costComplete = true;
        for (Material material : result.getRequiredMap().values()) {
            PriceQuote quote = materialPrices.get(material.getId());
            material.setQuote(quote);
            material.setValue(quote.getValue());
            material.setValueString(quote.getValue() == null ? null : BoxUtils.computePrice(quote.getValue()));
            if (quote.getValue() == null) costComplete = false;
            else knownCost = Math.addExact(knownCost, quote.getValue());
        }
        result.setCostComplete(costComplete);
        result.setKnownCost(knownCost);
        result.setKnownCostString(BoxUtils.computePrice(knownCost));
        if (costComplete) {
            result.setCost(knownCost);
            result.setCostString(BoxUtils.computePrice(knownCost));
        }

        Map<String, Integer> products = new LinkedHashMap<>();
        Map<String, PriceQuote> productPrices = new HashMap<>();
        for (OutputEstimate output : result.getOutputs()) {
            if (output.getAvailableNumber() == null || output.getAvailableNumber().signum() == 0) continue;
            PriceQuote existing = materialPrices.get(output.getId());
            if (existing != null && existing.getStatus() != PriceQuote.Status.VENDOR) productPrices.put(output.getId(), existing);
            else products.put(output.getId(), 1);
        }
        productPrices.putAll(queryQuotes(result.getServer(), products, false));
        BigDecimal knownValue = BigDecimal.ZERO;
        boolean valueComplete = true;
        for (OutputEstimate output : result.getOutputs()) {
            if (output.getAvailableNumber() == null) {
                valueComplete = false;
                notes.add(output.getName() + "的产出概率未知，无法计算完整收益。");
                continue;
            }
            if (output.getAvailableNumber().signum() == 0) {
                output.setValue(0L);
                output.setValueString(BoxUtils.computePrice(0L));
                continue;
            }
            PriceQuote quote = productPrices.get(output.getId());
            output.setPriceStatus(quote.getStatus());
            output.setUnitPrice(quote.getUnitPrice());
            if (quote.getUnitPrice() == null) {
                valueComplete = false;
                continue;
            }
            BigDecimal value = output.getAvailableNumber().multiply(BigDecimal.valueOf(quote.getUnitPrice()));
            knownValue = knownValue.add(value);
            output.setValue(value.setScale(0, RoundingMode.HALF_UP).longValueExact());
            output.setValueString(BoxUtils.computePrice(output.getValue()));
        }
        result.setValueComplete(valueComplete);
        result.setKnownValue(knownValue.setScale(0, RoundingMode.HALF_UP).longValueExact());
        result.setKnownValueString(BoxUtils.computePrice(result.getKnownValue()));
        if (valueComplete) {
            result.setValue(result.getKnownValue());
            result.setValueString(result.getKnownValueString());
        }
        if (costComplete && valueComplete) {
            // 使用未取整的总期望收益扣费，最后才取整到铜，避免逐项截断产生累计误差。
            long profit = CostCalculator.calculateProfit(knownValue, knownCost, fee, result.getCustodyFee());
            result.setActualProfit(profit);
            result.setActualProfitString(BoxUtils.computePrice(profit));
        }
    }

    private Map<String, PriceQuote> queryQuotes(String server, Map<String, Integer> quantities, boolean materials) {
        Map<String, Future<PriceQuote>> futures = new LinkedHashMap<>();
        quantities.forEach((id, number) -> futures.put(id, asyncTaskExecutor.submit(() -> {
            if (materials) {
                Long price = jx3BoxRemote.queryVendorPrice(id);
                if (price != null) {
                    PriceQuote quote = new PriceQuote();
                    quote.setStatus(PriceQuote.Status.VENDOR);
                    quote.setUnitPrice(price);
                    quote.setValue(Math.multiplyExact(price, number));
                    return quote;
                }
            }
            return jx3BoxRemote.queryPrice(server, id, number);
        })));
        Map<String, PriceQuote> quotes = new LinkedHashMap<>();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        for (Map.Entry<String, Future<PriceQuote>> entry : futures.entrySet()) {
            Future<PriceQuote> future = entry.getValue();
            PriceQuote quote;
            try {
                quote = future.get(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                future.cancel(true);
                quote = new PriceQuote();
                quote.setStatus(PriceQuote.Status.ERROR);
                log.warn("报价等待被中断：{}", entry.getKey());
            } catch (TimeoutException e) {
                future.cancel(true);
                quote = new PriceQuote();
                quote.setStatus(PriceQuote.Status.TIMEOUT);
            } catch (ExecutionException e) {
                quote = new PriceQuote();
                quote.setStatus(PriceQuote.Status.ERROR);
                log.warn("报价查询失败：{}", entry.getKey(), e.getCause());
            }
            // 工作线程只返回报价，不直接修改响应；超时返回后不会有后台写入导致部分成功。
            quotes.put(entry.getKey(), quote);
        }
        return quotes;
    }
}
