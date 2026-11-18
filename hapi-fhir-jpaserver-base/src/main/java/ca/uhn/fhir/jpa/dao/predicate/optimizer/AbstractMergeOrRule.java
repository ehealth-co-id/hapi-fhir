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

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Base for rules that merge leaves of an n-ary <code>or</code> that have the same parameter and the same
 * operator into one {@link FilterParameterOrList}, which is lowered to a single multi-valued predicate (one
 * index join) instead of one join per leaf.
 * <p>
 * Soundness: each leaf means "some index row of this parameter matches condition Ci". For one parameter on
 * one resource, <code>exists row(C1) or exists row(C2)</code> equals <code>exists row(C1 or C2)</code>, which
 * is what the multi-valued predicate builds. Only the duplicate result rows that the separate joins
 * produced disappear. Merged leaves keep the position of the first one; other operands stay in place.
 * </p>
 * <p>
 * Because the optimizer works bottom-up, the inner part of the parser's right-nested <code>or(a, or(b, c))</code>
 * may already be an {@link FilterParameterOrList} when the outer node is visited, so existing or-lists with
 * the same parameter and operator are absorbed too. Leaves with an identical value are kept once
 * (<code>A or A = A</code>).
 * </p>
 */
public abstract class AbstractMergeOrRule implements SearchFilterRule {

	private final RestSearchParameterTypeEnum myType;
	private final Set<SearchFilterParser.CompareOperation> myOperations;

	protected AbstractMergeOrRule(
			RestSearchParameterTypeEnum theType, EnumSet<SearchFilterParser.CompareOperation> theOperations) {
		myType = theType;
		myOperations = theOperations;
	}

	@Override
	public Optional<SearchFilterParser.BaseFilter> apply(
			SearchFilterParser.BaseFilter theNode, FilterOptimizerContext theContext) {
		if (!(theNode instanceof FilterNaryLogical)
				|| ((FilterNaryLogical) theNode).getOperation() != SearchFilterParser.FilterLogicalOperation.or) {
			return Optional.empty();
		}
		FilterNaryLogical nary = (FilterNaryLogical) theNode;

		// Group mergeable operands by parameter name and operator, in first-occurrence order
		Map<String, List<SearchFilterParser.BaseFilter>> groups = new LinkedHashMap<>();
		for (SearchFilterParser.BaseFilter next : nary.getOperands()) {
			String key = keyOf(next, theContext);
			if (key != null) {
				groups.computeIfAbsent(key, k -> new ArrayList<>()).add(next);
			}
		}
		if (groups.values().stream().noneMatch(t -> t.size() > 1)) {
			return Optional.empty();
		}

		List<SearchFilterParser.BaseFilter> result = new ArrayList<>();
		for (SearchFilterParser.BaseFilter next : nary.getOperands()) {
			String key = keyOf(next, theContext);
			if (key == null) {
				result.add(next);
				continue;
			}
			List<SearchFilterParser.BaseFilter> group = groups.get(key);
			if (group.size() == 1) {
				result.add(next);
			} else if (group.get(0) == next) {
				result.add(merge(group));
			}
			// else: a later member of a merged group, already represented by the first one
		}
		if (result.size() == 1) {
			return Optional.of(result.get(0));
		}
		return Optional.of(new FilterNaryLogical(nary.getOperation(), result));
	}

	private SearchFilterParser.BaseFilter merge(List<SearchFilterParser.BaseFilter> theGroup) {
		// Keep one leaf per distinct value, in first-occurrence order
		Map<String, SearchFilterParser.FilterParameter> byValue = new LinkedHashMap<>();
		for (SearchFilterParser.BaseFilter next : theGroup) {
			List<SearchFilterParser.FilterParameter> leaves = next instanceof FilterParameterOrList
					? ((FilterParameterOrList) next).getLeaves()
					: List.of((SearchFilterParser.FilterParameter) next);
			for (SearchFilterParser.FilterParameter leaf : leaves) {
				byValue.putIfAbsent(leaf.getValue(), leaf);
			}
		}
		List<SearchFilterParser.FilterParameter> distinct = new ArrayList<>(new LinkedHashSet<>(byValue.values()));
		if (distinct.size() == 1) {
			return distinct.get(0);
		}
		return new FilterParameterOrList(distinct);
	}

	/**
	 * @return the grouping key if the operand can take part in a merge, otherwise null
	 */
	private String keyOf(SearchFilterParser.BaseFilter theNode, FilterOptimizerContext theContext) {
		SearchFilterParser.FilterParameter leaf;
		if (theNode instanceof SearchFilterParser.FilterParameter) {
			leaf = (SearchFilterParser.FilterParameter) theNode;
		} else if (theNode instanceof FilterParameterOrList) {
			leaf = ((FilterParameterOrList) theNode).getLeaves().get(0);
		} else {
			return null;
		}
		SearchFilterParser.FilterParameterPath path = leaf.getParamPath();
		boolean mergeable = path != null
				&& path.getNext() == null
				&& path.getFilter() == null
				&& leaf.getValue() != null
				&& myOperations.contains(leaf.getOperation())
				&& theContext.paramType(path.getName()).filter(myType::equals).isPresent();
		return mergeable ? path.getName() + "|" + leaf.getOperation() : null;
	}
}
