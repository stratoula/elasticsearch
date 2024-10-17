/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */

package org.elasticsearch.action.admin.indices;

import org.apache.http.util.EntityUtils;
import org.elasticsearch.client.Request;
import org.elasticsearch.client.RequestOptions;
import org.elasticsearch.client.Response;
import org.elasticsearch.client.ResponseException;
import org.elasticsearch.common.xcontent.XContentHelper;
import org.elasticsearch.common.xcontent.support.XContentMapValues;
import org.elasticsearch.core.Strings;
import org.elasticsearch.tasks.Task;
import org.elasticsearch.test.cluster.ElasticsearchCluster;
import org.elasticsearch.test.cluster.local.LocalClusterSpecBuilder;
import org.elasticsearch.test.cluster.local.distribution.DistributionType;
import org.elasticsearch.test.rest.ESRestTestCase;
import org.elasticsearch.xcontent.ObjectPath;
import org.elasticsearch.xcontent.json.JsonXContent;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.rules.TestName;

import java.io.IOException;
import java.util.Locale;
import java.util.Map;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;

public class IndexMultiProjectCRUDIT extends ESRestTestCase {

    protected static final int NODE_NUM = 3;

    @ClassRule
    public static ElasticsearchCluster cluster = createCluster();

    @Rule
    public final TestName testNameRule = new TestName();

    private static ElasticsearchCluster createCluster() {
        LocalClusterSpecBuilder<ElasticsearchCluster> clusterBuilder = ElasticsearchCluster.local()
            .nodes(NODE_NUM)
            .distribution(DistributionType.INTEG_TEST) // TODO multi-rpoject: make this test suite work under the default distrib
            .module("multi-project")
            .setting("xpack.security.enabled", "false") // TODO multi-project: make this test suite work with Security enabled
            .setting("xpack.ml.enabled", "false"); // TODO multi-project: make this test suite work with ML enabled
        return clusterBuilder.build();
    }

    @Override
    protected String getTestRestCluster() {
        return cluster.getHttpAddresses();
    }

    public void testSameIndexNameInDifferentProjectsAllowed() throws Exception {
        final String projectId1 = "projectid1" + testNameRule.getMethodName().toLowerCase(Locale.ROOT);
        createProject(projectId1);
        final String projectId2 = "projectid2" + testNameRule.getMethodName().toLowerCase(Locale.ROOT);
        createProject(projectId2);
        final String indexName = "testindex" + testNameRule.getMethodName().toLowerCase(Locale.ROOT);
        final int numberOfShards1 = randomIntBetween(1, 3);
        final int numberOfReplicas1 = randomIntBetween(0, NODE_NUM - 1);
        final int numberOfShards2 = randomIntBetween(1, 3);
        final int numberOfReplicas2 = randomIntBetween(0, NODE_NUM - 1);
        {
            Request putIndexRequest1 = new Request("PUT", "/" + indexName + "?wait_for_active_shards=all&master_timeout=999s&timeout=999s");
            putIndexRequest1.setJsonEntity(Strings.format("""
                {
                    "settings": {
                      "number_of_shards": %d,
                      "number_of_replicas": %d
                    }
                }
                """, numberOfShards1, numberOfReplicas1));
            setRequestProjectId(putIndexRequest1, projectId1);
            Response putIndexResponse = client().performRequest(putIndexRequest1);
            assertOK(putIndexResponse);
            var putIndexResponseBodyMap = toMap(putIndexResponse);
            assertTrue((boolean) XContentMapValues.extractValue("acknowledged", putIndexResponseBodyMap));
            assertTrue((boolean) XContentMapValues.extractValue("shards_acknowledged", putIndexResponseBodyMap));
            assertThat((String) XContentMapValues.extractValue("index", putIndexResponseBodyMap), is(indexName));
        }
        {
            Request putIndexRequest2 = new Request("PUT", "/" + indexName + "?wait_for_active_shards=all&master_timeout=999s&timeout=999s");
            putIndexRequest2.setJsonEntity(Strings.format("""
                {
                    "settings": {
                      "number_of_shards": %d,
                      "number_of_replicas": %d
                    }
                }
                """, numberOfShards2, numberOfReplicas2));
            setRequestProjectId(putIndexRequest2, projectId2);
            Response putIndexResponse = client().performRequest(putIndexRequest2);
            assertOK(putIndexResponse);
            var putIndexResponseBodyMap = toMap(putIndexResponse);
            assertTrue((boolean) XContentMapValues.extractValue("acknowledged", putIndexResponseBodyMap));
            assertTrue((boolean) XContentMapValues.extractValue("shards_acknowledged", putIndexResponseBodyMap));
            assertThat((String) XContentMapValues.extractValue("index", putIndexResponseBodyMap), is(indexName));
        }
        final Request getIndexRequest = new Request("GET", "/" + indexName);
        final String uuidInProject1;
        {
            setRequestProjectId(getIndexRequest, projectId1);
            Response getIndexResponse = client().performRequest(getIndexRequest);
            Map<String, Object> indexSettingsMap = getIndexSettingsFromResponse(getIndexResponse, indexName);
            assertThat(indexSettingsMap.get("number_of_shards"), is(String.valueOf(numberOfShards1)));
            assertThat(indexSettingsMap.get("number_of_replicas"), is(String.valueOf(numberOfReplicas1)));
            uuidInProject1 = (String) indexSettingsMap.get("uuid");
        }
        final String uuidInProject2;
        {
            setRequestProjectId(getIndexRequest, projectId2);
            Response getIndexResponse = client().performRequest(getIndexRequest);
            Map<String, Object> indexSettingsMap = getIndexSettingsFromResponse(getIndexResponse, indexName);
            assertThat(indexSettingsMap.get("number_of_shards"), is(String.valueOf(numberOfShards2)));
            assertThat(indexSettingsMap.get("number_of_replicas"), is(String.valueOf(numberOfReplicas2)));
            uuidInProject2 = (String) indexSettingsMap.get("uuid");
        }
        // they are different indices (same name, different projects)
        assertNotEquals(uuidInProject1, uuidInProject2);
    }

    public void testIndexNotVisibleAcrossProjects() throws IOException {
        final String projectId1 = "projectid1" + testNameRule.getMethodName().toLowerCase(Locale.ROOT);
        createProject(projectId1);
        final String projectId2 = "projectid2" + testNameRule.getMethodName().toLowerCase(Locale.ROOT);
        createProject(projectId2);

        final String indexName1 = "testindex1" + testNameRule.getMethodName().toLowerCase(Locale.ROOT);
        createIndexAndWaitForShardAllocation(indexName1, projectId1);
        final String indexName2 = "testindex2" + testNameRule.getMethodName().toLowerCase(Locale.ROOT);
        createIndexAndWaitForShardAllocation(indexName2, projectId2);

        {
            // index1 in project2
            final Request getIndexRequest = new Request("GET", "/" + indexName1);
            setRequestProjectId(getIndexRequest, projectId2);
            ResponseException responseException = expectThrows(ResponseException.class, () -> client().performRequest(getIndexRequest));
            assertThat(responseException.getMessage(), containsString("index_not_found_exception"));
            assertThat(responseException.getMessage(), containsString("no such index [" + indexName1));
            assertThat(responseException.getResponse().getStatusLine().getStatusCode(), is(404));
        }
        {
            // index2 in project1
            final Request getIndexRequest = new Request("GET", "/" + indexName2);
            setRequestProjectId(getIndexRequest, projectId1);
            ResponseException responseException = expectThrows(ResponseException.class, () -> client().performRequest(getIndexRequest));
            assertThat(responseException.getMessage(), containsString("index_not_found_exception"));
            assertThat(responseException.getMessage(), containsString("no such index [" + indexName2));
            assertThat(responseException.getResponse().getStatusLine().getStatusCode(), is(404));
        }
    }

    // TODO multi-project: make test cluster cleanup work in a MP fashion
    @Override
    protected boolean preserveClusterUponCompletion() {
        return true;
    }

    private static Map<?, ?> toMap(Response response) throws IOException {
        return XContentHelper.convertToMap(JsonXContent.jsonXContent, EntityUtils.toString(response.getEntity()), false);
    }

    private static void createIndexAndWaitForShardAllocation(String indexName, String projectId) throws IOException {
        final Request putIndexRequest = new Request(
            "PUT",
            "/" + indexName + "?wait_for_active_shards=all&master_timeout=999s&timeout=999s"
        );
        final int numberOfShards = randomIntBetween(1, 3);
        final int numberOfReplicas = randomIntBetween(0, NODE_NUM - 1);
        putIndexRequest.setJsonEntity(Strings.format("""
            {
                "settings": {
                  "number_of_shards": %d,
                  "number_of_replicas": %d
                }
            }
            """, numberOfShards, numberOfReplicas));
        setRequestProjectId(putIndexRequest, projectId);
        Response putIndexResponse = client().performRequest(putIndexRequest);
        assertOK(putIndexResponse);
        var putIndexResponseBodyMap = toMap(putIndexResponse);
        assertTrue((boolean) XContentMapValues.extractValue("acknowledged", putIndexResponseBodyMap));
        assertTrue((boolean) XContentMapValues.extractValue("shards_acknowledged", putIndexResponseBodyMap));
        assertThat((String) XContentMapValues.extractValue("index", putIndexResponseBodyMap), is(indexName));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> getIndexSettingsFromResponse(Response getIndexResponse, String indexName) throws IOException {
        assertOK(getIndexResponse);
        Map<String, Object> getIndexResponseBodyMap = entityAsMap(getIndexResponse);
        return ObjectPath.eval(indexName + ".settings.index", getIndexResponseBodyMap);
    }

    private static void setRequestProjectId(Request request, String projectId) {
        RequestOptions.Builder options = RequestOptions.DEFAULT.toBuilder();
        options.addHeader(Task.X_ELASTIC_PROJECT_ID_HTTP_HEADER, projectId);
        request.setOptions(options);
    }

    private void createProject(String projectId) throws IOException {
        Request putProjectRequest = new Request("PUT", "/_project/" + projectId);
        Response putProjectResponse = adminClient().performRequest(putProjectRequest);
        assertOK(putProjectResponse);
    }
}
