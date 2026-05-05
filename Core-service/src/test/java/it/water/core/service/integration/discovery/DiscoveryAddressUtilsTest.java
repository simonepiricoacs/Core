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

class DiscoveryAddressUtilsTest {

    @Test
    void normalizeAdvertisedEndpointShouldTrimAndRemoveTrailingSlash() {
        Assertions.assertEquals("", DiscoveryAddressUtils.normalizeAdvertisedEndpoint(null));
        Assertions.assertEquals("", DiscoveryAddressUtils.normalizeAdvertisedEndpoint("   "));
        Assertions.assertEquals("http://host:8080/root",
                DiscoveryAddressUtils.normalizeAdvertisedEndpoint(" http://host:8080/root/ "));
        Assertions.assertEquals("http://host:8080/root",
                DiscoveryAddressUtils.normalizeAdvertisedEndpoint("http://host:8080/root"));
    }

    @Test
    void normalizeRootShouldReturnCanonicalRootPath() {
        Assertions.assertEquals("", DiscoveryAddressUtils.normalizeRoot(null));
        Assertions.assertEquals("", DiscoveryAddressUtils.normalizeRoot("   "));
        Assertions.assertEquals("/", DiscoveryAddressUtils.normalizeRoot("/"));
        Assertions.assertEquals("/water", DiscoveryAddressUtils.normalizeRoot("water"));
        Assertions.assertEquals("/water", DiscoveryAddressUtils.normalizeRoot("/water/"));
    }

    @Test
    void normalizeHostShouldTrimOrReturnBlank() {
        Assertions.assertEquals("", DiscoveryAddressUtils.normalizeHost(null));
        Assertions.assertEquals("", DiscoveryAddressUtils.normalizeHost(" "));
        Assertions.assertEquals("service.local", DiscoveryAddressUtils.normalizeHost(" service.local "));
    }

    @Test
    void shouldExtractEndpointPartsAndDefaultPorts() {
        Assertions.assertEquals("", DiscoveryAddressUtils.extractPortFromEndpoint(null));
        Assertions.assertEquals("", DiscoveryAddressUtils.extractPortFromEndpoint(" "));
        Assertions.assertEquals("8181", DiscoveryAddressUtils.extractPortFromEndpoint("http://host:8181/water"));
        Assertions.assertEquals("80", DiscoveryAddressUtils.extractPortFromEndpoint("http://host/water"));
        Assertions.assertEquals("443", DiscoveryAddressUtils.extractPortFromEndpoint("https://host/water"));
        Assertions.assertEquals("", DiscoveryAddressUtils.extractPortFromEndpoint("ftp://host/water"));
        Assertions.assertEquals("", DiscoveryAddressUtils.extractPortFromEndpoint("not a uri"));

        Assertions.assertEquals("", DiscoveryAddressUtils.extractHostFromEndpoint(null));
        Assertions.assertEquals("", DiscoveryAddressUtils.extractHostFromEndpoint(" "));
        Assertions.assertEquals("host", DiscoveryAddressUtils.extractHostFromEndpoint("http://host:8181/water"));
        Assertions.assertEquals("", DiscoveryAddressUtils.extractHostFromEndpoint("not a uri"));
    }

    @Test
    void shouldExtractRootOrUseDefault() {
        Assertions.assertEquals("/water", DiscoveryAddressUtils.extractRootFromEndpoint(null, "/water"));
        Assertions.assertEquals("/water", DiscoveryAddressUtils.extractRootFromEndpoint(" ", "/water"));
        Assertions.assertEquals("/water", DiscoveryAddressUtils.extractRootFromEndpoint("http://host", "/water"));
        Assertions.assertEquals("/api", DiscoveryAddressUtils.extractRootFromEndpoint("http://host/api/", "/water"));
        Assertions.assertEquals("/water", DiscoveryAddressUtils.extractRootFromEndpoint("not a uri", "/water"));
    }

    @Test
    void defaultPortForSchemeShouldRecognizeHttpAndHttpsOnly() {
        Assertions.assertEquals("80", DiscoveryAddressUtils.defaultPortForScheme("http"));
        Assertions.assertEquals("80", DiscoveryAddressUtils.defaultPortForScheme("HTTP"));
        Assertions.assertEquals("443", DiscoveryAddressUtils.defaultPortForScheme("https"));
        Assertions.assertEquals("443", DiscoveryAddressUtils.defaultPortForScheme("HTTPS"));
        Assertions.assertEquals("", DiscoveryAddressUtils.defaultPortForScheme(null));
        Assertions.assertEquals("", DiscoveryAddressUtils.defaultPortForScheme("ftp"));
    }
}
