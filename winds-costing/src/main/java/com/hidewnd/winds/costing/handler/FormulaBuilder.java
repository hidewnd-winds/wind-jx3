package com.hidewnd.winds.costing.handler;

import cn.hutool.core.util.RandomUtil;
import cn.hutool.core.util.StrUtil;
import com.hidewnd.winds.common.base.CommonException;
import com.hidewnd.winds.common.base.response.R;
import com.hidewnd.winds.costing.costant.CalculationMode;
import com.hidewnd.winds.costing.dto.*;
import lombok.Getter;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;

/** 请求内展开制作依赖，配方缓存只提供单耗数据。 */
public class FormulaBuilder {
    @Getter
    private final CalculationMode mode;
    @Getter
    private final Map<String, Integer> numberMap = new LinkedHashMap<>();
    private final Map<String, Formulas> roots = new LinkedHashMap<>();
    private final Map<String, Formulas> recipes = new LinkedHashMap<>();
    private final Map<String, Set<String>> dependencies = new LinkedHashMap<>();
    @Getter
    private final Map<String, Material> materialMap = new LinkedHashMap<>();
    @Getter
    private final Map<String, OutputEstimate> outputs = new LinkedHashMap<>();
    @Getter
    private final List<CostDetailDto> makeList = new ArrayList<>();
    @Getter
    private final List<String> notes = new ArrayList<>();
    @Getter
    private Integer totalEnergies;
    @Getter
    private boolean estimateComplete = true;

    private FormulaBuilder(CalculationMode mode) {
        this.mode = mode;
    }

    public static FormulaBuilder create(CalculationMode mode) {
        return new FormulaBuilder(mode);
    }

    public FormulaBuilder addFormula(String name, Integer number) {
        numberMap.merge(normalizeFormulaName(name), number, Math::addExact);
        return this;
    }

    public FormulaBuilder parseFormulas(FormulaParseAdapter adapter) {
        for (String name : numberMap.keySet()) {
            Formulas formula = adapter.parse(name);
            if (formula == null) throw new CommonException(R.CODE_PARAM_ERROR, "配方不存在: " + name);
            roots.put(name, formula);
            recipes.put(formula.getMaterialId(), formula);
        }
        Deque<Formulas> pending = new ArrayDeque<>(recipes.values());
        Map<String, Optional<Formulas>> materialRecipes = new HashMap<>();
        while (!pending.isEmpty()) {
            Formulas formula = pending.removeFirst();
            Set<String> children = new LinkedHashSet<>();
            for (Material material : formula.getItems()) {
                // 名称仅用于定位；必须核对产物ID，避免把同名装备变体当成可互换材料。
                Optional<Formulas> found = materialRecipes.computeIfAbsent(material.getId(), id -> {
                    Formulas candidate = recipes.get(id);
                    if (candidate == null) candidate = adapter.parse(material.getName());
                    return candidate != null && id.equals(candidate.getMaterialId()) ? Optional.of(candidate) : Optional.empty();
                });
                if (found.isEmpty()) continue;
                Formulas child = found.get();
                children.add(child.getMaterialId());
                if (recipes.putIfAbsent(child.getMaterialId(), child) == null) pending.addLast(child);
            }
            dependencies.put(formula.getMaterialId(), children);
        }
        return this;
    }

    public FormulaBuilder analysisMaterial() {
        Map<String, Integer> parents = new LinkedHashMap<>();
        recipes.keySet().forEach(id -> parents.put(id, 0));
        dependencies.values().forEach(children -> children.forEach(id -> parents.merge(id, 1, Integer::sum)));
        Deque<String> ready = new ArrayDeque<>();
        parents.forEach((id, count) -> { if (count == 0) ready.add(id); });
        List<String> order = new ArrayList<>();
        // 父配方先于子配方处理；不同深度的父配方全部完成后，子配方才会按汇总需求取整。
        while (!ready.isEmpty()) {
            String id = ready.removeFirst();
            order.add(id);
            for (String child : dependencies.get(id)) {
                if (parents.merge(child, -1, Integer::sum) == 0) ready.addLast(child);
            }
        }
        if (order.size() != recipes.size()) throw new CommonException("配方存在循环制作依赖，无法计算");
        if (mode == CalculationMode.EXPECTED) {
            for (Formulas formula : recipes.values()) {
                FormulaOutput primary = formula.getOutputs().getFirst();
                if (primary.getProbability() == null || primary.getProbability().signum() == 0) {
                    estimateComplete = false;
                    notes.add(formula.getFormulaName() + "的主产物概率未知或为零，无法估算目标数量成本；配方详情仅列单次消耗。");
                }
            }
        }
        if (!estimateComplete) {
            for (Formulas formula : recipes.values()) {
                CostDetailDto detail = new CostDetailDto();
                detail.setNo(makeList.size() + 1);
                detail.setName(formula.getFormulaName());
                detail.setFormula(formula);
                makeList.add(detail);
            }
            return this;
        }
        Map<String, Integer> demand = new HashMap<>();
        Map<String, Integer> consumed = new HashMap<>();
        roots.forEach((name, formula) -> demand.merge(formula.getMaterialId(), numberMap.get(name), Math::addExact));
        totalEnergies = 0;
        for (String id : order) {
            Formulas formula = recipes.get(id);
            int required = demand.getOrDefault(id, 0);
            int times;
            Integer actual = null;
            BigDecimal expected = null;
            if (mode == CalculationMode.EXPECTED) {
                FormulaOutput primary = formula.getOutputs().getFirst();
                BigDecimal perCraft = primary.getProbability().multiply(BigDecimal.valueOf(
                        (long) primary.getCreateMin() + primary.getCreateMax())).divide(BigDecimal.valueOf(2));
                times = BigDecimal.valueOf(required).divide(perCraft, 0, RoundingMode.CEILING).intValueExact();
                expected = perCraft.multiply(BigDecimal.valueOf(times));
            } else {
                times = 0;
                actual = 0;
                if (mode == CalculationMode.MINIMUM || formula.getCreateMin().equals(formula.getCreateMax())) {
                    times = BigDecimal.valueOf(required).divide(BigDecimal.valueOf(formula.getCreateMin()), 0, RoundingMode.CEILING).intValueExact();
                    actual = Math.multiplyExact(times, formula.getCreateMin());
                } else {
                    while (actual < required) {
                        actual = Math.addExact(actual, RandomUtil.randomInt(formula.getCreateMin(), Math.addExact(formula.getCreateMax(), 1)));
                        times = Math.incrementExact(times);
                    }
                }
            }
            totalEnergies = Math.addExact(totalEnergies, Math.multiplyExact(times, formula.getEnergies()));
            CostDetailDto detail = new CostDetailDto();
            detail.setNo(makeList.size() + 1);
            detail.setName(formula.getFormulaName());
            detail.setTimes(times);
            detail.setMakeNumber(actual);
            detail.setExpectedNumber(expected);
            detail.setFormula(formula);
            makeList.add(detail);
            for (FormulaOutput output : formula.getOutputs()) {
                // 旧模式保持“只模拟主产物数量”的口径；其他可能产物仍在配方详情中返回。
                if (mode != CalculationMode.EXPECTED && output.getSlot() != 1) continue;
                BigDecimal produced = mode == CalculationMode.EXPECTED
                        ? (output.getProbability() == null ? null : output.getProbability()
                            .multiply(BigDecimal.valueOf((long) output.getCreateMin() + output.getCreateMax()))
                            .multiply(BigDecimal.valueOf(times)).divide(BigDecimal.valueOf(2)))
                        : BigDecimal.valueOf(actual);
                OutputEstimate estimate = outputs.get(output.getId());
                if (estimate == null) {
                    estimate = new OutputEstimate();
                    estimate.setId(output.getId());
                    estimate.setName(output.getName());
                    estimate.setExpectedNumber(produced);
                    if (actual != null) estimate.setActualNumber(actual);
                    outputs.put(output.getId(), estimate);
                } else {
                    estimate.setExpectedNumber(produced == null || estimate.getExpectedNumber() == null
                            ? null : estimate.getExpectedNumber().add(produced));
                    if (actual != null) estimate.setActualNumber(Math.addExact(estimate.getActualNumber(), actual));
                }
            }
            for (Material item : formula.getItems()) {
                int amount = Math.multiplyExact(times, item.getNumber());
                if (dependencies.get(id).contains(item.getId())) {
                    demand.merge(item.getId(), amount, Math::addExact);
                    consumed.merge(item.getId(), amount, Math::addExact);
                } else {
                    Material material = materialMap.computeIfAbsent(item.getId(), key -> {
                        Material copy = new Material();
                        copy.setId(item.getId());
                        copy.setName(item.getName());
                        copy.setNumber(0);
                        return copy;
                    });
                    material.setNumber(Math.addExact(material.getNumber(), amount));
                }
            }
        }
        // 仅扣除内部制作消耗，用户指定的成品目标仍可出售；中间产物余量单独保留。
        outputs.forEach((id, output) -> {
            BigDecimal used = BigDecimal.valueOf(consumed.getOrDefault(id, 0));
            output.setConsumedNumber(used);
            output.setAvailableNumber(output.getExpectedNumber() == null ? null : output.getExpectedNumber().subtract(used));
        });
        return this;
    }

    public Formulas getByName(String name) {
        return roots.get(normalizeFormulaName(name));
    }

    public CostDetailDto getDetail(Formulas formula) {
        return makeList.stream().filter(detail -> detail.getFormula().getMaterialId().equals(formula.getMaterialId())).findFirst().orElseThrow();
    }

    public static String normalizeFormulaName(String name) {
        String normalized = StrUtil.emptyIfNull(name).trim();
        if (normalized.startsWith("[") && normalized.endsWith("]")) return normalized.substring(1, normalized.length() - 1).trim();
        return normalized;
    }
}
