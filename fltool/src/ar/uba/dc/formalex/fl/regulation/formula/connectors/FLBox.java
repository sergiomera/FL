package ar.uba.dc.formalex.fl.regulation.formula.connectors;

import ar.uba.dc.formalex.fl.bgtheory.BGUtil;
import ar.uba.dc.formalex.fl.regulation.formula.FLFormula;
import ar.uba.dc.formalex.fl.regulation.formula.terminals.FLInterval;
import ar.uba.dc.formalex.fl.regulation.formula.terminals.FLTerminal;
import ar.uba.dc.formalex.fl.regulation.permission.Permission;
import ar.uba.dc.formalex.fl.regulation.rules.Forbidden;
import ar.uba.dc.formalex.fl.regulation.rules.Obligation;


public class FLBox extends FLFormula{
    private FLFormula formula;
    private FLInterval interval;

    public FLBox(FLFormula formula) {
        this(null,formula);
    }

	public FLBox(FLInterval interval, FLFormula formula) {
        this.interval = interval;
        this.formula = formula;
	}

    @Override
    public FLBox instanciar(String variable, String agente, BGUtil bgUtil, Boolean forceAgent) {
        FLInterval newInterval = null;
        FLFormula newFormula;
        if (interval != null){
            newInterval = interval.instanciar(variable, agente, bgUtil, forceAgent);
            if (newInterval == null)
                return null;
        }
        newFormula = formula.instanciar(variable, agente,  bgUtil, forceAgent);
        if (newFormula == null)
            return null;

        return new FLBox(newInterval,  newFormula);
    }

    public FLFormula getFormula() {
        return formula;
    }

    @Override
    public String toString() {
        String res = "";
        if (interval != null)
            res = interval.toString() + " -> ";
        if (ponerParentesis(formula))
            return res + "(" + formula.toString() + ")";
        else
            return res + formula.toString();
    }

    private boolean ponerParentesis(FLFormula lf) {
        return !(lf instanceof FLNeg || lf instanceof FLTerminal || lf instanceof Permission
                || lf instanceof Forbidden || lf instanceof Obligation);
    }
}
