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

import it.water.core.api.bundle.ApplicationProperties;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class DefaultServiceDiscoveryGlobalOptionsImplTest {

    @Test
    void returnsFrameworkDefaultsWhenApplicationPropertiesAreMissing() {
        DefaultServiceDiscoveryGlobalOptionsImpl options = new DefaultServiceDiscoveryGlobalOptionsImpl();

        Assertions.assertEquals("", options.getDiscoveryUrl());
        Assertions.assertEquals("", options.getDefaultHost());
        Assertions.assertEquals(25L, options.getHeartbeatIntervalSeconds());
        Assertions.assertEquals(30L, options.getRegistrationRetryInitialDelaySeconds());
        Assertions.assertEquals(300L, options.getRegistrationRetryMaxDelaySeconds());
        Assertions.assertEquals(10L, options.getHttpTimeoutSeconds());
        Assertions.assertEquals(3, options.getRegistrationMaxAttempts());
        Assertions.assertArrayEquals(new long[]{2000L, 4000L, 8000L}, options.getRegistrationRetryBackoffMs());
    }

    @Test
    void readsAndNormalizesConfiguredProperties() {
        DefaultServiceDiscoveryGlobalOptionsImpl options = new DefaultServiceDiscoveryGlobalOptionsImpl();
        ApplicationProperties properties = Mockito.mock(ApplicationProperties.class);
        Mockito.when(properties.getPropertyOrDefault(ServiceDiscoveryGlobalConstants.PROP_DISCOVERY_URL, ""))
                .thenReturn(" http://localhost:8181/water ");
        Mockito.when(properties.getPropertyOrDefault(ServiceDiscoveryGlobalConstants.PROP_DEFAULT_HOST, ""))
                .thenReturn(" service-host ");
        Mockito.when(properties.getPropertyOrDefault(ServiceDiscoveryGlobalConstants.PROP_HEARTBEAT_INTERVAL_SECONDS, 25L))
                .thenReturn(11L);
        Mockito.when(properties.getPropertyOrDefault(ServiceDiscoveryGlobalConstants.PROP_REGISTRATION_RETRY_INITIAL_DELAY_SECONDS, 30L))
                .thenReturn(12L);
        Mockito.when(properties.getPropertyOrDefault(ServiceDiscoveryGlobalConstants.PROP_REGISTRATION_RETRY_MAX_DELAY_SECONDS, 300L))
                .thenReturn(90L);
        Mockito.when(properties.getPropertyOrDefault(ServiceDiscoveryGlobalConstants.PROP_HTTP_TIMEOUT_SECONDS, 10L))
                .thenReturn(7L);
        Mockito.when(properties.getPropertyOrDefault(ServiceDiscoveryGlobalConstants.PROP_REGISTRATION_MAX_ATTEMPTS, 3L))
                .thenReturn(5L);
        Mockito.when(properties.getPropertyOrDefault(ServiceDiscoveryGlobalConstants.PROP_REGISTRATION_RETRY_BACKOFF_MS, ""))
                .thenReturn("100, 200,300");
        options.setApplicationProperties(properties);

        Assertions.assertEquals("http://localhost:8181/water", options.getDiscoveryUrl());
        Assertions.assertEquals("service-host", options.getDefaultHost());
        Assertions.assertEquals(11L, options.getHeartbeatIntervalSeconds());
        Assertions.assertEquals(12L, options.getRegistrationRetryInitialDelaySeconds());
        Assertions.assertEquals(90L, options.getRegistrationRetryMaxDelaySeconds());
        Assertions.assertEquals(7L, options.getHttpTimeoutSeconds());
        Assertions.assertEquals(5, options.getRegistrationMaxAttempts());
        Assertions.assertArrayEquals(new long[]{100L, 200L, 300L}, options.getRegistrationRetryBackoffMs());
    }

    @Test
    void fallsBackToDefaultsForInvalidRegistrationAttemptsAndBackoff() {
        DefaultServiceDiscoveryGlobalOptionsImpl options = new DefaultServiceDiscoveryGlobalOptionsImpl();
        ApplicationProperties properties = Mockito.mock(ApplicationProperties.class);
        Mockito.when(properties.getPropertyOrDefault(ServiceDiscoveryGlobalConstants.PROP_REGISTRATION_MAX_ATTEMPTS, 3L))
                .thenReturn(0L);
        Mockito.when(properties.getPropertyOrDefault(ServiceDiscoveryGlobalConstants.PROP_REGISTRATION_RETRY_BACKOFF_MS, ""))
                .thenReturn("-1, 200");
        options.setApplicationProperties(properties);

        Assertions.assertEquals(3, options.getRegistrationMaxAttempts());
        Assertions.assertArrayEquals(new long[]{2000L, 4000L, 8000L}, options.getRegistrationRetryBackoffMs());

        Mockito.when(properties.getPropertyOrDefault(ServiceDiscoveryGlobalConstants.PROP_REGISTRATION_RETRY_BACKOFF_MS, ""))
                .thenReturn("100,not-a-number");

        Assertions.assertArrayEquals(new long[]{2000L, 4000L, 8000L}, options.getRegistrationRetryBackoffMs());
    }

    @Test
    void returnsEmptyStringWhenConfiguredStringPropertyIsNull() {
        DefaultServiceDiscoveryGlobalOptionsImpl options = new DefaultServiceDiscoveryGlobalOptionsImpl();
        ApplicationProperties properties = Mockito.mock(ApplicationProperties.class);
        Mockito.when(properties.getPropertyOrDefault(ServiceDiscoveryGlobalConstants.PROP_DISCOVERY_URL, ""))
                .thenReturn(null);
        options.setApplicationProperties(properties);

        Assertions.assertEquals("", options.getDiscoveryUrl());
    }
}
