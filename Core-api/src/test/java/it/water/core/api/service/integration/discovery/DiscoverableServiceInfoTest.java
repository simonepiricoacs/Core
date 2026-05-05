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

package it.water.core.api.service.integration.discovery;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class DiscoverableServiceInfoTest {

    @Test
    void defaultHostAndEndpointShouldBeNull() {
        DiscoverableServiceInfo serviceInfo = new MinimalDiscoverableServiceInfo();

        Assertions.assertNull(serviceInfo.getServiceHost());
        Assertions.assertNull(serviceInfo.getServiceEndpoint());
    }

    private static final class MinimalDiscoverableServiceInfo implements DiscoverableServiceInfo {
        @Override
        public String getServiceProtocol() {
            return "http";
        }

        @Override
        public String getServicePort() {
            return "8080";
        }

        @Override
        public String getServiceId() {
            return "catalog";
        }

        @Override
        public String getServiceInstanceId() {
            return "catalog-1";
        }

        @Override
        public String getServiceRoot() {
            return "/catalog";
        }
    }
}
