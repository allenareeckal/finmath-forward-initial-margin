package net.finmath.initialmargin.isdasimm.products;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.apache.commons.lang3.ArrayUtils;

import net.finmath.exception.CalculationException;
import net.finmath.initialmargin.isdasimm.aggregationscheme.CalculationSchemeInitialMarginISDA;
import net.finmath.initialmargin.isdasimm.changedfinmath.LIBORMarketModelFromCovarianceModelUtilities;
import net.finmath.initialmargin.isdasimm.sensitivity.AbstractSIMMSensitivityCalculation;
import net.finmath.initialmargin.isdasimm.sensitivity.AbstractSIMMSensitivityCalculation.SensitivityMode;
import net.finmath.initialmargin.isdasimm.sensitivity.AbstractSIMMSensitivityCalculation.WeightMode;
import net.finmath.initialmargin.isdasimm.sensitivity.SIMMSensitivityCalculation;
import net.finmath.montecarlo.RandomVariableFromDoubleArray;
import net.finmath.montecarlo.automaticdifferentiation.RandomVariableDifferentiable;
import net.finmath.montecarlo.interestrate.LIBORMarketModel;
import net.finmath.montecarlo.interestrate.LIBORModelMonteCarloSimulationModel;
import net.finmath.montecarlo.interestrate.models.LIBORMarketModelFromCovarianceModel;
import net.finmath.montecarlo.interestrate.products.TermStructureMonteCarloProduct;
import net.finmath.optimizer.SolverException;
import net.finmath.stochastic.ConditionalExpectationEstimator;
import net.finmath.stochastic.RandomVariable;
import net.finmath.time.TimeDiscretization;
import net.finmath.time.TimeDiscretizationFromArray;

/**
 * This class contains the functions and methods which are shared by all products to be considered for
 * initial margin (MVA) calculation by the SIMM.
 *
 * @author Mario Viehmann
 */
public abstract class AbstractSIMMProduct implements SIMMProductInterface {

	/**
	 * MVA Calculation method. For research interest we allow to approximate the MVA via an expected exposure.
	 * There is no computational benefit of this "approximation".
	 */
	public enum MVAMode {
		/**
		 * Calculation of MVA via expected exposure (neglecting correlation between exposure and interest rates)
		 */
		APPROXIMATION,

		/**
		 * Exact calculation of MVA (stochastic exposures with stochastic discounting).
		 */
		EXACT
	}

	// Product classification within ISDA SIMM
	private String productClass;      // RATES_FX, CREDIT,
	private String[] riskClass;         // INTEREST_RATE, CREDIT_Q, CREDIT_NON_Q, EQUITY, COMMODITY
	private String[] curveIndexNames;   // e.g. OIS & Libor6m
	private String currency;
	private boolean hasOptionality;    // determines the relevance of vega and curvature risk (e.g. Swap has no curvature risk)
	private String bucketKey;         // can be null (e.g. in risk class INTEREST_RATE it is null because the bucket is given by the currency

	// Further variables
	protected Map<Long, RandomVariable> gradient = null;// Same for all evaluationTimes; Is reset for different products
	protected boolean isGradientOfDeliveryProduct = false;
	protected RandomVariable exerciseIndicator;
	/*
	 * Model and Product are separate! When we call "getInitialMargin(time, model)", we set modelCache = model!
	 * Thus, we can check if the model has changed. If it has changed, we have to re-calculate the gradient and clear the sensitivity maps.
	 */
	protected LIBORModelMonteCarloSimulationModel modelCache;
	protected double lastEvaluationTime = -1;
	protected ConditionalExpectationEstimator conditionalExpectationOperator;
	protected AbstractSIMMSensitivityCalculation sensitivityCalculationScheme;
	private CalculationSchemeInitialMarginISDA simmScheme;

	public static final String[] IRMaturityBuckets = {"2w", "1m", "3m", "6m", "1y", "2y", "3y", "5y", "10y", "15y", "20y", "30y"};

	// Define the sensitivity maps.
	/**
	 * The map of delta sensitivities at a specific time. This map is filled once per evaluation time step and the
	 * function <code> getSensitivity </code> defined in class <code> AbstractSIMMProduct </code> which is called in
	 * <code> MarginSchemeIRDelta </code> picks the sensitivies for a specified riskClass, curveIndexName and maturityBucket
	 * from this map. This map may - in contrast to the second map "exactDeltaCache" - contain interpolated sensitivities.
	 */
	private HashMap<String/*RiskClass*/, List<HashMap<String/*curveIndexName*/,
	HashMap<String/*maturityBucket*/, RandomVariable>>>> deltaAtTime = new HashMap<String, List<HashMap<String, HashMap<String, RandomVariable>>>>(); // currently only for INTEREST_RATE riskClass

	/**
	 * The cache for the exact delta sensitivities as given by AAD (or analytic). Unlike the map
	 * "deltaAtTime", this map is not cleared if evaluationTime differs from lastEvaluationTime
	 */
	private HashMap<Double /*time*/, List<HashMap<String/*RiskClass*/, List<HashMap<String/*curveIndexName*/,
	RandomVariable[]>>>>> exactDeltaCache = new HashMap<Double /*time*/, List<HashMap<String/*RiskClass*/, List<HashMap<String/*curveIndexName*/, RandomVariable[]>>>>>();

	//private RandomVariable vegaSensitivity=null;

	/**
	 * The cache of numeraire OIS adjustment factors used in the evaluation of this product in the <code> LIBORMarketModelFromCovarianceModel </code>.
	 * This data is the basis of the OIS curve sensitivities, which we calculate by applying AAD to the numeraire adjustments
	 */
	protected Map<Double, RandomVariable> numeraireAdjustmentMap = new HashMap<>();
	public static boolean isPrintSensis = false;

	/**
	 * Wraps an <code> AbstractLIBORMonteCarloProduct </code> into a product classified according to the SIMM methodology requirement.
	 *
	 * @param productClass    The SIMM product class of this product (RatesFx etc.)
	 * @param riskClass       The SIMM risk class of this product (INTEREST_RATE etc.)
	 * @param curveIndexNames The name of the relevant curves for this product (OIS, Libor6m etc.)
	 * @param currency        The currency of this product
	 * @param bucketKey       The SIMM bucket key of this product (null for risk class INTEREST_RATE)
	 * @param hasOptionality  True if this product is not linear
	 */
	public AbstractSIMMProduct(String productClass,
			String[] riskClass,     // One product may contribute to several risk classes
			String[] curveIndexNames,
			String currency,
			String bucketKey,
			boolean hasOptionality) {

		this.productClass = productClass;
		this.riskClass = riskClass;
		this.curveIndexNames = curveIndexNames;
		this.currency = currency;
		this.hasOptionality = hasOptionality;
		this.bucketKey = bucketKey;
	}

	@Override
	public RandomVariable getInitialMargin(double evaluationTime, LIBORModelMonteCarloSimulationModel model, String calculationCCY) throws CalculationException {
		return getInitialMargin(evaluationTime, model, calculationCCY, SensitivityMode.EXACT, WeightMode.CONSTANT, 0, false, true);
	}

	public RandomVariable getInitialMargin(double evaluationTime,
			LIBORModelMonteCarloSimulationModel model,
			String calculationCCY,
			SensitivityMode sensitivityMode,
			WeightMode liborWeightMode,
			double interpolationStep,
			boolean isUseAnalyticSwapSensis,
			boolean isConsiderOISSensitivities) throws CalculationException {

		if (evaluationTime >= getFinalMaturity()) {
			return new RandomVariableFromDoubleArray(0.0);
		}

		if (this.modelCache == null || !model.equals(this.modelCache) || (sensitivityCalculationScheme != null && (sensitivityMode != sensitivityCalculationScheme.getSensitivityMode() || liborWeightMode != sensitivityCalculationScheme.getWeightMode()))) { // At inception (t=0) or if the model is reset
			setGradient(model); // Set the (new) gradient. The method setModel also clears the sensitivity maps and sets the model as modelCache.
			this.exerciseIndicator = null;
			this.exactDeltaCache.clear();
			if (this instanceof SIMMBermudanSwaption) {
				((SIMMBermudanSwaption) this).clearSwapSensitivityMap();
			}
			this.sensitivityCalculationScheme = new SIMMSensitivityCalculation(sensitivityMode, liborWeightMode, interpolationStep, model, isUseAnalyticSwapSensis, isConsiderOISSensitivities);
			this.simmScheme = new CalculationSchemeInitialMarginISDA(this, calculationCCY);
		}

		return simmScheme.getValue(evaluationTime);
	}

	// for risk weight calibration only. Not used in the thesis.
	public RandomVariable getInitialMargin(double evaluationTime,
			LIBORModelMonteCarloSimulationModel model,
			CalculationSchemeInitialMarginISDA simmScheme) throws CalculationException {

		if (evaluationTime >= getFinalMaturity()) {
			return new RandomVariableFromDoubleArray(0.0);
		}

		if (this.modelCache == null || !model.equals(this.modelCache) || sensitivityCalculationScheme != null) { // At inception (t=0) or if the model is reset
			setGradient(model); // Set the (new) gradient. The method setModel also clears the sensitivity maps and sets the model as modelCache.
			this.exerciseIndicator = null;
			this.exactDeltaCache.clear();
			this.sensitivityCalculationScheme = new SIMMSensitivityCalculation(SensitivityMode.MELTINGSIMMBUCKETS, WeightMode.TIMEDEPENDENT, 1.0, model, true /*isUseAnalyticSwapSensis*/, true /*isConsiderOISSensitivities*/);
		}

		return simmScheme.getValue(this, evaluationTime);
	}

	@Override
	public RandomVariable getSensitivity(String productClass,
			String riskClass,
			String maturityBucket, // only for IR and CREDIT risk class, null otherwise
			String curveIndexName, // null if riskClass is not IR
			String bucketKey,      // currency for IR otherwise bucket number
			String riskType, double evaluationTime) throws SolverException, CloneNotSupportedException, CalculationException {

		RandomVariable result = null;
		RandomVariable[] maturityBucketSensis; // Sensitivities mapped on the SIMM Buckets

		if (!hasOptionality && riskType != "delta") {
			return new RandomVariableFromDoubleArray(0.0);
		}

		if (evaluationTime != lastEvaluationTime) {
			clearMaps();  // Clear the deltaSensitivity Map. It needs to be reset at each time step.
		}

		if (productClass == this.productClass && Arrays.asList(this.riskClass).contains(riskClass)) {

			switch (riskType) {
			case ("delta"):
				switch (riskClass) {
				case ("INTEREST_RATE"):

					if (Arrays.asList(curveIndexNames).contains(curveIndexName) && bucketKey == this.currency) {
						// There exists a sensitivity. Check if the sensitivities (on all maturityBuckets) have already been calculated for given riskClass and riskType)

						if (!deltaAtTime.containsKey(riskClass) || !deltaAtTime.get(riskClass).stream().filter(n -> n.containsKey(curveIndexName)).findAny().isPresent()) {

							// The sensitivities need to be calculated for the given riskClass and riskType
							maturityBucketSensis = sensitivityCalculationScheme.getDeltaSensitivities(this, riskClass, curveIndexName, evaluationTime, modelCache);

							if (isPrintSensis && curveIndexName == "Libor6m") {
								System.out.println(evaluationTime + "\t" + maturityBucketSensis[3].getAverage() + "\t" + maturityBucketSensis[4].getAverage() + "\t" + maturityBucketSensis[5].getAverage() + "\t" + maturityBucketSensis[6].getAverage() + "\t" + maturityBucketSensis[7].getAverage() + "\t" + maturityBucketSensis[8].getAverage() + "\t" + maturityBucketSensis[9].getAverage() + "\t" + maturityBucketSensis[10].getAverage() + "\t" + maturityBucketSensis[11].getAverage());
							}
							// Create a new element of the curveIndex List for given risk class
							HashMap<String, HashMap<String, RandomVariable>> curveIndexNameexactDeltaCache = new HashMap<String, HashMap<String, RandomVariable>>();
							HashMap<String, RandomVariable> bucketSensitivities = new HashMap<String, RandomVariable>();

							for (int i = 0; i < IRMaturityBuckets.length; i++) {
								bucketSensitivities.put(IRMaturityBuckets[i], maturityBucketSensis[i]);
							}
							curveIndexNameexactDeltaCache.put(curveIndexName, bucketSensitivities);

							// Check if list already exist
							if (deltaAtTime.containsKey(riskClass)) {
								deltaAtTime.get(riskClass).add(curveIndexNameexactDeltaCache);
							} else {
								List<HashMap<String/*curveIndexName*/, HashMap<String/*maturityBucket*/, RandomVariable>>> list = new ArrayList<HashMap<String/*curveIndexName*/, HashMap<String/*maturityBucket*/, RandomVariable>>>();
								list.add(curveIndexNameexactDeltaCache);
								deltaAtTime.put(riskClass, list);
							}
						}
						result = deltaAtTime.get(riskClass).stream().filter(n -> n.containsKey(curveIndexName)).findFirst().get().get(curveIndexName).get(maturityBucket);
					} else {
						result = new RandomVariableFromDoubleArray(0.0); // There exists no delta Sensi for risk Class INTEREST_RATE
					}
				break;
				// @Todo Add sensitivity calculation for the subsequent cases
				case ("CREDIT_Q"):
				case ("CREDIT_NON_Q"):
				case ("FX"):
				case ("COMMODITY"):
				case ("EQUITY"):
					result = null;
				}
			break;
			// @Todo Add sensitivity calculation for the subsequent cases
			case ("vega"):
			case ("curvature"):
				switch (riskClass) {
				case ("INTEREST_RATE"): //if(vegaSensitivity!=null) vegaSensitivity = getVegaSensitivityIR(curveIndexNames, product, evaluationTime, model);
				case ("CREDIT_Q"):
				case ("CREDIT_NON_Q"):
				case ("FX"):
				case ("COMMODITY"):
				case ("EQUITY"):
					result = null;
				}
			}
		}

		this.lastEvaluationTime = evaluationTime;
		return result;
	} // end getSensitivity()

	/**
	 * Returns the cache of numeraire adjustments of the LIBOR market model. We need the numeraire adjustments
	 * to calculate the sensitivities w.r.t. the OIS curve.
	 *
	 * @return The cache of numeraire adjustments from the LIBOR market model
	 * @throws CalculationException
	 */
	public Map<Double, RandomVariable> getNumeraireAdjustmentMap() throws CalculationException {
		if (this.numeraireAdjustmentMap == null) {
			getLIBORMonteCarloProduct(0.0).getValue(0.0, modelCache);
			this.numeraireAdjustmentMap = ((LIBORMarketModelFromCovarianceModel)(modelCache.getModel())).getNumeraireAdjustments();
		}
		return this.numeraireAdjustmentMap;
	}

	@Override
	public Map<Long, RandomVariable> getGradient(LIBORModelMonteCarloSimulationModel model) throws CalculationException {

		if (gradient == null) {
			// Calculate the product value as of time 0.
			RandomVariableDifferentiable productValue = (RandomVariableDifferentiable) getLIBORMonteCarloProduct(0.0).getValue(0.0, model);
			// Get the map of numeraire adjustments used specifically for this product
			this.numeraireAdjustmentMap.putAll(((LIBORMarketModelFromCovarianceModel)model.getModel()).getNumeraireAdjustments());
			// Calculate the gradient
			Map<Long, RandomVariable> gradientOfProduct = productValue.getGradient();
			this.gradient = gradientOfProduct;
		}
		return this.gradient;
	}

	/**
	 * Set the cache of exact delta sensitivities (calculated by AAD or analytically for Swaps). This cache is used in the class
	 * <code> SIMMSensitivityCalculation </code> to obtain the sensitivities used for melting and interpolation.
	 *
	 * @param riskClass      The risk class
	 * @param curveIndexName The name of the curve (OIS or Libor6m)
	 * @param time           The time for which the forward sensitivity is calculated
	 * @param model          The LIBOR Market model
	 * @throws SolverException
	 * @throws CloneNotSupportedException
	 * @throws CalculationException
	 */
	private void setExactDeltaCache(String riskClass, String curveIndexName,
			double time, LIBORModelMonteCarloSimulationModel model, boolean isMarketRateSensi) throws SolverException, CloneNotSupportedException, CalculationException {

		// Calculate the sensitivities
		RandomVariable[] deltaSensis = null;
		if (isMarketRateSensi) {
			deltaSensis = sensitivityCalculationScheme.getExactDeltaSensitivities(this, curveIndexName, riskClass, time, model);
		} else {
			if (curveIndexName.equals("Libor6m")) {
				deltaSensis = getLiborModelSensitivities(time, model);
			}
			if (curveIndexName.equals("OIS")) {
				deltaSensis = getOISModelSensitivities(riskClass, time, model);
			}
		}
		// Create a new element of the curveIndex List for given risk class
		HashMap<String, RandomVariable[]> curveIndexNameDeltaCache = new HashMap<String, RandomVariable[]>();
		curveIndexNameDeltaCache.put(curveIndexName, deltaSensis);

		// Check if the list of riskClasses in the HashMap already exist
		if (exactDeltaCache.containsKey(new Double(time))) {
			if (exactDeltaCache.get(new Double(time)).stream().filter(n -> n.containsKey(riskClass)).findAny().isPresent()) {
				exactDeltaCache.get(new Double(time)).stream().filter(n -> n.containsKey(riskClass)).findAny().get().get(riskClass).add(curveIndexNameDeltaCache);
			} else { // there is no of risk classes. Set it.
				List<HashMap<String/*curveIndexName*/, RandomVariable[]>> curveList = new ArrayList<HashMap<String/*curveIndexName*/, RandomVariable[]>>();
				curveList.add(curveIndexNameDeltaCache);
				HashMap<String/*RiskClass*/, List<HashMap<String/*curveIndexName*/, RandomVariable[]>>> riskClassMap = new HashMap<String/*RiskClass*/, List<HashMap<String/*curveIndexName*/, RandomVariable[]>>>();
				riskClassMap.put(riskClass, curveList);
				exactDeltaCache.get(time).add(riskClassMap);
			}
		} else { // no entry at time
			List<HashMap<String/*curveIndexName*/, RandomVariable[]>> curveList = new ArrayList<HashMap<String/*curveIndexName*/, RandomVariable[]>>();
			curveList.add(curveIndexNameDeltaCache);
			HashMap<String/*RiskClass*/, List<HashMap<String/*curveIndexName*/, RandomVariable[]>>> riskClassMap = new HashMap<String/*RiskClass*/, List<HashMap<String/*curveIndexName*/, RandomVariable[]>>>();
			riskClassMap.put(riskClass, curveList);
			List<HashMap<String/*RiskClass*/, List<HashMap<String/*curveIndexName*/, RandomVariable[]>>>> riskClassList = new ArrayList<HashMap<String/*RiskClass*/, List<HashMap<String/*curveIndexName*/, RandomVariable[]>>>>();
			riskClassList.add(riskClassMap);
			exactDeltaCache.put(time, riskClassList);
		}
	}

	/**
	 * Calculate the forward derivatives of the product w.r.t. the LIBORs at a given evaluation time t.
	 * These derivatives are not w.r.t. the LIBORs on the LIBOR period discretization, but w.r.t. the general LIBORs
	 * L(t+i\Delta_T,t+(i+1)\Delta_T;t). This function is called by the subclasses in the overridden functions
	 * <code> getValueLiborSensitivities </code>.
	 *
	 * @param evaluationTime The time for which the forward sensitivities are calculated
	 * @param model          The LIBOR market model
	 * @return The forward LIBOR sensitivities of this product
	 * @throws CalculationException
	 */
	@Override
	public RandomVariable[] getLiborModelSensitivities(double evaluationTime, LIBORModelMonteCarloSimulationModel model) throws CalculationException {
		setConditionalExpectationOperator(evaluationTime, model);
		RandomVariableDifferentiable numeraire = (RandomVariableDifferentiable) model.getNumeraire(evaluationTime);

		// Calculate forward sensitivities
		int numberOfRemainingLibors = getNumberOfRemainingLibors(evaluationTime, model);
		int numberOfSensis = evaluationTime == getNextLiborTime(evaluationTime, model) ? numberOfRemainingLibors : numberOfRemainingLibors + 1;
		RandomVariable[] valueLiborSensitivities = new RandomVariable[numberOfSensis];
		int timeIndexAtEval = model.getTimeDiscretization().getTimeIndexNearestLessOrEqual(evaluationTime);

		// Set all entries of dVdL
		// Set dVdL for last libor which is already fixed (if applicable)
		int timeGridIndicator = 0;
		int lastLiborIndex = model.getLiborPeriodDiscretization().getTimeIndexNearestLessOrEqual(evaluationTime);

		if (numberOfSensis != numberOfRemainingLibors) {
			timeGridIndicator = 1;
			double lastLiborTime = model.getLiborPeriodDiscretization().getTime(lastLiborIndex);
			RandomVariable lastLibor = model.getLIBOR(model.getTimeDiscretization().getTimeIndex(lastLiborTime), lastLiborIndex);
			RandomVariable dVdL = getDerivative(lastLibor, model);
			valueLiborSensitivities[0] = dVdL.mult(numeraire);
		}

		for (int liborIndex = lastLiborIndex + timeGridIndicator; liborIndex < model.getNumberOfLibors(); liborIndex++) {
			RandomVariable liborAtTimeIndex = model.getLIBOR(timeIndexAtEval, liborIndex);
			RandomVariable dVdL = getDerivative(liborAtTimeIndex, model);
			valueLiborSensitivities[liborIndex - lastLiborIndex] = dVdL.mult(numeraire).getConditionalExpectation(conditionalExpectationOperator);
		}

		return valueLiborSensitivities;
	}

	/**
	 * Calculate the sensitivities w.r.t. the OIS Bonds: dV/dP. These sensitivities are calculated using the numeraire adjustments at future
	 * discount times (times at which the model has called the <code> getNumeraire </code> function of the <code> LIBORMarketModelFromCovarianceModel </code>.
	 * The function calculates the AAD derivatives dV/dA i.e. w.r.t. the adjustments at the future discount times and converts them into
	 * dV/dP (sensitivties w.r.t. the OIS Bonds P_{OIS}(t+i\Delta_T, t)).
	 * dV(t)/dP_{OIS}(t_cf;t), i.e. the bond sensitivities at the CF times, may be provided as input to this function such that only the
	 * conversion to dV(t)/dP(t+i\Delta_T;t) is perfromed. This is useful if we have already obtained the sensitivities dV/dP analytically.
	 * This function is called inside the subclasses in the overridden function
	 * <code> getDiscountCurveSensitivities(String riskClass, double evaluationTime) </code>.
	 *
	 * @param evaluationTime The time as of which the forward sensitivities are considered
	 * @param discountTimes  (may be null) The times after evaluation time at which the the numeraire has been used in the last valuation of this product
	 * @param dVdP           (may be null) The sensitivities w.r.t. the OIS bond. Only used if dV/dP is given analytically.
	 * @param riskClass      The risk class
	 * @param model          The LIBOR market model
	 * @return The forward sensitivities w.r.t. the OIS Bonds
	 * @throws CalculationException
	 */
	protected RandomVariable[] getOISModelSensitivities(double evaluationTime, double[] discountTimes,
			RandomVariable[] dVdP, String riskClass, LIBORModelMonteCarloSimulationModel model) throws CalculationException {

		if (dVdP == null || discountTimes == null) { //i.e. need to calculate it with AAD

			// Get map with all numeraire adjustments used for this product (may contain adjustment factors not relevant for this product)
			Map<Double, RandomVariable> adjustmentMap = getNumeraireAdjustmentMap();

			// Filter for adjustments after evaluationTime, since derivatives w.r.t. adjustments in the past may be non-zero.
			Set<Double> adjustmentSetAfterEval = new HashSet<Double>();
			adjustmentSetAfterEval = adjustmentMap.keySet().stream().filter(entry -> entry > evaluationTime).collect(Collectors.toCollection(HashSet::new));
			adjustmentMap.keySet().retainAll(adjustmentSetAfterEval);
			double[] adjustmentTimesAfterEval = ArrayUtils.toPrimitive(Arrays.stream(adjustmentMap.keySet().toArray()).sorted().toArray(Double[]::new));

			//Calculate derivative w.r.t. adjustment
			ArrayList<RandomVariable> dVdPList = new ArrayList<RandomVariable>();
			ArrayList<Double> relevantDiscountTimes = new ArrayList<Double>();
			setConditionalExpectationOperator(evaluationTime, model);
			RandomVariable numeraireAtEval = model.getNumeraire(evaluationTime);
			RandomVariable adjustmentAtEval = LIBORMarketModelFromCovarianceModelUtilities.getNumeraireOISAdjustmentFactor(model, evaluationTime);

			for (int i = 0; i < adjustmentTimesAfterEval.length; i++) {

				// Calculate dVdA
				RandomVariable adjustment = adjustmentMap.get(adjustmentTimesAfterEval[i]);
				RandomVariable dVdA = getDerivative(adjustment, model).getConditionalExpectation(conditionalExpectationOperator).mult(numeraireAtEval);

				if (!(dVdA.getMin() == 0 && dVdA.getMax() == 0)) { // If dVdA is zero the adjustment is assumed to belong to a different product.
					// Calculate dV(t)/dP(t_cf;t) where t_cf are the cash flow times of this product
					RandomVariable bond = LIBORMarketModelFromCovarianceModelUtilities.getForwardBondLibor(model, adjustmentTimesAfterEval[i], evaluationTime);
					dVdPList.add(dVdA.mult(adjustment.squared()).mult(-1.0).div(bond).div(adjustmentAtEval));
					relevantDiscountTimes.add(adjustmentTimesAfterEval[i]);
				}
			}
			dVdP = dVdPList.toArray(new RandomVariable[0]);
			discountTimes = ArrayUtils.toPrimitive(Arrays.stream(relevantDiscountTimes.toArray()).toArray(Double[]::new));
			if (dVdP.length == 0) {
				return AbstractSIMMSensitivityCalculation.zeroBucketsIR;
			}
		}

		// Perform a log-linear interpolation of the discount factors to obtain dP(t_cf;t)/dP(t+i\Delta_T;t).
		int numberOfP = getNumberOfRemainingLibors(evaluationTime, model);
		RandomVariable[][] dPdP = new RandomVariable[discountTimes.length][numberOfP];

		double deltaT = model.getLiborPeriodDiscretization().getTimeStep(0);
		TimeDiscretization timesP = new TimeDiscretizationFromArray(evaluationTime, numberOfP, deltaT);
		for (int cfIndex = 0; cfIndex < dPdP.length; cfIndex++) {
			int lowerIndex = timesP.getTimeIndexNearestLessOrEqual(discountTimes[cfIndex]);
			double alpha = (discountTimes[cfIndex] - timesP.getTime(lowerIndex)) / deltaT;
			Arrays.fill(dPdP[cfIndex], new RandomVariableFromDoubleArray(0.0));
			RandomVariable bondLowerIndex = lowerIndex == 0 ? new RandomVariableFromDoubleArray(1.0) : LIBORMarketModelFromCovarianceModelUtilities.getForwardBondOIS(model, timesP.getTime(lowerIndex), evaluationTime);
			RandomVariable bondUpperIndex = LIBORMarketModelFromCovarianceModelUtilities.getForwardBondOIS(model, timesP.getTime(lowerIndex + 1), evaluationTime);
			RandomVariable bondAtCF = LIBORMarketModelFromCovarianceModelUtilities.getForwardBondOIS(model, discountTimes[cfIndex], evaluationTime);
			dPdP[cfIndex][lowerIndex] = bondAtCF.mult(1 - alpha).div(bondLowerIndex);
			dPdP[cfIndex][lowerIndex + 1] = bondAtCF.mult(alpha).div(bondUpperIndex);
		}

		// Calulate dV(t)/dP(t+i\Delta_T;t)
		dVdP = AbstractSIMMSensitivityCalculation.multiply(dVdP, dPdP);

		return dVdP;
	}

	/**
	 * Clear the time dependent delta cache and the vega sensitivity.
	 * This is performed always upon change of the evaluation time of initial margin.
	 */
	public void clearMaps() {
		if (this.deltaAtTime != null) {
			this.deltaAtTime.clear();
			//this.vegaSensitivity = null;
		}
	}

	@Override
	public RandomVariable[] getExactDeltaFromCache(double time, String riskClass, String curveIndexName, boolean isMarketRateSensi) throws SolverException, CloneNotSupportedException, CalculationException {

		if (!exactDeltaCache.containsKey(time) || !exactDeltaCache.get(time).stream().filter(n -> n.containsKey(riskClass)).findAny().isPresent()) {

			for (String curveName : curveIndexNames) {
				setExactDeltaCache(riskClass, curveName, time, modelCache, isMarketRateSensi);
			}
		}

		return exactDeltaCache.get(time).stream().filter(n -> n.containsKey(riskClass)).findAny().get().get(riskClass).stream().filter(n -> n.containsKey(curveIndexName)).findAny().get().get(curveIndexName);
	}


	/*
	 * Getters and setters
	 */

	/**
	 * Set the LIBOR market model in the model cache, set the gradient of the product w.r.t. the new model.
	 *
	 * @param model The LIBOR market model
	 * @throws CalculationException
	 */
	public void setGradient(LIBORModelMonteCarloSimulationModel model) throws CalculationException {
		// If the model is set, we must clear all maps and set the gradient to null.
		clearMaps(); //...the maps containing sensitivities are reset to null (they have to be recalculated under the new model)
		this.modelCache = model;
		this.gradient = null;
		this.gradient = getGradient(model);
		this.isGradientOfDeliveryProduct = false; // for (bermudan) swaptions
	}

	public abstract TermStructureMonteCarloProduct getLIBORMonteCarloProduct(double time);

	public String getProductClass() {
		return this.productClass;
	}

	public String[] getRiskClasses() {
		return this.riskClass;
	}

	public String[] getCurveIndexNames() {
		return this.curveIndexNames;
	}

	public String getCurrency() {
		return this.currency;
	}

	public boolean getHasOptionality() {
		return this.hasOptionality;
	}

	public String getBucketKey() {
		return this.bucketKey;
	}

	public HashMap<Double /*time*/, List<HashMap<String/*RiskClass*/, List<HashMap<String/*curveIndexName*/,
	RandomVariable[]>>>>> getExactDeltaCache() {
		return this.exactDeltaCache;
	}

	public void clearDeltaCache() {
		this.exactDeltaCache.clear();
	}

	public void setSIMMSensitivityCalculation(AbstractSIMMSensitivityCalculation sensitivityCalculation) {
		this.sensitivityCalculationScheme = sensitivityCalculation;
	}

	public void setNullExerciseIndicator() {
		this.exerciseIndicator = null;
	}

	protected RandomVariable getDerivative(RandomVariable parameter, LIBORModelMonteCarloSimulationModel model) throws CalculationException {
		Map<Long, RandomVariable> gradient = getGradient(model);
		RandomVariable derivative = gradient.get(((RandomVariableDifferentiable) parameter).getID());
		return derivative == null ? new RandomVariableFromDoubleArray(0.0) : derivative;
	}

	protected double getNextLiborTime(double evaluationTime, LIBORModelMonteCarloSimulationModel model) {
		int nextLiborIndex = model.getLiborPeriodDiscretization().getTimeIndexNearestGreaterOrEqual(evaluationTime);
		return model.getLiborPeriodDiscretization().getTime(nextLiborIndex);
	}

	protected int getNumberOfRemainingLibors(double evaluationTime, LIBORModelMonteCarloSimulationModel model) {
		int nextLiborIndex = model.getLiborPeriodDiscretization().getTimeIndexNearestGreaterOrEqual(evaluationTime);
		return model.getNumberOfLibors() - nextLiborIndex;
	}

	protected double getPreviousLiborTime(double evaluationTime, LIBORModelMonteCarloSimulationModel model) {
		if (evaluationTime == 0) {
			return 0.0;
		}
		int nextLiborIndex = model.getLiborPeriodDiscretization().getTimeIndexNearestGreaterOrEqual(evaluationTime);
		return model.getLiborPeriodDiscretization().getTime(nextLiborIndex - 1);
	}

	public double getMVA(LIBORModelMonteCarloSimulationModel model, SensitivityMode sensitivityMode, WeightMode weightMode, double timeStep, double fundingSpread, MVAMode mvaMode) throws CalculationException {
		double finalMaturity = this.getFinalMaturity();
		RandomVariable forwardBond;
		RandomVariable initialMargin;
		RandomVariable MVA = new RandomVariableFromDoubleArray(0.0);
		for (int i = 0; i < ((int) finalMaturity / timeStep); i++) {
			forwardBond = model.getNumeraire((i + 1) * timeStep).mult(Math.exp((i + 1) * timeStep * fundingSpread)).invert();
			forwardBond = forwardBond.sub(model.getNumeraire(i * timeStep).mult(Math.exp(i * timeStep * fundingSpread)).invert());
			initialMargin = getInitialMargin(i * timeStep, model, "EUR", sensitivityMode, weightMode, 1.0, false, true);
			if (mvaMode == MVAMode.APPROXIMATION) {
				initialMargin = initialMargin.average();
			}
			MVA = MVA.add(forwardBond.mult(initialMargin));
		}
		return -MVA.getAverage();
	}

	//----------------------------------------------------------------------------------------------------------------------------------
	// Additional method for the case SensitivityMode.ExactConsideringDependencies, i.e. correct OIS-Libor dependence
	// NOT USED IN THE THESIS! PRELIMINARY TRIAL
	//----------------------------------------------------------------------------------------------------------------------------------

	/**
	 * Calculate the forward derivatives of the product w.r.t. the Numeraires at a given evaluation time.
	 * These derivatives are w.r.t. the numeraires on the Libor period discretization.
	 * This function is called by the subclasses in the overridden functions
	 * <code> getValueNumeraireSensitivities </code>.
	 *
	 * @param evaluationTime The time for which the forward sensitivities are calculated
	 * @param model          The libor market model
	 * @return The forward Numeraire sensitivities of this product
	 * @throws CalculationException
	 */
	protected RandomVariable[] getValueNumeraireSensitivitiesAAD(double evaluationTime, LIBORModelMonteCarloSimulationModel model) throws CalculationException {

		RandomVariable numeraireAtEval = model.getNumeraire(evaluationTime);
		Map<Long, RandomVariable> gradientOfNumeraireAtEval = ((RandomVariableDifferentiable) numeraireAtEval).getGradient();
		setConditionalExpectationOperator(evaluationTime, model);
		RandomVariable productValueAtEval = getLIBORMonteCarloProduct(evaluationTime).getValue(evaluationTime, model).getConditionalExpectation(conditionalExpectationOperator);
		// Calculate forward sensitivities
		int numberOfRemainingLibors = getNumberOfRemainingLibors(evaluationTime, model);
		int numberOfSensis = evaluationTime == getNextLiborTime(evaluationTime, model) ? numberOfRemainingLibors : numberOfRemainingLibors + 1;
		RandomVariable[] valueNumeraireSensitivities = new RandomVariable[numberOfSensis];

		// Set dVdN for last numeraire which is already fixed (if applicable)
		int timeGridIndicator = evaluationTime == getNextLiborTime(evaluationTime, model) ? 0 : 1;
		int lastLiborIndex = model.getLiborPeriodDiscretization().getTimeIndexNearestLessOrEqual(evaluationTime);

		if (numberOfSensis != numberOfRemainingLibors) {
			double lastLiborTime = model.getLiborPeriodDiscretization().getTime(lastLiborIndex);
			RandomVariable lastNumeraire = model.getNumeraire(lastLiborTime);
			RandomVariable dVdN = getDerivative(lastNumeraire, model);
			RandomVariable numeraireDerivative = gradientOfNumeraireAtEval.get(((RandomVariableDifferentiable) lastNumeraire).getID());
			RandomVariable dVdNSummand = numeraireDerivative == null ? new RandomVariableFromDoubleArray(0.0) : numeraireDerivative.mult(productValueAtEval);
			valueNumeraireSensitivities[0] = dVdN.mult(numeraireAtEval).add(dVdNSummand);
		}

		for (int liborIndex = lastLiborIndex + timeGridIndicator; liborIndex < model.getNumberOfLibors(); liborIndex++) {
			RandomVariable numeraire = model.getNumeraire(model.getLiborPeriod(liborIndex));
			RandomVariable dVdN = getDerivative(numeraire, model);
			RandomVariable numeraireDerivative = gradientOfNumeraireAtEval.get(((RandomVariableDifferentiable) numeraire).getID());
			RandomVariable dVdNSummand = numeraireDerivative == null ? new RandomVariableFromDoubleArray(0.0) : numeraireDerivative.mult(productValueAtEval);
			valueNumeraireSensitivities[liborIndex - lastLiborIndex] = dVdN.mult(numeraireAtEval).getConditionalExpectation(conditionalExpectationOperator).add(dVdNSummand);
		}

		return valueNumeraireSensitivities;
	}
}
