/*
 * Copyright (c) 2021 Airbyte, Inc., all rights reserved.
 */

package io.airbyte.workers.temporal.sync;

import com.google.common.base.Stopwatch;
import io.airbyte.commons.json.Jsons;
import io.airbyte.commons.lang.Exceptions;
import io.airbyte.config.ResourceRequirements;
import io.airbyte.scheduler.models.JobRunConfig;
import io.airbyte.workers.Worker;
import io.airbyte.workers.WorkerApp;
import io.airbyte.workers.WorkerException;
import io.airbyte.workers.process.AsyncKubePodStatus;
import io.airbyte.workers.process.AsyncOrchestratorPodProcess;
import io.airbyte.workers.process.KubePodInfo;
import io.airbyte.workers.process.KubePodProcess;
import io.airbyte.workers.process.KubeProcessFactory;
import io.airbyte.workers.temporal.TemporalUtils;
import io.fabric8.kubernetes.api.model.DeletionPropagation;
import io.fabric8.kubernetes.api.model.Pod;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;

/**
 * Coordinates configuring and managing the state of an async process. This is tied to the (job_id,
 * attempt_id) and will attempt to kill off lower attempt ids.
 *
 * @param <INPUT> a json-serializable input class for the worker
 * @param <OUTPUT> either {@link Void} or a json-serializable output class for the worker
 */
@Slf4j
public class LauncherWorker<INPUT, OUTPUT> implements Worker<INPUT, OUTPUT> {

  private static final String CONNECTION_ID_LABEL_KEY = "connection_id";
  private static final Duration MAX_DELETION_TIMEOUT = Duration.ofSeconds(45);

  private final UUID connectionId;
  private final String application;
  private final String podNamePrefix;
  private final JobRunConfig jobRunConfig;
  private final Map<String, String> additionalFileMap;
  private final WorkerApp.ContainerOrchestratorConfig containerOrchestratorConfig;
  private final String airbyteVersion;
  private final ResourceRequirements resourceRequirements;
  private final Class<OUTPUT> outputClass;

  private final AtomicBoolean cancelled = new AtomicBoolean(false);
  private AsyncOrchestratorPodProcess process;

  public LauncherWorker(final UUID connectionId,
                        final String application,
                        final String podNamePrefix,
                        final JobRunConfig jobRunConfig,
                        final Map<String, String> additionalFileMap,
                        final WorkerApp.ContainerOrchestratorConfig containerOrchestratorConfig,
                        final String airbyteVersion,
                        final ResourceRequirements resourceRequirements,
                        final Class<OUTPUT> outputClass) {
    this.connectionId = connectionId;
    this.application = application;
    this.podNamePrefix = podNamePrefix;
    this.jobRunConfig = jobRunConfig;
    this.additionalFileMap = additionalFileMap;
    this.containerOrchestratorConfig = containerOrchestratorConfig;
    this.airbyteVersion = airbyteVersion;
    this.resourceRequirements = resourceRequirements;
    this.outputClass = outputClass;
  }

  @Override
  public OUTPUT run(INPUT input, Path jobRoot) throws WorkerException {
    return TemporalUtils.withBackgroundHeartbeat(() -> {
      try {
        final Map<String, String> envMap = System.getenv().entrySet().stream()
            .filter(entry -> OrchestratorConstants.ENV_VARS_TO_TRANSFER.contains(entry.getKey()))
            .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));

        final Map<String, String> fileMap = new HashMap<>(additionalFileMap);
        fileMap.putAll(Map.of(
            OrchestratorConstants.INIT_FILE_APPLICATION, application,
            OrchestratorConstants.INIT_FILE_JOB_RUN_CONFIG, Jsons.serialize(jobRunConfig),
            OrchestratorConstants.INIT_FILE_INPUT, Jsons.serialize(input),
            OrchestratorConstants.INIT_FILE_ENV_MAP, Jsons.serialize(envMap)));

        final Map<Integer, Integer> portMap = Map.of(
            WorkerApp.KUBE_HEARTBEAT_PORT, WorkerApp.KUBE_HEARTBEAT_PORT,
            OrchestratorConstants.PORT1, OrchestratorConstants.PORT1,
            OrchestratorConstants.PORT2, OrchestratorConstants.PORT2,
            OrchestratorConstants.PORT3, OrchestratorConstants.PORT3,
            OrchestratorConstants.PORT4, OrchestratorConstants.PORT4);

        final var allLabels = KubeProcessFactory.getLabels(
            jobRunConfig.getJobId(),
            Math.toIntExact(jobRunConfig.getAttemptId()),
            Map.of(CONNECTION_ID_LABEL_KEY, connectionId.toString()));

        killRunningPodsForConnection();

        final var podNameAndJobPrefix = podNamePrefix + "-job-" + jobRunConfig.getJobId() + "-attempt-";
        final var podName = podNameAndJobPrefix + jobRunConfig.getAttemptId();
        final var kubePodInfo = new KubePodInfo(containerOrchestratorConfig.namespace(), podName);

        process = new AsyncOrchestratorPodProcess(
            kubePodInfo,
            containerOrchestratorConfig.documentStoreClient(),
            containerOrchestratorConfig.kubernetesClient());

        if (process.getDocStoreStatus().equals(AsyncKubePodStatus.NOT_STARTED)) {
          process.create(
              airbyteVersion,
              allLabels,
              resourceRequirements,
              fileMap,
              portMap);
        }

        // this waitFor can resume if the activity is re-run
        process.waitFor();

        if (cancelled.get()) {
          throw new CancellationException();
        }

        if (process.exitValue() != 0) {
          throw new WorkerException("Non-zero exit code!");
        }

        final var output = process.getOutput();

        if (output.isPresent()) {
          return Jsons.deserialize(output.get(), outputClass);
        } else {
          throw new WorkerException("Running the " + application + " launcher resulted in no readable output!");
        }
      } catch (Exception e) {
        if (cancelled.get()) {
          try {
            log.info("Destroying process due to cancellation.");
            process.destroy();
          } catch (Exception e2) {
            log.error("Failed to destroy process on cancellation.", e2);
          }
          throw new WorkerException("Launcher " + application + " was cancelled.", e);
        } else {
          throw new WorkerException("Running the launcher " + application + " failed", e);
        }
      }
    });
  }

  /**
   * It is imperative that we do not run multiple replications, normalizations, syncs, etc. at the
   * same time. Our best bet is to kill everything that is labelled with the connection id and wait
   * until no more pods exist with that connection id.
   */
  private void killRunningPodsForConnection() {
    final var client = containerOrchestratorConfig.kubernetesClient();

    // delete all pods with the connection id label
    List<Pod> runningPods = getNonTerminalPodsWithLabels();
    final Stopwatch stopwatch = Stopwatch.createStarted();

    while (!runningPods.isEmpty() && stopwatch.elapsed().compareTo(MAX_DELETION_TIMEOUT) < 0) {
      log.warn("There are currently running pods for the connection: " + getPodNames(runningPods).toString());

      log.info("Attempting to delete pods: " + getPodNames(runningPods).toString());
      runningPods.stream()
          .parallel()
          .forEach(kubePod -> client.resource(kubePod).withPropagationPolicy(DeletionPropagation.FOREGROUND).delete());

      log.info("Waiting for deletion...");
      Exceptions.toRuntime(() -> Thread.sleep(1000));

      runningPods = getNonTerminalPodsWithLabels();
    }

    if (runningPods.isEmpty()) {
      log.info("Successfully deleted all running pods for the connection!");
    } else {
      throw new RuntimeException("Unable to delete pods: " + getPodNames(runningPods).toString());
    }
  }

  private List<String> getPodNames(final List<Pod> pods) {
    return pods.stream().map(pod -> pod.getMetadata().getName()).collect(Collectors.toList());
  }

  private List<Pod> getNonTerminalPodsWithLabels() {
    return containerOrchestratorConfig.kubernetesClient().pods()
        .inNamespace(containerOrchestratorConfig.namespace())
        .withLabels(Map.of(CONNECTION_ID_LABEL_KEY, connectionId.toString()))
        .list()
        .getItems()
        .stream()
        .filter(kubePod -> !KubePodProcess.isTerminal(kubePod))
        .collect(Collectors.toList());
  }

  @Override
  public void cancel() {
    cancelled.set(true);

    if (process == null) {
      return;
    }

    log.debug("Closing sync runner process");
    killRunningPodsForConnection();

    if (process.hasExited()) {
      log.info("Successfully cancelled process.");
    } else {
      // try again
      killRunningPodsForConnection();

      if (process.hasExited()) {
        log.info("Successfully cancelled process.");
      } else {
        log.error("Unable to cancel process");
      }
    }
  }

}
