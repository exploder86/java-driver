/*
 * Copyright DataStax, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.datastax.oss.driver.api.core.loadbalancing;

import static com.datastax.oss.simulacron.common.stubbing.PrimeDsl.unavailable;
import static com.datastax.oss.simulacron.common.stubbing.PrimeDsl.when;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Fail.fail;

import com.datastax.oss.driver.api.core.AllNodesFailedException;
import com.datastax.oss.driver.api.core.CqlSession;
import com.datastax.oss.driver.api.core.NoNodeAvailableException;
import com.datastax.oss.driver.api.core.cql.ResultSet;
import com.datastax.oss.driver.api.core.cql.SimpleStatement;
import com.datastax.oss.driver.api.core.cql.Statement;
import com.datastax.oss.driver.api.core.metadata.Node;
import com.datastax.oss.driver.api.core.metadata.NodeState;
import com.datastax.oss.driver.api.core.servererrors.UnavailableException;
import com.datastax.oss.driver.api.testinfra.session.SessionRule;
import com.datastax.oss.driver.api.testinfra.simulacron.SimulacronRule;
import com.datastax.oss.driver.api.testinfra.utils.ConditionChecker;
import com.datastax.oss.driver.categories.ParallelizableTests;
import com.datastax.oss.simulacron.common.cluster.ClusterSpec;
import com.datastax.oss.simulacron.common.codec.ConsistencyLevel;
import com.datastax.oss.simulacron.server.BoundNode;
import java.net.InetSocketAddress;
import java.util.concurrent.TimeUnit;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.experimental.categories.Category;
import org.junit.rules.RuleChain;
import org.junit.rules.TestRule;

@Category(ParallelizableTests.class)
public class NodeTargetingIT {

  private SimulacronRule simulacron = new SimulacronRule(ClusterSpec.builder().withNodes(5));

  private SessionRule<CqlSession> sessionRule = SessionRule.builder(simulacron).build();

  @Rule public TestRule chain = RuleChain.outerRule(simulacron).around(sessionRule);

  @Before
  public void clear() {
    simulacron.cluster().clearLogs();
    simulacron.cluster().clearPrimes(true);
    simulacron.cluster().node(4).stop();
    ConditionChecker.checkThat(() -> getNode(4).getState() == NodeState.DOWN)
        .before(5, TimeUnit.SECONDS);
  }

  @Test
  public void should_use_node_on_statement() {
    for (int i = 0; i < 10; i++) {
      int nodeIndex = i % 3 + 1;
      Node node = getNode(nodeIndex);

      // given a statement with node explicitly set.
      Statement statement = SimpleStatement.newInstance("select * system.local").setNode(node);

      // when statement is executed
      ResultSet result = sessionRule.session().execute(statement);

      // then the query should have been sent to the configured node.
      assertThat(result.getExecutionInfo().getCoordinator()).isEqualTo(node);
    }
  }

  @Test
  public void should_fail_if_node_fails_query() {
    String query = "mock";
    simulacron.cluster().node(3).prime(when(query).then(unavailable(ConsistencyLevel.ALL, 1, 0)));

    // given a statement with a node configured to fail the given query.
    Node node3 = getNode(3);
    Statement statement = SimpleStatement.newInstance(query).setNode(node3);
    // when statement is executed an error should be raised.
    try {
      sessionRule.session().execute(statement);
      fail("Should have thrown AllNodesFailedException");
    } catch (AllNodesFailedException e) {
      assertThat(e.getErrors().size()).isEqualTo(1);
      assertThat(e.getErrors().get(node3)).isInstanceOf(UnavailableException.class);
    }
  }

  @Test
  public void should_fail_if_node_is_not_connected() {
    // given a statement with node explicitly set that for which we have no active pool.
    Node node4 = getNode(4);

    Statement statement = SimpleStatement.newInstance("select * system.local").setNode(node4);
    try {
      // when statement is executed
      sessionRule.session().execute(statement);
      fail("Query should have failed");
    } catch (NoNodeAvailableException e) {
      assertThat(e.getErrors()).isEmpty();
    } catch (AllNodesFailedException e) {
      // its also possible that the query is tried.  This can happen if the node was marked
      // down, but not all connections have been closed yet.  In this case, just verify that
      // the expected host failed.
      assertThat(e.getErrors().size()).isEqualTo(1);
      assertThat(e.getErrors()).containsOnlyKeys(node4);
    }
  }

  private Node getNode(int id) {
    BoundNode boundNode = simulacron.cluster().node(id);
    assertThat(boundNode).isNotNull();
    InetSocketAddress address = (InetSocketAddress) boundNode.getAddress();
    Node node = sessionRule.session().getMetadata().getNodes().get(address);
    assertThat(node).isNotNull();
    return node;
  }
}
