package org.datacommons.util;

import freemarker.template.Configuration;
import freemarker.template.Template;
import freemarker.template.TemplateException;
import java.io.FileWriter;
import java.io.IOException;
import java.io.Writer;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import org.datacommons.proto.Debug;

public class SummaryReportGenerator {

  // An object to save the information about a stat var. This contains all the necessary getters to
  // access the information in this object from SummaryReport.ftl
  // TODO(chejennifer): Look into a cleaner way to handle information about a stat var
  public static final class StatVarSummary {
    int numObservations = 0;
    Set<String> places = new HashSet<>();
    Set<String> mMethods = new HashSet<>();
    Set<String> units = new HashSet<>();
    Set<String> scalingFactors = new HashSet<>();
    // This set will not be populated when StatVarSummary is generated by PlaceSeriesSummary
    Set<String> dates = new HashSet<>();

    // The following two lists will only be populated for StatVarSummary generated by
    // PlaceSeriesSummary and the value at index n of seriesValues will correspond to the date at
    // index n of seriesDates.
    List<String> seriesDates = new ArrayList<>();
    List<Double> seriesValues = new ArrayList<>();

    public int getNumObservations() {
      return this.numObservations;
    }

    public Set<String> getPlaces() {
      return new TreeSet<>(this.places);
    }

    public Set<String> getMMethods() {
      return new TreeSet<>(this.mMethods);
    }

    public Set<String> getUnits() {
      return new TreeSet<>(this.units);
    }

    public Set<String> getSFactors() {
      return new TreeSet<>(this.scalingFactors);
    }

    public Set<String> getUniqueDates() {
      return new TreeSet<>(this.dates);
    }

    public List<String> getSeriesDates() {
      return this.seriesDates;
    }

    public List<Double> getSeriesValues() {
      return this.seriesValues;
    }
  }

  public static boolean TEST_mode = false;
  public static final String SUMMARY_REPORT_HTML = "summary_report.html";

  public static void generateReportSummary(
      Path outputDir,
      Debug.Log log,
      Map<String, StatVarSummary> svSummaryMap,
      Map<String, PlaceSeriesSummary> placeSeriesSummaryMap)
      throws IOException, TemplateException {
    Configuration cfg = getConfiguration();
    Template template = cfg.getTemplate("SummaryReport.ftl");
    HashMap<String, Object> data = new HashMap<>();
    data.put("levelSummary", log.getLevelSummaryMap());
    data.put("commandArgs", log.getCommandArgs());
    // When testing, we want the order of the sections in the html file to be deterministic
    if (TEST_mode) {
      svSummaryMap = new TreeMap<>(svSummaryMap);
      placeSeriesSummaryMap = new TreeMap<>(placeSeriesSummaryMap);
      PlaceSeriesSummary.TEST_mode = true;
    }
    data.put("svSummaryMap", svSummaryMap);
    data.put("placeSeriesSummaryMap", placeSeriesSummaryMap);
    Writer file = new FileWriter(Paths.get(outputDir.toString(), SUMMARY_REPORT_HTML).toString());
    template.process(data, file);
  }

  private static Configuration getConfiguration() throws IOException {
    Configuration cfg = new Configuration(Configuration.VERSION_2_3_31);
    cfg.setDefaultEncoding("UTF-8");
    cfg.setLocale(Locale.US);
    cfg.setClassForTemplateLoading(SummaryReportGenerator.class, "/");
    return cfg;
  }
}
