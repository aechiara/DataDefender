/*
 *
 * Copyright 2014-2019, Armenak Grigoryan, and individual contributors as indicated
 * by the @authors tag. See the copyright.txt in the distribution for a
 * full listing of individual contributors.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 */

package com.strider.datadefender;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import java.text.DateFormat;
import java.text.DecimalFormat;
import java.text.ParseException;
import java.text.SimpleDateFormat;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.regex.Pattern;

import static java.lang.Double.parseDouble;

import static java.util.regex.Pattern.compile;

import org.apache.commons.collections.ListUtils;

import org.apache.log4j.Logger;
import static org.apache.log4j.Logger.getLogger;

import opennlp.tools.util.Span;

import com.strider.datadefender.database.IDBFactory;
import com.strider.datadefender.database.metadata.IMetaData;
import com.strider.datadefender.database.metadata.MatchMetaData;
import com.strider.datadefender.database.sqlbuilder.ISQLBuilder;
import com.strider.datadefender.functions.Utils;
import com.strider.datadefender.report.ReportUtil;
import com.strider.datadefender.specialcase.SpecialCase;
import com.strider.datadefender.utils.CommonUtils;
import com.strider.datadefender.utils.Score;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Clob;
import org.apache.commons.io.IOUtils;

/**
 *
 * @author Armenak Grigoryan
 */
public class DatabaseDiscoverer extends Discoverer {
    private static final Logger LOG = getLogger(DatabaseDiscoverer.class);
    private static final String YES = "yes";
    private static String[]     modelList;

    /**
     * Calls a function defined as an extention
     * @param function
     * @param data
     * @param text
     * @return
     * @throws SQLException
     * @throws NoSuchMethodException
     * @throws SecurityException
     * @throws IllegalAccessException
     * @throws IllegalArgumentException
     * @throws InvocationTargetException
     */
    private Object callExtention(final String function, final MatchMetaData data, final String text)
            throws SQLException, NoSuchMethodException, SecurityException, IllegalAccessException,
                   IllegalArgumentException, InvocationTargetException {
        if ((function == null) || function.equals("")) {
            LOG.warn("Function " + function + " is not defined");

            return null;
        }

        Object value = null;

        try {
            final String className  = Utils.getClassName(function);
            final String methodName = Utils.getMethodName(function);
            final Method method     = Class.forName(className)
                                           .getDeclaredMethod(methodName, new Class[] { MatchMetaData.class, String.class });
            final SpecialCase         instance    = (SpecialCase) Class.forName(className).newInstance();
            final Map<String, Object> paramValues = new HashMap<>(2);

            paramValues.put("metadata", data);
            paramValues.put("text", text);
            value = method.invoke(instance, data, text);
        } catch (InstantiationException | ClassNotFoundException ex) {
            LOG.error(ex.toString());
        }

        return value;
    }

    @SuppressWarnings("unchecked")
    public List<MatchMetaData> discover(final IDBFactory factory, 
            final Properties dataDiscoveryProperties, String vendor)
            throws ParseException, DatabaseDiscoveryException, IOException {
        LOG.info("Data discovery in process");

        // Get the probability threshold from property file
        final double probabilityThreshold = parseDouble(dataDiscoveryProperties.getProperty("probability_threshold"));
        String       calculate_score      = dataDiscoveryProperties.getProperty("score_calculation");

        if (CommonUtils.isEmptyString(calculate_score)) {
            calculate_score = "false";
        }

        LOG.info("Probability threshold [" + probabilityThreshold + "]");

        // Get list of models used in data discovery
        final String models = dataDiscoveryProperties.getProperty("models");

        modelList = models.split(",");
        LOG.info("Model list [" + Arrays.toString(modelList) + "]");

        List<MatchMetaData> finalList = new ArrayList<>();

        for (final String model : modelList) {
            LOG.info("********************************");
            LOG.info("Processing model " + model);
            LOG.info("********************************");

            final Model modelPerson = createModel(dataDiscoveryProperties, model);

            matches = discoverAgainstSingleModel(factory,
                                                 dataDiscoveryProperties,
                                                 modelPerson,
                                                 probabilityThreshold,vendor);
            finalList = ListUtils.union(finalList, matches);
        }

        final DecimalFormat decimalFormat = new DecimalFormat("#.##");

        LOG.info("List of suspects:");

        final Score score           = new Score();
        int         highRiskColumns = 0;
        int         rowCount        = 0;

        for (final MatchMetaData data : finalList) {

            // Row count
            if (YES.equals(calculate_score)) {
                LOG.debug("Counting number of rows ...");
                rowCount = ReportUtil.rowCount(factory, 
                               data.getTableName());
            } else {
                LOG.debug("Skipping counting number of rows ...");
            }

            // Getting 5 sample values
            final List<String> sampleDataList = ReportUtil.sampleData(factory, data);
            // Output
            LOG.info("Column                      : " + data.toString());
            LOG.info(CommonUtils.fixedLengthString('=', data.toString().length() + 30));
            LOG.info("Model                       : " + data.getModel());
            LOG.info("Number of rows in the table : " + rowCount);

            if (YES.equals(calculate_score)) {
                LOG.info("Score                       : " + score.columnScore(rowCount));
            } else {
                LOG.info("Score                       : N/A");
            }

            LOG.info("Sample data");
            LOG.info(CommonUtils.fixedLengthString('-', 11));
            
            sampleDataList.forEach((sampleData) -> {
                LOG.info(sampleData);
            });

            LOG.info("");

            // Score calculation is evaluated with score_calculation parameter
            if (YES.equals(calculate_score) && score.columnScore(rowCount).equals("High")) {
                highRiskColumns++;
            }
        }

        // Only applicable when parameter table_rowcount=yes otherwise score calculation should not be done
        if (YES.equals(calculate_score)) {
            LOG.info("Overall score: " + score.dataStoreScore());
            LOG.info("");

            if ((finalList != null) && (finalList.size() > 0)) {
                LOG.info("============================================");

                final int threshold_count = Integer.valueOf(dataDiscoveryProperties.getProperty("threshold_count"));

                if (finalList.size() > threshold_count) {
                    LOG.info("Number of PI [" + finalList.size() + "] columns is higher than defined threashold ["
                             + threshold_count + "]");
                } else {
                    LOG.info("Number of PI [" + finalList.size()
                             + "] columns is lower or equal than defined threashold [" + threshold_count + "]");
                }

                final int threshold_highrisk =
                    Integer.valueOf(dataDiscoveryProperties.getProperty("threshold_highrisk"));

                if (highRiskColumns > threshold_highrisk) {
                    LOG.info("Number of High risk PI [" + highRiskColumns
                             + "] columns is higher than defined threashold [" + threshold_highrisk + "]");
                } else {
                    LOG.info("Number of High risk PI [" + highRiskColumns
                             + "] columns is lower or equal than defined threashold [" + threshold_highrisk + "]");
                }
            }
        } else {
            LOG.info("Overall score: N/A");
        }

        return matches;
    }

    private List<MatchMetaData> discoverAgainstSingleModel(final IDBFactory factory,
                                                           final Properties dataDiscoveryProperties,
                                                           final Model model,
                                                           final double probabilityThreshold,
                                                           final String vendor)
            throws ParseException, DatabaseDiscoveryException, IOException {
        final IMetaData           metaData = factory.fetchMetaData();
        final List<MatchMetaData> map      = metaData.getMetaData(vendor);

        // Start running NLP algorithms for each column and collect percentage
        matches = new ArrayList<>();

        MatchMetaData             specialCaseData;
        final List<MatchMetaData> specialCaseDataList  = new ArrayList();
        boolean                   specialCase          = false;
        final String              extentionList        = dataDiscoveryProperties.getProperty("extentions");
        String[]                  specialCaseFunctions = null;

        LOG.info("Extention list: " + extentionList);
        
        if (!CommonUtils.isEmptyString(extentionList)) {
            specialCaseFunctions = extentionList.split(",");
            if ((specialCaseFunctions != null) && (specialCaseFunctions.length > 0)) {
                specialCase = true;
            }
        }

        final ISQLBuilder sqlBuilder = factory.createSQLBuilder();
        List<Probability> probabilityList;

        for (final MatchMetaData data : map) {
            final String tableName  = data.getTableName();
            final String columnName = data.getColumnName();

            LOG.debug("Primary key(s) for table " + tableName + ": "+ data.getPkeys().toString() + "]");
            
            if (data.getPkeys().contains(columnName.toLowerCase(Locale.ENGLISH))) {
                LOG.debug("Column [" + columnName + "] is Primary Key. Skipping this column.");
                continue;
            }
            
            LOG.debug("Foreign key(s) for table " + tableName + ": "+ data.getFkeys().toString() + "]");
            if (data.getFkeys().contains(columnName.toLowerCase(Locale.ENGLISH))) {
                LOG.debug("Column [" + columnName + "] is Foreign Key. Skipping this column.");
                continue;
            }            
            
            LOG.debug("Column type: [" + data.getColumnType() + "]");
            probabilityList = new ArrayList<>();
            LOG.info("Analyzing column [" + tableName + "].[" + columnName + "]");

            final String tableNamePattern = dataDiscoveryProperties.getProperty("table_name_pattern");

            if (!CommonUtils.isEmptyString(tableNamePattern)) {
                final Pattern p = compile(tableNamePattern);

                if (!p.matcher(tableName).matches()) {
                    continue;
                }
            }
            
            final String table = sqlBuilder.prefixSchema(tableName);
            
            final int    limit = Integer.parseInt(dataDiscoveryProperties.getProperty("limit"));
            
            final String query = sqlBuilder.buildSelectWithLimit("SELECT " + columnName + 
                                                                 " FROM "  + table      +
                                                                 " WHERE " + columnName + " IS NOT NULL ",
                                                                 limit);

            LOG.debug("Executing query against database: " + query);

            try (Statement stmt = factory.getConnection().createStatement();
                ResultSet resultSet = stmt.executeQuery(query);) {
                while (resultSet.next()) {
                    if (data.getColumnType().equals("BLOB") || data.getColumnType().equals("GEOMETRY")) {
                        continue;
                    }

                    if (model.getName().equals("location") && data.getColumnType().contains("INT")) {
                        continue;
                    }

                    String sentence = "";
                    if (data.getColumnType().equals("CLOB")) {
                        Clob clob = resultSet.getClob(1);
                        InputStream is = clob.getAsciiStream();
                        sentence = IOUtils.toString(is, StandardCharsets.UTF_8.name());
                    } else {
                        sentence = resultSet.getString(1);
                    }
                    LOG.debug(sentence);
                    if (specialCaseFunctions != null && specialCase) {
                        try {
                            for (String specialCaseFunction : specialCaseFunctions) {
                                if ((sentence != null) && !sentence.isEmpty()) {
                                    LOG.debug("sentence: " + sentence);
                                    LOG.debug("data: " + data);
                                    specialCaseData = (MatchMetaData) callExtention(specialCaseFunction, data, sentence);
                                    if (specialCaseData != null) {
                                        if (!specialCaseDataList.contains(specialCaseData)) {
                                            LOG.debug("Adding new special case data: " + specialCaseData.toString());
                                            specialCaseDataList.add(specialCaseData);
                                        }
                                    } else {
                                        LOG.debug("No special case data found");
                                    }
                                }
                            }
                        } catch (NoSuchMethodException | IllegalAccessException | InvocationTargetException e) {
                            LOG.error(e.toString());
                        }
                    }
                    
                    if ((sentence != null) &&!sentence.isEmpty()) {
                        String processingValue;

                        if (data.getColumnType().equals("DATE")
                                || data.getColumnType().equals("TIMESTAMP")
                                || data.getColumnType().equals("DATETIME")) {
                            final DateFormat     originalFormat = new SimpleDateFormat(sentence, Locale.ENGLISH);
                            final DateFormat     targetFormat   = new SimpleDateFormat("MMM d, yy", Locale.ENGLISH);
                            final java.util.Date date           = originalFormat.parse(sentence);

                            processingValue = targetFormat.format(date);
                        } else {
                            processingValue = sentence;
                        }

                        // LOG.debug(sentence);
                        // Convert sentence into tokens
                        final String tokens[] = model.getTokenizer().tokenize(processingValue);

                        // Find names
                        final Span nameSpans[] = model.getNameFinder().find(tokens);

                        // find probabilities for names
                        final double[] spanProbs = model.getNameFinder().probs(nameSpans);

                        // Collect top X tokens with highest probability
                        // display names
                        for (int i = 0; i < nameSpans.length; i++) {
                            final String span = nameSpans[i].toString();

                            if (span.length() > 2) {
                                LOG.debug("Span: " + span);
                                LOG.debug("Covered text is: " + tokens[nameSpans[i].getStart()]);
                                LOG.debug("Probability is: " + spanProbs[i]);
                                probabilityList.add(new Probability(tokens[nameSpans[i].getStart()], spanProbs[i]));
                            }
                        }

                        // From OpenNLP documentation:
                        // After every document clearAdaptiveData must be called to clear the adaptive data in the feature generators.
                        // Not calling clearAdaptiveData can lead to a sharp drop in the detection rate after a few documents.
                        model.getNameFinder().clearAdaptiveData();
                    }
                }
            } catch (SQLException sqle) {
                LOG.error(sqle.toString());
            }

            final double averageProbability = calculateAverage(probabilityList);

            if (averageProbability >= probabilityThreshold) {
                data.setAverageProbability(averageProbability);
                data.setModel(model.getName());
                data.setProbabilityList(probabilityList);
                matches.add(data);
            }
        }

        // Special processing
        if (!specialCaseDataList.isEmpty()) {
            LOG.debug("Special case data is processed :" + specialCaseDataList.toString());

            specialCaseDataList.forEach((specialData) -> {
                matches.add(specialData);
            });
        }

        return matches;
    }
}
