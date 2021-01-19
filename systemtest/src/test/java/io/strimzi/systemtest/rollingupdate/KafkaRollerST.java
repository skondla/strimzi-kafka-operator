/*
 * Copyright Strimzi authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.strimzi.systemtest.rollingupdate;

import io.fabric8.kubernetes.api.model.Affinity;
import io.fabric8.kubernetes.api.model.AffinityBuilder;
import io.fabric8.kubernetes.api.model.DeletionPropagation;
import io.fabric8.kubernetes.api.model.Event;
import io.fabric8.kubernetes.api.model.NodeSelectorRequirement;
import io.fabric8.kubernetes.api.model.NodeSelectorRequirementBuilder;
import io.fabric8.kubernetes.api.model.NodeSelectorTerm;
import io.fabric8.kubernetes.api.model.NodeSelectorTermBuilder;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.Quantity;
import io.fabric8.kubernetes.api.model.ResourceRequirements;
import io.fabric8.kubernetes.api.model.ResourceRequirementsBuilder;
import io.strimzi.api.kafka.model.KafkaResources;
import io.strimzi.api.kafka.model.template.KafkaClusterTemplate;
import io.strimzi.api.kafka.model.template.KafkaClusterTemplateBuilder;
import io.strimzi.api.kafka.model.template.PodTemplate;
import io.strimzi.operator.common.Annotations;
import io.strimzi.systemtest.AbstractST;
import io.strimzi.systemtest.Constants;
import io.strimzi.systemtest.Environment;
import io.strimzi.systemtest.resources.ResourceManager;
import io.strimzi.systemtest.resources.crd.KafkaResource;
import io.strimzi.systemtest.resources.crd.KafkaTopicResource;
import io.strimzi.systemtest.utils.StUtils;
import io.strimzi.systemtest.utils.kafkaUtils.KafkaTopicUtils;
import io.strimzi.systemtest.utils.kafkaUtils.KafkaUtils;
import io.strimzi.systemtest.utils.kubeUtils.controllers.StatefulSetUtils;
import io.strimzi.systemtest.utils.kubeUtils.objects.PodUtils;
import io.strimzi.test.timemeasuring.Operation;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static io.strimzi.systemtest.Constants.INTERNAL_CLIENTS_USED;
import static io.strimzi.systemtest.Constants.REGRESSION;
import static io.strimzi.systemtest.Constants.ROLLING_UPDATE;
import static io.strimzi.systemtest.k8s.Events.Created;
import static io.strimzi.systemtest.k8s.Events.Pulled;
import static io.strimzi.systemtest.k8s.Events.Scheduled;
import static io.strimzi.systemtest.k8s.Events.Started;
import static io.strimzi.systemtest.matchers.Matchers.hasAllOfReasons;
import static io.strimzi.test.k8s.KubeClusterResource.kubeClient;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.not;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag(REGRESSION)
@Tag(INTERNAL_CLIENTS_USED)
@Tag(ROLLING_UPDATE)
public class KafkaRollerST extends AbstractST {
    private static final Logger LOGGER = LogManager.getLogger(RollingUpdateST.class);
    static final String NAMESPACE = "kafka-roller-cluster-test";

    @Test
    void testKafkaRollsWhenTopicIsUnderReplicated() {
        String topicName = KafkaTopicUtils.generateRandomNameOfTopic();
        timeMeasuringSystem.setOperationID(timeMeasuringSystem.startTimeMeasuring(Operation.CLUSTER_RECOVERY));

        // We need to start with 3 replicas / brokers,
        // so that KafkaStreamsTopicStore topic gets set/distributed on this first 3 [0, 1, 2],
        // since this topic has replication-factor 3 and minISR 2.
        KafkaResource.create(KafkaResource.kafkaPersistent(clusterName, 3)
                .editSpec()
                    .editKafka()
                        .addToConfig("auto.create.topics.enable", "false")
                    .endKafka()
                .endSpec()
                .build());

        LOGGER.info("Running kafkaScaleUpScaleDown {}", clusterName);
        final int initialReplicas = kubeClient().getStatefulSet(KafkaResources.kafkaStatefulSetName(clusterName)).getStatus().getReplicas();
        assertEquals(3, initialReplicas);

        // Now that KafkaStreamsTopicStore topic is set on the first 3 brokers, lets spin-up another one.
        int scaledUpReplicas = 4;
        KafkaResource.replaceKafkaResource(clusterName, k -> k.getSpec().getKafka().setReplicas(scaledUpReplicas));
        StatefulSetUtils.waitForAllStatefulSetPodsReady(KafkaResources.kafkaStatefulSetName(clusterName), scaledUpReplicas);

        StatefulSetUtils.ssSnapshot(KafkaResources.kafkaStatefulSetName(clusterName));

        KafkaTopicResource.create(KafkaTopicResource.topic(clusterName, topicName, 4, 4, 4).build());

        //Test that the new pod does not have errors or failures in events
        String uid = kubeClient().getPodUid(KafkaResources.kafkaPodName(clusterName,  3));
        List<Event> events = kubeClient().listEvents(uid);
        assertThat(events, hasAllOfReasons(Scheduled, Pulled, Created, Started));

        //Test that CO doesn't have any exceptions in log
        timeMeasuringSystem.stopOperation(timeMeasuringSystem.getOperationID());
        assertNoCoErrorsLogged(timeMeasuringSystem.getDurationInSeconds(testClass, testName, timeMeasuringSystem.getOperationID()));

        // scale down
        int scaledDownReplicas = 3;
        LOGGER.info("Scaling down to {}", scaledDownReplicas);
        timeMeasuringSystem.setOperationID(timeMeasuringSystem.startTimeMeasuring(Operation.SCALE_DOWN));
        KafkaResource.replaceKafkaResource(clusterName, k -> k.getSpec().getKafka().setReplicas(scaledDownReplicas));
        StatefulSetUtils.waitForAllStatefulSetPodsReady(KafkaResources.kafkaStatefulSetName(clusterName), scaledDownReplicas);

        PodUtils.verifyThatRunningPodsAreStable(clusterName);
        Map<String, String> kafkaPods = StatefulSetUtils.ssSnapshot(KafkaResources.kafkaStatefulSetName(clusterName));

        // set annotation to trigger Kafka rolling update
        kubeClient().statefulSet(KafkaResources.kafkaStatefulSetName(clusterName)).withPropagationPolicy(DeletionPropagation.ORPHAN).edit()
            .editMetadata()
                .addToAnnotations(Annotations.ANNO_STRIMZI_IO_MANUAL_ROLLING_UPDATE, "true")
            .endMetadata()
            .done();

        StatefulSetUtils.waitTillSsHasRolled(KafkaResources.kafkaStatefulSetName(clusterName), kafkaPods);
    }

    @Test
    void testKafkaTopicRFLowerThanMinInSyncReplicas() {
        KafkaResource.create(KafkaResource.kafkaPersistent(clusterName, 3, 3).build());
        KafkaTopicResource.create(KafkaTopicResource.topic(clusterName, TOPIC_NAME, 1, 1).build());

        String kafkaName = KafkaResources.kafkaStatefulSetName(clusterName);
        Map<String, String> kafkaPods = StatefulSetUtils.ssSnapshot(kafkaName);

        LOGGER.info("Setting KafkaTopic's min.insync.replicas to be higher than replication factor");
        KafkaTopicResource.replaceTopicResource(TOPIC_NAME, kafkaTopic -> kafkaTopic.getSpec().getConfig().replace("min.insync.replicas", 2));

        // rolling update for kafka
        LOGGER.info("Annotate Kafka StatefulSet {} with manual rolling update annotation", kafkaName);
        timeMeasuringSystem.setOperationID(timeMeasuringSystem.startTimeMeasuring(Operation.ROLLING_UPDATE));
        // set annotation to trigger Kafka rolling update
        kubeClient().statefulSet(kafkaName).withPropagationPolicy(DeletionPropagation.ORPHAN).edit()
            .editMetadata()
                .addToAnnotations(Annotations.ANNO_STRIMZI_IO_MANUAL_ROLLING_UPDATE, "true")
            .endMetadata()
            .done();

        StatefulSetUtils.waitTillSsHasRolled(kafkaName, 3, kafkaPods);
        assertThat(StatefulSetUtils.ssSnapshot(kafkaName), is(not(kafkaPods)));
    }

    @Test
    void testKafkaPodCrashLooping() {
        KafkaResource.create(KafkaResource.kafkaPersistent(clusterName, 3, 3)
            .editSpec()
                .editKafka()
                    .withNewJvmOptions()
                        .withXx(Collections.emptyMap())
                    .endJvmOptions()
                .endKafka()
            .endSpec()
            .build());

        KafkaResource.replaceKafkaResource(clusterName, kafka ->
                kafka.getSpec().getKafka().getJvmOptions().setXx(Collections.singletonMap("UseParNewGC", "true")));

        KafkaUtils.waitForKafkaNotReady(clusterName);

        KafkaResource.replaceKafkaResource(clusterName, kafka ->
                kafka.getSpec().getKafka().getJvmOptions().setXx(Collections.emptyMap()));

        // kafka should get back ready in some reasonable time frame.
        // Current timeout for wait is set to 14 minutes, which should be enough.
        // No additional checks are needed, because in case of wait failure, the test will not continue.
        KafkaUtils.waitForKafkaReady(clusterName);
    }

    @Test
    void testKafkaPodImagePullBackOff() {
        KafkaResource.create(KafkaResource.kafkaPersistent(clusterName, 3, 3).build());

        KafkaResource.replaceKafkaResource(clusterName, kafka -> {
            kafka.getSpec().getKafka().setImage("quay.io/strimzi/kafka:not-existent-tag");
            kafka.getSpec().getZookeeper().setImage(StUtils.changeOrgAndTag("quay.io/strimzi/kafka:latest-kafka-" + Environment.ST_KAFKA_VERSION));
        });

        KafkaUtils.waitForKafkaNotReady(clusterName);

        assertTrue(checkIfExactlyOneKafkaPodIsNotReady(clusterName));

        KafkaResource.replaceKafkaResource(clusterName, kafka -> kafka.getSpec().getKafka().setImage(StUtils.changeOrgAndTag("quay.io/strimzi/kafka:latest-kafka-" + Environment.ST_KAFKA_VERSION)));

        // kafka should get back ready in some reasonable time frame.
        // Current timeout for wait is set to 14 minutes, which should be enough.
        // No additional checks are needed, because in case of wait failure, the test will not continue.
        KafkaUtils.waitForKafkaReady(clusterName);
    }

    @Test
    public void testKafkaPodPending() {
        ResourceRequirements rr = new ResourceRequirementsBuilder()
                .withRequests(Collections.emptyMap())
                .build();
        KafkaResource.create(KafkaResource.kafkaPersistent(clusterName, 3, 3)
                .editSpec()
                    .editKafka()
                        .withResources(rr)
                    .endKafka()
                .endSpec()
                .build());

        Map<String, Quantity> requests = new HashMap<>(2);
        requests.put("cpu", new Quantity("123456"));
        requests.put("memory", new Quantity("512Mi"));
        KafkaResource.replaceKafkaResource(clusterName, kafka ->
                kafka.getSpec().getKafka().getResources().setRequests(requests));

        KafkaUtils.waitForKafkaNotReady(clusterName);

        assertTrue(checkIfExactlyOneKafkaPodIsNotReady(clusterName));

        requests.put("cpu", new Quantity("250m"));

        KafkaResource.replaceKafkaResource(clusterName, kafka ->
                kafka.getSpec().getKafka().getResources().setRequests(requests));

        // kafka should get back ready in some reasonable time frame.
        // Current timeout for wait is set to 14 minutes, which should be enough.
        // No additional checks are needed, because in case of wait failure, the test will not continue.
        KafkaUtils.waitForKafkaReady(clusterName);
    }

    @Test
    void testKafkaPodPendingDueToRack() {
        // Testing this scenario
        // 1. deploy Kafka with wrong pod template (looking for nonexistent node) kafka pods should not exist
        // 2. wait for Kafka not ready, kafka pods should be in the pending state
        // 3. fix the Kafka CR, kafka pods should be in the pending state
        // 4. wait for Kafka ready, kafka pods should NOT be in the pending state

        NodeSelectorRequirement nsr = new NodeSelectorRequirementBuilder()
                .withKey("dedicated_test")
                .withNewOperator("In")
                .withValues("Kafka")
                .build();

        NodeSelectorTerm nst = new NodeSelectorTermBuilder()
                .withMatchExpressions(nsr)
                .build();

        Affinity affinity = new AffinityBuilder()
                .withNewNodeAffinity()
                    .withNewRequiredDuringSchedulingIgnoredDuringExecution()
                        .withNodeSelectorTerms(nst)
                    .endRequiredDuringSchedulingIgnoredDuringExecution()
                .endNodeAffinity()
                .build();

        PodTemplate pt = new PodTemplate();
        pt.setAffinity(affinity);

        KafkaClusterTemplate kct = new KafkaClusterTemplateBuilder()
                .withPod(pt)
                .build();

        KafkaResource.kafkaWithoutWait(KafkaResource.kafkaEphemeral(clusterName, 3, 3)
                .editSpec()
                    .editKafka()
                        .withTemplate(kct)
                    .endKafka()
                .endSpec()
                .build());

        // pods are stable in the Pending state
        PodUtils.waitUntilPodStabilityReplicasCount(KafkaResources.kafkaStatefulSetName(clusterName), 3);

        LOGGER.info("Removing requirement for the affinity");
        KafkaResource.replaceKafkaResource(clusterName, kafka ->
                kafka.getSpec().getKafka().getTemplate().getPod().setAffinity(null));

        // kafka should get back ready in some reasonable time frame
        KafkaUtils.waitForKafkaReady(clusterName);
    }

    boolean checkIfExactlyOneKafkaPodIsNotReady(String clusterName) {
        List<Pod> kafkaPods = kubeClient().listPodsByPrefixInName(KafkaResources.kafkaStatefulSetName(clusterName));
        int runningKafkaPods = (int) kafkaPods.stream().filter(pod -> pod.getStatus().getPhase().equals("Running")).count();

        return runningKafkaPods == (kafkaPods.size() - 1);
    }

    @BeforeAll
    void setup() {
        ResourceManager.setClassResources();
        installClusterOperator(NAMESPACE, Constants.CO_OPERATION_TIMEOUT_MEDIUM);
    }
}
