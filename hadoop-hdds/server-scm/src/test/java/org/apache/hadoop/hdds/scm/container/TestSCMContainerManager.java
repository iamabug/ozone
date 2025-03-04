/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with this
 * work for additional information regarding copyright ownership.  The ASF
 * licenses this file to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package org.apache.hadoop.hdds.scm.container;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.apache.hadoop.fs.FileUtil;
import org.apache.hadoop.hdds.HddsConfigKeys;
import org.apache.hadoop.hdds.conf.OzoneConfiguration;
import org.apache.hadoop.hdds.protocol.DatanodeDetails;
import org.apache.hadoop.hdds.protocol.proto.HddsProtos;
import org.apache.hadoop.hdds.protocol.proto.HddsProtos.LifeCycleEvent;
import org.apache.hadoop.hdds.protocol.proto.HddsProtos.LifeCycleState;
import org.apache.hadoop.hdds.protocol.proto.StorageContainerDatanodeProtocolProtos.ContainerReplicaProto;
import org.apache.hadoop.hdds.scm.XceiverClientManager;
import org.apache.hadoop.hdds.scm.ha.MockSCMHAManager;
import org.apache.hadoop.hdds.scm.ha.SCMContext;
import org.apache.hadoop.hdds.scm.ha.SCMServiceManager;
import org.apache.hadoop.hdds.scm.metadata.SCMMetadataStore;
import org.apache.hadoop.hdds.scm.metadata.SCMMetadataStoreImpl;
import org.apache.hadoop.hdds.scm.pipeline.Pipeline;
import org.apache.hadoop.hdds.scm.pipeline.PipelineManagerImpl;
import org.apache.hadoop.hdds.server.events.EventQueue;
import org.apache.hadoop.ozone.OzoneConsts;
import org.apache.hadoop.ozone.container.common.SCMTestUtils;
import org.apache.ozone.test.GenericTestUtils;

import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;


/**
 * Tests for Container ContainerManager.
 */
public class TestSCMContainerManager {
  private static SCMContainerManager containerManager;
  private static MockNodeManager nodeManager;
  private static PipelineManagerImpl pipelineManager;
  private static File testDir;
  private static XceiverClientManager xceiverClientManager;
  private static Random random;
  private static HddsProtos.ReplicationFactor replicationFactor;
  private static HddsProtos.ReplicationType replicationType;

  private static final long TIMEOUT = 10000;

  @Rule
  public ExpectedException thrown = ExpectedException.none();

  @BeforeClass
  public static void setUp() throws Exception {
    OzoneConfiguration conf = SCMTestUtils.getConf();

    testDir = GenericTestUtils
        .getTestDir(TestSCMContainerManager.class.getSimpleName());
    conf.set(HddsConfigKeys.OZONE_METADATA_DIRS,
        testDir.getAbsolutePath());
    boolean folderExisted = testDir.exists() || testDir.mkdirs();
    if (!folderExisted) {
      throw new IOException("Unable to create test directory path");
    }
    nodeManager = new MockNodeManager(true, 10);
    SCMMetadataStore scmMetadataStore = new SCMMetadataStoreImpl(conf);
    pipelineManager = PipelineManagerImpl.newPipelineManager(
        conf,
        MockSCMHAManager.getInstance(true),
        nodeManager,
        scmMetadataStore.getPipelineTable(),
        new EventQueue(),
        SCMContext.emptyContext(),
        new SCMServiceManager());
    containerManager = new SCMContainerManager(conf,
        scmMetadataStore.getContainerTable(),
        scmMetadataStore.getStore(),
        pipelineManager);
    xceiverClientManager = new XceiverClientManager(conf);
    replicationFactor = SCMTestUtils.getReplicationFactor(conf);
    replicationType = SCMTestUtils.getReplicationType(conf);
    random = new Random();
  }

  @AfterClass
  public static void cleanup() throws IOException {
    if(containerManager != null) {
      containerManager.close();
    }
    if (pipelineManager != null) {
      pipelineManager.close();
    }
    FileUtil.fullyDelete(testDir);
  }

  @Before
  public void clearSafeMode() {
    nodeManager.setSafemode(false);
  }

  @Test
  public void testallocateContainer() throws Exception {
    ContainerInfo containerInfo = containerManager.allocateContainer(
        replicationType, replicationFactor, OzoneConsts.OZONE);
    Assert.assertNotNull(containerInfo);
  }

  @Test
  public void testallocateContainerDistributesAllocation() throws Exception {
    /* This is a lame test, we should really be testing something like
    z-score or make sure that we don't have 3sigma kind of events. Too lazy
    to write all that code. This test very lamely tests if we have more than
    5 separate nodes  from the list of 10 datanodes that got allocated a
    container.
     */
    Set<UUID> pipelineList = new TreeSet<>();
    for (int x = 0; x < 30; x++) {
      ContainerInfo containerInfo = containerManager.allocateContainer(
          replicationType, replicationFactor, OzoneConsts.OZONE);

      Assert.assertNotNull(containerInfo);
      Assert.assertNotNull(containerInfo.getPipelineID());
      pipelineList.add(pipelineManager.getPipeline(
          containerInfo.getPipelineID()).getFirstNode()
          .getUuid());
    }
    Assert.assertTrue(pipelineList.size() >= 1);
  }

  @Test
  public void testAllocateContainerInParallel() throws Exception {
    int threadCount = 20;
    List<ExecutorService> executors = new ArrayList<>(threadCount);
    for (int i = 0; i < threadCount; i++) {
      executors.add(Executors.newSingleThreadExecutor());
    }
    List<CompletableFuture<ContainerInfo>> futureList =
        new ArrayList<>(threadCount);
    for (int i = 0; i < threadCount; i++) {
      final CompletableFuture<ContainerInfo> future = new CompletableFuture<>();
      CompletableFuture.supplyAsync(() -> {
        try {
          ContainerInfo containerInfo = containerManager
              .allocateContainer(replicationType, replicationFactor,
                  OzoneConsts.OZONE);

          Assert.assertNotNull(containerInfo);
          Assert.assertNotNull(containerInfo.getPipelineID());
          future.complete(containerInfo);
          return containerInfo;
        } catch (IOException e) {
          future.completeExceptionally(e);
        }
        return future;
      }, executors.get(i));
      futureList.add(future);
    }
    try {
      CompletableFuture
          .allOf(futureList.toArray(new CompletableFuture[futureList.size()]))
          .get();
    } catch (Exception e) {
      Assert.fail("testAllocateBlockInParallel failed");
    }
  }

  @Test
  public void testGetContainer() throws IOException {
    ContainerInfo containerInfo = containerManager.allocateContainer(
        replicationType, replicationFactor, OzoneConsts.OZONE);
    Assert.assertNotNull(containerInfo);
    Pipeline pipeline  = pipelineManager
        .getPipeline(containerInfo.getPipelineID());
    Assert.assertNotNull(pipeline);
    Assert.assertEquals(containerInfo,
        containerManager.getContainer(containerInfo.containerID()));
  }

  @Test
  public void testGetContainerWithPipeline() throws Exception {
    ContainerInfo contInfo = containerManager
        .allocateContainer(replicationType, replicationFactor,
            OzoneConsts.OZONE);
    // Add dummy replicas for container.
    Iterator<DatanodeDetails> nodes = pipelineManager
        .getPipeline(contInfo.getPipelineID()).getNodes().iterator();
    DatanodeDetails dn1 = nodes.next();
    containerManager.updateContainerState(contInfo.containerID(),
        LifeCycleEvent.FINALIZE);
    containerManager
        .updateContainerState(contInfo.containerID(), LifeCycleEvent.CLOSE);
    ContainerInfo finalContInfo = contInfo;
    Assert.assertEquals(0,
        containerManager.getContainerReplicas(
            finalContInfo.containerID()).size());

    containerManager.updateContainerReplica(contInfo.containerID(),
        ContainerReplica.newBuilder().setContainerID(contInfo.containerID())
            .setContainerState(ContainerReplicaProto.State.CLOSED)
            .setDatanodeDetails(dn1).build());

    Assert.assertEquals(1,
        containerManager.getContainerReplicas(
            finalContInfo.containerID()).size());

    contInfo = containerManager.getContainer(contInfo.containerID());
    Assert.assertEquals(LifeCycleState.CLOSED, contInfo.getState());
    // After closing the container, we should get the replica and construct
    // standalone pipeline. No more ratis pipeline.

    Set<DatanodeDetails> replicaNodes = containerManager
        .getContainerReplicas(contInfo.containerID())
        .stream().map(ContainerReplica::getDatanodeDetails)
        .collect(Collectors.toSet());
    Assert.assertTrue(replicaNodes.contains(dn1));
  }

  @Test
  public void testGetContainerReplicaWithParallelUpdate() throws Exception {
    testGetContainerWithPipeline();
    final Optional<ContainerID> id = containerManager.getContainerIDs()
        .stream().findFirst();
    Assert.assertTrue(id.isPresent());
    final ContainerID cId = id.get();
    final Optional<ContainerReplica> replica = containerManager
        .getContainerReplicas(cId).stream().findFirst();
    Assert.assertTrue(replica.isPresent());
    final ContainerReplica cReplica = replica.get();
    final AtomicBoolean runUpdaterThread =
        new AtomicBoolean(true);

    Thread updaterThread = new Thread(() -> {
      while (runUpdaterThread.get()) {
        try {
          containerManager.removeContainerReplica(cId, cReplica);
          containerManager.updateContainerReplica(cId, cReplica);
        } catch (ContainerException e) {
          Assert.fail("Container Exception: " + e.getMessage());
        }
      }
    });

    updaterThread.setDaemon(true);
    updaterThread.start();

    IntStream.range(0, 100).forEach(i -> {
      try {
        Assert.assertNotNull(containerManager
            .getContainerReplicas(cId)
            .stream().map(ContainerReplica::getDatanodeDetails)
            .collect(Collectors.toSet()));
      } catch (ContainerNotFoundException e) {
        Assert.fail("Missing Container " + id);
      }
    });
    runUpdaterThread.set(false);
  }

  @Test
  public void testgetNoneExistentContainer() {
    try {
      containerManager.getContainer(ContainerID.valueOf(
          random.nextInt() & Integer.MAX_VALUE));
      Assert.fail();
    } catch (ContainerNotFoundException ex) {
      // Success!
    }
  }

  @Test
  public void testCloseContainer() throws IOException {
    ContainerID id = createContainer().containerID();
    containerManager.updateContainerState(id,
        HddsProtos.LifeCycleEvent.FINALIZE);
    containerManager.updateContainerState(id,
        HddsProtos.LifeCycleEvent.CLOSE);
    ContainerInfo closedContainer = containerManager.getContainer(id);
    Assert.assertEquals(LifeCycleState.CLOSED, closedContainer.getState());
  }

  /**
   * Creates a container with the given name in SCMContainerManager.
   * @throws IOException
   */
  private ContainerInfo createContainer()
      throws IOException {
    nodeManager.setSafemode(false);
    return containerManager
        .allocateContainer(replicationType, replicationFactor,
            OzoneConsts.OZONE);
  }

}
