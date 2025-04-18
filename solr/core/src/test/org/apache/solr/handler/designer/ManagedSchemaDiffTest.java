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

package org.apache.solr.handler.designer;

import static org.apache.solr.handler.admin.ConfigSetsHandler.DEFAULT_CONFIGSET_NAME;
import static org.apache.solr.handler.designer.ManagedSchemaDiff.mapFieldsToPropertyValues;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.solr.cloud.SolrCloudTestCase;
import org.apache.solr.common.util.SimpleOrderedMap;
import org.apache.solr.schema.BoolField;
import org.apache.solr.schema.IntPointField;
import org.apache.solr.schema.ManagedIndexSchema;
import org.apache.solr.schema.SchemaField;
import org.apache.solr.util.ExternalPaths;
import org.junit.BeforeClass;

public class ManagedSchemaDiffTest extends SolrCloudTestCase {

  @BeforeClass
  public static void createCluster() throws Exception {
    System.setProperty("managed.schema.mutable", "true");
    configureCluster(1)
        .addConfig(DEFAULT_CONFIGSET_NAME, ExternalPaths.DEFAULT_CONFIGSET)
        .configure();
  }

  public void testFieldDiff() {
    SchemaDesignerConfigSetHelper helper =
        new SchemaDesignerConfigSetHelper(cluster.getJettySolrRunner(0).getCoreContainer(), null);
    ManagedIndexSchema schema = helper.loadLatestSchema(DEFAULT_CONFIGSET_NAME);

    Map<String, SchemaField> schema1FieldMap = new HashMap<>();
    schema1FieldMap.put("strfield", schema.newField("strfield", "string", Collections.emptyMap()));
    schema1FieldMap.put("boolfield", new SchemaField("boolfield", new BoolField()));

    Map<String, SchemaField> schema2FieldMap = new HashMap<>();
    schema2FieldMap.put("strfield", schema.newField("strfield", "strings", Collections.emptyMap()));
    schema2FieldMap.put("intfield", new SchemaField("intfield", new IntPointField()));

    Map<String, Object> diff =
        ManagedSchemaDiff.diff(
            mapFieldsToPropertyValues(schema1FieldMap), mapFieldsToPropertyValues(schema2FieldMap));
    assertTrue(diff.containsKey("updated"));
    assertTrue(diff.containsKey("added"));
    assertTrue(diff.containsKey("removed"));

    Map<String, Object> changedFields = getInnerMap(diff, "updated");
    assertEquals(1, changedFields.size());
    assertTrue(changedFields.containsKey("strfield"));
    assertEquals(
        Arrays.asList(
            Map.of("type", "string", "multiValued", false),
            Map.of("type", "strings", "multiValued", true)),
        changedFields.get("strfield"));

    Map<String, Object> addedFields = getInnerMap(diff, "added");
    assertEquals(1, addedFields.size());
    assertTrue(addedFields.containsKey("intfield"));
    assertEquals(
        schema2FieldMap.get("intfield").getNamedPropertyValues(true), addedFields.get("intfield"));

    Map<String, Object> removedFields = getInnerMap(diff, "removed");
    assertEquals(1, removedFields.size());
    assertTrue(removedFields.containsKey("boolfield"));
    assertEquals(
        schema1FieldMap.get("boolfield").getNamedPropertyValues(true),
        removedFields.get("boolfield"));
  }

  public void testSimpleOrderedMapListDiff() {
    SimpleOrderedMap<Object> obj1 = new SimpleOrderedMap<>();
    obj1.add("name", "obj1");
    obj1.add("type", "objtype1");

    SimpleOrderedMap<Object> obj2 = new SimpleOrderedMap<>();
    obj2.add("name", "obj2");
    obj2.add("type", "objtype2");

    SimpleOrderedMap<Object> obj3 = new SimpleOrderedMap<>();
    obj3.add("name", "obj3");
    obj3.add("type", "objtype3");

    SimpleOrderedMap<Object> obj4 = new SimpleOrderedMap<>();
    obj4.add("name", "obj4");
    obj4.add("type", "objtype4");

    List<SimpleOrderedMap<Object>> list1 = Arrays.asList(obj1, obj2);
    List<SimpleOrderedMap<Object>> list2 = Arrays.asList(obj1, obj3, obj4);

    Map<String, Object> diff = ManagedSchemaDiff.diff(list1, list2);
    assertTrue(diff.containsKey("old"));
    assertTrue(diff.containsKey("new"));
    assertEquals(Collections.singletonList(obj2), diff.get("old"));
    assertEquals(Arrays.asList(obj3, obj4), diff.get("new"));
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> getInnerMap(Map<String, Object> map, String key) {
    return (Map<String, Object>) map.get(key);
  }
}
