package it.water.core.service.integration.discovery;

import it.water.core.api.bundle.ApplicationProperties;
import it.water.core.api.model.BaseEntity;
import it.water.core.api.registry.ComponentConfiguration;
import it.water.core.api.registry.ComponentRegistration;
import it.water.core.api.registry.ComponentRegistry;
import it.water.core.api.registry.filter.ComponentFilterBuilder;
import it.water.core.api.repository.BaseRepository;
import it.water.core.api.service.BaseApi;
import it.water.core.api.service.BaseEntitySystemApi;
import it.water.core.api.service.BaseSystemApi;
import it.water.core.api.service.integration.discovery.DiscoverableServiceInfo;
import it.water.core.api.service.integration.discovery.ServiceDiscoveryGlobalOptions;
import it.water.core.api.service.integration.discovery.ServiceLivenessClient;
import it.water.core.api.service.integration.discovery.ServiceLivenessListener;
import it.water.core.api.service.integration.discovery.ServiceLivenessRegistration;
import it.water.core.api.service.integration.discovery.ServiceLivenessSession;
import it.water.core.api.service.rest.FrameworkRestApi;
import it.water.core.api.service.rest.FrameworkRestController;
import it.water.core.api.service.rest.RestApi;
import it.water.core.interceptors.annotations.Inject;
import it.water.core.service.BaseServiceImpl;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import javax.ws.rs.Path;
import java.io.File;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

class RestApiServiceRegistrationLifecycleManagerImplTest {

    @Test
    void activatesAndDeactivatesRegistrationsFromBusinessRestApi() {
        RestApiServiceRegistrationLifecycleManagerImpl manager = new RestApiServiceRegistrationLifecycleManagerImpl();
        InMemoryComponentRegistry registry = createRegistry("http://127.0.0.1:8181/water");
        RecordingRegistryClient discoveryClient =
                (RecordingRegistryClient) registry.findComponent(ServiceDiscoveryRegistryClientInternal.class, null);
        RecordingLivenessClient livenessClient =
                (RecordingLivenessClient) registry.findComponent(ServiceLivenessClient.class, null);

        manager.activateRestApiRegistrations(registry, getClass().getClassLoader());

        Assertions.assertNotNull(discoveryClient.registeredInfo);
        Assertions.assertEquals("companyrestapi", discoveryClient.registeredInfo.getServiceId());
        Assertions.assertEquals("/water/companies", discoveryClient.registeredInfo.getServiceRoot());
        Assertions.assertEquals("8381", discoveryClient.registeredInfo.getServicePort());
        Assertions.assertNotNull(livenessClient.lastRegistration);
        Assertions.assertEquals("companyrestapi", livenessClient.lastRegistration.getServiceName());

        manager.deactivate();

        Assertions.assertEquals("companyrestapi", discoveryClient.unregisteredServiceName);
        Assertions.assertTrue(livenessClient.stopped);
    }

    @Test
    void derivesLowercaseServiceNameFromRestApiClassName() {
        RestApiServiceRegistrationLifecycleManagerImpl manager = new RestApiServiceRegistrationLifecycleManagerImpl();

        Assertions.assertEquals("assetcategoryrestapi", manager.deriveRestServiceName(AssetCategoryRestApi.class));
        Assertions.assertEquals("companyrestapi", manager.deriveRestServiceName(CompanyRestApi.class));
        Assertions.assertEquals("assetcategoryspringrestapi", manager.deriveRestServiceName(AssetCategorySpringRestApi.class));
    }

    @Test
    void skipsRegistrationWhenDiscoveryUrlIsMissing() {
        RestApiServiceRegistrationLifecycleManagerImpl manager = new RestApiServiceRegistrationLifecycleManagerImpl();
        InMemoryComponentRegistry registry = createRegistry("");
        RecordingRegistryClient discoveryClient =
                (RecordingRegistryClient) registry.findComponent(ServiceDiscoveryRegistryClientInternal.class, null);

        manager.activateRestApiRegistrations(registry, getClass().getClassLoader());

        Assertions.assertNull(discoveryClient.registeredInfo);
    }

    @Test
    void skipsTechnicalRoots() {
        RestApiServiceRegistrationLifecycleManagerImpl manager = new RestApiServiceRegistrationLifecycleManagerImpl();

        Assertions.assertFalse(manager.isBusinessRoot("/"));
        Assertions.assertFalse(manager.isBusinessRoot("/serviceregistration"));
        Assertions.assertFalse(manager.isBusinessRoot("/internal/serviceregistration"));
        Assertions.assertFalse(manager.isBusinessRoot("/gateway/routes"));
        Assertions.assertFalse(manager.isBusinessRoot("/proxy"));
        Assertions.assertFalse(manager.isBusinessRoot("/status"));
        Assertions.assertTrue(manager.isBusinessRoot("/assetcategories"));
    }

    private InMemoryComponentRegistry createRegistry(String discoveryUrl) {
        InMemoryComponentRegistry registry = new InMemoryComponentRegistry();
        RecordingRegistryClient discoveryClient = new RecordingRegistryClient();
        RecordingLivenessClient livenessClient = new RecordingLivenessClient();
        MapApplicationProperties applicationProperties = new MapApplicationProperties();
        applicationProperties.put(ServiceDiscoveryGlobalConstants.PROP_DISCOVERY_URL, discoveryUrl);
        applicationProperties.put("org.osgi.service.http.port", "8381");

        registry.register(ApplicationProperties.class, applicationProperties);
        registry.register(ServiceDiscoveryGlobalOptions.class,
                new FixedGlobalOptions(discoveryUrl, "127.0.0.1"));
        registry.register(ServiceDiscoveryRegistryClientInternal.class, discoveryClient);
        registry.register(ServiceLivenessClient.class, livenessClient);
        registry.register(CompanyApi.class, new CompanyServiceImpl());
        return registry;
    }

    private static final class RecordingRegistryClient implements ServiceDiscoveryRegistryClientInternal {
        private DiscoverableServiceInfoImpl registeredInfo;
        private String unregisteredServiceName;

        @Override
        public void registerService(DiscoverableServiceInfo registration) {
            this.registeredInfo = (DiscoverableServiceInfoImpl) registration;
        }

        @Override
        public void unregisterService(String serviceName, String instanceId) {
            this.unregisteredServiceName = serviceName;
            this.registeredInfo = null;
        }

        @Override
        public DiscoverableServiceInfo getServiceInfo(String id) {
            return registeredInfo;
        }

        @Override
        public void setup(String remoteUrl, String port) {
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

    private static final class RecordingLivenessClient implements ServiceLivenessClient {
        private ServiceLivenessRegistration lastRegistration;
        private boolean stopped;

        @Override
        public ServiceLivenessSession start(ServiceLivenessRegistration registration, ServiceLivenessListener listener) {
            this.lastRegistration = registration;
            this.stopped = false;
            return () -> stopped = true;
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

    private static final class MapApplicationProperties implements ApplicationProperties {
        private final Map<String, Object> properties = new HashMap<>();

        void put(String key, Object value) {
            properties.put(key, value);
        }

        @Override
        public void setup() {
        }

        @Override
        public Object getProperty(String key) {
            return properties.get(key);
        }

        @Override
        public boolean containsKey(String key) {
            return properties.containsKey(key);
        }

        @Override
        public void loadProperties(File file) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void loadProperties(Properties props) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void unloadProperties(File file) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void unloadProperties(Properties props) {
            throw new UnsupportedOperationException();
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
}

@FrameworkRestApi
@Path("/companies")
interface CompanyRestApi extends RestApi {
}

interface CompanyApi extends BaseApi {
}

@FrameworkRestController(referredRestApi = CompanyRestApi.class)
class CompanyRestControllerImpl implements CompanyRestApi {
    @Inject
    private CompanyApi companyApi;
}

class CompanyServiceImpl extends BaseServiceImpl implements CompanyApi {
    @Override
    protected BaseSystemApi getSystemService() {
        return null;
    }
}

class AssetCategoryRestApi {
}

class AssetCategorySpringRestApi {
}

class AssetCategoryServiceImpl extends BaseServiceImpl {
    @Override
    protected BaseSystemApi getSystemService() {
        return null;
    }
}

@FrameworkRestApi
@Path("/serviceregistration")
interface ServiceRegistrationRestApi extends RestApi {
}
