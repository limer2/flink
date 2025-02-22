/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.flink.table.planner.utils;

import org.apache.flink.api.dag.Transformation;
import org.apache.flink.streaming.api.transformations.UnionTransformation;
import org.apache.flink.table.api.CompiledPlan;
import org.apache.flink.table.api.EnvironmentSettings;
import org.apache.flink.table.api.TableEnvironment;
import org.apache.flink.table.api.TableResult;
import org.apache.flink.table.api.internal.TableEnvironmentImpl;
import org.apache.flink.table.api.internal.TableEnvironmentInternal;
import org.apache.flink.table.planner.delegation.PlannerBase;
import org.apache.flink.table.planner.factories.TestValuesTableFactory;
import org.apache.flink.table.planner.plan.ExecNodeGraphCompiledPlan;
import org.apache.flink.test.util.AbstractTestBase;
import org.apache.flink.types.Row;
import org.apache.flink.util.StringUtils;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.rules.ExpectedException;

import javax.annotation.Nullable;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.apache.flink.util.Preconditions.checkArgument;
import static org.apache.flink.util.Preconditions.checkNotNull;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.Assert.assertEquals;

/** The base class for json plan testing. */
public abstract class JsonPlanTestBase extends AbstractTestBase {

    @Rule public ExpectedException exception = ExpectedException.none();

    protected TableEnvironmentInternal tableEnv;

    @Before
    public void setup() throws Exception {
        tableEnv =
                (TableEnvironmentInternal)
                        TableEnvironment.create(EnvironmentSettings.inStreamingMode());
    }

    @After
    public void after() {
        TestValuesTableFactory.clearAllData();
    }

    protected TableResult compileSqlAndExecutePlan(String sql) {
        CompiledPlan compiledPlan = tableEnv.compilePlanSql(sql);
        checkTransformationUids(compiledPlan);
        return tableEnv.executePlan(compiledPlan);
    }

    protected void checkTransformationUids(CompiledPlan compiledPlan) {
        List<Transformation<?>> transformations =
                ((PlannerBase) ((TableEnvironmentImpl) tableEnv).getPlanner())
                        .translateToPlan(
                                ((ExecNodeGraphCompiledPlan) compiledPlan).getExecNodeGraph());

        transformations.stream()
                .flatMap(t -> t.getTransitivePredecessors().stream())
                // UnionTransformations don't need an uid
                .filter(t -> !(t instanceof UnionTransformation))
                .forEach(
                        t ->
                                assertThat(t.getUid())
                                        .as(
                                                "Transformation '"
                                                        + t.getName()
                                                        + "' with description '"
                                                        + t.getDescription()
                                                        + "' should contain a defined uid")
                                        .isNotBlank());
    }

    protected void createTestValuesSourceTable(
            String tableName, List<Row> data, String... fieldNameAndTypes) {
        createTestValuesSourceTable(tableName, data, fieldNameAndTypes, new HashMap<>());
    }

    protected void createTestValuesSourceTable(
            String tableName,
            List<Row> data,
            String[] fieldNameAndTypes,
            Map<String, String> extraProperties) {
        createTestValuesSourceTable(tableName, data, fieldNameAndTypes, null, extraProperties);
    }

    protected void createTestValuesSourceTable(
            String tableName,
            List<Row> data,
            String[] fieldNameAndTypes,
            @Nullable String partitionFields,
            Map<String, String> extraProperties) {
        checkArgument(fieldNameAndTypes.length > 0);

        String dataId = TestValuesTableFactory.registerData(data);
        Map<String, String> properties = new HashMap<>();
        properties.put("connector", "values");
        properties.put("data-id", dataId);
        properties.put("bounded", "true");
        properties.put("disable-lookup", "true");
        properties.putAll(extraProperties);
        createTestSourceTable(tableName, fieldNameAndTypes, partitionFields, properties);
    }

    protected void createTestSourceTable(
            String tableName,
            String[] fieldNameAndTypes,
            @Nullable String partitionFields,
            Map<String, String> properties) {
        checkArgument(fieldNameAndTypes.length > 0);
        String partitionedBy =
                StringUtils.isNullOrWhitespaceOnly(partitionFields)
                        ? ""
                        : "\n partitioned by (" + partitionFields + ") \n";
        String ddl =
                String.format(
                        "CREATE TABLE %s (\n" + "%s\n" + ") %s with (\n%s)",
                        tableName,
                        String.join(",\n", fieldNameAndTypes),
                        partitionedBy,
                        properties.entrySet().stream()
                                .map(e -> String.format("'%s'='%s'", e.getKey(), e.getValue()))
                                .collect(Collectors.joining(",\n")));
        tableEnv.executeSql(ddl);
    }

    protected void createTestValuesSinkTable(String tableName, String... fieldNameAndTypes) {
        createTestValuesSinkTable(tableName, fieldNameAndTypes, new HashMap<>());
    }

    protected void createTestNonInsertOnlyValuesSinkTable(
            String tableName, String... fieldNameAndTypes) {
        createTestValuesSinkTable(
                tableName,
                fieldNameAndTypes,
                new HashMap<String, String>() {
                    {
                        put("sink-insert-only", "false");
                    }
                });
    }

    protected void createTestValuesSinkTable(
            String tableName, String[] fieldNameAndTypes, Map<String, String> extraProperties) {
        createTestValuesSinkTable(tableName, fieldNameAndTypes, null, extraProperties);
    }

    protected void createTestValuesSinkTable(
            String tableName,
            String[] fieldNameAndTypes,
            @Nullable String partitionFields,
            Map<String, String> properties) {

        Map<String, String> extraProperties = new HashMap<>();
        extraProperties.put("connector", "values");

        properties.putAll(extraProperties);

        createTestSinkTable(tableName, fieldNameAndTypes, partitionFields, properties);
    }

    protected void createTestSinkTable(
            String tableName,
            String[] fieldNameAndTypes,
            @Nullable String partitionFields,
            Map<String, String> properties) {
        checkArgument(fieldNameAndTypes.length > 0);
        String partitionedBy =
                StringUtils.isNullOrWhitespaceOnly(partitionFields)
                        ? ""
                        : "\n partitioned by (" + partitionFields + ") \n";
        String ddl =
                String.format(
                        "CREATE TABLE %s (\n" + "%s\n" + ") %s with (\n%s)",
                        tableName,
                        String.join(",\n", fieldNameAndTypes),
                        partitionedBy,
                        properties.entrySet().stream()
                                .map(e -> String.format("'%s'='%s'", e.getKey(), e.getValue()))
                                .collect(Collectors.joining(",\n")));
        tableEnv.executeSql(ddl);
    }

    protected void createTestCsvSourceTable(
            String tableName, List<String> data, String... fieldNameAndTypes) throws IOException {
        checkArgument(fieldNameAndTypes.length > 0);
        File sourceFile = TEMPORARY_FOLDER.newFile();
        Collections.shuffle(data);
        Files.write(sourceFile.toPath(), String.join("\n", data).getBytes());
        String ddl =
                String.format(
                        "CREATE TABLE %s (\n"
                                + "%s\n"
                                + ") with (\n"
                                + "  'connector' = 'filesystem',\n"
                                + "  'path' = '%s',\n"
                                + "  'format' = 'testcsv')",
                        tableName,
                        String.join(",\n", fieldNameAndTypes),
                        sourceFile.getAbsolutePath());
        tableEnv.executeSql(ddl);
    }

    protected File createTestCsvSinkTable(String tableName, String... fieldNameAndTypes)
            throws IOException {
        return createTestCsvSinkTable(tableName, fieldNameAndTypes, null);
    }

    protected File createTestCsvSinkTable(
            String tableName, String[] fieldNameAndTypes, @Nullable String partitionFields)
            throws IOException {
        checkArgument(fieldNameAndTypes.length > 0);
        String partitionedBy =
                StringUtils.isNullOrWhitespaceOnly(partitionFields)
                        ? ""
                        : "\n partitioned by (" + partitionFields + ") \n";
        File sinkPath = TEMPORARY_FOLDER.newFolder();
        String ddl =
                String.format(
                        "CREATE TABLE %s (\n"
                                + "%s\n"
                                + ") %s with (\n"
                                + "  'connector' = 'filesystem',\n"
                                + "  'path' = '%s',\n"
                                + "  'format' = 'testcsv')",
                        tableName,
                        String.join(",\n", fieldNameAndTypes),
                        partitionedBy,
                        sinkPath.getAbsolutePath());
        tableEnv.executeSql(ddl);
        return sinkPath;
    }

    protected void assertResult(List<String> expected, File resultFile) throws IOException {
        List<String> actual = readLines(resultFile);
        assertResult(expected, actual);
    }

    protected void assertResult(List<String> expected, List<String> actual) {
        Collections.sort(expected);
        Collections.sort(actual);
        assertEquals(expected, actual);
    }

    protected List<String> readLines(File path) throws IOException {
        List<String> result = new ArrayList<>();
        for (File file : checkNotNull(path.listFiles())) {
            if (file.isHidden()) {
                continue;
            }
            if (file.isFile()) {
                String value = new String(Files.readAllBytes(file.toPath()));
                result.addAll(Arrays.asList(value.split("\n")));
            } else {
                result.addAll(readLines(file));
            }
        }
        return result;
    }
}
