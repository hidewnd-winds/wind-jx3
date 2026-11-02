package com.hidewnd.winds.costing.handler;

import com.hidewnd.winds.costing.dto.Formulas;
import com.hidewnd.winds.costing.service.Jx3BoxRemote;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class DefaultFormulaParseAdapterImpl implements FormulaParseAdapter {
    private Jx3BoxRemote jx3BoxRemote;

    @Autowired
    public void setJx3BoxRemote(Jx3BoxRemote jx3BoxRemote) {
        this.jx3BoxRemote = jx3BoxRemote;
    }

    @Override
    public Formulas parse(String formulaName) {
        return jx3BoxRemote.queryFormulasAndNumber(null, formulaName);
    }
}
