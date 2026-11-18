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
 * R4: <code>type eq A or type eq B</code> on a TOKEN parameter becomes one predicate with
 * <code>HASH_VALUE IN (A, B)</code> on a single <code>HFJ_SPIDX_TOKEN</code> join. See
 * {@link AbstractMergeOrRule} for the soundness argument. The token builder already accepts a value list
 * (sorted, de-duplicated, bare and <code>system|code</code> values may be mixed).
 */
public class MergeOrEqTokenRule extends AbstractMergeOrRule {

	public MergeOrEqTokenRule() {
		super(RestSearchParameterTypeEnum.TOKEN, EnumSet.of(SearchFilterParser.CompareOperation.eq));
	}
}
