/*
 * Copyright 2024 Aristide Cittadino
 *
 * Licensed under the Apache License, Version 2.0 (the "License")
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package it.water.core.service.integration.discovery;

import it.water.core.api.registry.ComponentRegistry;
import it.water.core.api.service.cluster.ClusterNodeOptions;
import it.water.core.api.service.integration.discovery.ServiceDiscoveryGlobalOptions;
import it.water.core.api.service.integration.discovery.ServiceLivenessClient;
import it.water.core.api.service.integration.discovery.ServiceLivenessListener;
import it.water.core.api.service.integration.discovery.ServiceLivenessRegistration;
import it.water.core.api.service.integration.discovery.ServiceLivenessSession;
import it.water.core.api.service.integration.discovery.ServiceRegistrationOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.UUID;

/**
 * Shared helper used by runtime registration lifecycle components.
 */
public abstract class ServiceRegistrationLifecycleSupport {
    private static final Logger log = LoggerFactory.getLogger(ServiceRegistrationLifecycleSupport.class);
    private static final long BOOTSTRAP_RETRY_SECONDS = 1L;
    private static final Duration ENDPOINT_CHECK_TIMEOUT = Duration.ofSeconds(2);
    private static final long ENDPOINT_CHECK_INITIAL_DELAY_SECONDS = 3L;
    private static final long ENDPOINT_CHECK_RETRY_DELAY_SECONDS = 3L;
    private static final int ENDPOINT_CHECK_MAX_ATTEMPTS = 3;

    protected ServiceDiscoveryRegistryClientInternal client;
    protected String registeredServiceName;
    protected String registeredInstanceId;

    private final Object lifecycleMonitor = new Object();
    private ScheduledExecutorService scheduler;
    private ScheduledFuture<?> retryFuture;
    private ScheduledFuture<?> bootstrapRegistrationFuture;
    private ScheduledFuture<?> endpointValidationFuture;
    private HttpClient endpointCheckClient;
    private DiscoverableServiceInfoImpl lastRegisteredServiceInfo;
    private String discoveryUrl;
    private ServiceLivenessClient livenessClient;
    private ServiceLivenessSession livenessSession;
    private long registrationRetryInitialDelaySeconds = 30L;
    private long registrationRetryMaxDelaySeconds = 300L;
    private String clusterNodeId = "";
    private String clusterLayer = "";
    private boolean active;
    private boolean registrationConfirmed;

    protected void doRegister(ServiceDiscoveryRegistryClientInternal client,
                              ServiceRegistrationOptions options,
                              ServiceDiscoveryGlobalOptions globalOptions,
                              ClusterNodeOptions clusterNodeOptions,
                              ServiceLivenessClient providedLivenessClient) {
        ServiceDiscoveryRegistryClientInternal effectiveClient = client != null ? client : this.client;
        if (effectiveClient == null) {
            log.warn("Service registration skipped: ServiceDiscoveryRegistryClient not available");
            return;
        }
        if (options == null) {
            log.warn("Service registration skipped: ServiceRegistrationOptions not available");
            return;
        }

        String resolvedDiscoveryUrl = defaultString(options.getDiscoveryUrl());
        if (resolvedDiscoveryUrl.isBlank() && globalOptions != null) {
            resolvedDiscoveryUrl = defaultString(globalOptions.getDiscoveryUrl());
        }
        if (resolvedDiscoveryUrl.isBlank()) {
            log.debug("Service registration disabled: discovery URL not configured (module nor global fallback)");
            return;
        }

        String serviceName = defaultString(options.getServiceName());
        if (serviceName.isBlank()) {
            log.debug("Service registration skipped: service name not configured");
            return;
        }

        String advertisedEndpoint = DiscoveryAddressUtils.normalizeAdvertisedEndpoint(options.getAdvertisedEndpoint());
        String servicePort = resolveServicePort(options, advertisedEndpoint);
        if (servicePort.isBlank()) {
            log.warn("Service registration skipped for '{}': unable to resolve service port", serviceName);
            return;
        }

        String effectiveInstanceId = defaultString(options.getInstanceId());
        if (effectiveInstanceId.isBlank()) {
            effectiveInstanceId = serviceName + "-" + UUID.randomUUID().toString().substring(0, 8);
        }

        String protocol = defaultIfBlank(options.getProtocol(), "http");
        String root = DiscoveryAddressUtils.normalizeRoot(options.getRoot());
        if (advertisedEndpoint.isBlank() && root.isBlank()) {
            log.warn("Service registration skipped for '{}': root or advertised endpoint is required", serviceName);
            return;
        }

        String serviceVersion = defaultIfBlank(options.getServiceVersion(), "1.0.0");
        String serviceHost = resolveServiceHost(options, globalOptions, clusterNodeOptions, serviceName, advertisedEndpoint);
        if (advertisedEndpoint.isBlank() && serviceHost.isBlank()) {
            log.warn("Service registration skipped for '{}': unable to resolve service host", serviceName);
            return;
        }
        long resolvedRetryInitialDelay = resolveRegistrationRetryInitialDelay(globalOptions);
        long resolvedRetryMaxDelay = Math.max(resolvedRetryInitialDelay,
                resolveRegistrationRetryMaxDelay(globalOptions));
        String resolvedClusterNodeId = clusterNodeOptions != null ? defaultString(clusterNodeOptions.getNodeId()) : "";
        String resolvedClusterLayer = clusterNodeOptions != null ? defaultString(clusterNodeOptions.getLayer()) : "";

        DiscoverableServiceInfoImpl serviceInfo = new DiscoverableServiceInfoImpl(
                protocol, servicePort, serviceName, effectiveInstanceId, root, serviceVersion, serviceHost, advertisedEndpoint
        );

        synchronized (lifecycleMonitor) {
            this.client = effectiveClient;
            this.discoveryUrl = resolvedDiscoveryUrl;
            this.lastRegisteredServiceInfo = serviceInfo;
            this.livenessClient = providedLivenessClient;
            this.registeredServiceName = serviceName;
            this.registeredInstanceId = effectiveInstanceId;
            this.registrationRetryInitialDelaySeconds = resolvedRetryInitialDelay;
            this.registrationRetryMaxDelaySeconds = resolvedRetryMaxDelay;
            this.clusterNodeId = resolvedClusterNodeId;
            this.clusterLayer = resolvedClusterLayer;
            this.active = true;
            this.registrationConfirmed = false;
            ensureSchedulerLocked();
            cancelBootstrapRegistrationTaskLocked();
            cancelLivenessSessionLocked();
            cancelRetryTaskLocked();
            cancelEndpointValidationTaskLocked();
        }

        if (!attemptRegistrationOnce()) {
            scheduleRetry(resolvedRetryInitialDelay);
        }
    }

    protected void bootstrapRegister(ComponentRegistry componentRegistry,
                                     ServiceRegistrationOptions options) {
        synchronized (lifecycleMonitor) {
            active = true;
            ensureSchedulerLocked();
            if (tryBootstrapRegistrationLocked(componentRegistry, options)) {
                return;
            }
            scheduleBootstrapRegistrationLocked(componentRegistry, options);
        }
    }

    protected void doDeregister(ServiceDiscoveryRegistryClientInternal client) {
        ServiceDiscoveryRegistryClientInternal effectiveClient = client != null ? client : this.client;
        String serviceName;
        String instanceId;
        synchronized (lifecycleMonitor) {
            this.active = false;
            this.registrationConfirmed = false;
            cancelBootstrapRegistrationTaskLocked();
            serviceName = registeredServiceName;
            instanceId = registeredInstanceId;
            cancelLivenessSessionLocked();
            cancelRetryTaskLocked();
            cancelEndpointValidationTaskLocked();
            shutdownSchedulerLocked();
            lastRegisteredServiceInfo = null;
            discoveryUrl = null;
            livenessClient = null;
            clusterNodeId = "";
            clusterLayer = "";
        }
        if (serviceName == null || instanceId == null) {
            return;
        }

        log.info("Deregistering service '{}' instance '{}'", serviceName, instanceId);
        try {
            if (effectiveClient != null) {
                effectiveClient.unregisterService(serviceName, instanceId);
            } else {
                log.warn("Cannot deregister service '{}': ServiceDiscoveryRegistryClient not available", serviceName);
            }
        } catch (Exception e) {
            log.warn("Failed to deregister service '{}' instance '{}': {}", serviceName, instanceId, e.getMessage());
        } finally {
            synchronized (lifecycleMonitor) {
                registeredServiceName = null;
                registeredInstanceId = null;
            }
        }
    }

    private boolean tryBootstrapRegistrationLocked(ComponentRegistry componentRegistry,
                                                   ServiceRegistrationOptions options) {
        if (componentRegistry == null || options == null) {
            log.warn("Service registration bootstrap skipped: ComponentRegistry or options instance not available");
            return false;
        }
        ServiceDiscoveryGlobalOptions globalOptions = findComponentQuietly(componentRegistry, ServiceDiscoveryGlobalOptions.class);
        ServiceDiscoveryRegistryClientInternal discoveryClient = findComponentQuietly(componentRegistry, ServiceDiscoveryRegistryClientInternal.class);
        ClusterNodeOptions clusterNodeOptions = findComponentQuietly(componentRegistry, ClusterNodeOptions.class);
        ServiceLivenessClient runtimeLivenessClient = findComponentQuietly(componentRegistry, ServiceLivenessClient.class);

        if (globalOptions == null || discoveryClient == null || runtimeLivenessClient == null) {
            log.debug("Service registration bootstrap still waiting for dependencies: globalOptions={}, client={}, livenessClient={}",
                    globalOptions != null, discoveryClient != null, runtimeLivenessClient != null);
            return false;
        }

        cancelBootstrapRegistrationTaskLocked();
        doRegister(discoveryClient, options, globalOptions, clusterNodeOptions, runtimeLivenessClient);
        return true;
    }

    private void scheduleBootstrapRegistrationLocked(ComponentRegistry componentRegistry,
                                                     ServiceRegistrationOptions options) {
        if (!active || scheduler == null || componentRegistry == null || options == null) {
            return;
        }
        if (bootstrapRegistrationFuture != null && !bootstrapRegistrationFuture.isCancelled() && !bootstrapRegistrationFuture.isDone()) {
            return;
        }
        log.debug("Service registration waiting for runtime dependencies; retrying every {} second(s)", BOOTSTRAP_RETRY_SECONDS);
        bootstrapRegistrationFuture = scheduler.scheduleAtFixedRate(() -> {
                    synchronized (lifecycleMonitor) {
                        tryBootstrapRegistrationLocked(componentRegistry, options);
                    }
                },
                BOOTSTRAP_RETRY_SECONDS,
                BOOTSTRAP_RETRY_SECONDS,
                TimeUnit.SECONDS);
    }

    private void cancelBootstrapRegistrationTaskLocked() {
        if (bootstrapRegistrationFuture != null) {
            bootstrapRegistrationFuture.cancel(false);
            bootstrapRegistrationFuture = null;
        }
    }

    protected String resolveServicePort(ServiceRegistrationOptions options, String advertisedEndpoint) {
        String configuredPort = defaultString(options.getServicePort());
        if (!configuredPort.isBlank()) {
            return configuredPort;
        }
        return DiscoveryAddressUtils.extractPortFromEndpoint(advertisedEndpoint);
    }

    protected String resolveServiceHost(ServiceRegistrationOptions options,
                                        ServiceDiscoveryGlobalOptions globalOptions,
                                        ClusterNodeOptions clusterNodeOptions,
                                        String serviceName,
                                        String advertisedEndpoint) {
        if (!DiscoveryAddressUtils.normalizeAdvertisedEndpoint(advertisedEndpoint).isBlank()) {
            return "";
        }
        String configuredHost = defaultString(options.getServiceHost());
        if (!configuredHost.isBlank()) {
            return configuredHost;
        }
        if (globalOptions != null) {
            String globalHost = defaultString(globalOptions.getDefaultHost());
            if (!globalHost.isBlank()) {
                return globalHost;
            }
        }
        if (clusterNodeOptions != null) {
            if (clusterNodeOptions.useIpInClusterRegistration()) {
                return clusterNodeOptions.getIp();
            }
            return clusterNodeOptions.getHost();
        }
        return resolveLocalHostname(serviceName);
    }

    protected String defaultIfBlank(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value.trim();
    }

    protected String defaultString(String value) {
        return value == null ? "" : value.trim();
    }

    private EndpointValidationOutcome validateEndpointReachability(DiscoverableServiceInfoImpl serviceInfo) {
        String endpoint = resolveEndpoint(serviceInfo);
        if (endpoint.isBlank()) {
            return EndpointValidationOutcome.REACHABLE;
        }
        try {
            HttpRequest headRequest = HttpRequest.newBuilder()
                    .uri(URI.create(endpoint))
                    .timeout(ENDPOINT_CHECK_TIMEOUT)
                    .method("HEAD", HttpRequest.BodyPublishers.noBody())
                    .build();
            HttpResponse<Void> response = getEndpointCheckClient().send(headRequest, HttpResponse.BodyHandlers.discarding());
            int statusCode = response.statusCode();
            if (statusCode == 404) {
                HttpRequest optionsRequest = HttpRequest.newBuilder()
                        .uri(URI.create(endpoint))
                        .timeout(ENDPOINT_CHECK_TIMEOUT)
                        .method("OPTIONS", HttpRequest.BodyPublishers.noBody())
                        .build();
                HttpResponse<Void> optionsResponse = getEndpointCheckClient().send(optionsRequest, HttpResponse.BodyHandlers.discarding());
                if (optionsResponse.statusCode() == 404) {
                    return EndpointValidationOutcome.NOT_READY_OR_UNREACHABLE;
                }
                return EndpointValidationOutcome.REACHABLE;
            }
            if (isReachabilityAccepted(statusCode)) {
                return EndpointValidationOutcome.REACHABLE;
            }
            return EndpointValidationOutcome.NOT_READY_OR_UNREACHABLE;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.debug("Endpoint reachability check interrupted for '{}': {}", endpoint, e.getMessage());
            return EndpointValidationOutcome.NOT_READY_OR_UNREACHABLE;
        } catch (Exception e) {
            log.debug("Endpoint reachability check skipped for '{}': {}", endpoint, e.getMessage());
            return EndpointValidationOutcome.NOT_READY_OR_UNREACHABLE;
        }
    }

    private boolean isReachabilityAccepted(int statusCode) {
        return (statusCode >= 200 && statusCode < 400)
                || statusCode == 401
                || statusCode == 403
                || statusCode == 405;
    }

    private void scheduleEndpointValidation(DiscoverableServiceInfoImpl serviceInfo) {
        synchronized (lifecycleMonitor) {
            scheduleEndpointValidationLocked(serviceInfo, ENDPOINT_CHECK_INITIAL_DELAY_SECONDS, ENDPOINT_CHECK_MAX_ATTEMPTS);
        }
    }

    private void scheduleEndpointValidationLocked(DiscoverableServiceInfoImpl serviceInfo, long delaySeconds, int remainingAttempts) {
        if (!active || scheduler == null || serviceInfo == null || remainingAttempts <= 0) {
            return;
        }
        cancelEndpointValidationTaskLocked();
        endpointValidationFuture = scheduler.schedule(
                () -> runEndpointValidation(serviceInfo, remainingAttempts),
                Math.max(0L, delaySeconds),
                TimeUnit.SECONDS);
    }

    private void runEndpointValidation(DiscoverableServiceInfoImpl serviceInfo, int remainingAttempts) {
        EndpointValidationOutcome outcome = validateEndpointReachability(serviceInfo);
        synchronized (lifecycleMonitor) {
            endpointValidationFuture = null;
            if (!active || !registrationConfirmed) {
                return;
            }
            if (outcome == EndpointValidationOutcome.REACHABLE) {
                return;
            }
            if (remainingAttempts > 1) {
                scheduleEndpointValidationLocked(serviceInfo, ENDPOINT_CHECK_RETRY_DELAY_SECONDS, remainingAttempts - 1);
                return;
            }
        }
        String endpoint = resolveEndpoint(serviceInfo);
        if (!endpoint.isBlank()) {
            log.warn("Registered endpoint '{}' for service '{}' is not reachable after {} attempt(s)",
                    endpoint, serviceInfo.getServiceId(), ENDPOINT_CHECK_MAX_ATTEMPTS);
        }
    }

    private HttpClient getEndpointCheckClient() {
        if (endpointCheckClient == null) {
            endpointCheckClient = HttpClient.newBuilder()
                    .connectTimeout(ENDPOINT_CHECK_TIMEOUT)
                    .build();
        }
        return endpointCheckClient;
    }

    private String resolveEndpoint(DiscoverableServiceInfoImpl serviceInfo) {
        String explicitEndpoint = DiscoveryAddressUtils.normalizeAdvertisedEndpoint(serviceInfo.getServiceEndpoint());
        if (!explicitEndpoint.isBlank()) {
            return explicitEndpoint;
        }
        String serviceHost = DiscoveryAddressUtils.normalizeHost(serviceInfo.getServiceHost());
        String serviceRoot = DiscoveryAddressUtils.normalizeRoot(serviceInfo.getServiceRoot());
        if (serviceHost.isBlank() || serviceInfo.getServicePort() == null || serviceInfo.getServicePort().isBlank() || serviceRoot.isBlank()) {
            return "";
        }
        return serviceInfo.getServiceProtocol() + "://" + serviceHost + ":" + serviceInfo.getServicePort() + serviceRoot;
    }

    protected String resolveLocalHostname(String serviceName) {
        return DiscoveryAddressUtils.resolveLocalHostname(log, "service '" + serviceName + "'");
    }

    private boolean startLivenessLocked(DiscoverableServiceInfoImpl serviceInfo) {
        if (!active || serviceInfo == null || livenessClient == null) {
            return false;
        }
        cancelLivenessSessionLocked();
        String endpoint = resolveEndpoint(serviceInfo);
        ServiceLivenessRegistration registration = new ServiceLivenessRegistration(
                serviceInfo.getServiceId(),
                serviceInfo.getServiceInstanceId(),
                serviceInfo.getServiceVersion(),
                serviceInfo.getServiceProtocol(),
                serviceInfo.getServiceRoot(),
                endpoint,
                serviceInfo.getServiceHost(),
                serviceInfo.getServicePort(),
                clusterNodeId,
                clusterLayer
        );
        try {
            livenessSession = livenessClient.start(registration, new LivenessListener());
            return livenessSession != null;
        } catch (Exception e) {
            log.warn("Unable to start liveness client for service '{}' instance '{}': {}",
                    serviceInfo.getServiceId(), serviceInfo.getServiceInstanceId(), e.getMessage());
            livenessSession = null;
            return false;
        }
    }

    private void cancelLivenessSessionLocked() {
        if (livenessSession != null) {
            livenessSession.stop();
            livenessSession = null;
        }
    }

    private boolean attemptRegistrationOnce() {
        ServiceDiscoveryRegistryClientInternal effectiveClient;
        DiscoverableServiceInfoImpl serviceInfo;
        String effectiveDiscoveryUrl;
        boolean livenessStarted = false;
        synchronized (lifecycleMonitor) {
            if (!active || client == null || lastRegisteredServiceInfo == null || discoveryUrl == null || discoveryUrl.isBlank()) {
                return false;
            }
            effectiveClient = client;
            serviceInfo = lastRegisteredServiceInfo;
            effectiveDiscoveryUrl = discoveryUrl;
        }

        log.info("Auto-registering service: {}", serviceInfo);
        effectiveClient.setup(effectiveDiscoveryUrl, serviceInfo.getServicePort());
        effectiveClient.registerService(serviceInfo);

        boolean confirmed = effectiveClient.isRegistered(serviceInfo.getServiceInstanceId());
        synchronized (lifecycleMonitor) {
            if (!active) {
                return confirmed;
            }
            registrationConfirmed = confirmed;
            if (confirmed) {
                cancelRetryTaskLocked();
                livenessStarted = startLivenessLocked(serviceInfo);
                registrationConfirmed = livenessStarted;
                if (!livenessStarted) {
                    scheduleRetryLocked(registrationRetryInitialDelaySeconds);
                }
            } else {
                cancelLivenessSessionLocked();
            }
        }

        if (confirmed && livenessStarted) {
            log.info("Service '{}' registration confirmed", serviceInfo.getServiceId());
            scheduleEndpointValidation(serviceInfo);
        } else if (confirmed) {
            log.warn("Service '{}' registration confirmed but liveness client could not start", serviceInfo.getServiceId());
        } else {
            log.warn("Service '{}' registration could not be confirmed", serviceInfo.getServiceId());
        }
        return confirmed && livenessStarted;
    }

    private void scheduleRetry(long delaySeconds) {
        synchronized (lifecycleMonitor) {
            scheduleRetryLocked(delaySeconds);
        }
    }

    private void scheduleRetryLocked(long delaySeconds) {
        if (!active || scheduler == null || lastRegisteredServiceInfo == null) {
            return;
        }
        long effectiveDelay = Math.max(0L, delaySeconds);
        cancelRetryTaskLocked();
        retryFuture = scheduler.schedule(() -> retryRegistration(effectiveDelay), effectiveDelay, TimeUnit.SECONDS);
    }

    private void retryRegistration(long previousDelaySeconds) {
        boolean confirmed = attemptRegistrationOnce();
        synchronized (lifecycleMonitor) {
            retryFuture = null;
            if (!confirmed && active) {
                long nextDelay = previousDelaySeconds <= 0
                        ? registrationRetryInitialDelaySeconds
                        : Math.min(previousDelaySeconds * 2, registrationRetryMaxDelaySeconds);
                scheduleRetryLocked(nextDelay);
            }
        }
    }

    private void ensureSchedulerLocked() {
        if (scheduler != null && !scheduler.isShutdown()) {
            return;
        }
        scheduler = Executors.newSingleThreadScheduledExecutor(new SchedulerThreadFactory(getClass().getSimpleName()));
    }

    private void cancelRetryTaskLocked() {
        if (retryFuture != null) {
            retryFuture.cancel(false);
            retryFuture = null;
        }
    }

    private void cancelEndpointValidationTaskLocked() {
        if (endpointValidationFuture != null) {
            endpointValidationFuture.cancel(false);
            endpointValidationFuture = null;
        }
    }

    private void shutdownSchedulerLocked() {
        if (scheduler != null) {
            scheduler.shutdownNow();
            scheduler = null;
        }
    }

    private <T> T findComponentQuietly(ComponentRegistry componentRegistry, Class<T> componentClass) {
        if (componentRegistry == null || componentClass == null) {
            return null;
        }
        try {
            return componentRegistry.findComponent(componentClass, null);
        } catch (Exception e) {
            log.debug("Component {} not yet available during service registration bootstrap: {}",
                    componentClass.getSimpleName(), e.getMessage());
            return null;
        }
    }

    private long normalizePositive(long value, long defaultValue) {
        return value > 0 ? value : defaultValue;
    }

    private long resolveRegistrationRetryInitialDelay(ServiceDiscoveryGlobalOptions globalOptions) {
        return globalOptions != null
                ? normalizePositive(globalOptions.getRegistrationRetryInitialDelaySeconds(), 30L)
                : 30L;
    }

    private long resolveRegistrationRetryMaxDelay(ServiceDiscoveryGlobalOptions globalOptions) {
        return globalOptions != null
                ? normalizePositive(globalOptions.getRegistrationRetryMaxDelaySeconds(), 300L)
                : 300L;
    }

    private final class LivenessListener implements ServiceLivenessListener {
        @Override
        public void onLivenessLost(ServiceLivenessRegistration registration, String reason) {
            synchronized (lifecycleMonitor) {
                if (!active || !registrationConfirmed || registeredInstanceId == null
                        || !registeredInstanceId.equals(registration.getInstanceId())) {
                    return;
                }
                registrationConfirmed = false;
                cancelLivenessSessionLocked();
            }
            log.warn("Liveness lost for service '{}' instance '{}': {}; scheduling re-registration",
                    registration.getServiceName(), registration.getInstanceId(), reason);
            scheduleRetry(registrationRetryInitialDelaySeconds);
        }
    }

    private static final class SchedulerThreadFactory implements ThreadFactory {
        private final String threadName;

        private SchedulerThreadFactory(String ownerName) {
            this.threadName = ownerName + "-discovery-scheduler";
        }

        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, threadName);
            thread.setDaemon(true);
            return thread;
        }
    }

    private enum EndpointValidationOutcome {
        REACHABLE,
        NOT_READY_OR_UNREACHABLE
    }
}
