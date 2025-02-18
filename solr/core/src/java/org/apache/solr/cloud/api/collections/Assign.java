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
package org.apache.solr.cloud.api.collections;

import static org.apache.solr.cloud.api.collections.CollectionHandlingUtils.CREATE_NODE_SET;

import java.io.IOException;
import java.lang.invoke.MethodHandles;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.Set;
import org.apache.solr.client.solrj.cloud.AlreadyExistsException;
import org.apache.solr.client.solrj.cloud.BadVersionException;
import org.apache.solr.client.solrj.cloud.DistribStateManager;
import org.apache.solr.client.solrj.cloud.SolrCloudManager;
import org.apache.solr.client.solrj.cloud.VersionedData;
import org.apache.solr.cluster.placement.PlacementPlugin;
import org.apache.solr.cluster.placement.impl.PlacementPluginAssignStrategy;
import org.apache.solr.cluster.placement.plugins.SimplePlacementFactory;
import org.apache.solr.common.SolrException;
import org.apache.solr.common.cloud.ClusterState;
import org.apache.solr.common.cloud.DocCollection;
import org.apache.solr.common.cloud.Replica;
import org.apache.solr.common.cloud.ReplicaCount;
import org.apache.solr.common.cloud.ReplicaPosition;
import org.apache.solr.common.cloud.Slice;
import org.apache.solr.common.cloud.ZkNodeProps;
import org.apache.solr.common.cloud.ZkStateReader;
import org.apache.solr.common.util.StrUtils;
import org.apache.solr.core.CoreContainer;
import org.apache.solr.core.NodeRoles;
import org.apache.solr.handler.ClusterAPI;
import org.apache.solr.util.NumberUtils;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Assign {
  private static final Logger log = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());
  public static final String SYSTEM_COLL_PREFIX = ".sys.";
  public static final String PLACEMENTPLUGIN_DEFAULT_SYSPROP = "solr.placementplugin.default";

  public static String getCounterNodePath(String collection) {
    return ZkStateReader.COLLECTIONS_ZKNODE + "/" + collection + "/counter";
  }

  public static int incAndGetId(DistribStateManager stateManager, String collection) {
    String path = ZkStateReader.COLLECTIONS_ZKNODE + "/" + collection;
    try {
      if (!stateManager.hasData(path)) {
        try {
          stateManager.makePath(path);
        } catch (AlreadyExistsException e) {
          // it's okay if another beats us creating the node
        }
      }
      path += "/counter";
      if (!stateManager.hasData(path)) {
        try {
          stateManager.createData(path, NumberUtils.intToBytes(0), CreateMode.PERSISTENT);
        } catch (AlreadyExistsException e) {
          // it's okay if another beats us creating the node
        }
      }
    } catch (InterruptedException e) {
      Thread.interrupted();
      throw new SolrException(
          SolrException.ErrorCode.SERVER_ERROR,
          "Error creating counter node in Zookeeper for collection:" + collection,
          e);
    } catch (IOException | KeeperException e) {
      throw new SolrException(
          SolrException.ErrorCode.SERVER_ERROR,
          "Error creating counter node in Zookeeper for collection:" + collection,
          e);
    }

    while (true) {
      try {
        int version = 0;
        int currentId = 0;
        VersionedData data = stateManager.getData(path, null);
        if (data != null) {
          currentId = NumberUtils.bytesToInt(data.getData());
          version = data.getVersion();
        }
        byte[] bytes = NumberUtils.intToBytes(++currentId);
        stateManager.setData(path, bytes, version);
        return currentId;
      } catch (BadVersionException e) {
        // Outdated version, try again
      } catch (IOException | KeeperException e) {
        throw new SolrException(
            SolrException.ErrorCode.SERVER_ERROR,
            "Error inc and get counter from Zookeeper for collection:" + collection,
            e);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new SolrException(
            SolrException.ErrorCode.SERVER_ERROR,
            "Error inc and get counter from Zookeeper for collection:" + collection,
            e);
      }
    }
  }

  public static String assignCoreNodeName(
      DistribStateManager stateManager, DocCollection collection) {
    return "core_node" + incAndGetId(stateManager, collection.getName());
  }

  /**
   * Assign a new unique id up to slices count - then add replicas evenly.
   *
   * @return the assigned shard id
   */
  public static String assignShard(DocCollection collection, Integer numShards) {
    if (numShards == null) {
      numShards = 1;
    }
    String returnShardId = null;
    Map<String, Slice> sliceMap = collection != null ? collection.getActiveSlicesMap() : null;

    // TODO: now that we create shards ahead of time, is this code needed?  Esp since hash ranges
    // aren't assigned when creating via this method?

    if (sliceMap == null) {
      return "shard1";
    }

    List<String> shardIdNames = new ArrayList<>(sliceMap.keySet());

    if (shardIdNames.size() < numShards) {
      return "shard" + (shardIdNames.size() + 1);
    }

    // TODO: don't need to sort to find shard with fewest replicas!

    // else figure out which shard needs more replicas
    final Map<String, Integer> map = new HashMap<>();
    for (String shardId : shardIdNames) {
      int cnt = sliceMap.get(shardId).getReplicasMap().size();
      map.put(shardId, cnt);
    }

    shardIdNames.sort(
        (String o1, String o2) -> {
          Integer one = map.get(o1);
          Integer two = map.get(o2);
          return one.compareTo(two);
        });

    returnShardId = shardIdNames.get(0);
    return returnShardId;
  }

  public static String buildSolrCoreName(
      String collectionName, String shard, Replica.Type type, int replicaNum) {
    // TODO: Adding the suffix is great for debugging, but may be an issue if at some point we want
    // to support a way to change replica type
    return String.format(
        Locale.ROOT,
        "%s_%s_replica_%s%s",
        collectionName,
        shard,
        type.name().substring(0, 1).toLowerCase(Locale.ROOT),
        replicaNum);
  }

  public static String buildSolrCoreName(
      DistribStateManager stateManager, String collectionName, String shard, Replica.Type type) {

    int replicaNum = incAndGetId(stateManager, collectionName);
    return buildSolrCoreName(collectionName, shard, type, replicaNum);
  }

  public static String buildSolrCoreName(
      DistribStateManager stateManager, DocCollection collection, String shard, Replica.Type type) {
    return buildSolrCoreName(stateManager, collection.getName(), shard, type);
  }

  public static List<String> getLiveOrLiveAndCreateNodeSetList(
      final Set<String> liveNodes,
      final ZkNodeProps message,
      final Random random,
      DistribStateManager zk) {

    List<String> nodeList;
    final String createNodeSetStr = message.getStr(CREATE_NODE_SET);
    final List<String> createNodeList =
        (createNodeSetStr == null)
            ? null
            : StrUtils.splitSmart(
                (CollectionHandlingUtils.CREATE_NODE_SET_EMPTY.equals(createNodeSetStr)
                    ? ""
                    : createNodeSetStr),
                ",",
                true);

    if (createNodeList != null) {
      nodeList = new ArrayList<>(createNodeList);
      nodeList.retainAll(liveNodes);
      if (message.getBool(
          CollectionHandlingUtils.CREATE_NODE_SET_SHUFFLE,
          CollectionHandlingUtils.CREATE_NODE_SET_SHUFFLE_DEFAULT)) {
        Collections.shuffle(nodeList, random);
      }
    } else {
      nodeList = new ArrayList<>(filterNonDataNodes(zk, liveNodes));
      Collections.shuffle(nodeList, random);
    }

    return nodeList;
  }

  public static Collection<String> filterNonDataNodes(
      DistribStateManager zk, Collection<String> liveNodes) {
    try {
      List<String> noData = ClusterAPI.getNodesByRole(NodeRoles.Role.DATA, NodeRoles.MODE_OFF, zk);
      if (noData.isEmpty()) {
        return liveNodes;
      } else {
        liveNodes = new HashSet<>(liveNodes);
        liveNodes.removeAll(noData);
        return liveNodes;
      }
    } catch (Exception e) {
      throw new SolrException(
          SolrException.ErrorCode.SERVER_ERROR, "Error fetching roles from Zookeeper", e);
    }
  }

  // Only called from addReplica (and by extension createShard) (so far).
  //
  // Gets a list of candidate nodes to put the required replica(s) on. Throws errors if the
  // AssignStrategy can't allocate valid positions.
  @SuppressWarnings({"unchecked"})
  public static List<ReplicaPosition> getNodesForNewReplicas(
      ClusterState clusterState,
      String collectionName,
      String shard,
      ReplicaCount numReplicas,
      Object createNodeSet,
      SolrCloudManager cloudManager,
      CoreContainer coreContainer)
      throws IOException, InterruptedException, AssignmentException {
    if (log.isDebugEnabled()) {
      log.debug(
          "getNodesForNewReplicas() shard={}, {} , createNodeSet={}",
          shard,
          numReplicas,
          createNodeSet);
    }
    List<String> createNodeList;

    if (createNodeSet instanceof List) {
      createNodeList = (List<String>) createNodeSet;
    } else {
      // deduplicate
      createNodeList =
          createNodeSet == null
              ? null
              : new ArrayList<>(
                  new LinkedHashSet<>(StrUtils.splitSmart((String) createNodeSet, ",", true)));
    }

    // produces clear message when down nodes are the root cause, without this the user just
    // gets a log message of detail about the nodes that are up, and a message that policies could
    // not be satisfied which then requires study to diagnose the issue.
    checkLiveNodes(createNodeList, clusterState);

    AssignRequest assignRequest =
        new AssignRequestBuilder()
            .forCollection(collectionName)
            .forShard(Collections.singletonList(shard))
            .assignReplicas(numReplicas)
            .onNodes(createNodeList)
            .build();
    AssignStrategy assignStrategy = createAssignStrategy(coreContainer);
    return assignStrategy.assign(cloudManager, assignRequest);
  }

  // throw an exception if any node in the supplied list is not live.
  // Empty or null list always succeeds and returns the input.
  private static List<String> checkLiveNodes(
      List<String> createNodeList, ClusterState clusterState) {
    Set<String> liveNodes = clusterState.getLiveNodes();
    if (createNodeList != null) {
      if (!liveNodes.containsAll(createNodeList)) {
        throw new SolrException(
            SolrException.ErrorCode.BAD_REQUEST,
            "At least one of the node(s) specified "
                + createNodeList
                + " are not currently active in "
                + liveNodes
                + ", no action taken.");
      }
      // the logic that was extracted to this method used to create a defensive copy but no code
      // was modifying the copy, if this method is made protected or public we want to go back to
      // that
    }
    return createNodeList; // unmodified, but return for inline use
  }

  /** Thrown if there is an exception while assigning nodes for replicas */
  public static class AssignmentException extends RuntimeException {
    public AssignmentException() {}

    public AssignmentException(String message) {
      super(message);
    }

    public AssignmentException(String message, Throwable cause) {
      super(message, cause);
    }

    public AssignmentException(Throwable cause) {
      super(cause);
    }

    public AssignmentException(
        String message, Throwable cause, boolean enableSuppression, boolean writableStackTrace) {
      super(message, cause, enableSuppression, writableStackTrace);
    }
  }

  /** Strategy for assigning replicas to nodes. */
  public interface AssignStrategy {

    /**
     * Assign new replicas to nodes. If multiple {@link AssignRequest}s are provided, then every
     * {@link ReplicaPosition} made for an {@link AssignRequest} will be applied to the {@link
     * SolrCloudManager}'s state when processing subsequent {@link AssignRequest}s. Therefore, the
     * order in which {@link AssignRequest}s are provided can and will affect the {@link
     * ReplicaPosition}s returned.
     *
     * @param solrCloudManager current instance of {@link SolrCloudManager}.
     * @param assignRequests assign request.
     * @return list of {@link ReplicaPosition}-s for new replicas.
     * @throws AssignmentException when assignment request cannot produce any valid assignments.
     */
    default List<ReplicaPosition> assign(
        SolrCloudManager solrCloudManager, AssignRequest... assignRequests)
        throws AssignmentException, IOException, InterruptedException {
      return assign(solrCloudManager, Arrays.asList(assignRequests));
    }

    /**
     * Assign new replicas to nodes. If multiple {@link AssignRequest}s are provided, then every
     * {@link ReplicaPosition} made for an {@link AssignRequest} will be applied to the {@link
     * SolrCloudManager}'s state when processing subsequent {@link AssignRequest}s. Therefore, the
     * order in which {@link AssignRequest}s are provided can and will affect the {@link
     * ReplicaPosition}s returned.
     *
     * @param solrCloudManager current instance of {@link SolrCloudManager}.
     * @param assignRequests list of assign requests to process together ().
     * @return list of {@link ReplicaPosition}-s for new replicas.
     * @throws AssignmentException when assignment request cannot produce any valid assignments.
     */
    List<ReplicaPosition> assign(
        SolrCloudManager solrCloudManager, List<AssignRequest> assignRequests)
        throws AssignmentException, IOException, InterruptedException;

    /**
     * Balance replicas across nodes.
     *
     * @param solrCloudManager current instance of {@link SolrCloudManager}.
     * @param nodes to compute replica balancing across.
     * @param maxBalanceSkew to ensure strictness of replica balancing.
     * @return Map from Replica to the Node where that Replica should be moved.
     * @throws AssignmentException when balance request cannot produce any valid assignments.
     */
    default Map<Replica, String> computeReplicaBalancing(
        SolrCloudManager solrCloudManager, Set<String> nodes, int maxBalanceSkew)
        throws AssignmentException, IOException, InterruptedException {
      return Collections.emptyMap();
    }

    /**
     * Verify that deleting a collection doesn't violate the replica assignment constraints.
     *
     * @param solrCloudManager current instance of {@link SolrCloudManager}.
     * @param collection collection to delete.
     * @throws AssignmentException when deleting the collection would violate replica assignment
     *     constraints.
     * @throws IOException on general errors.
     */
    default void verifyDeleteCollection(SolrCloudManager solrCloudManager, DocCollection collection)
        throws AssignmentException, IOException, InterruptedException {}

    /**
     * Verify that deleting these replicas doesn't violate the replica assignment constraints.
     *
     * @param solrCloudManager current instance of {@link SolrCloudManager}.
     * @param collection collection to delete replicas from.
     * @param shardName shard name.
     * @param replicas replicas to delete.
     * @throws AssignmentException when deleting the replicas would violate replica assignment
     *     constraints.
     * @throws IOException on general errors.
     */
    default void verifyDeleteReplicas(
        SolrCloudManager solrCloudManager,
        DocCollection collection,
        String shardName,
        Set<Replica> replicas)
        throws AssignmentException, IOException, InterruptedException {}
  }

  public static class AssignRequest {
    public final String collectionName;
    public final List<String> shardNames;
    public final List<String> nodes;
    public final ReplicaCount numReplicas;

    public AssignRequest(
        String collectionName,
        List<String> shardNames,
        List<String> nodes,
        ReplicaCount numReplicas) {
      this.collectionName = collectionName;
      this.shardNames = shardNames;
      this.nodes = nodes;
      this.numReplicas = numReplicas;
    }
  }

  public static class AssignRequestBuilder {
    private String collectionName;
    private List<String> shardNames;
    private List<String> nodes;
    private ReplicaCount numReplicas;

    public AssignRequestBuilder() {
      this.numReplicas = ReplicaCount.empty();
    }

    public AssignRequestBuilder forCollection(String collectionName) {
      this.collectionName = collectionName;
      return this;
    }

    public AssignRequestBuilder forShard(List<String> shardNames) {
      this.shardNames = shardNames;
      return this;
    }

    public AssignRequestBuilder onNodes(List<String> nodes) {
      this.nodes = nodes;
      return this;
    }

    public AssignRequestBuilder assignReplicas(ReplicaCount numReplicas) {
      this.numReplicas = numReplicas;
      return this;
    }

    public AssignRequest build() {
      Objects.requireNonNull(collectionName, "The collectionName cannot be null");
      Objects.requireNonNull(shardNames, "The shard names cannot be null");
      return new AssignRequest(collectionName, shardNames, nodes, numReplicas);
    }
  }

  /**
   * Creates the appropriate instance of {@link AssignStrategy} based on how the cluster and/or
   * individual collections are configured.
   *
   * <p>If {@link PlacementPlugin} instance is null this call will return a strategy from {@link
   * SimplePlacementFactory}, otherwise {@link PlacementPluginAssignStrategy} will be used.
   */
  public static AssignStrategy createAssignStrategy(CoreContainer coreContainer) {
    // If a cluster wide placement plugin is configured (and that's the only way to define a
    // placement plugin)
    PlacementPlugin placementPlugin =
        coreContainer.getPlacementPluginFactory().createPluginInstance();
    return new PlacementPluginAssignStrategy(placementPlugin);
  }
}
