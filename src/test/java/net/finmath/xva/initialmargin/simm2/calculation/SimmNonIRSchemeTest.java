package net.finmath.xva.initialmargin.simm2.calculation;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.number.IsCloseTo.closeTo;

import java.util.Map;

import org.junit.Test;

import com.google.common.collect.ImmutableMap;

import net.finmath.sensitivities.simm2.MarginType;
import net.finmath.sensitivities.simm2.ProductClass;
import net.finmath.sensitivities.simm2.RiskClass;
import net.finmath.sensitivities.simm2.SimmCoordinate;
import net.finmath.stochastic.RandomVariable;
import net.finmath.stochastic.Scalar;
import net.finmath.xva.initialmargin.simm2.specs.ParameterSet;
import net.finmath.xva.initialmargin.simm2.specs.Simm2_0;

public class SimmNonIRSchemeTest {

	@Test
	public void getMarginForSingleStockDelta() {

		final ParameterSet parameters = new Simm2_0();
		final SimmNonIRScheme scheme = new SimmNonIRScheme(parameters);
		final SimmCoordinate coordinate = new SimmCoordinate(null, "DAX", "11", RiskClass.EQUITY, MarginType.DELTA, ProductClass.EQUITY);
		final double riskWeight = parameters.getRiskWeight(coordinate);

		//By choosing a sensitivity above the threshold we don't have to care about it in the assertion
		final double marketSensitivity = parameters.getConcentrationThreshold(coordinate)*2.0;

		Map<SimmCoordinate, RandomVariable> gradient = ImmutableMap.of(
				coordinate,
				new Scalar(marketSensitivity)
				);

		final RandomVariable result = scheme.getMargin(RiskClass.EQUITY, gradient);

		//For a single weighted sensitivity (one stock)
		//the result should be the weighted sensitivity

		assertThat(result.getAverage(), is(closeTo(marketSensitivity*riskWeight, 1E-8)));

	}

	@Test
	public void getMarginForSingleStockVega() {

		final ParameterSet parameters = new Simm2_0();
		final SimmNonIRScheme scheme = new SimmNonIRScheme(parameters);
		final SimmCoordinate coordinate = new SimmCoordinate(null, "DAX", "11", RiskClass.EQUITY, MarginType.VEGA, ProductClass.EQUITY);
		final double vegaRiskWeight = parameters.getRiskWeight(coordinate);
		final double additionalWeight = parameters.getAdditionalWeight(coordinate);

		//By choosing a sensitivity above the threshold we don't have to care about it in the assertion
		final double marketSensitivity = parameters.getConcentrationThreshold(coordinate)/ additionalWeight * 2.0;

		Map<SimmCoordinate, RandomVariable> gradient = ImmutableMap.of(
				coordinate,
				new Scalar(marketSensitivity)
				);

		final RandomVariable result = scheme.getMargin(RiskClass.EQUITY, gradient);

		//For a single weighted sensitivity (one stock)
		//the result should be the vega risk (VRW * HVR * RW * scale * marketSensitivity)

		assertThat(result.getAverage(), is(closeTo(marketSensitivity*vegaRiskWeight * additionalWeight, 1E-8)));

	}
}