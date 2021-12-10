package org.datacommons.util;

import freemarker.template.Configuration;
import freemarker.template.Template;
import freemarker.template.TemplateException;
import java.awt.*;
import java.awt.geom.Rectangle2D;
import java.io.FileWriter;
import java.io.IOException;
import java.io.Writer;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.SimpleDateFormat;
import java.time.LocalDateTime;
import java.util.*;
import java.util.List;
import org.datacommons.proto.Debug;
import org.jfree.chart.ChartFactory;
import org.jfree.chart.JFreeChart;
import org.jfree.chart.axis.DateAxis;
import org.jfree.chart.plot.XYPlot;
import org.jfree.chart.renderer.xy.XYItemRenderer;
import org.jfree.chart.renderer.xy.XYLineAndShapeRenderer;
import org.jfree.chart.util.ShapeUtils;
import org.jfree.data.time.Day;
import org.jfree.data.time.TimeSeries;
import org.jfree.data.time.TimeSeriesCollection;
import org.jfree.graphics2d.svg.SVGGraphics2D;

public class SummaryReportGenerator {

  public static boolean TEST_mode = false;
  public static final String SUMMARY_REPORT_HTML = "summary_report.html";

  // An object to save the information about a stat var. This contains all the necessary getters to
  // access the information in this object from SummaryReport.ftl
  // TODO(chejennifer): Look into a cleaner way to handle information about a stat var
  public static final class StatVarSummary {
    private static final int CHART_WIDTH = 500;
    private static final int CHART_HEIGHT = 250;

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

    public String getTimeSeriesChartSVG() {
      TimeSeriesCollection dataset = new TimeSeriesCollection();
      TimeSeries timeSeries = new TimeSeries("ts");
      // populate timeSeries with dates and values from this.seriesDates and this.seriesValues
      for (int i = 0; i < this.seriesDates.size(); i++) {
        if (i >= this.seriesValues.size()) break;
        LocalDateTime localDateTime = StringUtil.getValidISO8601Date(seriesDates.get(i));
        if (localDateTime == null) continue;
        timeSeries.addOrUpdate(
            new Day(
                localDateTime.getDayOfMonth(),
                localDateTime.getMonthValue(),
                localDateTime.getYear()),
            this.seriesValues.get(i));
      }
      if (timeSeries.isEmpty()) return "";
      dataset.addSeries(timeSeries);
      // create the time series chart with default settings
      JFreeChart chart = ChartFactory.createTimeSeriesChart("", "", "", dataset);
      XYPlot plot = chart.getXYPlot();
      // create and use a renderer to draw each data point on the time series chart as a diamond and
      // remove the legend
      XYItemRenderer renderer = new XYLineAndShapeRenderer(true, true);
      renderer.setSeriesShape(0, ShapeUtils.createDiamond(5));
      renderer.setSeriesVisibleInLegend(0, false);
      plot.setRenderer(renderer);
      // change the background color of the chart to be white
      plot.setBackgroundPaint(Color.WHITE);
      if (timeSeries.findValueRange().getLength() == 0) {
        // Manually set the range when the values in the time series are all the same. Otherwise,
        // the chart library will draw an axis with multiple ticks all labeled with that same value.
        plot.getRangeAxis().setRange(0, this.seriesValues.get(0) * 2);
      }
      if (this.seriesDates.size() == 1) {
        // Override the date formatter for the x axis when there is only one data point. Otherwise,
        // the chart library will label the single date as 00:00:00.
        DateAxis xAxis = (DateAxis) plot.getDomainAxis();
        String datePattern = StringUtil.getValidISO8601DatePattern(this.seriesDates.get(0));
        if (StringUtil.getValidISO8601DateTemplate(datePattern).isEmpty()) {
          xAxis.setDateFormatOverride(new SimpleDateFormat("yyyy-MM-dd"));
        } else {
          xAxis.setDateFormatOverride(new SimpleDateFormat(datePattern));
        }
      }
      SVGGraphics2D svg = new SVGGraphics2D(CHART_WIDTH, CHART_HEIGHT);
      if (TEST_mode) {
        // When testing, we want the svg clipPath id to be consistent.
        svg.setDefsKeyPrefix("test");
      }
      // draw the chart on the svg
      chart.draw(svg, new Rectangle2D.Double(0, 0, CHART_WIDTH, CHART_HEIGHT));
      return svg.getSVGElement();
    }
  }

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
