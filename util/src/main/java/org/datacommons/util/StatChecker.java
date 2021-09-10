// Copyright 2021 Google LLC
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     https://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package org.datacommons.util;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import org.datacommons.proto.Debug.DataPoint;
import org.datacommons.proto.Debug.DataPoint.DataValue;
import org.datacommons.proto.Debug.StatValidationResult;
import org.datacommons.proto.Debug.StatValidationResult.PercentDifference;
import org.datacommons.proto.Debug.StatValidationResult.StatValidationCounter;
import org.datacommons.proto.Mcf.McfGraph;
import org.datacommons.proto.Mcf.ValueType;

// A checker that checks time-series for holes, variance in values, etc.
//
// TODO: add counters to main log counters
public class StatChecker {
  private static final class SeriesSummary {
    StatValidationResult.Builder validationResult;
    Map<String, DataPoint> timeSeries;
  }

  private static final int MAX_NUM_PLACE_PER_TYPE = 5;
  private static final double SMALL_NUMBER = 0.000001;
  private static final String RECEIVED_SAMPLE_PLACES_KEY = "Received_Sample_Places";
  private static final Character PLACE_NAMESPACE_DELIMITER = '/';
  private final LogWrapper logCtx;
  private final Map<String, SeriesSummary> seriesSummaryMap;
  private final Map<String, Set<String>> samplePlaces;
  private final boolean shouldGenerateSamplePlaces;

  public StatChecker(LogWrapper logCtx, Set<String> samplePlaces) {
    this.logCtx = logCtx;
    this.seriesSummaryMap = new HashMap<>();
    this.samplePlaces = new HashMap<>();
    if (samplePlaces == null) {
      this.shouldGenerateSamplePlaces = true;
    } else {
      this.shouldGenerateSamplePlaces = false;
      this.samplePlaces.put(RECEIVED_SAMPLE_PLACES_KEY, samplePlaces);
    }
  }

  // Given a graph, extract time series info (about the chosen sample places) from the
  // statVarObservation nodes and save it into seriesSummaryMap.
  public void extractSeriesInfoFromGraph(McfGraph graph) {
    for (String nodeId : graph.getNodesMap().keySet()) {
      McfGraph.PropertyValues node = graph.getNodesMap().get(nodeId);
      if (shouldExtractSeriesInfo(node)) {
        extractSeriesInfoFromNode(node);
      }
    }
  }

  // Only extract series information for a statVarObservation node that is about a sample place and
  // that has a value of number type.
  private boolean shouldExtractSeriesInfo(McfGraph.PropertyValues node) {
    String placeDcid = McfUtil.getPropVal(node, Vocabulary.OBSERVATION_ABOUT);
    List<String> types = McfUtil.getPropVals(node, Vocabulary.TYPE_OF);
    McfGraph.Values nodeValues =
        node.getPvsOrDefault(Vocabulary.VALUE, McfGraph.Values.getDefaultInstance());
    if (!types.contains(Vocabulary.STAT_VAR_OBSERVATION_TYPE)
        || placeDcid.isEmpty()
        || nodeValues.getTypedValuesCount() == 0
        || nodeValues.getTypedValues(0).getType() != ValueType.NUMBER) {
      return false;
    }
    if (shouldGenerateSamplePlaces) {
      int nameSpaceSplit = placeDcid.indexOf('/');
      String placeNameSpace = "";
      if (nameSpaceSplit >= 0) {
        placeNameSpace = placeDcid.substring(0, nameSpaceSplit);
      }
      String placekey = placeNameSpace + placeDcid.length();
      // If this is a new place type (determined by place dcid namespace and length) and/or the
      // place type hasn't hit the maximum number of sample places, add place to sample places map.
      if (!samplePlaces.containsKey(placekey)) {
        samplePlaces.put(placekey, new HashSet<>());
      }
      if (samplePlaces.get(placekey).size() >= MAX_NUM_PLACE_PER_TYPE
          && !samplePlaces.get(placekey).contains(placeDcid)) {
        return false;
      }
      samplePlaces.get(placekey).add(placeDcid);
    } else {
      return samplePlaces.get(RECEIVED_SAMPLE_PLACES_KEY).contains(placeDcid);
    }
    return true;
  }

  // Given a statVarObservation node, extract time series info (if it is about the chosen sample
  // places) and save it into seriesSummaryMap.
  private void extractSeriesInfoFromNode(McfGraph.PropertyValues node) {
    StatValidationResult.Builder vres = StatValidationResult.newBuilder();
    vres.setPlaceDcid(McfUtil.getPropVal(node, Vocabulary.OBSERVATION_ABOUT));
    vres.setStatVarDcid(McfUtil.getPropVal(node, Vocabulary.VARIABLE_MEASURED));
    vres.setMeasurementMethod(McfUtil.getPropVal(node, Vocabulary.MEASUREMENT_METHOD));
    vres.setObservationPeriod(McfUtil.getPropVal(node, Vocabulary.OBSERVATION_PERIOD));
    vres.setScalingFactor(McfUtil.getPropVal(node, Vocabulary.SCALING_FACTOR));
    vres.setUnit(McfUtil.getPropVal(node, Vocabulary.SCALING_FACTOR));
    String hash = vres.toString();
    SeriesSummary summary = new SeriesSummary();
    if (seriesSummaryMap.containsKey(hash)) {
      summary = seriesSummaryMap.get(hash);
    } else {
      summary.validationResult = vres;
      summary.timeSeries = new TreeMap<>();
    }
    String obsDate = McfUtil.getPropVal(node, Vocabulary.OBSERVATION_DATE);
    String value = McfUtil.getPropVal(node, Vocabulary.VALUE);
    DataValue dataVal =
        DataValue.newBuilder()
            .setValue(Double.parseDouble(value))
            .addAllLocations(node.getLocationsList())
            .build();
    DataPoint.Builder dataPoint = DataPoint.newBuilder().setDate(obsDate);
    if (summary.timeSeries.containsKey(obsDate)) {
      dataPoint = summary.timeSeries.get(obsDate).toBuilder();
    }
    dataPoint.addValues(dataVal);
    summary.timeSeries.put(obsDate, dataPoint.build());
    seriesSummaryMap.put(hash, summary);
  }

  public void check() {
    for (String hash : seriesSummaryMap.keySet()) {
      List<DataPoint> timeSeries = new ArrayList<>(seriesSummaryMap.get(hash).timeSeries.values());
      StatValidationResult.Builder resBuilder = seriesSummaryMap.get(hash).validationResult;
      // Check inconsistent values (sawtooth).
      checkValueInconsistencies(timeSeries, resBuilder);
      // Check N-Sigma variance.
      checkSigmaDivergence(timeSeries, resBuilder);
      // Check N-Percent fluctuations.
      checkPercentFluctuations(timeSeries, resBuilder);
      // Check for holes in dates, invalid dates, etc.
      checkDates(timeSeries, resBuilder);
      // add result to log.
      logCtx.addStatsCheckSummaryEntry(resBuilder.build());
    }
  }

  protected static void checkValueInconsistencies(
      List<DataPoint> timeSeries, StatValidationResult.Builder resBuilder) {
    StatValidationCounter.Builder inconsistentValueCounter = StatValidationCounter.newBuilder();
    inconsistentValueCounter.setCounterName("Series_Inconsistent_Values");
    for (DataPoint dp : timeSeries) {
      double v = 0;
      boolean vInitialized = false;
      for (DataValue val : dp.getValuesList()) {
        if (vInitialized && val.getValue() != v) {
          inconsistentValueCounter.addProblemPoints(dp);
        }
        vInitialized = true;
        v = val.getValue();
      }
    }
    if (!inconsistentValueCounter.getProblemPointsList().isEmpty()) {
      resBuilder.addValidationCounters(inconsistentValueCounter.build());
    }
  }

  protected static void checkSigmaDivergence(
      List<DataPoint> timeSeries, StatValidationResult.Builder resBuilder) {
    Stats stats = getStats(timeSeries);
    if (stats.stdDev == 0) {
      return;
    }
    StatValidationCounter.Builder sigma1Counter = StatValidationCounter.newBuilder();
    sigma1Counter.setCounterName("Series_Beyond_1_Sigma");
    StatValidationCounter.Builder sigma2Counter = StatValidationCounter.newBuilder();
    sigma2Counter.setCounterName("Series_Beyond_2_Sigma");
    StatValidationCounter.Builder sigma3Counter = StatValidationCounter.newBuilder();
    sigma3Counter.setCounterName("Series_Beyond_3_Sigma");
    // Only add data points to the counter of the greatest standard deviation that it belongs to.
    // ie. if the data point is beyond 3 std deviation, only add it to that counter.
    for (DataPoint dp : timeSeries) {
      double val = dp.getValues(0).getValue();
      if (Math.abs(val - stats.mean) > 3 * stats.stdDev) {
        sigma3Counter.addProblemPoints(dp);
        continue;
      }
      if (Math.abs(val - stats.mean) > 2 * stats.stdDev) {
        sigma2Counter.addProblemPoints(dp);
        continue;
      }
      if (Math.abs(val - stats.mean) > 1 * stats.stdDev) {
        sigma1Counter.addProblemPoints(dp);
      }
    }
    for (StatValidationCounter.Builder counter :
        List.of(sigma1Counter, sigma2Counter, sigma3Counter)) {
      if (counter.getProblemPointsList().isEmpty()) {
        continue;
      }
      resBuilder.addValidationCounters(counter.build());
    }
  }

  private static class Stats {
    double mean = 0;
    double stdDev = 0;
  }

  private static Stats getStats(List<DataPoint> timeSeries) {
    Stats result = new Stats();
    if (timeSeries.size() < 2) return result;
    double weights = 0;
    double sum = 0;
    double sumSqDev = 0;
    for (DataPoint dp : timeSeries) {
      double val = dp.getValues(0).getValue();
      if (weights > 0) {
        sumSqDev += 1 * weights / 1 / (weights + 1) * Math.pow((1 / weights * sum - val), 2);
      }
      weights++;
      sum += val;
    }
    if (weights > 0) {
      result.stdDev = Math.sqrt(sumSqDev * (1.0 / weights));
      result.mean = sum * (1.0 / weights);
    }
    return result;
  }

  // Goes through sorted (but possibly discontinuous) time series, saving the largest fluctuation,
  // and the datapoints that cause this fluctuation.
  // Currently ignore fluctuations starting from 0 (division by 0 problem).
  protected static void checkPercentFluctuations(
      List<DataPoint> timeSeries, StatValidationResult.Builder resBuilder) {
    double maxDelta = 0;
    DataPoint maxDeltaDP = null;
    DataPoint maxDeltaBaseDP = null;
    DataPoint baseDataPoint = null;

    for (DataPoint dp : timeSeries) {
      // Don't try to compare between times because this is a Sawtooth
      if (dp.getValuesCount() > 1) return;
      if (dp.getValuesCount() == 0) continue;
      double currVal = dp.getValues(0).getValue();
      if (baseDataPoint != null) {
        double currDelta;
        double baseVal = baseDataPoint.getValues(0).getValue();
        if (baseVal == 0) {
          currDelta = (currVal) / SMALL_NUMBER;
        } else {
          currDelta = (currVal - baseVal) / Math.abs(baseVal);
        }
        if (Math.abs(maxDelta) < Math.abs(currDelta)) {
          maxDelta = currDelta;
          maxDeltaDP = dp;
          maxDeltaBaseDP = baseDataPoint;
        }
      }
      baseDataPoint = dp;
    }
    PercentDifference.Builder largestPercentDiff =
        resBuilder.getSeriesLargestPercentDiffBuilder().setPercentDifference(maxDelta);
    if (maxDeltaDP != null) {
      largestPercentDiff.setDiffDataPoint(maxDeltaDP);
    }
    if (maxDeltaBaseDP != null) {
      largestPercentDiff.setBaseDataPoint(maxDeltaBaseDP);
    }
    largestPercentDiff.build();
  }

  // Check if there are holes in the dates by inferring based on whether the successive dates have
  // equal duration. If the sizes of the date strings don't match or there are invalid dates, don't
  // try to infer holes and instead mark as "series_inconsistent_date_granularity" and
  // "series_invalid_date".
  protected static void checkDates(
      List<DataPoint> timeSeries, StatValidationResult.Builder resBuilder) {
    Set<LocalDateTime> dateTimes = new TreeSet<>();
    StatValidationCounter.Builder invalidDateCounter = StatValidationCounter.newBuilder();
    invalidDateCounter.setCounterName("Series_Invalid_Date");
    // To keep track of the different lengths of the date strings.
    Map<Integer, List<DataPoint>> dateLen = new HashMap<>();
    // In the first pass, get sorted dates in LocalDateTime form and check for invalid dates and
    // inconsistent date granularities.
    for (DataPoint dp : timeSeries) {
      String date = dp.getDate();
      LocalDateTime dateTime = StringUtil.getValidISO8601Date(date);
      if (dateTime == null) {
        invalidDateCounter.addProblemPoints(dp);
        continue;
      }
      if (!dateLen.containsKey(date.length())) {
        dateLen.put(date.length(), new ArrayList<>());
      }
      dateLen.get(date.length()).add(dp);
      dateTimes.add(dateTime);
    }
    List<Integer> dateLenList = new ArrayList<>(dateLen.keySet());
    if (dateLenList.size() > 1) {
      StatValidationCounter.Builder inconsistentDateCounter = StatValidationCounter.newBuilder();
      inconsistentDateCounter.setCounterName("Series_Inconsistent_Date_Granularity");
      // When there are multiple date granularity, the problem points will be those that are of a
      // different date granularity than the most common one.
      dateLenList.sort((d1, d2) -> dateLen.get(d2).size() - dateLen.get(d1).size());
      for (int i = 1; i < dateLenList.size(); i++) {
        dateLen.get(dateLenList.get(i)).forEach(inconsistentDateCounter::addProblemPoints);
      }
      resBuilder.addValidationCounters(inconsistentDateCounter.build());
      return;
    }
    if (!invalidDateCounter.getProblemPointsList().isEmpty()) {
      resBuilder.addValidationCounters(invalidDateCounter.build());
      return;
    }
    long window = -1;
    LocalDateTime prev = null;
    List<LocalDateTime> dateTimesList = new ArrayList<>(dateTimes);
    // In this second pass, compute the data holes.
    for (LocalDateTime dt : dateTimesList) {
      if (prev != null) {
        long delta = ChronoUnit.MONTHS.between(prev, dt);
        if (window > 0 && window != delta) {
          StatValidationCounter.Builder dataHoleCounter = StatValidationCounter.newBuilder();
          dataHoleCounter.setCounterName("Series_Data_Holes");
          dataHoleCounter.setDetails(
              "Data hole found between the dates: " + prev.toString() + " and " + dt.toString());
          resBuilder.addValidationCounters(dataHoleCounter.build());
          return;
        }
        window = delta;
      }
      prev = dt;
    }
  }
}
