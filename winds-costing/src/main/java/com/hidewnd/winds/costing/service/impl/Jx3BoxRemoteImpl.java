package com.hidewnd.winds.costing.service.impl;

import cn.hutool.core.collection.CollectionUtil;
import cn.hutool.core.net.URLEncodeUtil;
import cn.hutool.core.util.StrUtil;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.hidewnd.winds.common.base.CommonException;
import com.hidewnd.winds.costing.costant.FormulasEnum;
import com.hidewnd.winds.costing.dto.*;
import com.hidewnd.winds.costing.service.CacheService;
import com.hidewnd.winds.costing.service.Jx3BoxRemote;
import com.hidewnd.winds.costing.utils.MarketPricing;
import com.hidewnd.winds.costing.utils.RequestUtil;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.net.SocketTimeoutException;
import java.util.*;

@Slf4j
@Service
public class Jx3BoxRemoteImpl implements Jx3BoxRemote {
    public static final String ITEM_SEARCH = "https://helper.jx3box.com/api/item/search?keyword={}&page=1&limit=20";
    public static final String ITEM_PRICE = "https://next2.jx3box.com/api/item-price/{}/detail?server={}";
    public static final String ITEM_FORMULAS = "https://node.jx3box.com/manufactures?client=std&type={}&mode=simple";
    public static final String FORMULA_DETAIL = "https://node.jx3box.com/manufacture/{}/{}?client=std";
    public static final String ITEM_MERGED = "https://node.jx3box.com/resource/std/item_merged.{}";
    public static final String CRAFT_PRICE = "https://node.jx3box.com/craft/price?client=std";
    public static final String CACHE_CRAFT_PRICE = "box:craft:v2:prices";
    public static final String CACHE_MANUFACTURES_TYPE = "box:manufactures:v2:catalog:";
    public static final String CACHE_MANUFACTURES_DATA = "box:manufactures:v2:data:";
    public static final long RECIPE_TTL_SECONDS = 86400;
    // 游戏表内概率以2^20为分母；保留原权重，不把缺失概率重新归一化为必出。
    private static final BigDecimal PROBABILITY_BASE = BigDecimal.valueOf(1048576);

    @Value("${box.default.server:剑胆琴心}")
    private String defaultServer;
    private CacheService cacheService;

    @Autowired
    @Qualifier("redisCacheService")
    public void setCacheService(CacheService cacheService) {
        this.cacheService = cacheService;
    }

    @PostConstruct
    public void initBean() {
        getCraftPrice();
        initFormulasType();
    }

    public Map<String, Long> getCraftPrice() {
        String cached = cacheService.getString(CACHE_CRAFT_PRICE);
        if (cached != null) {
            JSONObject object = JSONObject.parseObject(cached);
            Map<String, Long> prices = new HashMap<>();
            object.forEach((id, value) -> prices.put(id, new BigDecimal(value.toString()).longValueExact()));
            return prices;
        }
        List<JSONObject> array = JSONArray.parseArray(RequestUtil.getRequest(CRAFT_PRICE), JSONObject.class);
        if (array == null || array.isEmpty()) throw new CommonException("商店材料价格响应为空");
        Map<String, Long> prices = new HashMap<>();
        for (JSONObject item : array) {
            if (!Boolean.TRUE.equals(item.getBoolean("IsValid"))) continue;
            Integer index = integer(item, "ItemIndex");
            BigDecimal price = item.getBigDecimal("Price");
            if (index != null && index > 0 && price != null && price.signum() >= 0) {
                prices.put("5_" + index, price.longValueExact());
            }
        }
        cacheService.set(CACHE_CRAFT_PRICE, JSONObject.toJSONString(prices), RECIPE_TTL_SECONDS);
        return prices;
    }

    @Override
    public Long queryVendorPrice(String itemId) {
        return getCraftPrice().get(itemId);
    }

    public void initFormulasType() {
        for (FormulasEnum type : FormulasEnum.values()) queryCatalog(type);
    }

    private List<JSONObject> queryCatalog(FormulasEnum type) {
        String key = CACHE_MANUFACTURES_TYPE + type.getType();
        String cached = cacheService.getString(key);
        if (cached != null) return JSONArray.parseArray(cached, JSONObject.class);
        List<JSONObject> rows = JSONArray.parseArray(RequestUtil.getRequest(ITEM_FORMULAS, type.getType()), JSONObject.class);
        if (rows == null || rows.isEmpty()) throw new CommonException("配方目录响应为空：" + type.getType());
        List<JSONObject> catalog = new ArrayList<>();
        for (JSONObject row : rows) {
            Integer id = integer(row, "ID");
            Integer level = integer(row, "nLevel");
            if (id != null && id > 0 && level != null && level > 0 && StrUtil.isNotBlank(row.getString("Name"))) {
                catalog.add(row);
            }
        }
        cacheService.set(key, JSONObject.toJSONString(catalog), RECIPE_TTL_SECONDS);
        return catalog;
    }

    @Override
    public Material queryMaterialById(String id) {
        JSONObject item = JSONObject.parseObject(RequestUtil.getRequest(ITEM_MERGED, id));
        if (item == null || !id.equals(item.getString("id")) || StrUtil.isBlank(item.getString("Name"))) {
            throw new CommonException("物品详情缺失：" + id);
        }
        return convertMaterial(item);
    }

    public Material convertMaterial(JSONObject item) {
        Material material = new Material();
        material.setId(item.getString("id"));
        material.setName(item.getString("Name"));
        material.setDesc(item.getString("Desc"));
        material.setQuality(integer(item, "Quality"));
        material.setBindType(integer(item, "BindType"));
        material.setUiId(item.getString("UiID"));
        material.setIconId(item.getString("IconID"));
        material.setSourceId(item.getString("SourceID"));
        material.setLink(item.getString("Link"));
        return material;
    }

    @Override
    public Material queryMaterialByName(String name) {
        JSONObject item = queryItem(name);
        return item == null ? null : convertMaterial(item);
    }

    @Override
    public PriceQuote queryPrice(String server, String itemId, int number) {
        try {
            String body = RequestUtil.getRequest(ITEM_PRICE, itemId,
                    URLEncodeUtil.encode(StrUtil.emptyToDefault(server, defaultServer)));
            JSONObject response = JSONObject.parseObject(body);
            if (response == null || !Integer.valueOf(0).equals(response.getInteger("code"))) {
                throw new CommonException("交易行响应失败");
            }
            JSONObject data = response.getJSONObject("data");
            JSONArray prices = data == null ? null : data.getJSONArray("prices");
            List<MarketPricing.Listing> listings = new ArrayList<>();
            if (prices != null) {
                for (JSONObject row : prices.toJavaList(JSONObject.class)) {
                    Long price = row.getLong("unit_price");
                    Integer count = integer(row, "n_count");
                    if (price != null && price > 0 && count != null && count > 0) {
                        listings.add(new MarketPricing.Listing(price, count));
                    }
                }
            }
            return MarketPricing.quote(listings, number);
        } catch (RuntimeException e) {
            // 外部报价失败仍返回配方信息，但不能伪造零价格；区分超时与其他失败。
            PriceQuote quote = new PriceQuote();
            quote.setStatus(PriceQuote.Status.ERROR);
            for (Throwable cause = e; cause != null; cause = cause.getCause()) {
                if (cause instanceof SocketTimeoutException) quote.setStatus(PriceQuote.Status.TIMEOUT);
            }
            log.warn("交易行报价失败：{} {} {}", server, itemId, quote.getStatus(), e);
            return quote;
        }
    }

    @Override
    public Formulas queryFormulasAndNumber(FormulasEnum type, String name) {
        String key = CACHE_MANUFACTURES_DATA + (type == null ? "all" : type.getType()) + ":" + name;
        Formulas cached = cacheService.getObject(key, Formulas.class);
        if (cached != null) return cached;
        JSONObject row = getFormulasJSON(type, name);
        if (row == null) return null;
        Formulas formula = new Formulas();
        formula.setId(row.getString("ID"));
        formula.setFormulaName(row.getString("Name"));
        formula.setType(FormulasEnum.getFormulasEnum(row.getString("__TabType")));
        Integer vigor = integer(row, "CostVigor");
        if (vigor == null || vigor < 0) throw new CommonException("配方精力数据无效：" + name);
        formula.setEnergies(vigor);
        formula.setRequireLevel(integer(row, "RequireLevel"));
        formula.setRecipeLevel(integer(row, "RecipeLevel"));
        Integer expertise = integer(row, "NeedExpertise");
        formula.setNeedExpertise(expertise == null ? null : expertise != 0);
        List<Material> materials = new ArrayList<>();
        List<FormulaOutput> outputs = new ArrayList<>();
        Map<String, Material> itemDetails = new HashMap<>();
        for (int i = 1; i <= 8; i++) {
            Integer itemType = integer(row, "RequireItemType" + i);
            Integer index = integer(row, "RequireItemIndex" + i);
            if (itemType != null && itemType > 0 && index != null && index > 0) {
                Integer count = integer(row, "RequireItemCount" + i);
                if (count == null || count <= 0) throw new CommonException("配方材料数量无效：" + name);
                Material material = queryMaterialById(itemType + "_" + index);
                material.setNumber(count);
                materials.add(material);
                itemDetails.put(material.getId(), material);
            }
            itemType = integer(row, "CreateItemType" + i);
            index = integer(row, "CreateItemIndex" + i);
            if (itemType == null || itemType <= 0 || index == null || index <= 0) continue;
            String id = itemType + "_" + index;
            Material item = itemDetails.computeIfAbsent(id, this::queryMaterialById);
            FormulaOutput output = new FormulaOutput();
            output.setSlot(i);
            output.setId(id);
            output.setName(item.getName());
            output.setQuality(item.getQuality());
            output.setBindType(item.getBindType());
            output.setDesc(item.getDesc());
            output.setCreateMin(integer(row, "CreateItemMin" + i));
            output.setCreateMax(integer(row, "CreateItemMax" + i));
            if (output.getCreateMin() == null || output.getCreateMin() <= 0 || output.getCreateMax() == null
                    || output.getCreateMax() < output.getCreateMin()) {
                throw new CommonException("配方产量范围无效：" + name);
            }
            Integer weight = integer(row, "CreateItemProbability" + i);
            if (weight != null && (weight < 0 || weight > 1048576)) throw new CommonException("配方概率无效：" + name);
            output.setProbabilityWeight(weight);
            output.setProbability(weight == null ? null : BigDecimal.valueOf(weight).divide(PROBABILITY_BASE));
            outputs.add(output);
            if (i == 1) {
                formula.setMaterialId(id);
                formula.setCreateMin(output.getCreateMin());
                formula.setCreateMax(output.getCreateMax());
            }
        }
        if (formula.getMaterialId() == null) throw new CommonException("配方缺少主产物：" + name);
        formula.setItems(materials);
        formula.setOutputs(outputs);
        cacheService.set(key, JSONObject.toJSONString(formula), RECIPE_TTL_SECONDS);
        return formula;
    }

    @Override
    public JSONObject getFormulasJSON(FormulasEnum type, String name) {
        for (FormulasEnum candidate : type == null ? FormulasEnum.values() : new FormulasEnum[]{type}) {
            for (JSONObject item : queryCatalog(candidate)) {
                if (!name.equals(item.getString("Name"))) continue;
                JSONObject detail = JSONObject.parseObject(RequestUtil.getRequest(FORMULA_DETAIL, candidate.getType(), item.getInteger("ID")));
                if (detail == null || !name.equals(detail.getString("Name"))) throw new CommonException("配方详情响应不匹配：" + name);
                detail.put("__TabType", candidate.getType());
                return detail;
            }
        }
        return null;
    }

    private static Integer integer(JSONObject object, String key) {
        Object value = object.get(key);
        if (value == null) return null;
        if (value instanceof Boolean flag) return flag ? 1 : 0;
        return new BigDecimal(value.toString()).intValueExact();
    }

    public JSONObject queryItem(String name) {
        JSONObject json = new JSONObject();
        String body = RequestUtil.getRequest(ITEM_SEARCH, URLEncodeUtil.encode(name));
        if (StrUtil.isNotEmpty(body)) {
            JSONObject jsonObject = JSONObject.parseObject(body);
            if (jsonObject.get("data") != null) {
                JSONObject data = jsonObject.getObject("data", JSONObject.class);
                List<JSONObject> array = JSONArray.parseArray(JSONObject.toJSONString(data.get("data")), JSONObject.class);
                if (CollectionUtil.isNotEmpty(array)) {
                    json = array.stream().filter(obj -> Objects.equals(obj.getString("Name"), name))
                            .filter(obj -> !obj.getString("Desc").contains("已过期"))
                            .reduce((_, second) -> second)
                            .orElse(null);
                    if (json == null) {
                        json = array.getFirst();
                    }
                }
            }
        }
        return json;
    }


}
