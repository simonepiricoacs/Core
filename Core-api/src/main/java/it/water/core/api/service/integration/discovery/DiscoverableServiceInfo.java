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

package it.water.core.api.service.integration.discovery;

/**
 * @Author Aristide Cittadino
 * This interfaces maps all properties required for a service registration.
 * ServiceRoot and ServicePath identify uniquely the single service.
 * ES. for rest exposure
 * serviceProtocol : http
 * servicePort: 8080
 * serviceId: myService
 * serviceInstanceId: myService-instance-001
 * serviceRoot: /myService
 */
public interface DiscoverableServiceInfo {
    String getServiceProtocol();

    String getServicePort();

    String getServiceId();

    String getServiceInstanceId();

    String getServiceRoot();

    default String getServiceHost() {
        return null;
    }

    default String getServiceEndpoint() {
        return null;
    }
}
