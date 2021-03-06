/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.gobblin.service.modules.flow;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.hadoop.fs.Path;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Joiner;
import com.google.common.base.Optional;
import com.google.common.collect.Lists;
import com.google.common.io.Files;
import com.typesafe.config.Config;
import com.typesafe.config.ConfigValueFactory;

import lombok.Getter;

import org.apache.gobblin.configuration.ConfigurationKeys;
import org.apache.gobblin.runtime.api.FlowSpec;
import org.apache.gobblin.runtime.api.JobSpec;
import org.apache.gobblin.runtime.api.JobTemplate;
import org.apache.gobblin.runtime.api.SpecExecutor;
import org.apache.gobblin.runtime.api.SpecNotFoundException;
import org.apache.gobblin.service.modules.dataset.DatasetDescriptor;
import org.apache.gobblin.service.modules.flowgraph.Dag;
import org.apache.gobblin.service.modules.flowgraph.Dag.DagNode;
import org.apache.gobblin.service.modules.flowgraph.FlowEdge;
import org.apache.gobblin.service.modules.spec.JobExecutionPlan;
import org.apache.gobblin.service.modules.spec.JobExecutionPlanDagFactory;
import org.apache.gobblin.service.modules.template.FlowTemplate;
import org.apache.gobblin.util.ConfigUtils;


/**
 * A class that encapsulates a path in the {@link org.apache.gobblin.service.modules.flowgraph.FlowGraph}.
 */
public class FlowGraphPath {
  @Getter
  private List<List<FlowEdgeContext>> paths;
  private FlowSpec flowSpec;
  private Long flowExecutionId;

  public FlowGraphPath(FlowSpec flowSpec, Long flowExecutionId) {
    this.flowSpec = flowSpec;
    this.flowExecutionId = flowExecutionId;
  }

  public void addPath(List<FlowEdgeContext> path) {
    if (this.paths == null) {
      this.paths = new ArrayList<>();
    }
    this.paths.add(path);
  }

  public Dag<JobExecutionPlan> asDag() throws SpecNotFoundException, JobTemplate.TemplateException, URISyntaxException {
    Dag<JobExecutionPlan> flowDag = new Dag<>(new ArrayList<>());

    for (List<FlowEdgeContext> path: paths) {
      Dag<JobExecutionPlan> pathDag = new Dag<>(new ArrayList<>());
      Iterator<FlowEdgeContext> pathIterator = path.iterator();
      while (pathIterator.hasNext()) {
        Dag<JobExecutionPlan> flowEdgeDag = convertHopToDag(pathIterator.next());
        pathDag = concatenate(pathDag, flowEdgeDag);
      }
      flowDag = flowDag.merge(pathDag);
    }
    return flowDag;
  }

  /**
   * Concatenate two {@link Dag}s. Modify the {@link ConfigurationKeys#JOB_DEPENDENCIES} in the {@link JobSpec}s of the child
   * {@link Dag} to reflect the concatenation operation.
   * @param dagLeft The parent dag.
   * @param dagRight The child dag.
   * @return The concatenated dag with modified {@link ConfigurationKeys#JOB_DEPENDENCIES}.
   */
  @VisibleForTesting
  static Dag<JobExecutionPlan> concatenate(Dag<JobExecutionPlan> dagLeft, Dag<JobExecutionPlan> dagRight) {
    List<DagNode<JobExecutionPlan>> endNodes = dagLeft.getEndNodes();
    List<DagNode<JobExecutionPlan>> startNodes = dagRight.getStartNodes();
    List<String> dependenciesList = Lists.newArrayList();
    //List of nodes with no dependents in the concatenated dag.
    Set<DagNode<JobExecutionPlan>> forkNodes = new HashSet<>();

    for (DagNode<JobExecutionPlan> dagNode: endNodes) {
      if (isNodeForkable(dagNode)) {
        //If node is forkable, then add its parents (if any) to the dependencies list.
        forkNodes.add(dagNode);
        List<DagNode<JobExecutionPlan>> parentNodes = dagLeft.getParents(dagNode);
        for (DagNode<JobExecutionPlan> parentNode: parentNodes) {
          dependenciesList.add(parentNode.getValue().getJobSpec().getConfig().getString(ConfigurationKeys.JOB_NAME_KEY));
        }
      } else {
        dependenciesList.add(dagNode.getValue().getJobSpec().getConfig().getString(ConfigurationKeys.JOB_NAME_KEY));
      }
    }

    if (!dependenciesList.isEmpty()) {
      String dependencies = Joiner.on(",").join(dependenciesList);

      for (DagNode<JobExecutionPlan> childNode : startNodes) {
        JobSpec jobSpec = childNode.getValue().getJobSpec();
        jobSpec.setConfig(jobSpec.getConfig().withValue(ConfigurationKeys.JOB_DEPENDENCIES, ConfigValueFactory.fromAnyRef(dependencies)));
      }
    }

    return dagLeft.concatenate(dagRight, forkNodes);
  }

  private static boolean isNodeForkable(DagNode<JobExecutionPlan> dagNode) {
    Config jobConfig = dagNode.getValue().getJobSpec().getConfig();
    return ConfigUtils.getBoolean(jobConfig, ConfigurationKeys.JOB_FORK_ON_CONCAT, false);
  }

  /**
   * Given an instance of {@link FlowEdge}, this method returns a {@link Dag < JobExecutionPlan >} that moves data
   * from the source of the {@link FlowEdge} to the destination of the {@link FlowEdge}.
   * @param flowEdgeContext an instance of {@link FlowEdgeContext}.
   * @return a {@link Dag} of {@link JobExecutionPlan}s associated with the {@link FlowEdge}.
   */
   private Dag<JobExecutionPlan> convertHopToDag(FlowEdgeContext flowEdgeContext)
      throws SpecNotFoundException, JobTemplate.TemplateException, URISyntaxException {
    FlowTemplate flowTemplate = flowEdgeContext.getEdge().getFlowTemplate();
    DatasetDescriptor inputDatasetDescriptor = flowEdgeContext.getInputDatasetDescriptor();
    DatasetDescriptor outputDatasetDescriptor = flowEdgeContext.getOutputDatasetDescriptor();
    Config mergedConfig = flowEdgeContext.getMergedConfig();
    SpecExecutor specExecutor = flowEdgeContext.getSpecExecutor();

    List<JobExecutionPlan> jobExecutionPlans = new ArrayList<>();
    Map<String, String> templateToJobNameMap = new HashMap<>();

    //Get resolved job configs from the flow template
    List<Config> resolvedJobConfigs = flowTemplate.getResolvedJobConfigs(mergedConfig, inputDatasetDescriptor, outputDatasetDescriptor);
    //Iterate over each resolved job config and convert the config to a JobSpec.
    for (Config resolvedJobConfig : resolvedJobConfigs) {
      JobExecutionPlan jobExecutionPlan = new JobExecutionPlan.Factory().createPlan(flowSpec, resolvedJobConfig, specExecutor, flowExecutionId);
      jobExecutionPlans.add(jobExecutionPlan);
      templateToJobNameMap.put(getJobTemplateName(jobExecutionPlan), jobExecutionPlan.getJobSpec().getConfig().getString(
          ConfigurationKeys.JOB_NAME_KEY));
    }
    updateJobDependencies(jobExecutionPlans, templateToJobNameMap);
    return new JobExecutionPlanDagFactory().createDag(jobExecutionPlans);
  }

  /**
   * The job template name is derived from the {@link org.apache.gobblin.runtime.api.JobTemplate} URI. It is the
   * simple name of the path component of the URI.
   * @param jobExecutionPlan
   * @return the simple name of the job template from the URI of its path.
   */
  private static String getJobTemplateName(JobExecutionPlan jobExecutionPlan) {
    Optional<URI> jobTemplateUri = jobExecutionPlan.getJobSpec().getTemplateURI();
    if (jobTemplateUri.isPresent()) {
      return Files.getNameWithoutExtension(new Path(jobTemplateUri.get()).getName());
    } else {
      return null;
    }
  }

  /**
   * A method to modify the {@link ConfigurationKeys#JOB_DEPENDENCIES} specified in a {@link JobTemplate} to those
   * which are usable in a {@link JobSpec}.
   * The {@link ConfigurationKeys#JOB_DEPENDENCIES} specified in a JobTemplate use the JobTemplate names
   * (i.e. the file names of the templates without the extension). However, the same {@link FlowTemplate} may be used
   * across multiple {@link FlowEdge}s. To ensure that we capture dependencies between jobs correctly as Dags from
   * successive hops are merged, we translate the {@link JobTemplate} name specified in the dependencies config to
   * {@link ConfigurationKeys#JOB_NAME_KEY} from the corresponding {@link JobSpec}, which is guaranteed to be globally unique.
   * For example, consider a {@link JobTemplate} with URI job1.job which has "job.dependencies=job2,job3" (where job2.job and job3.job are
   * URIs of other {@link JobTemplate}s). Also, let the job.name config for the three jobs (after {@link JobSpec} is compiled) be as follows:
   *  "job.name=flowgrp1_flowName1_jobName1_1111", "job.name=flowgrp1_flowName1_jobName2_1121", and "job.name=flowgrp1_flowName1_jobName3_1131". Then,
   *  for job1, this method will set "job.dependencies=flowgrp1_flowName1_jobName2_1121, flowgrp1_flowName1_jobName3_1131".
   * @param jobExecutionPlans a list of {@link JobExecutionPlan}s
   * @param templateToJobNameMap a HashMap that has the mapping from the {@link JobTemplate} names to job.name in corresponding
   * {@link JobSpec}
   */
  private void updateJobDependencies(List<JobExecutionPlan> jobExecutionPlans, Map<String, String> templateToJobNameMap) {
    for (JobExecutionPlan jobExecutionPlan: jobExecutionPlans) {
      JobSpec jobSpec = jobExecutionPlan.getJobSpec();
      List<String> updatedDependenciesList = new ArrayList<>();
      if (jobSpec.getConfig().hasPath(ConfigurationKeys.JOB_DEPENDENCIES)) {
        for (String dependency : ConfigUtils.getStringList(jobSpec.getConfig(), ConfigurationKeys.JOB_DEPENDENCIES)) {
          if (!templateToJobNameMap.containsKey(dependency)) {
            //We should never hit this condition. The logic here is a safety check.
            throw new RuntimeException("TemplateToJobNameMap does not contain dependency " + dependency);
          }
          updatedDependenciesList.add(templateToJobNameMap.get(dependency));
        }
        String updatedDependencies = Joiner.on(",").join(updatedDependenciesList);
        jobSpec.setConfig(jobSpec.getConfig().withValue(ConfigurationKeys.JOB_DEPENDENCIES, ConfigValueFactory.fromAnyRef(updatedDependencies)));
      }
    }
  }
}
