package net.finmath.montecarlo.interestrate.products;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.number.IsCloseTo.closeTo;

import java.util.stream.IntStream;

import org.junit.experimental.theories.DataPoints;
import org.junit.experimental.theories.FromDataPoints;
import org.junit.experimental.theories.Theories;
import org.junit.experimental.theories.Theory;
import org.junit.runner.RunWith;

import net.finmath.exception.CalculationException;
import net.finmath.marketdata.model.curves.DiscountCurveInterpolation;
import net.finmath.marketdata.model.curves.ForwardCurveInterpolation;
import net.finmath.montecarlo.BrownianMotionLazyInit;
import net.finmath.montecarlo.interestrate.LIBORModel;
import net.finmath.montecarlo.interestrate.LIBORMonteCarloSimulationFromLIBORModel;
import net.finmath.montecarlo.interestrate.models.LIBORMarketModelFromCovarianceModel;
import net.finmath.montecarlo.interestrate.models.covariance.LIBORCovarianceModel;
import net.finmath.montecarlo.interestrate.models.covariance.LIBORCovarianceModelExponentialForm5Param;
import net.finmath.montecarlo.process.EulerSchemeFromProcessModel;
import net.finmath.montecarlo.process.MonteCarloProcess;
import net.finmath.time.TimeDiscretization;
import net.finmath.time.TimeDiscretizationFromArray;

@RunWith(Theories.class)
public class AnalyticDiscountZeroCouponBondTest {

	@DataPoints("timeSpans")
	public static double[] timeSpans = new double[] { 0.01, 0.1, 0.25, 0.5, 1.0 };

	@DataPoints("discountFactors")
	public static double[] discountFactors = IntStream.range(1, 26).mapToDouble(x -> 1.0 - x*0.01).toArray();

	@DataPoints("forwardRates")
	public static double[] forwardRates = IntStream.range(1, 26).mapToDouble(x -> x*0.01).toArray();

	@Theory
	public void testGetValueSinglePeriodInSingleCurve(@FromDataPoints("forwardRates") double forwardRate, @FromDataPoints("timeSpans") double periodLength)
			throws CalculationException {

		TimeDiscretization periodTenor = new TimeDiscretizationFromArray(0.0, periodLength);
		TimeDiscretization processTenor = periodTenor.union(
				new TimeDiscretizationFromArray(0.0, periodLength, 0.1, TimeDiscretizationFromArray.ShortPeriodLocation.SHORT_PERIOD_AT_END));

		ForwardCurveInterpolation forwardCurve = ForwardCurveInterpolation.createForwardCurveFromForwards("",
				new double[] {0.0}, new double[] {forwardRate}, periodLength);
		LIBORCovarianceModel covariance = new LIBORCovarianceModelExponentialForm5Param(processTenor, periodTenor, 1, new double[] { 0.1, 0.1, 0.1, 0.1, 0.1});

		LIBORModel model = new LIBORMarketModelFromCovarianceModel(periodTenor, forwardCurve, covariance);
		MonteCarloProcess process = new EulerSchemeFromProcessModel(model, new BrownianMotionLazyInit(processTenor, 1, 100, 42));

		LIBORMonteCarloSimulationFromLIBORModel simulation = new LIBORMonteCarloSimulationFromLIBORModel(model, process);

		double bondPriceIbor = new AnalyticZeroCouponBond(periodLength).getValue(0.0, simulation).getAverage();
		double bondPriceDiscount = new AnalyticDiscountZeroCouponBond(periodLength).getValue(0.0, simulation).getAverage();

		assertThat(bondPriceIbor, is(closeTo(bondPriceDiscount, 1E-3)));
	}

	@Theory
	public void testGetValueSinglePeriodInMultiCurve(@FromDataPoints("forwardRates") double forwardRate,
			@FromDataPoints("discountFactors") double discountFactor,
			@FromDataPoints("timeSpans") double periodLength)
					throws CalculationException {

		TimeDiscretization periodTenor = new TimeDiscretizationFromArray(0.0, periodLength);
		TimeDiscretization processTenor = periodTenor.union(
				new TimeDiscretizationFromArray(0.0, periodLength, 0.1, TimeDiscretizationFromArray.ShortPeriodLocation.SHORT_PERIOD_AT_END));

		ForwardCurveInterpolation forwardCurve = ForwardCurveInterpolation.createForwardCurveFromForwards("",
				new double[] {0.0}, new double[] {forwardRate}, periodLength);
		DiscountCurveInterpolation discountCurve = DiscountCurveInterpolation.createDiscountCurveFromDiscountFactors("", new double[] { periodLength}, new double[] { discountFactor });
		LIBORCovarianceModel covariance = new LIBORCovarianceModelExponentialForm5Param(processTenor, periodTenor, 1, new double[] { 0.1, 0.1, 0.1, 0.1, 0.1});

		LIBORModel model = new LIBORMarketModelFromCovarianceModel(periodTenor, forwardCurve, discountCurve, covariance);
		MonteCarloProcess process = new EulerSchemeFromProcessModel(model, new BrownianMotionLazyInit(processTenor, 1, 100, 42));

		LIBORMonteCarloSimulationFromLIBORModel simulation = new LIBORMonteCarloSimulationFromLIBORModel(model, process);

		double bondPriceDiscount = new AnalyticDiscountZeroCouponBond(periodLength).getValue(0.0, simulation).getAverage();

		assertThat(bondPriceDiscount, is(closeTo(discountFactor, 1E-3)));
	}
}