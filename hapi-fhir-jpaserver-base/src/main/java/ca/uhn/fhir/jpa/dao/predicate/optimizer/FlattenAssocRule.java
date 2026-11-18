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
import java.util.List;
import java.util.Optional;

/**
 * R2: a nested chain of the same operator, such as the parser's <code>or(a, or(b, c))</code>, becomes one
 * n-ary node. Sound because <code>and</code> and <code>or</code> are associative. Nodes with different
 * operators are never combined, so the parser's (non-standard) precedence is preserved.
 */
public class FlattenAssocRule implements SearchFilterRule {

	@Override
	public Optional<SearchFilterParser.BaseFilter> apply(
			SearchFilterParser.BaseFilter theNode, FilterOptimizerContext theContext) {
		if (!(theNode instanceof FilterNaryLogical)) {
			return Optional.empty();
		}
		FilterNaryLogical nary = (FilterNaryLogical) theNode;
		boolean changed = false;
		List<SearchFilterParser.BaseFilter> flat = new ArrayList<>();
		for (SearchFilterParser.BaseFilter next : nary.getOperands()) {
			if (next instanceof FilterNaryLogical && ((FilterNaryLogical) next).getOperation() == nary.getOperation()) {
				flat.addAll(((FilterNaryLogical) next).getOperands());
				changed = true;
			} else {
				flat.add(next);
			}
		}
		if (!changed) {
			return Optional.empty();
		}
		return Optional.of(new FilterNaryLogical(nary.getOperation(), flat));
	}
}
