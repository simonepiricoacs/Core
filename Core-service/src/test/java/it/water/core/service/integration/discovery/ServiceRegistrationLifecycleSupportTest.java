/*
 * Copyright 2024 Aristide Cittadino
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
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

import it.water.core.api.model.BaseEntity;
import it.water.core.api.registry.ComponentConfiguration;
import it.water.core.api.registry.ComponentRegistration;
import it.water.core.api.registry.ComponentRegistry;
import it.water.core.api.registry.filter.ComponentFilterBuilder;
import it.water.core.api.repository.BaseRepository;
import it.water.core.api.service.BaseEntitySystemApi;
import it.water.core.api.service.cluster.ClusterNodeOptions;
import it.water.core.api.service.integration.discovery.ServiceDiscoveryGlobalOptions;
import it.water.core.api.service.integration.discovery.DiscoverableServiceInfo;
import it.water.core.api.service.integration.discovery.ServiceLivenessClient;
import it.water.core.api.service.integration.discovery.ServiceLivenessListener;
import it.water.core.api.service.integration.discovery.ServiceLivenessRegistration;
import it.water.core.api.service.integration.discovery.ServiceLivenessSession;
import it.water.core.api.service.integration.discovery.ServiceRegistrationOptions;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

class ServiceRegistrationLifecycleSupportTest {

    @Test
    void registersWithModuleScopedOptionsEvenWhenGlobalOptionsAreMissing() {
        TestServiceRegistrationLifecycleSupport support = new TestServiceRegistrationLifecycleSupport();
        RecordingRegistryClient client = new RecordingRegistryClient();
        RecordingLivenessClient livenessClient = new RecordingLivenessClient();
        FixedRegistrationOptions options = new FixedRegistrationOptions(
                "http://127.0.0.1:8181/water",
                "catalog-service",
                "1.0.0",
                "",
                "http",
                "/water/catalog",
                "",
                "8381",
                "127.0.0.1"
        );

        support.register(client, options, livenessClient);

        Assertions.assertEquals("http://127.0.0.1:8181/water", client.setupRemoteUrl);
        Assertions.assertEquals("8381", client.setupPort);
        Assertions.assertNotNull(client.registeredInfo);
        Assertions.assertNotNull(livenessClient.lastRegistration);
        Assertions.assertEquals("catalog-service", client.registeredInfo.getServiceId());
        Assertions.assertEquals("8381", client.registeredInfo.getServicePort());
        Assertions.assertEquals("/water/catalog", client.registeredInfo.getServiceRoot());
        Assertions.assertEquals("127.0.0.1", client.registeredInfo.getServiceHost());
        Assertions.assertEquals("catalog-service", support.currentRegisteredServiceName());
        Assertions.assertNotNull(support.currentRegisteredInstanceId());
        Assertions.assertTrue(support.currentRegisteredInstanceId().startsWith("catalog-service-"));

        String registeredInstanceId = support.currentRegisteredInstanceId();
        support.deregister(client);

        Assertions.assertEquals("catalog-service", client.unregisteredServiceName);
        Assertions.assertEquals(registeredInstanceId, client.unregisteredInstanceId);
    }

    @Test
    void retriesBootstrapRegistrationUntilDependenciesBecomeAvailable() throws InterruptedException {
        TestServiceRegistrationLifecycleSupport support = new TestServiceRegistrationLifecycleSupport();
        RecordingRegistryClient client = new RecordingRegistryClient();
        RecordingLivenessClient livenessClient = new RecordingLivenessClient();
        FixedRegistrationOptions options = new FixedRegistrationOptions(
                "http://127.0.0.1:8181/water",
                "catalog-service",
                "1.0.0",
                "",
                "http",
                "/water/catalog",
                "",
                "8381",
                "127.0.0.1"
        );
        FixedGlobalOptions globalOptions = new FixedGlobalOptions("http://127.0.0.1:8181/water", "127.0.0.1");
        InMemoryComponentRegistry registry = new InMemoryComponentRegistry();
        support.register(registry, options);
        Assertions.assertFalse(client.awaitRegistration(250L, TimeUnit.MILLISECONDS));
        Assertions.assertNull(client.registeredInfo);

        registry.register(ServiceDiscoveryGlobalOptions.class, globalOptions);
        registry.register(ServiceDiscoveryRegistryClientInternal.class, client);
        registry.register(ServiceLivenessClient.class, livenessClient);

        Assertions.assertTrue(client.awaitRegistration(2500L, TimeUnit.MILLISECONDS));
        Assertions.assertTrue(livenessClient.awaitStart(2500L, TimeUnit.MILLISECONDS));

        Assertions.assertNotNull(client.registeredInfo);
        Assertions.assertNotNull(livenessClient.lastRegistration);
        Assertions.assertEquals("catalog-service", client.registeredInfo.getServiceId());
        Assertions.assertEquals("127.0.0.1", client.registeredInfo.getServiceHost());
        support.deregister(null);
    }

    @Test
    void validateEndpointReachabilityTreatsHttp500AsNotReady() throws Exception {
        TestServiceRegistrationLifecycleSupport support = new TestServiceRegistrationLifecycleSupport();
        setEndpointCheckClient(support, mockHttpClientReturning(500));

        Enum<?> outcome = invokeEndpointValidation(support,
                new DiscoverableServiceInfoImpl("http", "9081", "catalog-service", "catalog-1",
                        "/water/catalog", "1.0.0", "127.0.0.1", null));

        Assertions.assertEquals("NOT_READY_OR_UNREACHABLE", outcome.name());
    }

    @Test
    void validateEndpointReachabilityAcceptsUnauthorizedEndpoints() throws Exception {
        TestServiceRegistrationLifecycleSupport support = new TestServiceRegistrationLifecycleSupport();
        setEndpointCheckClient(support, mockHttpClientReturning(401));

        Enum<?> outcome = invokeEndpointValidation(support,
                new DiscoverableServiceInfoImpl("http", "9081", "catalog-service", "catalog-1",
                        "/water/catalog", "1.0.0", "127.0.0.1", null));

        Assertions.assertEquals("REACHABLE", outcome.name());
    }

    @Test
    void validateEndpointReachabilityShouldFallbackFromHead404ToOptions() throws Exception {
        TestServiceRegistrationLifecycleSupport support = new TestServiceRegistrationLifecycleSupport();
        setEndpointCheckClient(support, mockHttpClientReturning(404, 204));

        Enum<?> outcome = invokeEndpointValidation(support,
                new DiscoverableServiceInfoImpl("http", "9081", "catalog-service", "catalog-1",
                        "/water/catalog", "1.0.0", "127.0.0.1", null));

        Assertions.assertEquals("REACHABLE", outcome.name());
    }

    @Test
    void validateEndpointReachabilityShouldRejectWhenHeadAndOptionsReturn404() throws Exception {
        TestServiceRegistrationLifecycleSupport support = new TestServiceRegistrationLifecycleSupport();
        setEndpointCheckClient(support, mockHttpClientReturning(404, 404));

        Enum<?> outcome = invokeEndpointValidation(support,
                new DiscoverableServiceInfoImpl("http", "9081", "catalog-service", "catalog-1",
                        "/water/catalog", "1.0.0", "127.0.0.1", null));

        Assertions.assertEquals("NOT_READY_OR_UNREACHABLE", outcome.name());
    }

    @Test
    void validateEndpointReachabilityShouldAcceptBlankEndpoint() throws Exception {
        TestServiceRegistrationLifecycleSupport support = new TestServiceRegistrationLifecycleSupport();

        Enum<?> outcome = invokeEndpointValidation(support,
                new DiscoverableServiceInfoImpl("http", "", "catalog-service", "catalog-1",
                        "", "1.0.0", "", null));

        Assertions.assertEquals("REACHABLE", outcome.name());
    }

    @Test
    void registersUsingGlobalDiscoveryDefaultsClusterHostAndExplicitInstance() {
        TestServiceRegistrationLifecycleSupport support = new TestServiceRegistrationLifecycleSupport();
        RecordingRegistryClient client = new RecordingRegistryClient();
        RecordingLivenessClient livenessClient = new RecordingLivenessClient();
        FixedRegistrationOptions options = new FixedRegistrationOptions(
                "",
                "catalog-service",
                "",
                "catalog-fixed",
                "",
                "catalog",
                "",
                "8381",
                ""
        );
        FixedGlobalOptions globalOptions = new FixedGlobalOptions("http://discovery:8181/water", "");
        FixedClusterNodeOptions clusterNodeOptions = new FixedClusterNodeOptions(
                "node-1", "layer-1", "10.0.0.1", "node-host", false);

        support.register(client, options, globalOptions, clusterNodeOptions, livenessClient);

        Assertions.assertEquals("http://discovery:8181/water", client.setupRemoteUrl);
        Assertions.assertEquals("catalog-fixed", client.registeredInfo.getServiceInstanceId());
        Assertions.assertEquals("http", client.registeredInfo.getServiceProtocol());
        Assertions.assertEquals("1.0.0", client.registeredInfo.getServiceVersion());
        Assertions.assertEquals("/catalog", client.registeredInfo.getServiceRoot());
        Assertions.assertEquals("node-host", client.registeredInfo.getServiceHost());
        Assertions.assertEquals("node-1", livenessClient.lastRegistration.getNodeId());
        Assertions.assertEquals("layer-1", livenessClient.lastRegistration.getLayer());
        support.deregister(client);
    }

    @Test
    void registersUsingClusterIpWhenConfigured() {
        TestServiceRegistrationLifecycleSupport support = new TestServiceRegistrationLifecycleSupport();
        RecordingRegistryClient client = new RecordingRegistryClient();
        RecordingLivenessClient livenessClient = new RecordingLivenessClient();
        FixedRegistrationOptions options = new FixedRegistrationOptions(
                "http://discovery:8181/water", "catalog-service", "1.0.0", "catalog-fixed",
                "http", "/catalog", "", "8381", "");
        FixedClusterNodeOptions clusterNodeOptions = new FixedClusterNodeOptions(
                "node-1", "layer-1", "10.0.0.1", "node-host", true);

        support.register(client, options, null, clusterNodeOptions, livenessClient);

        Assertions.assertEquals("10.0.0.1", client.registeredInfo.getServiceHost());
        support.deregister(client);
    }

    @Test
    void advertisedEndpointShouldBypassHostResolution() {
        TestServiceRegistrationLifecycleSupport support = new TestServiceRegistrationLifecycleSupport();
        RecordingRegistryClient client = new RecordingRegistryClient();
        RecordingLivenessClient livenessClient = new RecordingLivenessClient();
        FixedRegistrationOptions options = new FixedRegistrationOptions(
                "http://discovery:8181/water", "catalog-service", "1.0.0", "catalog-fixed",
                "http", "", " https://public.example.com/catalog/ ", "", "ignored-host");

        support.register(client, options, null, null, livenessClient);

        Assertions.assertEquals("", client.registeredInfo.getServiceHost());
        Assertions.assertEquals("https://public.example.com/catalog", client.registeredInfo.getServiceEndpoint());
        Assertions.assertEquals("443", client.registeredInfo.getServicePort());
        support.deregister(client);
    }

    @Test
    void registrationShouldSkipInvalidConfigurationWithoutCallingClient() {
        RecordingRegistryClient client = new RecordingRegistryClient();
        RecordingLivenessClient livenessClient = new RecordingLivenessClient();

        new TestServiceRegistrationLifecycleSupport().register(null, validOptions(), livenessClient);
        new TestServiceRegistrationLifecycleSupport().register(client, null, livenessClient);
        new TestServiceRegistrationLifecycleSupport().register(client,
                options("", "catalog-service", "/catalog", "8381", "127.0.0.1", ""), livenessClient);
        new TestServiceRegistrationLifecycleSupport().register(client,
                options("http://discovery:8181/water", "", "/catalog", "8381", "127.0.0.1", ""), livenessClient);
        new TestServiceRegistrationLifecycleSupport().register(client,
                options("http://discovery:8181/water", "catalog-service", "/catalog", "", "127.0.0.1", ""), livenessClient);
        new TestServiceRegistrationLifecycleSupport().register(client,
                options("http://discovery:8181/water", "catalog-service", "", "8381", "127.0.0.1", ""), livenessClient);
        TestServiceRegistrationLifecycleSupport noLocalHostSupport = new TestServiceRegistrationLifecycleSupport("");
        noLocalHostSupport.register(client,
                options("http://discovery:8181/water", "catalog-service", "/catalog", "8381", "", ""), livenessClient);

        Assertions.assertNull(client.registeredInfo);
        Assertions.assertNull(livenessClient.lastRegistration);
    }

    @Test
    void deregisterShouldHandleMissingOrFailingClients() {
        TestServiceRegistrationLifecycleSupport support = new TestServiceRegistrationLifecycleSupport();
        support.deregister(null);

        RecordingRegistryClient client = new RecordingRegistryClient();
        support.register(client, validOptions(), new RecordingLivenessClient());
        support.deregister(null);
        Assertions.assertNull(support.currentRegisteredServiceName());
        Assertions.assertNull(support.currentRegisteredInstanceId());

        RecordingRegistryClient failingClient = new RecordingRegistryClient();
        failingClient.throwOnUnregister = true;
        support.register(failingClient, validOptions(), new RecordingLivenessClient());
        support.deregister(failingClient);
        Assertions.assertNull(support.currentRegisteredServiceName());
        Assertions.assertNull(support.currentRegisteredInstanceId());
    }

    @Test
    void failedLivenessStartShouldLeaveRegistrationUnconfirmedAndScheduleRetry() {
        TestServiceRegistrationLifecycleSupport support = new TestServiceRegistrationLifecycleSupport();
        RecordingRegistryClient client = new RecordingRegistryClient();
        RecordingLivenessClient livenessClient = new RecordingLivenessClient();
        livenessClient.throwOnStart = true;

        support.register(client, validOptions(), livenessClient);

        Assertions.assertNotNull(client.registeredInfo);
        Assertions.assertEquals("catalog-service", support.currentRegisteredServiceName());
        support.deregister(client);
    }

    @Test
    void livenessLostShouldScheduleRetryOnlyForCurrentConfirmedRegistration() {
        TestServiceRegistrationLifecycleSupport support = new TestServiceRegistrationLifecycleSupport();
        RecordingRegistryClient client = new RecordingRegistryClient();
        RecordingLivenessClient livenessClient = new RecordingLivenessClient();
        support.register(client, validOptions(), livenessClient);
        Assertions.assertNotNull(livenessClient.lastListener);

        livenessClient.lastListener.onLivenessLost(
                new ServiceLivenessRegistration("catalog-service", "other", "1.0.0",
                        "http", "/catalog", "http://127.0.0.1:8381/catalog",
                        "127.0.0.1", "8381", "", ""),
                "ignored");
        Assertions.assertFalse(livenessClient.stopped);

        livenessClient.lastListener.onLivenessLost(livenessClient.lastRegistration, "lost");
        Assertions.assertTrue(livenessClient.stopped);
        support.deregister(client);
    }

    @Test
    void validateEndpointReachabilityRestoresInterruptFlagWhenHttpClientIsInterrupted() throws Exception {
        TestServiceRegistrationLifecycleSupport support = new TestServiceRegistrationLifecycleSupport();
        HttpClient httpClient = Mockito.mock(HttpClient.class);
        Mockito.when(httpClient.send(Mockito.any(), Mockito.any(HttpResponse.BodyHandler.class)))
                .thenThrow(new InterruptedException("interrupted"));
        setEndpointCheckClient(support, httpClient);

        try {
            Enum<?> outcome = invokeEndpointValidation(support,
                    new DiscoverableServiceInfoImpl("http", "9081", "catalog-service", "catalog-1",
                            "/water/catalog", "1.0.0", "127.0.0.1", null));

            Assertions.assertEquals("NOT_READY_OR_UNREACHABLE", outcome.name());
            Assertions.assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
    }

    private static void setEndpointCheckClient(ServiceRegistrationLifecycleSupport support, HttpClient httpClient) throws Exception {
        Field field = ServiceRegistrationLifecycleSupport.class.getDeclaredField("endpointCheckClient");
        field.setAccessible(true);
        field.set(support, httpClient);
    }

    private static Enum<?> invokeEndpointValidation(ServiceRegistrationLifecycleSupport support,
                                                    DiscoverableServiceInfoImpl serviceInfo) throws Exception {
        Method method = ServiceRegistrationLifecycleSupport.class
                .getDeclaredMethod("validateEndpointReachability", DiscoverableServiceInfoImpl.class);
        method.setAccessible(true);
        try {
            return (Enum<?>) method.invoke(support, serviceInfo);
        } catch (InvocationTargetException e) {
            if (e.getCause() instanceof Exception cause) {
                throw cause;
            }
            throw e;
        }
    }

    @SuppressWarnings("unchecked")
    private static HttpClient mockHttpClientReturning(int statusCode) throws Exception {
        return mockHttpClientReturning(statusCode, statusCode);
    }

    @SuppressWarnings("unchecked")
    private static HttpClient mockHttpClientReturning(int firstStatusCode, int secondStatusCode) throws Exception {
        HttpClient httpClient = Mockito.mock(HttpClient.class);
        HttpResponse<Void> firstResponse = Mockito.mock(HttpResponse.class);
        HttpResponse<Void> secondResponse = Mockito.mock(HttpResponse.class);
        Mockito.when(firstResponse.statusCode()).thenReturn(firstStatusCode);
        Mockito.when(secondResponse.statusCode()).thenReturn(secondStatusCode);
        Mockito.when(httpClient.send(Mockito.any(), Mockito.any(HttpResponse.BodyHandler.class)))
                .thenReturn(firstResponse, secondResponse);
        return httpClient;
    }

    private static FixedRegistrationOptions validOptions() {
        return options("http://127.0.0.1:8181/water", "catalog-service", "/catalog", "8381", "127.0.0.1", "");
    }

    private static FixedRegistrationOptions options(String discoveryUrl,
                                                    String serviceName,
                                                    String root,
                                                    String servicePort,
                                                    String serviceHost,
                                                    String advertisedEndpoint) {
        return new FixedRegistrationOptions(
                discoveryUrl,
                serviceName,
                "1.0.0",
                "catalog-fixed",
                "http",
                root,
                advertisedEndpoint,
                servicePort,
                serviceHost
        );
    }

    private static final class TestServiceRegistrationLifecycleSupport extends ServiceRegistrationLifecycleSupport {
        private final String localHostname;

        private TestServiceRegistrationLifecycleSupport() {
            this("local-host");
        }

        private TestServiceRegistrationLifecycleSupport(String localHostname) {
            this.localHostname = localHostname;
        }

        void register(ServiceDiscoveryRegistryClientInternal client,
                      ServiceRegistrationOptions options,
                      ServiceLivenessClient livenessClient) {
            doRegister(client, options, null, null, livenessClient);
        }

        void register(ServiceDiscoveryRegistryClientInternal client,
                      ServiceRegistrationOptions options,
                      ServiceDiscoveryGlobalOptions globalOptions,
                      ClusterNodeOptions clusterNodeOptions,
                      ServiceLivenessClient livenessClient) {
            doRegister(client, options, globalOptions, clusterNodeOptions, livenessClient);
        }

        void register(ComponentRegistry componentRegistry, ServiceRegistrationOptions options) {
            bootstrapRegister(componentRegistry, options);
        }

        void deregister(ServiceDiscoveryRegistryClientInternal client) {
            doDeregister(client);
        }

        String currentRegisteredServiceName() {
            return registeredServiceName;
        }

        String currentRegisteredInstanceId() {
            return registeredInstanceId;
        }

        @Override
        protected String resolveLocalHostname(String serviceName) {
            return localHostname;
        }
    }

    private static final class RecordingRegistryClient implements ServiceDiscoveryRegistryClientInternal {
        private String setupRemoteUrl;
        private String setupPort;
        private DiscoverableServiceInfoImpl registeredInfo;
        private String unregisteredServiceName;
        private String unregisteredInstanceId;
        private boolean throwOnUnregister;
        private final CountDownLatch registrationLatch = new CountDownLatch(1);

        @Override
        public void registerService(DiscoverableServiceInfo registration) {
            this.registeredInfo = (DiscoverableServiceInfoImpl) registration;
            registrationLatch.countDown();
        }

        private boolean awaitRegistration(long timeout, TimeUnit timeUnit) throws InterruptedException {
            return registrationLatch.await(timeout, timeUnit);
        }

        @Override
        public void unregisterService(String serviceName, String instanceId) {
            if (throwOnUnregister) {
                throw new IllegalStateException("unregister failed");
            }
            this.unregisteredServiceName = serviceName;
            this.unregisteredInstanceId = instanceId;
            if (registeredInfo != null && instanceId.equals(registeredInfo.getServiceInstanceId())) {
                registeredInfo = null;
            }
        }

        @Override
        public DiscoverableServiceInfo getServiceInfo(String id) {
            return registeredInfo;
        }

        @Override
        public void setup(String remoteUrl, String port) {
            this.setupRemoteUrl = remoteUrl;
            this.setupPort = port;
        }

        @Override
        public boolean isRegistered(String instanceId) {
            return registeredInfo != null && instanceId.equals(registeredInfo.getServiceInstanceId());
        }

        @Override
        public boolean heartbeat(String serviceName, String instanceId) {
            return registeredInfo != null
                    && serviceName.equals(registeredInfo.getServiceId())
                    && instanceId.equals(registeredInfo.getServiceInstanceId());
        }
    }

    private static final class FixedGlobalOptions implements ServiceDiscoveryGlobalOptions {
        private final String discoveryUrl;
        private final String defaultHost;

        private FixedGlobalOptions(String discoveryUrl, String defaultHost) {
            this.discoveryUrl = discoveryUrl;
            this.defaultHost = defaultHost;
        }

        @Override
        public String getDiscoveryUrl() {
            return discoveryUrl;
        }

        @Override
        public String getDefaultHost() {
            return defaultHost;
        }

        @Override
        public long getHeartbeatIntervalSeconds() {
            return 25L;
        }

        @Override
        public long getRegistrationRetryInitialDelaySeconds() {
            return 30L;
        }

        @Override
        public long getRegistrationRetryMaxDelaySeconds() {
            return 300L;
        }

        @Override
        public long getHttpTimeoutSeconds() {
            return 10L;
        }

        @Override
        public int getRegistrationMaxAttempts() {
            return 3;
        }

        @Override
        public long[] getRegistrationRetryBackoffMs() {
            return new long[]{2000L, 4000L, 8000L};
        }
    }

    private static final class RecordingLivenessClient implements ServiceLivenessClient {
        private ServiceLivenessRegistration lastRegistration;
        private ServiceLivenessListener lastListener;
        private boolean stopped;
        private boolean throwOnStart;
        private final CountDownLatch startLatch = new CountDownLatch(1);

        @Override
        public ServiceLivenessSession start(ServiceLivenessRegistration registration, ServiceLivenessListener listener) {
            if (throwOnStart) {
                throw new IllegalStateException("liveness failed");
            }
            this.lastRegistration = registration;
            this.lastListener = listener;
            this.stopped = false;
            startLatch.countDown();
            return () -> stopped = true;
        }

        private boolean awaitStart(long timeout, TimeUnit timeUnit) throws InterruptedException {
            return startLatch.await(timeout, timeUnit);
        }
    }

    private static final class FixedClusterNodeOptions implements ClusterNodeOptions {
        private final String nodeId;
        private final String layer;
        private final String ip;
        private final String host;
        private final boolean useIp;

        private FixedClusterNodeOptions(String nodeId, String layer, String ip, String host, boolean useIp) {
            this.nodeId = nodeId;
            this.layer = layer;
            this.ip = ip;
            this.host = host;
            this.useIp = useIp;
        }

        @Override
        public boolean clusterModeEnabled() {
            return true;
        }

        @Override
        public String getNodeId() {
            return nodeId;
        }

        @Override
        public String getLayer() {
            return layer;
        }

        @Override
        public String getIp() {
            return ip;
        }

        @Override
        public String getHost() {
            return host;
        }

        @Override
        public boolean useIpInClusterRegistration() {
            return useIp;
        }
    }

    private static final class InMemoryComponentRegistry implements ComponentRegistry {
        private final Map<Class<?>, Object> components = new HashMap<>();

        <T> void register(Class<T> componentClass, T component) {
            components.put(componentClass, component);
        }

        @Override
        public <T> List<T> findComponents(Class<T> componentClass, it.water.core.api.registry.filter.ComponentFilter filter) {
            T component = findComponent(componentClass, filter);
            return component == null ? Collections.emptyList() : Collections.singletonList(component);
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> T findComponent(Class<T> componentClass, it.water.core.api.registry.filter.ComponentFilter filter) {
            return (T) components.get(componentClass);
        }

        @Override
        public <T, K> ComponentRegistration<T, K> registerComponent(Class<? extends T> componentClass, T component, ComponentConfiguration configuration) {
            throw new UnsupportedOperationException();
        }

        @Override
        public <T> boolean unregisterComponent(ComponentRegistration<T, ?> registration) {
            throw new UnsupportedOperationException();
        }

        @Override
        public <T> boolean unregisterComponent(Class<T> componentClass, T component) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ComponentFilterBuilder getComponentFilterBuilder() {
            throw new UnsupportedOperationException();
        }

        @Override
        public <T extends BaseEntitySystemApi> T findEntitySystemApi(String entityClassName) {
            throw new UnsupportedOperationException();
        }

        @Override
        public <T extends BaseRepository> T findEntityRepository(String entityClassName) {
            throw new UnsupportedOperationException();
        }

        @Override
        public <T extends BaseEntity> BaseRepository<T> findEntityExtensionRepository(Class<T> type) {
            throw new UnsupportedOperationException();
        }
    }

    private static final class FixedRegistrationOptions implements ServiceRegistrationOptions {
        private final String discoveryUrl;
        private final String serviceName;
        private final String serviceVersion;
        private final String instanceId;
        private final String protocol;
        private final String root;
        private final String advertisedEndpoint;
        private final String servicePort;
        private final String serviceHost;

        private FixedRegistrationOptions(String discoveryUrl,
                                         String serviceName,
                                         String serviceVersion,
                                         String instanceId,
                                         String protocol,
                                         String root,
                                         String advertisedEndpoint,
                                         String servicePort,
                                         String serviceHost) {
            this.discoveryUrl = discoveryUrl;
            this.serviceName = serviceName;
            this.serviceVersion = serviceVersion;
            this.instanceId = instanceId;
            this.protocol = protocol;
            this.root = root;
            this.advertisedEndpoint = advertisedEndpoint;
            this.servicePort = servicePort;
            this.serviceHost = serviceHost;
        }

        @Override
        public String getDiscoveryUrl() {
            return discoveryUrl;
        }

        @Override
        public String getServiceName() {
            return serviceName;
        }

        @Override
        public String getServiceVersion() {
            return serviceVersion;
        }

        @Override
        public String getInstanceId() {
            return instanceId;
        }

        @Override
        public String getProtocol() {
            return protocol;
        }

        @Override
        public String getRoot() {
            return root;
        }

        @Override
        public String getAdvertisedEndpoint() {
            return advertisedEndpoint;
        }

        @Override
        public String getServicePort() {
            return servicePort;
        }

        @Override
        public String getServiceHost() {
            return serviceHost;
        }
    }
}
