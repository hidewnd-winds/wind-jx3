package com.hidewnd.winds.costing.service;


import com.alibaba.fastjson2.JSONObject;
import com.hidewnd.winds.costing.costant.FormulasEnum;
import com.hidewnd.winds.costing.dto.Formulas;
import com.hidewnd.winds.costing.dto.Material;
import com.hidewnd.winds.costing.dto.PriceQuote;

/**
 * Jx3BoxRemote接口
 * 该接口定义了与JX3盒子远程交互的相关方法，主要用于查询材料信息、交易价格以及配方数据等功能
 */
public interface Jx3BoxRemote {


    /**
     * 查询材料详情
     *
     * @param id 材料ID
     * @return material | null return empty Object
     */
    Material queryMaterialById(String id);

/**
 * 根据材料名称查询材料信息的方法
 * @param name 材料名称，用于查询的依据
 * @return 返回匹配名称的材料对象，如果未找到则返回null
 */
    Material queryMaterialByName(String name);

    /**
     * 查询交易行价格
     *
     * @param server 服务器
     * @param itemId 材料ID
     * @param number 数量
     * @return 报价状态及金额，缺价不返回零
     */
    PriceQuote queryPrice(String server, String itemId, int number);

    Long queryVendorPrice(String itemId);


    /**
     * 查询配方及所需材料
     *
     * @param type 所需物品类型
     * @param name 所需物品名称
     * @return 配方
     */
    Formulas queryFormulasAndNumber(FormulasEnum type, String name);

/**
 * 获取指定公式类型的JSON对象
 *
 * @param type 公式类型枚举，用于标识要获取的公式类型
 * @param name 公式名称，用于标识具体的公式
 * @return 返回一个JSONObject对象，包含指定公式类型和名称的公式数据
 */
    JSONObject getFormulasJSON(FormulasEnum type, String name);
}
