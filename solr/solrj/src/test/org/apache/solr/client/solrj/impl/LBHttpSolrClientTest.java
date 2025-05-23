/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.solr.client.solrj.impl;

import java.io.IOException;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.solr.SolrTestCase;
import org.apache.solr.client.solrj.ResponseParser;
import org.apache.solr.common.params.ModifiableSolrParams;
import org.junit.Test;

/** Test the LBHttpSolrClient. */
public class LBHttpSolrClientTest extends SolrTestCase {

  /**
   * Test method for {@link LBHttpSolrClient.Builder}.
   *
   * <p>Validate that the parser passed in is used in the <code>HttpSolrClient</code> instances
   * created.
   */
  @Test
  public void testLBHttpSolrClientHttpClientResponseParserStringArray() throws IOException {
    CloseableHttpClient httpClient = HttpClientUtil.createClient(new ModifiableSolrParams());
    try (LBHttpSolrClient testClient =
            new LBHttpSolrClient.Builder()
                .withHttpClient(httpClient)
                .withResponseParser(null)
                .build();
        HttpSolrClient httpSolrClient =
            testClient.makeSolrClient(new LBSolrClient.Endpoint("http://127.0.0.1:8080"))) {
      assertNull("Generated server should have null parser.", httpSolrClient.getParser());
    } finally {
      HttpClientUtil.close(httpClient);
    }

    ResponseParser parser = new JavaBinResponseParser();
    httpClient = HttpClientUtil.createClient(new ModifiableSolrParams());
    try {
      try (LBHttpSolrClient testClient =
              new LBHttpSolrClient.Builder()
                  .withHttpClient(httpClient)
                  .withResponseParser(parser)
                  .build();
          HttpSolrClient httpSolrClient =
              testClient.makeSolrClient(new LBSolrClient.Endpoint("http://127.0.0.1:8080"))) {
        assertEquals(
            "Invalid parser passed to generated server.", parser, httpSolrClient.getParser());
      }
    } finally {
      HttpClientUtil.close(httpClient);
    }
  }
}
