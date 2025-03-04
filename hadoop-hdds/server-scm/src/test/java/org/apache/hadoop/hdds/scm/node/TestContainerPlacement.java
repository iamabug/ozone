/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.hadoop.hdds.scm.node;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.TimeoutException;

import org.apache.hadoop.fs.FileUtil;
import org.apache.hadoop.hdds.HddsConfigKeys;
import org.apache.hadoop.hdds.conf.ConfigurationSource;
import org.apache.hadoop.hdds.conf.OzoneConfiguration;
import org.apache.hadoop.hdds.protocol.DatanodeDetails;
import org.apache.hadoop.hdds.protocol.proto
    .StorageContainerDatanodeProtocolProtos.LayoutVersionProto;
import org.apache.hadoop.hdds.scm.PlacementPolicy;
import org.apache.hadoop.hdds.scm.ScmConfigKeys;
import org.apache.hadoop.hdds.scm.TestUtils;
import org.apache.hadoop.hdds.scm.XceiverClientManager;
import org.apache.hadoop.hdds.scm.container.ContainerInfo;
import org.apache.hadoop.hdds.scm.container.SCMContainerManager;
import org.apache.hadoop.hdds.scm.container.placement.algorithms.SCMContainerPlacementCapacity;
import org.apache.hadoop.hdds.scm.events.SCMEvents;
import org.apache.hadoop.hdds.scm.ha.MockSCMHAManager;
import org.apache.hadoop.hdds.scm.ha.SCMContext;
import org.apache.hadoop.hdds.scm.ha.SCMServiceManager;
import org.apache.hadoop.hdds.scm.metadata.SCMMetadataStore;
import org.apache.hadoop.hdds.scm.metadata.SCMMetadataStoreImpl;
import org.apache.hadoop.hdds.scm.pipeline.PipelineManager;
import org.apache.hadoop.hdds.scm.pipeline.PipelineManagerImpl;
import org.apache.hadoop.hdds.scm.server.SCMStorageConfig;
import org.apache.hadoop.hdds.server.events.EventQueue;
import org.apache.hadoop.hdds.upgrade.HDDSLayoutVersionManager;
import org.apache.hadoop.ozone.OzoneConsts;
import org.apache.hadoop.ozone.container.common.SCMTestUtils;
import org.apache.hadoop.ozone.upgrade.LayoutVersionManager;
import org.apache.hadoop.test.PathUtils;

import org.apache.commons.io.IOUtils;
import static org.apache.hadoop.hdds.protocol.proto.HddsProtos.NodeState.HEALTHY;
import org.junit.After;

import static org.apache.hadoop.ozone.container.upgrade.UpgradeUtils.toLayoutVersionProto;
import static org.apache.hadoop.hdds.upgrade.HDDSLayoutVersionManager.maxLayoutVersion;
import static org.junit.Assert.assertEquals;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.mockito.Mockito;

/**
 * Test for different container placement policy.
 */
public class TestContainerPlacement {

  @Rule
  public ExpectedException thrown = ExpectedException.none();
  private SCMMetadataStore scmMetadataStore;

  @Before
  public void createDbStore() throws IOException {
    scmMetadataStore = new SCMMetadataStoreImpl(getConf());
  }

  @After
  public void destroyDBStore() throws Exception {
    scmMetadataStore.getStore().close();
  }
  /**
   * Returns a new copy of Configuration.
   *
   * @return Config
   */
  OzoneConfiguration getConf() {
    return new OzoneConfiguration();
  }

  /**
   * Creates a NodeManager.
   *
   * @param config - Config for the node manager.
   * @return SCNNodeManager
   * @throws IOException
   */

  SCMNodeManager createNodeManager(OzoneConfiguration config)
      throws IOException {
    EventQueue eventQueue = new EventQueue();
    eventQueue.addHandler(SCMEvents.NEW_NODE,
        Mockito.mock(NewNodeHandler.class));
    eventQueue.addHandler(SCMEvents.STALE_NODE,
        Mockito.mock(StaleNodeHandler.class));
    eventQueue.addHandler(SCMEvents.DEAD_NODE,
        Mockito.mock(DeadNodeHandler.class));

    SCMStorageConfig storageConfig = Mockito.mock(SCMStorageConfig.class);
    Mockito.when(storageConfig.getClusterID()).thenReturn("cluster1");

    HDDSLayoutVersionManager versionManager =
        Mockito.mock(HDDSLayoutVersionManager.class);
    Mockito.when(versionManager.getMetadataLayoutVersion())
        .thenReturn(maxLayoutVersion());
    Mockito.when(versionManager.getSoftwareLayoutVersion())
        .thenReturn(maxLayoutVersion());
    SCMNodeManager nodeManager = new SCMNodeManager(config, storageConfig,
        eventQueue, null, SCMContext.emptyContext(), versionManager);
    return nodeManager;
  }

  SCMContainerManager createContainerManager(ConfigurationSource config,
      NodeManager scmNodeManager) throws IOException {
    EventQueue eventQueue = new EventQueue();

    PipelineManager pipelineManager =
        PipelineManagerImpl.newPipelineManager(
            config,
            MockSCMHAManager.getInstance(true),
            scmNodeManager,
            scmMetadataStore.getPipelineTable(),
            eventQueue,
            SCMContext.emptyContext(),
            new SCMServiceManager());

    return new SCMContainerManager(config, scmMetadataStore.getContainerTable(),
        scmMetadataStore.getStore(),
        pipelineManager);

  }

  /**
   * Test capacity based container placement policy with node reports.
   *
   * @throws IOException
   * @throws InterruptedException
   * @throws TimeoutException
   */
  @Test
  @Ignore
  public void testContainerPlacementCapacity() throws IOException,
      InterruptedException, TimeoutException {
    OzoneConfiguration conf = getConf();
    final int nodeCount = 4;
    final long capacity = 10L * OzoneConsts.GB;
    final long used = 2L * OzoneConsts.GB;
    final long remaining = capacity - used;

    final File testDir = PathUtils.getTestDir(
        TestContainerPlacement.class);
    conf.set(HddsConfigKeys.OZONE_METADATA_DIRS,
        testDir.getAbsolutePath());
    conf.setClass(ScmConfigKeys.OZONE_SCM_CONTAINER_PLACEMENT_IMPL_KEY,
        SCMContainerPlacementCapacity.class, PlacementPolicy.class);

    SCMNodeManager nodeManager = createNodeManager(conf);
    SCMContainerManager containerManager =
        createContainerManager(conf, nodeManager);
    List<DatanodeDetails> datanodes =
        TestUtils.getListOfRegisteredDatanodeDetails(nodeManager, nodeCount);
    XceiverClientManager xceiverClientManager = null;
    LayoutVersionManager versionManager = nodeManager.getLayoutVersionManager();
    LayoutVersionProto layoutInfo =
        toLayoutVersionProto(versionManager.getMetadataLayoutVersion(),
            versionManager.getSoftwareLayoutVersion());
    try {
      for (DatanodeDetails datanodeDetails : datanodes) {
        nodeManager.processHeartbeat(datanodeDetails, layoutInfo);
      }

      //TODO: wait for heartbeat to be processed
      Thread.sleep(4 * 1000);
      assertEquals(nodeCount, nodeManager.getNodeCount(null, HEALTHY));
      assertEquals(capacity * nodeCount,
          (long) nodeManager.getStats().getCapacity().get());
      assertEquals(used * nodeCount,
          (long) nodeManager.getStats().getScmUsed().get());
      assertEquals(remaining * nodeCount,
          (long) nodeManager.getStats().getRemaining().get());

      xceiverClientManager= new XceiverClientManager(conf);

      ContainerInfo container = containerManager
          .allocateContainer(
              SCMTestUtils.getReplicationType(conf),
              SCMTestUtils.getReplicationFactor(conf),
              OzoneConsts.OZONE);
      assertEquals(SCMTestUtils.getReplicationFactor(conf).getNumber(),
          containerManager.getContainerReplicas(
              container.containerID()).size());
    } finally {
      IOUtils.closeQuietly(containerManager);
      IOUtils.closeQuietly(nodeManager);
      if (xceiverClientManager != null) {
        xceiverClientManager.close();
      }
      FileUtil.fullyDelete(testDir);
    }
  }
}
