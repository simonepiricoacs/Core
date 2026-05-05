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

import org.slf4j.Logger;

import java.net.InetAddress;
import java.net.URI;

/**
 * Shared utility methods used to resolve and normalize advertised service addresses.
 */
public final class DiscoveryAddressUtils {

    private DiscoveryAddressUtils() {
    }

    public static String normalizeAdvertisedEndpoint(String value) {
        if (value == null) {
            return "";
        }
        String trimmed = value.trim();
        if (trimmed.isBlank()) {
            return "";
        }
        return trimmed.endsWith("/") ? trimmed.substring(0, trimmed.length() - 1) : trimmed;
    }

    public static String normalizeRoot(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String trimmed = value.trim();
        if (!trimmed.startsWith("/")) {
            trimmed = "/" + trimmed;
        }
        return trimmed.endsWith("/") && trimmed.length() > 1
                ? trimmed.substring(0, trimmed.length() - 1)
                : trimmed;
    }

    public static String normalizeHost(String value) {
        if (value == null) {
            return "";
        }
        String trimmed = value.trim();
        return trimmed.isBlank() ? "" : trimmed;
    }

    public static String resolveLocalHostname(Logger log, String context) {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            if (log != null) {
                log.warn("Unable to resolve local hostname{}: {}", context != null ? " for " + context : "", e.getMessage());
            }
            return "";
        }
    }

    public static String extractPortFromEndpoint(String endpoint) {
        if (endpoint == null || endpoint.isBlank()) {
            return "";
        }
        try {
            URI uri = URI.create(endpoint.trim());
            if (uri.getPort() > 0) {
                return String.valueOf(uri.getPort());
            }
            return defaultPortForScheme(uri.getScheme());
        } catch (Exception e) {
            return "";
        }
    }

    public static String extractHostFromEndpoint(String endpoint) {
        if (endpoint == null || endpoint.isBlank()) {
            return "";
        }
        try {
            URI uri = URI.create(endpoint.trim());
            return normalizeHost(uri.getHost());
        } catch (Exception e) {
            return "";
        }
    }

    public static String extractRootFromEndpoint(String endpoint, String defaultRoot) {
        if (endpoint == null || endpoint.isBlank()) {
            return defaultRoot;
        }
        try {
            URI uri = URI.create(endpoint.trim());
            String path = uri.getPath();
            if (path == null || path.isBlank()) {
                return defaultRoot;
            }
            return normalizeRoot(path);
        } catch (Exception e) {
            return defaultRoot;
        }
    }

    public static String defaultPortForScheme(String scheme) {
        if ("https".equalsIgnoreCase(scheme)) {
            return "443";
        }
        if ("http".equalsIgnoreCase(scheme)) {
            return "80";
        }
        return "";
    }
}
