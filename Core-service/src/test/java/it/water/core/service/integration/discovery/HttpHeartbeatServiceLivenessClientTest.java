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

import it.water.core.api.service.integration.discovery.ServiceDiscoveryGlobalOptions;
import it.water.core.api.service.integration.discovery.ServiceLivenessRegistration;
import it.water.core.api.service.integration.discovery.ServiceLivenessSession;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

class HttpHeartbeatServiceLivenessClientTest {

    @Test
    void startShouldRegisterSessionAndStopPreviousSessionForSameInstance() throws Exception {
        HttpHeartbeatServiceLivenessClient client = new HttpHeartbeatServiceLivenessClient();
        client.setGlobalOptions(globalOptions(1L));
        ServiceLivenessRegistration registration = registration("catalog-1");

        ServiceLivenessSession first = client.start(registration, null);
        Object firstSession = getSession(client, "catalog-1");
        ServiceLivenessSession second = client.start(registration, null);
        Object secondSession = getSession(client, "catalog-1");

        Assertions.assertNotSame(firstSession, secondSession);
        Assertions.assertFalse(isSessionRunning(firstSession));
        Assertions.assertTrue(isSessionRunning(secondSession));

        first.stop();
        second.stop();
        Assertions.assertNull(getSession(client, "catalog-1"));
    }

    @Test
    void heartbeatTickShouldKeepSessionRunningWhenHeartbeatSucceeds() throws Exception {
        HttpHeartbeatServiceLivenessClient client = new HttpHeartbeatServiceLivenessClient();
        ServiceDiscoveryRegistryClientInternal discoveryClient = Mockito.mock(ServiceDiscoveryRegistryClientInternal.class);
        Mockito.when(discoveryClient.heartbeat("catalog", "catalog-1")).thenReturn(true);
        client.setDiscoveryClient(discoveryClient);

        client.start(registration("catalog-1"), null);
        Object session = getSession(client, "catalog-1");
        invokeHeartbeatTick(session);

        Assertions.assertTrue(isSessionRunning(session));
        Assertions.assertSame(session, getSession(client, "catalog-1"));
        client.deactivate();
    }

    @Test
    void heartbeatTickShouldNotifyListenerAndRemoveSessionWhenHeartbeatFails() throws Exception {
        HttpHeartbeatServiceLivenessClient client = new HttpHeartbeatServiceLivenessClient();
        ServiceDiscoveryRegistryClientInternal discoveryClient = Mockito.mock(ServiceDiscoveryRegistryClientInternal.class);
        Mockito.when(discoveryClient.heartbeat("catalog", "catalog-1")).thenReturn(false);
        client.setDiscoveryClient(discoveryClient);
        AtomicReference<ServiceLivenessRegistration> lostRegistration = new AtomicReference<>();
        AtomicReference<String> lostReason = new AtomicReference<>();

        client.start(registration("catalog-1"), (registration, reason) -> {
            lostRegistration.set(registration);
            lostReason.set(reason);
        });
        Object session = getSession(client, "catalog-1");
        invokeHeartbeatTick(session);

        Assertions.assertFalse(isSessionRunning(session));
        Assertions.assertNull(getSession(client, "catalog-1"));
        Assertions.assertEquals("catalog-1", lostRegistration.get().getInstanceId());
        Assertions.assertEquals("HTTP heartbeat failed", lostReason.get());
    }

    @Test
    void heartbeatTickShouldReturnWhenStoppedOrClientIsMissing() throws Exception {
        HttpHeartbeatServiceLivenessClient client = new HttpHeartbeatServiceLivenessClient();
        client.start(registration("catalog-1"), null);
        Object session = getSession(client, "catalog-1");

        invokeHeartbeatTick(session);
        Assertions.assertTrue(isSessionRunning(session));

        setSessionRunning(session, false);
        invokeHeartbeatTick(session);
        Assertions.assertFalse(isSessionRunning(session));
        client.deactivate();
    }

    @Test
    void deactivateShouldStopAndClearAllSessions() throws Exception {
        HttpHeartbeatServiceLivenessClient client = new HttpHeartbeatServiceLivenessClient();
        client.start(registration("catalog-1"), null);
        Object session = getSession(client, "catalog-1");

        client.deactivate();

        Assertions.assertFalse(isSessionRunning(session));
        Assertions.assertNull(getSession(client, "catalog-1"));
    }

    private static ServiceLivenessRegistration registration(String instanceId) {
        return new ServiceLivenessRegistration(
                "catalog", instanceId, "1.0.0", "http", "/catalog",
                "http://localhost:8080/catalog", "localhost", "8080", "node-1", "layer-1");
    }

    private static ServiceDiscoveryGlobalOptions globalOptions(long heartbeatIntervalSeconds) {
        ServiceDiscoveryGlobalOptions options = Mockito.mock(ServiceDiscoveryGlobalOptions.class);
        Mockito.when(options.getHeartbeatIntervalSeconds()).thenReturn(heartbeatIntervalSeconds);
        return options;
    }

    private static Object getSession(HttpHeartbeatServiceLivenessClient client, String instanceId) throws Exception {
        Field field = HttpHeartbeatServiceLivenessClient.class.getDeclaredField("sessions");
        field.setAccessible(true);
        Map<?, ?> sessions = (Map<?, ?>) field.get(client);
        return sessions.get(instanceId);
    }

    private static boolean isSessionRunning(Object session) throws Exception {
        Field field = session.getClass().getDeclaredField("running");
        field.setAccessible(true);
        return (boolean) field.get(session);
    }

    private static void setSessionRunning(Object session, boolean running) throws Exception {
        Field field = session.getClass().getDeclaredField("running");
        field.setAccessible(true);
        field.set(session, running);
    }

    private static void invokeHeartbeatTick(Object session) throws Exception {
        Method method = session.getClass().getDeclaredMethod("heartbeatTick");
        method.setAccessible(true);
        method.invoke(session);
    }
}
