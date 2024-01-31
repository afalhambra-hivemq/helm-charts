package com.hivemq.helmcharts.util;

import io.fabric8.kubernetes.api.model.ConfigMap;
import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import io.fabric8.kubernetes.api.model.HasMetadata;
import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.api.model.SecretBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.LocalPortForward;
import io.fabric8.kubernetes.client.dsl.Resource;
import io.fabric8.kubernetes.client.dsl.base.ResourceDefinitionContext;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;

public class K8sUtil {

    private static final @NotNull Logger LOG = LoggerFactory.getLogger(K8sUtil.class);

    private K8sUtil() {
    }

    /**
     * Creates a ConfigMap from the given resource file on the classpath.
     *
     * @param client       the Kubernetes client to use
     * @param namespace    the namespace to create the custom resource in
     * @param resourceName the name of the resource file to use
     * @return the created ConfigMap instance
     */
    public static @NotNull ConfigMap createConfigMap(
            final @NotNull KubernetesClient client,
            final @NotNull String namespace,
            final @NotNull String resourceName) {
        return loadResource(client, namespace, resourceName, ConfigMap.class).create();
    }

    /**
     * Creates a Secret resource from the given parameters.
     *
     * @param client    the Kubernetes client to use
     * @param namespace the namespace to create the custom resource in
     * @param content   the data content to hold for the provided key param
     */
    public static void createSecret(
            final @NotNull KubernetesClient client,
            final @NotNull String namespace,
            final @NotNull String name,
            final @NotNull Map<String, String> content) {
        final var secret =
                new SecretBuilder().withNewMetadata().withName(name).endMetadata().addToData(content).build();
        assertThat(client.secrets().inNamespace(namespace).resource(secret).create()).isNotNull();
    }

    /**
     * Creates a Secret resource from the given parameters.
     *
     * @param client    the Kubernetes client to use
     * @param namespace the namespace to create the custom resource in
     * @param name      the name of the Secret to create
     * @param key       the key entry for the Secret
     * @param content   the data content to hold for the provided key param
     */
    public static void createSecret(
            final @NotNull KubernetesClient client,
            final @NotNull String namespace,
            final @NotNull String name,
            final @NotNull String key,
            final @NotNull String content) {
        final var secret =
                new SecretBuilder().withNewMetadata().withName(name).endMetadata().addToData(key, content).build();
        assertThat(client.secrets().inNamespace(namespace).resource(secret).create()).isNotNull();
    }

    /**
     * Creates a Secret from the given resource file on the classpath.
     *
     * @param client       the Kubernetes client to use
     * @param namespace    the namespace to create the custom resource in
     * @param resourceName the name of the resource file to use
     * @return the created Secret instance
     */
    public static @NotNull Secret createSecret(
            final @NotNull KubernetesClient client,
            final @NotNull String namespace,
            final @NotNull String resourceName) {
        return loadResource(client, namespace, resourceName, Secret.class).create();
    }

    /**
     * @param clazz Class object to get the namespace from.
     * @return Namespace generated from the class passed as argument, up to 63 characters.
     */
    public static @NotNull String getNamespaceName(final @NotNull Class<?> clazz) {
        final var namespace = clazz.getSimpleName().toLowerCase();
        if (namespace.length() > 63) {
            return namespace.substring(0, 63);
        }
        return namespace;
    }

    @SuppressWarnings("SameParameterValue")
    private static <T extends HasMetadata> @NotNull Resource<T> loadResource(
            final @NotNull KubernetesClient client,
            final @NotNull String namespace,
            final @NotNull String resourceName,
            final @NotNull Class<T> clazz) {
        try (final InputStream is = K8sUtil.class.getClassLoader().getResourceAsStream(resourceName)) {
            return client.resources(clazz).inNamespace(namespace).load(is);
        } catch (final IOException e) {
            throw new AssertionError("Could not read resource " + resourceName + ": " + e.getMessage());
        }
    }

    public static @NotNull Resource<GenericKubernetesResource> getHiveMQPlatform(
            final @NotNull KubernetesClient client, final @NotNull String namespace, final @NotNull String name) {
        final var context = new ResourceDefinitionContext.Builder().withGroup("hivemq.com")
                .withKind("HiveMQPlatform")
                .withVersion("v1")
                .withPlural("hivemq-platforms")
                .withNamespaced(true)
                .build();
        return client.genericKubernetesResources(context).inNamespace(namespace).withName(name);
    }

    /**
     * Returns a Predicate condition based on the state of a Kubernetes resource.
     */
    @SuppressWarnings("unchecked")
    public static @NotNull Predicate<GenericKubernetesResource> getHiveMQPlatformStatus(final @NotNull String state) {
        return resource -> {
            if (resource == null) {
                return false;
            }
            final var status = (Map<String, String>) resource.getAdditionalProperties().get("status");
            if (status == null) {
                return false;
            }
            LOG.debug("State to compare {} and waiting for {}", status.get("state"), state);
            return status.get("state").contains(state);
        };
    }

    /**
     * Returns the {@link LocalPortForward} object the service and target port passed as parameters.
     * @param client        the Kubernetes client to use
     * @param namespace     the namespace to look up for the service to expose
     * @param serviceName   the service name to port forward
     * @param targetPort    the service port to forward
     * @return {@link LocalPortForward}
     */
    public static @NotNull LocalPortForward getPortForward(
            final @NotNull KubernetesClient client,
            final @NotNull String namespace,
            final @NotNull String serviceName,
            final int targetPort) {
        return client.services().inNamespace(namespace).withName(serviceName).portForward(targetPort);
    }

    /**
     * Waits for the given platform to be in a RUNNING status.
     */
    public static void waitForHiveMQPlatformStateRunning(
            final @NotNull KubernetesClient client,
            final @NotNull String namespace,
            final @NotNull String customResourceName) {
        final Resource<GenericKubernetesResource> hivemqCustomResource =
                K8sUtil.getHiveMQPlatform(client, namespace, customResourceName);
        hivemqCustomResource.waitUntilCondition(getHiveMQPlatformStatus("RUNNING"), 5, TimeUnit.MINUTES);
        assertThat(hivemqCustomResource.get().get("status").toString()).contains("RUNNING");
    }

    /**
     * Updates a ConfigMap from the given resource file on the classpath.
     *
     * @param client       the Kubernetes client to use
     * @param namespace    the namespace to update the custom resource in
     * @param resourceName the name of the resource file to use
     */
    public static void updateConfigMap(
            final @NotNull KubernetesClient client,
            final @NotNull String namespace,
            final @NotNull String resourceName) {
        loadResource(client, namespace, resourceName, ConfigMap.class).update();
    }
}