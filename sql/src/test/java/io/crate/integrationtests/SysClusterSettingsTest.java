/*
 * Licensed to CRATE Technology GmbH ("Crate") under one or more contributor
 * license agreements.  See the NOTICE file distributed with this work for
 * additional information regarding copyright ownership.  Crate licenses
 * this file to you under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.  You may
 * obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.  See the
 * License for the specific language governing permissions and limitations
 * under the License.
 *
 * However, if you have executed another commercial license agreement
 * with Crate these terms will supersede the license and you may use the
 * software solely pursuant to the terms of the relevant commercial agreement.
 */

package io.crate.integrationtests;

import io.crate.metadata.settings.CrateSettings;
import io.crate.testing.UseJdbc;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.test.ESIntegTestCase;
import org.junit.After;
import org.junit.Test;

import java.util.Map;

import static org.hamcrest.Matchers.is;

@ESIntegTestCase.ClusterScope
@UseJdbc
public class SysClusterSettingsTest extends SQLTransportIntegrationTest {

    @Override
    protected Settings nodeSettings(int nodeOrdinal) {
        Settings.Builder builder = Settings.builder().put(super.nodeSettings(nodeOrdinal));
        builder.put(CrateSettings.BULK_REQUEST_TIMEOUT.settingName(), "42s");
        return builder.build();
    }

    @After
    public void resetSettings() throws Exception {
        execute("reset global stats.operations_log_size, bulk.request_timeout");
    }

    @Test
    public void testSetResetGlobalSetting() throws Exception {
        execute("set global persistent stats.enabled = true");
        execute("select settings['stats']['enabled'] from sys.cluster");
        assertThat(response.rowCount(), is(1L));
        assertThat((Boolean) response.rows()[0][0], is(true));

        execute("reset global stats.enabled");
        execute("select settings['stats']['enabled'] from sys.cluster");
        assertThat(response.rowCount(), is(1L));
        assertThat((Boolean) response.rows()[0][0], is(false));

        execute("set global transient stats = { enabled = true, jobs_log_size = 3, operations_log_size = 4 }");
        execute("select settings['stats']['enabled'], settings['stats']['jobs_log_size']," +
                "settings['stats']['operations_log_size'] from sys.cluster");
        assertThat(response.rowCount(), is(1L));
        assertThat((Boolean) response.rows()[0][0], is(true));
        assertThat((Integer) response.rows()[0][1], is(3));
        assertThat((Integer) response.rows()[0][2], is(4));

        execute("reset global stats");
        execute("select settings['stats']['enabled'], settings['stats']['jobs_log_size']," +
                "settings['stats']['operations_log_size'] from sys.cluster");
        assertThat(response.rowCount(), is(1L));
        assertThat((Boolean) response.rows()[0][0], is(false));
        assertThat((Integer) response.rows()[0][1], is(10_000));
        assertThat((Integer) response.rows()[0][2], is(10_000));
    }

    @Test
    public void testResetPersistent() throws Exception {

        execute("select settings['bulk']['request_timeout'] from sys.cluster");
        assertThat(response.rowCount(), is(1L));
        assertThat((String) response.rows()[0][0], is("42s")); // configured via nodeSettings

        execute("set global persistent bulk.request_timeout = '59s'");
        execute("select settings['bulk']['request_timeout'] from sys.cluster");
        assertThat(response.rowCount(), is(1L));
        assertThat((String) response.rows()[0][0], is("59s"));

        execute("reset global bulk.request_timeout");
        execute("select settings['bulk']['request_timeout'] from sys.cluster");
        assertThat(response.rowCount(), is(1L));
        assertThat((String) response.rows()[0][0], is("42s"));
    }

    @Test
    public void testDynamicTransientSettings() throws Exception {
        Settings.Builder builder = Settings.builder()
            .put(CrateSettings.STATS_JOBS_LOG_SIZE.settingName(), 1)
            .put(CrateSettings.STATS_OPERATIONS_LOG_SIZE.settingName(), 2)
            .put(CrateSettings.STATS_ENABLED.settingName(), false);
        client().admin().cluster().prepareUpdateSettings().setTransientSettings(builder.build()).execute().actionGet();

        execute("select settings from sys.cluster");
        assertEquals(1L, response.rowCount());
        Map<String, Map> settings = (Map<String, Map>) response.rows()[0][0];
        Map stats = settings.get(CrateSettings.STATS.name());
        assertEquals(1, stats.get(CrateSettings.STATS_JOBS_LOG_SIZE.name()));
        assertEquals(2, stats.get(CrateSettings.STATS_OPERATIONS_LOG_SIZE.name()));
        assertEquals(false, stats.get(CrateSettings.STATS_ENABLED.name()));

        internalCluster().fullRestart();

        execute("select settings from sys.cluster");
        assertEquals(1L, response.rowCount());
        settings = (Map<String, Map>) response.rows()[0][0];
        stats = settings.get(CrateSettings.STATS.name());
        assertEquals(CrateSettings.STATS_JOBS_LOG_SIZE.defaultValue(),
            stats.get(CrateSettings.STATS_JOBS_LOG_SIZE.name()));
        assertEquals(CrateSettings.STATS_OPERATIONS_LOG_SIZE.defaultValue(),
            stats.get(CrateSettings.STATS_OPERATIONS_LOG_SIZE.name()));
        assertEquals(CrateSettings.STATS_ENABLED.defaultValue(),
            stats.get(CrateSettings.STATS_ENABLED.name()));
    }

    @Test
    public void testDynamicPersistentSettings() throws Exception {
        Settings.Builder builder = Settings.builder()
            .put(CrateSettings.STATS_OPERATIONS_LOG_SIZE.settingName(), 100);
        client().admin().cluster().prepareUpdateSettings().setPersistentSettings(builder.build()).execute().actionGet();

        execute("select settings from sys.cluster");
        assertEquals(1L, response.rowCount());
        Map<String, Map> settings = (Map<String, Map>) response.rows()[0][0];
        Map bulk = settings.get(CrateSettings.STATS.name());
        assertEquals(100, bulk.get(CrateSettings.STATS_OPERATIONS_LOG_SIZE.name()));

        internalCluster().fullRestart();
        // the gateway recovery is async and
        // it might take a bit until it reads the persisted cluster state and updates the settings expression
        assertBusy(new Runnable() {
            @Override
            public void run() {
                execute("select settings from sys.cluster");
                assertEquals(1L, response.rowCount());
                Map<String, Map> settings = (Map<String, Map>) response.rows()[0][0];
                Map bulk = settings.get(CrateSettings.STATS.name());
                assertEquals(100, bulk.get(CrateSettings.STATS_OPERATIONS_LOG_SIZE.name()));
            }
        });
    }

    @Test
    public void testStaticGatewayDefaultSettings() {
        execute("select settings from sys.cluster");
        assertEquals(1L, response.rowCount());
        Map<String, Map> settings = (Map<String, Map>) response.rows()[0][0];
        Map gateway = settings.get(CrateSettings.GATEWAY.name());
        assertEquals("5m", gateway.get(CrateSettings.GATEWAY_RECOVER_AFTER_TIME.name()));
        assertEquals(-1, gateway.get(CrateSettings.GATEWAY_EXPECTED_NODES.name()));
        assertEquals(-1, gateway.get(CrateSettings.GATEWAY_RECOVERY_AFTER_NODES.name()));
    }

    @Test
    public void testStaticUDCDefaultSettings() {
        execute("select settings['udc'] from sys.cluster");
        assertEquals(1L, response.rowCount());
        Map<String, Map> settings = (Map<String, Map>) response.rows()[0][0];
        assertEquals(4, settings.size());
        assertEquals(true, settings.get(CrateSettings.UDC_ENABLED.name()));
        assertEquals("10m", settings.get(CrateSettings.UDC_INITIAL_DELAY.name()));
        assertEquals("1d", settings.get(CrateSettings.UDC_INTERVAL.name()));
        assertEquals("https://udc.crate.io", settings.get(CrateSettings.UDC_URL.name()));
    }

    @Test
    public void testStatsCircuitBreakerLogsDefaultSettings() {
        execute("select settings['stats'] from sys.cluster");
        assertEquals(1L, response.rowCount());
        Map<String, Map> stats = (Map<String, Map>) response.rows()[0][0];
        Map<String, Map> breaker = stats.get(CrateSettings.STATS_BREAKER.name());
        Map<String, Map> logs = breaker.get(CrateSettings.STATS_BREAKER_JOBS_LOG.name());
        assertThat(logs.get(CrateSettings.STATS_BREAKER_JOBS_LOG_LIMIT.name()), is("5%"));
    }
}
