/*
 * Copyright Strimzi authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.strimzi.systemtest.utils.kubeUtils.objects;

import io.fabric8.kubernetes.api.model.ContainerStatus;
import io.fabric8.kubernetes.api.model.LabelSelector;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.client.internal.readiness.Readiness;
import io.strimzi.systemtest.Constants;
import io.strimzi.systemtest.utils.kubeUtils.controllers.StatefulSetUtils;
import io.strimzi.test.TestUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static io.strimzi.test.k8s.KubeClusterResource.cmdKubeClient;
import static io.strimzi.test.k8s.KubeClusterResource.kubeClient;

public class PodUtils {

    private static final Logger LOGGER = LogManager.getLogger(PodUtils.class);

    private PodUtils() { }

    /**
     * Returns a map of resource name to resource version for all the pods in the given {@code namespace}
     * matching the given {@code selector}.
     */
    public static Map<String, String> podSnapshot(LabelSelector selector) {
        List<Pod> pods = kubeClient().listPods(selector);
        return pods.stream()
            .collect(
                Collectors.toMap(pod -> pod.getMetadata().getName(),
                    pod -> pod.getMetadata().getUid()));
    }

    public static void waitForPodsReady(LabelSelector selector, int expectPods, boolean containers) {
        TestUtils.waitFor("All pods matching " + selector + "to be ready", Constants.POLL_INTERVAL_FOR_RESOURCE_READINESS, Constants.TIMEOUT_FOR_RESOURCE_READINESS, () -> {
            List<Pod> pods = kubeClient().listPods(selector);
            if (pods.isEmpty()) {
                LOGGER.debug("Not ready (no pods matching {})", selector);
                return false;
            }
            if (pods.size() != expectPods) {
                LOGGER.debug("Expected pods not ready");
                return false;
            }
            for (Pod pod : pods) {
                if (!Readiness.isPodReady(pod)) {
                    LOGGER.debug("Not ready (at least 1 pod not ready: {})", pod.getMetadata().getName());
                    return false;
                } else {
                    if (containers) {
                        for (ContainerStatus cs : pod.getStatus().getContainerStatuses()) {
                            LOGGER.debug("Not ready (at least 1 container of pod {} not ready: {})", pod.getMetadata().getName(), cs.getName());
                            if (!Boolean.TRUE.equals(cs.getReady())) {
                                return false;
                            }
                        }
                    }
                }
            }
            LOGGER.debug("Pods {} are ready",
                pods.stream().map(p -> p.getMetadata().getName()).collect(Collectors.joining(", ")));
            return true;
        });
    }

    public static void waitForPodUpdate(String podName, Date startTime) {
        TestUtils.waitFor(podName + " update", Constants.POLL_INTERVAL_FOR_RESOURCE_READINESS, Constants.TIMEOUT_FOR_RESOURCE_READINESS, () ->
            startTime.before(kubeClient().getCreationTimestampForPod(podName))
        );
    }

    /**
     * The method to wait when all pods for Kafka cluster will be deleted.
     * To wait for the cluster to be updated, the following methods must be used: {@link StatefulSetUtils#ssHasRolled(String, Map)}, {@link StatefulSetUtils#waitTillSsHasRolled(String, int, Map)} )}
     * @param clusterName Cluster name where pods should be deleted
     */
    public static void waitForKafkaClusterPodsDeletion(String clusterName) {
        LOGGER.info("Waiting when all pods in Kafka cluster {} will be deleted", clusterName);
        kubeClient().listPods().stream()
            .filter(p -> p.getMetadata().getName().startsWith(clusterName))
            .forEach(p -> waitForPodDeletion(p.getMetadata().getName()));
    }

    public static String getPodNameByPrefix(String prefix) {
        return kubeClient().listPods().stream().filter(pod -> pod.getMetadata().getName().startsWith(prefix))
            .findFirst().get().getMetadata().getName();
    }

    public static void waitForPod(String name) {
        LOGGER.info("Waiting when Pod {} will be ready", name);

        TestUtils.waitFor("pod " + name + " to be ready", Constants.POLL_INTERVAL_FOR_RESOURCE_READINESS, Constants.TIMEOUT_FOR_RESOURCE_READINESS,
            () -> {
                List<ContainerStatus> statuses =  kubeClient().getPod(name).getStatus().getContainerStatuses();
                for (ContainerStatus containerStatus : statuses) {
                    if (!containerStatus.getReady()) {
                        return false;
                    }
                }
                return true;
            });
    }

    public static void waitForPodDeletion(String name) {
        LOGGER.info("Waiting when Pod {} will be deleted", name);

        TestUtils.waitFor("Pod " + name + " could not be deleted", Constants.POLL_INTERVAL_FOR_RESOURCE_DELETION, Constants.TIMEOUT_FOR_POD_DELETION,
            () -> {
                Pod pod = kubeClient().getPod(name);
                if (pod == null) {
                    return true;
                } else {
                    LOGGER.debug("Deleting pod {}", pod.getMetadata().getName());
                    cmdKubeClient().deleteByName("pod", pod.getMetadata().getName());
                    return false;
                }
            });
    }

    public static void waitUntilPodsCountIsPresent(String podNamePrefix, int numberOfPods) {
        LOGGER.info("Waiting till pods with prefix {} count is present", podNamePrefix);
        TestUtils.waitFor("", Constants.GLOBAL_POLL_INTERVAL, Constants.GLOBAL_STATUS_TIMEOUT,
            () -> kubeClient().listPodsByPrefixInName(podNamePrefix).size() == numberOfPods);
        LOGGER.info("Pods with count {} are present", numberOfPods);
    }

    public static void waitUntilMessageIsInPodLogs(String podName, String message) {
        LOGGER.info("Waiting for message will be in the log");
        TestUtils.waitFor("Waiting for message will be in the log", Constants.GLOBAL_POLL_INTERVAL, Constants.TIMEOUT_FOR_LOG,
            () -> kubeClient().logs(podName).contains(message));
    }

    public static void waitUntilMessageIsInLogs(String podName, String containerName, String message) {
        LOGGER.info("Waiting for message will be in the log");
        TestUtils.waitFor("Waiting for message will be in the log", Constants.GLOBAL_POLL_INTERVAL, Constants.TIMEOUT_FOR_LOG,
            () -> kubeClient().logs(podName, containerName).contains(message));
    }

    public static void waitUntilPodContainersCount(String podNamePrefix, int numberOfContainers) {
        LOGGER.info("Waiting till pod {} will have {} containers", podNamePrefix, numberOfContainers);
        TestUtils.waitFor("Waiting till pod" + podNamePrefix + " will have " + numberOfContainers + " containers",
            Constants.GLOBAL_POLL_INTERVAL, Constants.GLOBAL_STATUS_TIMEOUT,
            () -> kubeClient().listPodsByPrefixInName(podNamePrefix).get(0).getSpec().getContainers().size() == numberOfContainers);
        LOGGER.info("Waiting till pod {} will have {} containers", podNamePrefix, numberOfContainers);
    }

    public static void waitUntilPodIsInCrashLoopBackOff(String podName) {
        LOGGER.info("Waiting till pod {} is in CrashLoopBackOff state", podName);
        TestUtils.waitFor("Waiting till pod {} is in CrashLoopBackOff state",
            Constants.GLOBAL_POLL_INTERVAL, Constants.GLOBAL_STATUS_TIMEOUT,
            () -> kubeClient().getPod(podName).getStatus().getContainerStatuses().get(0)
                .getState().getWaiting().getReason().equals("CrashLoopBackOff"));
        LOGGER.info("Pod {} is in CrashLoopBackOff state", podName);
    }

    public static void waitUntilPodIsPresent(String podNamePrefix) {
        LOGGER.info("Waiting till pod {} is present but not ready", podNamePrefix);
        TestUtils.waitFor("Waiting till pod {} is in CrashLoopBackOff state",
            Constants.GLOBAL_POLL_INTERVAL, Constants.GLOBAL_STATUS_TIMEOUT,
            () -> kubeClient().listPodsByPrefixInName(podNamePrefix).get(0) != null);
        LOGGER.info("Pod {} is present but not ready", podNamePrefix);
    }

    public static void waitUntilPodIsInPendingStatus(String podName) {
        LOGGER.info("Waiting till pod:" + podName + " is in pending status");
        TestUtils.waitFor("Waiting till pod:" + podName + " is in pending status", Constants.GLOBAL_POLL_INTERVAL, Constants.GLOBAL_TIMEOUT,
            () ->  kubeClient().getPod(podName).getStatus().getPhase().equals("Pending")
        );
        LOGGER.info("Pod:" + podName + " is in pending status");
    }

    public static void waitUntilPodLabelsDeletion(String podName, String... labelKeys) {
        for (final String labelKey : labelKeys) {
            LOGGER.info("Waiting for Kafka pod label {} change to {}", labelKey, null);
            TestUtils.waitFor("Waiting for Kafka pod label" + labelKey + " change to " + null, Constants.POLL_INTERVAL_FOR_RESOURCE_READINESS,
                Constants.TIMEOUT_FOR_RESOURCE_READINESS, () ->
                    kubeClient().getPod(podName).getMetadata().getLabels().get(labelKey) == null
            );
            LOGGER.info("Kafka pod label {} change to {}", labelKey, null);
        }
    }
}
