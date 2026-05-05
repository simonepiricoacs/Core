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

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class DiscoverableServiceInfoImplTest {

    @Test
    void shouldExposeConstructorValues() {
        DiscoverableServiceInfoImpl info = new DiscoverableServiceInfoImpl(
                "https", "9443", "catalog", "catalog-1", "/catalog", "2.0.0",
                "catalog-host", "https://catalog-host:9443/catalog");

        Assertions.assertEquals("https", info.getServiceProtocol());
        Assertions.assertEquals("9443", info.getServicePort());
        Assertions.assertEquals("catalog", info.getServiceId());
        Assertions.assertEquals("catalog-1", info.getServiceInstanceId());
        Assertions.assertEquals("/catalog", info.getServiceRoot());
        Assertions.assertEquals("2.0.0", info.getServiceVersion());
        Assertions.assertEquals("catalog-host", info.getServiceHost());
        Assertions.assertEquals("https://catalog-host:9443/catalog", info.getServiceEndpoint());
        Assertions.assertTrue(info.toString().contains("https://catalog-host:9443/catalog"));
    }

    @Test
    void toStringShouldBuildDisplayEndpointWhenExplicitEndpointIsMissing() {
        DiscoverableServiceInfoImpl info = new DiscoverableServiceInfoImpl(
                "http", "8080", "catalog", "catalog-1", "/catalog", "1.0.0",
                "catalog-host", "");

        Assertions.assertTrue(info.toString().contains("http://catalog-host:8080/catalog"));
    }

    @Test
    void toStringShouldUseFallbackHostWhenHostIsMissing() {
        DiscoverableServiceInfoImpl info = new DiscoverableServiceInfoImpl(
                "http", "8080", "catalog", "catalog-1", "/catalog", "1.0.0");

        Assertions.assertNull(info.getServiceHost());
        Assertions.assertNull(info.getServiceEndpoint());
        Assertions.assertTrue(info.toString().contains("http://host:8080/catalog"));
    }

    @Test
    void toStringShouldUseFallbackHostWhenHostIsBlank() {
        DiscoverableServiceInfoImpl info = new DiscoverableServiceInfoImpl(
                "http", "8080", "catalog", "catalog-1", "/catalog", "1.0.0",
                " ", null);

        Assertions.assertTrue(info.toString().contains("http://host:8080/catalog"));
    }
}
