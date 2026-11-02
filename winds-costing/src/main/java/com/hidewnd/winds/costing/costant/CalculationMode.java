package com.hidewnd.winds.costing.costant;

public enum CalculationMode {
    EXPECTED, RANDOM, MINIMUM;

    public static CalculationMode resolve(CalculationMode mode, Boolean rangeCreate) {
        if (mode != null) return mode;
        if (rangeCreate == null) return EXPECTED;
        return rangeCreate ? RANDOM : MINIMUM;
    }
}
