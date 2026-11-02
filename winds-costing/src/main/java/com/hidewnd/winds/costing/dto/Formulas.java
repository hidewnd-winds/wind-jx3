package com.hidewnd.winds.costing.dto;

import com.hidewnd.winds.costing.costant.FormulasEnum;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import java.io.Serializable;
import java.util.List;

@Data
@Schema(description = "配方单耗，缓存不包含制作依赖或本次预测结果")
public class Formulas implements Serializable {
    private String id;
    private String formulaName;
    private FormulasEnum type;
    private String materialId;
    private Integer energies;
    private Integer createMin;
    private Integer createMax;
    private Integer requireLevel;
    private Integer recipeLevel;
    private Boolean needExpertise;
    private List<Material> items;
    private List<FormulaOutput> outputs;
}
