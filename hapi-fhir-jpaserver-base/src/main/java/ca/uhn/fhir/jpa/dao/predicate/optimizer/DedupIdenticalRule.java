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

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * R3: inside an n-ary node, operands with identical text are reduced to the first one. Sound because
 * <code>A or A = A</code> and <code>A and A = A</code>: both sides test for the existence of a row matching
 * the same condition. If a single operand remains, it replaces the n-ary node.
 */
public class DedupIdenticalRule implements SearchFilterRule {

	@Override
	public Optional<SearchFilterParser.BaseFilter> apply(
			SearchFilterParser.BaseFilter theNode, FilterOptimizerContext theContext) {
		if (!(theNode instanceof FilterNaryLogical)) {
			return Optional.empty();
		}
		FilterNaryLogical nary = (FilterNaryLogical) theNode;
		Set<String> seen = new HashSet<>();
		List<SearchFilterParser.BaseFilter> kept = new ArrayList<>();
		for (SearchFilterParser.BaseFilter next : nary.getOperands()) {
			if (seen.add(next.toString())) {
				kept.add(next);
			}
		}
		if (kept.size() == nary.getOperands().size()) {
			return Optional.empty();
		}
		if (kept.size() == 1) {
			return Optional.of(kept.get(0));
		}
		return Optional.of(new FilterNaryLogical(nary.getOperation(), kept));
	}
}
