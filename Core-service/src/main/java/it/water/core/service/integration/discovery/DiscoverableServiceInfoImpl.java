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

import it.water.core.api.service.integration.discovery.DiscoverableServiceInfo;

/**
 * Simple POJO implementation of DiscoverableServiceInfo.
 * Holds all the data needed to register a microservice in the ServiceDiscovery.
 */
public class DiscoverableServiceInfoImpl implements DiscoverableServiceInfo {
    private final String serviceProtocol;
    private final String servicePort;
    private final String serviceId;
    private final String serviceInstanceId;
    private final String serviceRoot;
    private final String serviceVersion;
    private final String serviceHost;
    private final String serviceEndpoint;

    public DiscoverableServiceInfoImpl(String serviceProtocol, String servicePort,
                                       String serviceId, String serviceInstanceId,
                                       String serviceRoot, String serviceVersion) {
        this(serviceProtocol, servicePort, serviceId, serviceInstanceId, serviceRoot, serviceVersion, null, null);
    }

    public DiscoverableServiceInfoImpl(String serviceProtocol, String servicePort,
                                       String serviceId, String serviceInstanceId,
                                       String serviceRoot, String serviceVersion,
                                       String serviceHost, String serviceEndpoint) {
        this.serviceProtocol = serviceProtocol;
        this.servicePort = servicePort;
        this.serviceId = serviceId;
        this.serviceInstanceId = serviceInstanceId;
        this.serviceRoot = serviceRoot;
        this.serviceVersion = serviceVersion;
        this.serviceHost = serviceHost;
        this.serviceEndpoint = serviceEndpoint;
    }

    @Override
    public String getServiceProtocol() {
        return serviceProtocol;
    }

    @Override
    public String getServicePort() {
        return servicePort;
    }

    @Override
    public String getServiceId() {
        return serviceId;
    }

    @Override
    public String getServiceInstanceId() {
        return serviceInstanceId;
    }

    @Override
    public String getServiceRoot() {
        return serviceRoot;
    }

    public String getServiceVersion() {
        return serviceVersion;
    }

    @Override
    public String getServiceHost() {
        return serviceHost;
    }

    @Override
    public String getServiceEndpoint() {
        return serviceEndpoint;
    }

    @Override
    public String toString() {
        return "DiscoverableServiceInfo{" +
                "serviceId='" + serviceId + '\'' +
                ", instanceId='" + serviceInstanceId + '\'' +
                ", endpoint='" + resolveDisplayEndpoint() + '\'' +
                ", version='" + serviceVersion + '\'' +
                '}';
    }

    private String resolveDisplayEndpoint() {
        if (serviceEndpoint != null && !serviceEndpoint.isBlank()) {
            return serviceEndpoint;
        }
        String host = serviceHost != null && !serviceHost.isBlank() ? serviceHost : "host";
        return serviceProtocol + "://" + host + ":" + servicePort + serviceRoot;
    }
}
