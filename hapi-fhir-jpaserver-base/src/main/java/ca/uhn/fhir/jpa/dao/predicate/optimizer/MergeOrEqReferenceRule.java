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
import ca.uhn.fhir.rest.api.RestSearchParameterTypeEnum;

import java.util.EnumSet;

/**
 * R6: non-chained REFERENCE <code>eq</code> leaves joined by <code>or</code> become one
 * <code>TARGET_RESOURCE_ID IN (...)</code> predicate. See {@link AbstractMergeOrRule}.
 * <p>
 * Behaviour note: when one referenced id cannot be resolved, the unmerged form currently makes the whole
 * search match nothing, whereas the merged form (like the plain <code>param=a,b</code> search) only matches
 * nothing if none of the ids resolve.
 * </p>
 */
public class MergeOrEqReferenceRule extends AbstractMergeOrRule {

	public MergeOrEqReferenceRule() {
		super(RestSearchParameterTypeEnum.REFERENCE, EnumSet.of(SearchFilterParser.CompareOperation.eq));
	}
}
