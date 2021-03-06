package edu.snu.bdcs.differentsc.mlpractice;

import com.microsoft.reef.driver.task.TaskConfigurationOptions;
import com.microsoft.reef.io.network.group.operators.Broadcast;
import com.microsoft.reef.io.network.group.operators.Reduce;
import com.microsoft.reef.io.network.nggroup.api.task.CommunicationGroupClient;
import com.microsoft.reef.io.network.nggroup.api.task.GroupCommClient;
import com.microsoft.reef.task.Task;
import com.microsoft.tang.annotations.Parameter;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;

import javax.inject.Inject;
import java.io.BufferedWriter;
import java.io.OutputStreamWriter;
import java.util.ArrayList;

public final class ControllerTask implements Task {

  private final CommunicationGroupClient communicationGroupClient;
  private final Broadcast.Sender<ArrayList<Double>> vectorBroadcastSender;
  private final Reduce.Receiver<ArrayList<Double>> sumVectorReduceReceiver;
  private final Reduce.Receiver<ArrayList<Double>> averageVectorReduceReceiver;
  private final Reduce.Receiver<Double> sumDoubleReduceReceiver;
  private final int iterNum;

  @Inject
  ControllerTask(final GroupCommClient groupCommClient,
      @Parameter(TaskConfigurationOptions.Identifier.class) final String identifier,
      @Parameter(Parameters.IterNum.class) final int iterNum) {

    this.communicationGroupClient = groupCommClient.getCommunicationGroup(MLGroupCommucation.class);
    this.vectorBroadcastSender = communicationGroupClient.getBroadcastSender(GroupCommunicationNames
        .VectorBroadcaster.class);
    this.sumVectorReduceReceiver = communicationGroupClient.getReduceReceiver(GroupCommunicationNames
        .SumVectorReducer.class);
    this.averageVectorReduceReceiver = communicationGroupClient.getReduceReceiver(GroupCommunicationNames
        .AverageVectorReducer.class);
    this.sumDoubleReduceReceiver = communicationGroupClient.getReduceReceiver(GroupCommunicationNames
        .SumDoubleReducer.class);
    this.iterNum = iterNum;
  }

  @Override
  public final byte[] call(final byte[] memento) throws Exception {

    // Prepare for HDFS file writing
    final Path pt = new Path("hdfs://localhost:9000/output.txt");
    final FileSystem fs = FileSystem.get(new Configuration());
    final BufferedWriter br = new BufferedWriter(new OutputStreamWriter(fs.create(pt, true)));
    br.write("Result for Linear Regression using L-BFGS\n");
    for (int i = 0; i < iterNum; i++) {
      br.write("Iteration " + i + "\n");

      // Calculate global graident from local gradients
      final ArrayList<Double> globalGradient = sumVectorReduceReceiver.reduce();
      vectorBroadcastSender.send(globalGradient);
      
      // Calculate global weights from local weights
      final MyVector globalWeights = new MyVector(averageVectorReduceReceiver.reduce());
      vectorBroadcastSender.send(globalWeights.getArrayList());

      if(globalWeights.containsNaN()) {
        br.write("One of the worker nodes has converged\n");
        break;
      }
      br.write("Weight Vector: " + globalWeights.getArrayList().toString() + "\n");
      final Double globalError = sumDoubleReduceReceiver.reduce();
      br.write("Total error: " + globalError + "\n\n");
    }
    br.close();
    return null;
  }
}