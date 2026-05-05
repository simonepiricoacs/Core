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

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import it.water.core.api.service.integration.discovery.DiscoverableServiceInfo;
import it.water.core.api.service.integration.discovery.ServiceDiscoveryGlobalOptions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

class ServiceDiscoveryRegistryClientImplTest {

    private HttpServer server;
    private int serverPort;
    private final AtomicReference<String> postBody = new AtomicReference<>();
    private final AtomicReference<String> deletePath = new AtomicReference<>();
    private final AtomicReference<String> heartbeatPath = new AtomicReference<>();

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/water/internal/serviceregistration", this::handleInternalDiscoveryRequest);
        server.createContext("/water/serviceregistration", this::handlePublicDiscoveryRequest);
        server.start();
        serverPort = server.getAddress().getPort();
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void registerGetAndUnregisterUseExpectedPayload() {
        ServiceDiscoveryRegistryClientImpl client = new ServiceDiscoveryRegistryClientImpl();
        client.setup("http://localhost:" + serverPort + "/water/", "9191");

        DiscoverableServiceInfoImpl info = new DiscoverableServiceInfoImpl(
                "http", "9191", "catalog-service", "catalog-01", "/catalog", "2.1.0",
                "public-host", null
        );

        client.registerService(info);

        Assertions.assertTrue(client.isRegistered("catalog-01"));
        Assertions.assertNotNull(postBody.get());
        Assertions.assertTrue(postBody.get().contains("\"serviceName\":\"catalog-service\""));
        Assertions.assertTrue(postBody.get().contains("\"serviceVersion\":\"2.1.0\""));
        Assertions.assertTrue(postBody.get().contains("\"instanceId\":\"catalog-01\""));
        Assertions.assertTrue(postBody.get().contains("http://public-host:9191/catalog"));

        DiscoverableServiceInfoImpl remoteInfo = (DiscoverableServiceInfoImpl) client.getServiceInfo("42");
        Assertions.assertNotNull(remoteInfo);
        Assertions.assertEquals("catalog-service", remoteInfo.getServiceId());
        Assertions.assertEquals("catalog-01", remoteInfo.getServiceInstanceId());
        Assertions.assertEquals("2.1.0", remoteInfo.getServiceVersion());
        Assertions.assertEquals("remote-host", remoteInfo.getServiceHost());
        Assertions.assertEquals("http://remote-host:8181/water", remoteInfo.getServiceEndpoint());
        Assertions.assertEquals("8181", remoteInfo.getServicePort());
        Assertions.assertEquals("/water", remoteInfo.getServiceRoot());

        client.unregisterService("catalog-service", "catalog-01");

        Assertions.assertEquals("/water/internal/serviceregistration/catalog-service/catalog-01", deletePath.get());
        Assertions.assertFalse(client.isRegistered("catalog-01"));
    }

    @Test
    void registerUsesExplicitAdvertisedEndpointWhenProvided() {
        ServiceDiscoveryRegistryClientImpl client = new ServiceDiscoveryRegistryClientImpl();
        client.setup("http://localhost:" + serverPort + "/water", "9191");

        DiscoverableServiceInfoImpl info = new DiscoverableServiceInfoImpl(
                "http", "9191", "catalog-service", "catalog-02", "/catalog", "2.1.0",
                null, "https://public.example.com/catalog"
        );

        client.registerService(info);

        Assertions.assertNotNull(postBody.get());
        Assertions.assertTrue(postBody.get().contains("\"endpoint\":\"https://public.example.com/catalog\""));
    }

    @Test
    void registerShouldLeaveInstanceUnregisteredWhenServerRejectsRequest() {
        ServiceDiscoveryRegistryClientImpl client = new ServiceDiscoveryRegistryClientImpl();
        client.setGlobalOptions(shortRetryOptions());
        client.setup("http://localhost:" + serverPort + "/water", "9191");

        DiscoverableServiceInfoImpl info = new DiscoverableServiceInfoImpl(
                "http", "9191", "reject-service", "reject-01", "/reject", "1.0.0",
                "public-host", null
        );

        client.registerService(info);

        Assertions.assertFalse(client.isRegistered("reject-01"));
    }

    @Test
    void registerShouldRejectUnsupportedServiceInfoImplementation() {
        ServiceDiscoveryRegistryClientImpl client = new ServiceDiscoveryRegistryClientImpl();
        client.setup("http://localhost:" + serverPort + "/water", "9191");

        DiscoverableServiceInfo info = new DiscoverableServiceInfo() {
            @Override
            public String getServiceProtocol() {
                return "http";
            }

            @Override
            public String getServicePort() {
                return "9191";
            }

            @Override
            public String getServiceId() {
                return "catalog-service";
            }

            @Override
            public String getServiceInstanceId() {
                return "catalog-unsupported";
            }

            @Override
            public String getServiceRoot() {
                return "/catalog";
            }
        };

        Assertions.assertThrows(IllegalArgumentException.class, () -> client.registerService(info));
    }

    @Test
    void unregisterShouldKeepRegistrationWhenServerReturnsError() {
        ServiceDiscoveryRegistryClientImpl client = new ServiceDiscoveryRegistryClientImpl();
        client.setup("http://localhost:" + serverPort + "/water", "9191");

        DiscoverableServiceInfoImpl info = new DiscoverableServiceInfoImpl(
                "http", "9191", "catalog-service", "error-01", "/catalog", "2.1.0",
                "public-host", null
        );
        client.registerService(info);

        client.unregisterService("error-service", "error-01");

        Assertions.assertTrue(client.isRegistered("error-01"));
    }

    @Test
    void heartbeatReturnsTrueOn204AndFalseOn404() {
        ServiceDiscoveryRegistryClientImpl client = new ServiceDiscoveryRegistryClientImpl();
        client.setup("http://localhost:" + serverPort + "/water", "9191");

        DiscoverableServiceInfoImpl info = new DiscoverableServiceInfoImpl(
                "http", "9191", "catalog-service", "catalog-01", "/catalog", "2.1.0",
                "public-host", null
        );
        client.registerService(info);

        Assertions.assertTrue(client.heartbeat("catalog-service", "catalog-01"));
        Assertions.assertEquals("/water/internal/serviceregistration/heartbeat/catalog-service/catalog-01", heartbeatPath.get());
        Assertions.assertFalse(client.heartbeat("missing-service", "missing-instance"));
    }

    @Test
    void heartbeatShouldReturnFalseWhenServerReturnsError() {
        ServiceDiscoveryRegistryClientImpl client = new ServiceDiscoveryRegistryClientImpl();
        client.setup("http://localhost:" + serverPort + "/water", "9191");

        Assertions.assertFalse(client.heartbeat("error-service", "error-instance"));
    }

    @Test
    void registerFailsWhenEndpointCannotBeResolved() {
        ServiceDiscoveryRegistryClientImpl client = new ServiceDiscoveryRegistryClientImpl();
        client.setup("http://localhost:" + serverPort + "/water", "9191");

        DiscoverableServiceInfoImpl info = new DiscoverableServiceInfoImpl(
                "http", "", "catalog-service", "catalog-03", "", "2.1.0",
                "", ""
        );

        Assertions.assertThrows(IllegalStateException.class, () -> client.registerService(info));
    }

    @Test
    void getServiceInfoShouldUseWaterRootWhenDiscoveryUrlHasNoRoot() {
        ServiceDiscoveryRegistryClientImpl client = new ServiceDiscoveryRegistryClientImpl();
        client.setup("http://localhost:" + serverPort, "9191");

        DiscoverableServiceInfoImpl remoteInfo = (DiscoverableServiceInfoImpl) client.getServiceInfo("42");

        Assertions.assertNotNull(remoteInfo);
        Assertions.assertEquals("catalog-service", remoteInfo.getServiceId());
    }

    @Test
    void getServiceInfoUsesStandardPortDefaults() {
        ServiceDiscoveryRegistryClientImpl client = new ServiceDiscoveryRegistryClientImpl();
        client.setup("http://localhost:" + serverPort + "/water", "9191");

        DiscoverableServiceInfoImpl httpInfo = (DiscoverableServiceInfoImpl) client.getServiceInfo("80");
        Assertions.assertNotNull(httpInfo);
        Assertions.assertEquals("remote-host", httpInfo.getServiceHost());
        Assertions.assertEquals("http://remote-host/water", httpInfo.getServiceEndpoint());
        Assertions.assertEquals("80", httpInfo.getServicePort());
        Assertions.assertEquals("/water", httpInfo.getServiceRoot());

        DiscoverableServiceInfoImpl httpsInfo = (DiscoverableServiceInfoImpl) client.getServiceInfo("443");
        Assertions.assertNotNull(httpsInfo);
        Assertions.assertEquals("secure-host", httpsInfo.getServiceHost());
        Assertions.assertEquals("https://secure-host/secure", httpsInfo.getServiceEndpoint());
        Assertions.assertEquals("443", httpsInfo.getServicePort());
        Assertions.assertEquals("/secure", httpsInfo.getServiceRoot());
    }

    private void handleInternalDiscoveryRequest(HttpExchange exchange) throws IOException {
        String method = exchange.getRequestMethod();
        String path = exchange.getRequestURI().getPath();
        if ("POST".equals(method) && path.endsWith("/register")) {
            postBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            if (postBody.get().contains("\"serviceName\":\"reject-service\"")) {
                writeResponse(exchange, 500, "rejected");
                return;
            }
            writeResponse(exchange, 200, "{\"id\":42}");
            return;
        }
        if ("DELETE".equals(method) && path.endsWith("/catalog-service/catalog-01")) {
            deletePath.set(path);
            writeResponse(exchange, 204, "");
            return;
        }
        if ("DELETE".equals(method) && path.endsWith("/missing-service/missing-instance")) {
            deletePath.set(path);
            writeResponse(exchange, 204, "");
            return;
        }
        if ("DELETE".equals(method) && path.endsWith("/error-service/error-01")) {
            deletePath.set(path);
            writeResponse(exchange, 500, "failed");
            return;
        }
        if ("PUT".equals(method) && path.endsWith("/heartbeat/catalog-service/catalog-01")) {
            heartbeatPath.set(path);
            writeResponse(exchange, 204, "");
            return;
        }
        if ("PUT".equals(method) && path.endsWith("/heartbeat/missing-service/missing-instance")) {
            heartbeatPath.set(path);
            writeResponse(exchange, 404, "");
            return;
        }
        if ("PUT".equals(method) && path.endsWith("/heartbeat/error-service/error-instance")) {
            heartbeatPath.set(path);
            writeResponse(exchange, 500, "failed");
            return;
        }
        writeResponse(exchange, 404, "");
    }

    private void handlePublicDiscoveryRequest(HttpExchange exchange) throws IOException {
        String method = exchange.getRequestMethod();
        String path = exchange.getRequestURI().getPath();
        if ("GET".equals(method) && path.endsWith("/42")) {
            writeResponse(exchange, 200, "{" +
                    "\"id\":42," +
                    "\"serviceName\":\"catalog-service\"," +
                    "\"serviceVersion\":\"2.1.0\"," +
                    "\"instanceId\":\"catalog-01\"," +
                    "\"endpoint\":\"http://remote-host:8181/water\"," +
                    "\"protocol\":\"http\"" +
                    "}");
            return;
        }
        if ("GET".equals(method) && path.endsWith("/80")) {
            writeResponse(exchange, 200, "{" +
                    "\"id\":80," +
                    "\"serviceName\":\"catalog-service\"," +
                    "\"serviceVersion\":\"2.1.0\"," +
                    "\"instanceId\":\"catalog-80\"," +
                    "\"endpoint\":\"http://remote-host/water\"," +
                    "\"protocol\":\"http\"" +
                    "}");
            return;
        }
        if ("GET".equals(method) && path.endsWith("/443")) {
            writeResponse(exchange, 200, "{" +
                    "\"id\":443," +
                    "\"serviceName\":\"secure-service\"," +
                    "\"serviceVersion\":\"2.1.0\"," +
                    "\"instanceId\":\"secure-443\"," +
                    "\"endpoint\":\"https://secure-host/secure\"," +
                    "\"protocol\":\"https\"" +
                    "}");
            return;
        }
        writeResponse(exchange, 404, "");
    }

    private void writeResponse(HttpExchange exchange, int statusCode, String body) throws IOException {
        byte[] responseBytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(statusCode, responseBytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(responseBytes);
        }
    }

    private ServiceDiscoveryGlobalOptions shortRetryOptions() {
        return new ServiceDiscoveryGlobalOptions() {
            @Override
            public String getDiscoveryUrl() {
                return "";
            }

            @Override
            public String getDefaultHost() {
                return "";
            }

            @Override
            public long getHeartbeatIntervalSeconds() {
                return 1L;
            }

            @Override
            public long getRegistrationRetryInitialDelaySeconds() {
                return 1L;
            }

            @Override
            public long getRegistrationRetryMaxDelaySeconds() {
                return 1L;
            }

            @Override
            public long getHttpTimeoutSeconds() {
                return 1L;
            }

            @Override
            public int getRegistrationMaxAttempts() {
                return 1;
            }

            @Override
            public long[] getRegistrationRetryBackoffMs() {
                return new long[]{0L};
            }
        };
    }
}
