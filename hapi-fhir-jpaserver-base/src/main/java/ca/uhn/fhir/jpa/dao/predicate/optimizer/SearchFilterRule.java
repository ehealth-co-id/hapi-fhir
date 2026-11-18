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

import ca.uhn.fhir.jpa.dao.predicate.SearchFilterParser;

import java.util.Optional;

/**
 * One semantics-preserving rewrite of a single node of a parsed <code>_filter</code> tree. Rules must
 * not mutate their input, and every rule must strictly reduce the node count or convert a node into its
 * normal form, so the optimizer terminates. The soundness argument belongs in the rule's javadoc.
 */
public interface SearchFilterRule {

	/**
	 * @return the rewritten node, or empty if this rule does not apply to the node
	 */
	Optional<SearchFilterParser.BaseFilter> apply(
			SearchFilterParser.BaseFilter theNode, FilterOptimizerContext theContext);
}
