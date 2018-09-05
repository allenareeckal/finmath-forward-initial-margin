package net.finmath.xva.tradespecifications;

import net.finmath.xva.coordinates.simm2.Simm2Coordinate;
import net.finmath.xva.initialmargin.SIMMParameter;

import java.util.Set;
import java.util.stream.Collectors;

public class SIMMTradeSpecification {



    Set<Simm2Coordinate> sensitivityKeySet;

    public SIMMTradeSpecification(double notional, double maturity, String IRCurveKey){

    }

    public double   getMaxTimeToMaturity(){
        return 0.0;
    }

    public double    getNotional(){
        return 0.0;
    }

    public SIMMParameter.ProductClass getProductClass(){
        return sensitivityKeySet.stream().map(key->key.getProductClass()).distinct().findAny().get();
    }

    public Set<SIMMParameter.RiskClass> getRiskClasses(){
        return sensitivityKeySet.stream().map(key->key.getRiskClass()).collect(Collectors.toSet());
    }


    public Set<String>  getRiskfactors(){
        return this.sensitivityKeySet.stream().map(key->key.getRiskFactorKey()).collect(Collectors.toSet());
    }

    public String    getTradeID(){
        return "";
    }

    public Set<Simm2Coordinate> getSensitivityKeySet(double evaluationTime) {
        return sensitivityKeySet;
    }


    public IRCurveSpec getIRCurve() {
        //TODO implement
        return null;
    }
}