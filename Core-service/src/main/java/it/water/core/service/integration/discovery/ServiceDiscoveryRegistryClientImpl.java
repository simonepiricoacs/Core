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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import it.water.core.api.service.integration.discovery.DiscoverableServiceInfo;
import it.water.core.api.service.integration.discovery.ServiceDiscoveryGlobalOptions;
import it.water.core.api.service.integration.discovery.ServiceDiscoveryRegistryClient;
import it.water.core.interceptors.annotations.FrameworkComponent;
import it.water.core.interceptors.annotations.Inject;
import lombok.Getter;
import lombok.Setter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * HTTP client implementation of ServiceDiscoveryRegistryClient.
 */
@FrameworkComponent(priority = 1, services = {
        ServiceDiscoveryRegistryClient.class,
        ServiceDiscoveryRegistryClientInternal.class
})
public class ServiceDiscoveryRegistryClientImpl implements ServiceDiscoveryRegistryClientInternal {
    private static final Logger log = LoggerFactory.getLogger(ServiceDiscoveryRegistryClientImpl.class);
    // Safety defaults used only when ServiceDiscoveryGlobalOptions is not yet
    // available (e.g. very early bundle activation). Normal runtime values are
    // read from the injected options so they stay configurable.
    private static final int DEFAULT_MAX_ATTEMPTS = 3;
    private static final long[] DEFAULT_RETRY_BACKOFF_MS = {2000L, 4000L, 8000L};
    private static final long DEFAULT_HTTP_TIMEOUT_SECONDS = 10L;
    private static final String INTERNAL_API_PATH = "/internal/serviceregistration";
    private static final String REGISTER_API_PATH = INTERNAL_API_PATH + "/register";
    private static final String PUBLIC_API_PATH = "/serviceregistration";
    private static final String APPLICATION_JSON = "application/json";
    private static final String WATER_ROOT = "/water";

    @Getter
    @Setter
    private String remoteUrl;

    @Getter
    @Setter
    private String port;

    @Inject
    @Setter
    private ServiceDiscoveryGlobalOptions globalOptions;

    private HttpClient httpClient;
    private final ObjectMapper objectMapper = new ObjectMapper();
    // Tracks instance ids for which a successful registration has been confirmed.
    // Used only by isRegistered() so the registration lifecycle support can check the
    // outcome of a register call; deregistration is now driven by (serviceName, instanceId)
    // supplied by the caller, so no lookup map is required anymore.
    private final Set<String> registeredInstances = ConcurrentHashMap.newKeySet();

    @Override
    public void registerService(DiscoverableServiceInfo registration) {
        String endpoint = resolveEndpoint(REGISTER_API_PATH);
        String jsonBody = buildRegistrationJson(registration);
        registeredInstances.remove(registration.getServiceInstanceId());

        int maxAttempts = resolveMaxAttempts();
        long[] backoffMs = resolveBackoffMs();
        Duration httpTimeout = resolveHttpTimeout();

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                        .uri(URI.create(endpoint))
                        .header("Content-Type", APPLICATION_JSON)
                        .header("Accept", APPLICATION_JSON)
                        .timeout(httpTimeout)
                        .POST(HttpRequest.BodyPublishers.ofString(jsonBody));

                HttpResponse<String> response = getHttpClient().send(requestBuilder.build(), HttpResponse.BodyHandlers.ofString());

                if (response.statusCode() == 200) {
                    registeredInstances.add(registration.getServiceInstanceId());
                    log.info("Service '{}' instance '{}' registered successfully",
                            registration.getServiceId(), registration.getServiceInstanceId());
                    return;
                }
                if (log.isWarnEnabled()) {
                    log.warn("Registration attempt {}/{} failed: HTTP {} - {}",
                            attempt, maxAttempts, response.statusCode(), response.body());
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception e) {
                log.warn("Registration attempt {}/{} failed: {}", attempt, maxAttempts, e.getMessage());
            }

            if (attempt < maxAttempts) {
                long delay = pickBackoff(backoffMs, attempt - 1);
                if (delay > 0L) {
                    try {
                        Thread.sleep(delay);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            }
        }
        log.error("Failed to register service '{}' after {} attempts", registration.getServiceId(), maxAttempts);
    }

    @Override
    public void unregisterService(String serviceName, String instanceId) {
        String endpoint = resolveEndpoint(INTERNAL_API_PATH) + "/" + encodePathSegment(serviceName) + "/" + encodePathSegment(instanceId);
        try {
            HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                    .uri(URI.create(endpoint))
                    .timeout(resolveHttpTimeout())
                    .DELETE();

            HttpResponse<String> response = getHttpClient().send(requestBuilder.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 204 || response.statusCode() == 200) {
                registeredInstances.remove(instanceId);
                log.info("Service '{}' instance '{}' unregistered successfully", serviceName, instanceId);
            } else if (log.isWarnEnabled()) {
                log.warn("Unregister returned HTTP {}: {}", response.statusCode(), response.body());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            log.warn("Failed to unregister service '{}' instance '{}': {}", serviceName, instanceId, e.getMessage());
        }
    }

    @Override
    public boolean heartbeat(String serviceName, String instanceId) {
        String endpoint = resolveEndpoint(INTERNAL_API_PATH) + "/heartbeat/" + encodePathSegment(serviceName) + "/" + encodePathSegment(instanceId);
        try {
            HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                    .uri(URI.create(endpoint))
                    .timeout(resolveHttpTimeout())
                    .PUT(HttpRequest.BodyPublishers.noBody());

            HttpResponse<String> response = getHttpClient().send(requestBuilder.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 204 || response.statusCode() == 200) {
                return true;
            }
            if (response.statusCode() == 404) {
                registeredInstances.remove(instanceId);
                return false;
            }
            if (log.isWarnEnabled()) {
                log.warn("Heartbeat returned HTTP {} for service '{}' instance '{}': {}",
                        response.statusCode(), serviceName, instanceId, response.body());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (Exception e) {
            log.warn("Heartbeat failed for service '{}' instance '{}': {}", serviceName, instanceId, e.getMessage());
        }
        return false;
    }

    @Override
    public DiscoverableServiceInfo getServiceInfo(String id) {
        String endpoint = resolveEndpoint(PUBLIC_API_PATH) + "/" + encodePathSegment(id);
        try {
            HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                    .uri(URI.create(endpoint))
                    .header("Accept", APPLICATION_JSON)
                    .timeout(resolveHttpTimeout())
                    .GET();

            HttpResponse<String> response = getHttpClient().send(requestBuilder.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                return parseServiceInfo(response.body());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (Exception e) {
            log.warn("Failed to get service info for id '{}': {}", id, e.getMessage());
        }
        return null;
    }

    @Override
    public void setup(String remoteUrl, String port) {
        this.remoteUrl = normalizeRemoteUrl(remoteUrl);
        this.port = port != null ? port.trim() : null;
    }

    @Override
    public boolean isRegistered(String instanceId) {
        return registeredInstances.contains(instanceId);
    }

    private HttpClient getHttpClient() {
        if (httpClient == null) {
            httpClient = HttpClient.newBuilder()
                    .connectTimeout(resolveHttpTimeout())
                    .build();
        }
        return httpClient;
    }

    private Duration resolveHttpTimeout() {
        long seconds = globalOptions != null ? globalOptions.getHttpTimeoutSeconds() : DEFAULT_HTTP_TIMEOUT_SECONDS;
        return Duration.ofSeconds(seconds > 0 ? seconds : DEFAULT_HTTP_TIMEOUT_SECONDS);
    }

    private int resolveMaxAttempts() {
        int attempts = globalOptions != null ? globalOptions.getRegistrationMaxAttempts() : DEFAULT_MAX_ATTEMPTS;
        return attempts > 0 ? attempts : DEFAULT_MAX_ATTEMPTS;
    }

    private long[] resolveBackoffMs() {
        long[] values = globalOptions != null ? globalOptions.getRegistrationRetryBackoffMs() : null;
        return (values != null && values.length > 0) ? values : DEFAULT_RETRY_BACKOFF_MS;
    }

    private long pickBackoff(long[] values, int index) {
        if (values.length == 0) {
            return 0L;
        }
        int idx = Math.min(index, values.length - 1);
        long v = values[idx];
        return v < 0L ? 0L : v;
    }

    private String resolveEndpoint(String path) {
        if (remoteUrl == null || remoteUrl.isBlank()) {
            throw new IllegalStateException("ServiceDiscovery remoteUrl not configured. Call setup() first.");
        }
        String normalized = remoteUrl;
        if (normalized.endsWith(path)) {
            return normalized;
        }
        if (normalized.endsWith(WATER_ROOT)) {
            return normalized + path;
        }
        return normalized + WATER_ROOT + path;
    }

    private String buildRegistrationJson(DiscoverableServiceInfo info) {
        if (!(info instanceof DiscoverableServiceInfoImpl serviceInfo)) {
            throw new IllegalArgumentException("ServiceDiscoveryRegistryClientImpl requires DiscoverableServiceInfoImpl");
        }
        String version = "1.0.0";
        if (serviceInfo.getServiceVersion() != null && !serviceInfo.getServiceVersion().isBlank()) {
            version = serviceInfo.getServiceVersion();
        }
        String endpoint = resolveRegistrationEndpoint(serviceInfo);
        if (endpoint.isBlank()) {
            throw new IllegalStateException("Cannot register service '" + info.getServiceId() + "': endpoint could not be resolved");
        }
        try {
            ObjectNode payload = objectMapper.createObjectNode();
            payload.put("serviceName", info.getServiceId());
            payload.put("serviceVersion", version);
            payload.put("instanceId", info.getServiceInstanceId());
            payload.put("endpoint", endpoint);
            payload.put("protocol", info.getServiceProtocol());
            payload.put("status", "UP");
            return objectMapper.writeValueAsString(payload);
        } catch (Exception e) {
            throw new IllegalStateException("Unable to serialize service registration payload", e);
        }
    }

    private String resolveRegistrationEndpoint(DiscoverableServiceInfoImpl serviceInfo) {
        String explicitEndpoint = DiscoveryAddressUtils.normalizeAdvertisedEndpoint(serviceInfo.getServiceEndpoint());
        if (!explicitEndpoint.isBlank()) {
            return explicitEndpoint;
        }
        String serviceHost = DiscoveryAddressUtils.normalizeHost(serviceInfo.getServiceHost());
        String servicePort = resolveServicePort(serviceInfo);
        String serviceRoot = DiscoveryAddressUtils.normalizeRoot(serviceInfo.getServiceRoot());
        if (serviceHost.isBlank() || servicePort.isBlank() || serviceRoot.isBlank()) {
            log.warn("Cannot build registration endpoint for service '{}' instance '{}': host='{}' port='{}' root='{}'",
                    serviceInfo.getServiceId(), serviceInfo.getServiceInstanceId(), serviceHost, servicePort, serviceRoot);
            return "";
        }
        return serviceInfo.getServiceProtocol() + "://" + serviceHost + ":" + servicePort + serviceRoot;
    }

    private String resolveServicePort(DiscoverableServiceInfo info) {
        if (info != null && info.getServicePort() != null && !info.getServicePort().isBlank()) {
            return info.getServicePort().trim();
        }
        return port != null && !port.isBlank() ? port.trim() : "";
    }

    private String normalizeRemoteUrl(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.endsWith("/") ? trimmed.substring(0, trimmed.length() - 1) : trimmed;
    }

    private DiscoverableServiceInfo parseServiceInfo(String responseBody) {
        try {
            JsonNode rootNode = objectMapper.readTree(responseBody);
            String endpoint = rootNode.path("endpoint").asText("");
            String protocol = rootNode.path("protocol").asText("");
            String serviceName = rootNode.path("serviceName").asText("");
            String serviceVersion = rootNode.path("serviceVersion").asText("");
            String instanceId = rootNode.path("instanceId").asText("");
            String serviceHost = DiscoveryAddressUtils.extractHostFromEndpoint(endpoint);
            String servicePort = DiscoveryAddressUtils.extractPortFromEndpoint(endpoint);
            String serviceRoot = DiscoveryAddressUtils.extractRootFromEndpoint(endpoint, WATER_ROOT);
            return new DiscoverableServiceInfoImpl(protocol, servicePort, serviceName, instanceId, serviceRoot,
                    serviceVersion, serviceHost, endpoint);
        } catch (Exception e) {
            log.warn("Failed to parse service info response: {}", e.getMessage());
            return null;
        }
    }

    private String encodePathSegment(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
