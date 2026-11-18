/*-
 * #%L
 * HAPI FHIR JPA Server
 * %%
 * Copyright (C) 2014 - 2024 Smile CDR, Inc.
 * %%
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * #L%
 */
package ca.uhn.fhir.jpa.dao.predicate.optimizer;

import ca.uhn.fhir.rest.api.RestSearchParameterTypeEnum;

import java.util.Optional;

/**
 * Lets optimizer rules ask about the search parameters of the resource type being searched, without
 * depending on Spring or the database. An empty result means "do not touch this parameter" (unknown
 * parameter, or one that has special handling such as <code>_id</code>).
 */
@FunctionalInterface
public interface FilterOptimizerContext {

	Optional<RestSearchParameterTypeEnum> paramType(String theParamName);
}
