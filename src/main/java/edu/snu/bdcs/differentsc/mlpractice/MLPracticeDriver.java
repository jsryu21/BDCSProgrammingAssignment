/**
 * Copyright (C) 2014 Microsoft Corporation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package edu.snu.bdcs.differentsc.mlpractice;

import com.microsoft.reef.driver.context.ActiveContext;
import com.microsoft.reef.driver.evaluator.EvaluatorRequestor;
import com.microsoft.reef.driver.task.CompletedTask;
import com.microsoft.reef.driver.task.TaskConfiguration;
import com.microsoft.reef.evaluator.context.parameters.ContextIdentifier;
import com.microsoft.reef.io.data.loading.api.DataLoadingService;
import com.microsoft.reef.io.network.nggroup.api.driver.CommunicationGroupDriver;
import com.microsoft.reef.io.network.nggroup.api.driver.GroupCommDriver;
import com.microsoft.reef.io.network.nggroup.impl.config.BroadcastOperatorSpec;
import com.microsoft.reef.io.network.nggroup.impl.config.ReduceOperatorSpec;
import com.microsoft.reef.io.serialization.SerializableCodec;
import com.microsoft.tang.Configuration;
import com.microsoft.tang.Injector;
import com.microsoft.tang.JavaConfigurationBuilder;
import com.microsoft.tang.Tang;
import com.microsoft.tang.annotations.Parameter;
import com.microsoft.tang.annotations.Unit;
import com.microsoft.tang.exceptions.InjectionException;
import com.microsoft.wake.EventHandler;

import javax.inject.Inject;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

@Unit
public final class MLPracticeDriver {

  private static final Logger LOG = Logger.getLogger(MLPracticeDriver.class.getName());

  private final EvaluatorRequestor requestor;
  private final DataLoadingService dataLoadingService;
  private final GroupCommDriver groupCommDriver;
  private final CommunicationGroupDriver MLCommGroup;
  private AtomicBoolean isControllerTaskStarted = new AtomicBoolean(false);
  private final int workerNum;
  private final int iterNum;
  private int submittedWorkerTask;
  private String groupCommConfiguredMasterId;

  @Inject
  public MLPracticeDriver(final EvaluatorRequestor requestor,
    final GroupCommDriver groupCommDriver,
    final DataLoadingService dataLoadingService,
    @Parameter(WorkerNum.class) int workerNum,
    @Parameter(IterNum.class) int iterNum) {
    LOG.log(Level.FINE, "Instantiated Driver");
    this.dataLoadingService = dataLoadingService;
    this.groupCommDriver = groupCommDriver;
    this.MLCommGroup = groupCommDriver.newCommunicationGroup(MLGroupCommucation.class, workerNum + 1);
    this.MLCommGroup.addBroadcast(BroadCastVector.class, BroadcastOperatorSpec.newBuilder()
        .setSenderId("ControllerTask")
        .setDataCodecClass(SerializableCodec.class)
        .build()).addReduce(ComputeInitialParameter.class, ReduceOperatorSpec.newBuilder()
        .setReceiverId("ControllerTask")
        .setDataCodecClass(SerializableCodec.class)
        .setReduceFunctionClass(CalculateInitialParameter.class)
        .build()).addReduce(ComputeGlobalGradient.class, ReduceOperatorSpec.newBuilder()
        .setReceiverId("ControllerTask")
        .setDataCodecClass(SerializableCodec.class)
        .setReduceFunctionClass(CalculateGlobalGradient.class).build())
        .finalise();

    this.requestor = requestor;
    this.workerNum = workerNum;
    this.iterNum = iterNum;
    this.submittedWorkerTask = 0;
  }

  public class ActiveContextHandler implements EventHandler<ActiveContext> {
    @Override
    public void onNext(final ActiveContext activeContext) {
      String contextId = activeContext.getId();

      // Context is for WorkerTask, not added task for group communication yet.
      if(dataLoadingService.isDataLoadedContext(activeContext)) {
        final Configuration contextConf = groupCommDriver.getContextConfiguration();
        final Configuration serviceConf = groupCommDriver.getServiceConfiguration();

        activeContext.submitContextAndService(contextConf, serviceConf);
      }
      // Context is ready for task with group communication conf
      else if (groupCommDriver.isConfigured(activeContext)) {
        final Configuration basicTaskConf;
        if (contextId.equals(groupCommConfiguredMasterId)) {
          basicTaskConf = TaskConfiguration.CONF
              .set(TaskConfiguration.IDENTIFIER, "ControllerTask")
              .set(TaskConfiguration.TASK, ControllerTask.class)
              .build();
        }
        else {
          basicTaskConf = TaskConfiguration.CONF
              .set(TaskConfiguration.IDENTIFIER, "WorkerTask_" + (submittedWorkerTask++))
              .set(TaskConfiguration.TASK, WorkerTask.class)
              .build();
        }
        final JavaConfigurationBuilder partialTaskConfigurationBuilder = Tang.Factory.getTang()
            .newConfigurationBuilder();
        partialTaskConfigurationBuilder.addConfiguration(basicTaskConf);
        partialTaskConfigurationBuilder.bindNamedParameter(IterNum.class, Integer.toString(iterNum));
        final Configuration partialTaskConfiguration = partialTaskConfigurationBuilder.build();
        MLCommGroup.addTask(partialTaskConfiguration);

        final Configuration taskConfiguration = groupCommDriver.getTaskConfiguration(partialTaskConfiguration);

        activeContext.submitTask(taskConfiguration);
      }
      // Context is for ControllerTask, not added task for group communication yet. Only called once.
      else {
        final Configuration contextConf = groupCommDriver.getContextConfiguration();
        final Configuration serviceConf = groupCommDriver.getServiceConfiguration();
        groupCommConfiguredMasterId = contextId(contextConf);

        activeContext.submitContextAndService(contextConf, serviceConf);
      }
    }

    private String contextId(final Configuration contextConf) {
      try {
        final Injector injector = Tang.Factory.getTang().newInjector(contextConf);
        return injector.getNamedInstance(ContextIdentifier.class);
      } catch (final InjectionException e) {
        throw new RuntimeException("Unable to inject context identifier from context conf", e);
      }
    }
  }

  public class CompleteTaskHandler implements EventHandler<CompletedTask> {
    @Override
    public void onNext(final CompletedTask completedTask) {
      completedTask.getActiveContext().close();
    }
  }


}