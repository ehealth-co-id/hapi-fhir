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
 * R5: STRING leaves with the same parameter and the same operator (<code>eq</code>, <code>sw</code>,
 * <code>ew</code>, <code>co</code>) joined by <code>or</code> share one <code>HFJ_SPIDX_STRING</code> join.
 * Operators are never mixed and <code>ne/gt/lt/ge/le</code> are never merged. See
 * {@link AbstractMergeOrRule}.
 */
public class MergeOrSameOpStringRule extends AbstractMergeOrRule {

	public MergeOrSameOpStringRule() {
		super(
				RestSearchParameterTypeEnum.STRING,
				EnumSet.of(
						SearchFilterParser.CompareOperation.eq,
						SearchFilterParser.CompareOperation.sw,
						SearchFilterParser.CompareOperation.ew,
						SearchFilterParser.CompareOperation.co));
	}
}
