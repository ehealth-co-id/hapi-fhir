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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Rule-based optimizer for a parsed <code>_filter</code> tree. It never mutates its input and returns the
 * original tree whenever it bails out: more than {@link #MAX_LEAVES} leaves, any <code>not</code> operator,
 * or an exception inside a rule. Rules are applied bottom-up until a fixpoint (hard cap of
 * {@link #MAX_PASSES} passes).
 * <p>
 * Parser binary <code>and</code>/<code>or</code> nodes are first converted to {@link FilterNaryLogical}
 * nodes, which is why the optimizer never has to rebuild parser nodes.
 * </p>
 */
public class SearchFilterOptimizer {

	public static final int MAX_LEAVES = 100;
	public static final int MAX_PASSES = 10;
	private static final Logger ourLog = LoggerFactory.getLogger(SearchFilterOptimizer.class);

	private final List<SearchFilterRule> myRules;

	public SearchFilterOptimizer(List<SearchFilterRule> theRules) {
		myRules = new ArrayList<>(theRules);
	}

	/** The rules in application order. Add new heuristics here. */
	public static SearchFilterOptimizer createDefault() {
		List<SearchFilterRule> rules = new ArrayList<>();
		rules.add(new StripGroupsRule());
		rules.add(new FlattenAssocRule());
		rules.add(new DedupIdenticalRule());
		rules.add(new MergeOrEqTokenRule());
		rules.add(new MergeOrSameOpStringRule());
		rules.add(new MergeOrEqReferenceRule());
		return new SearchFilterOptimizer(rules);
	}

	public SearchFilterParser.BaseFilter optimize(
			SearchFilterParser.BaseFilter theFilter, FilterOptimizerContext theContext) {
		try {
			if (theFilter == null || !isSupported(theFilter, new int[1])) {
				return theFilter;
			}
			SearchFilterParser.BaseFilter current = theFilter;
			for (int pass = 0; pass < MAX_PASSES; pass++) {
				String before = current.toString();
				current = rewrite(current, theContext);
				if (before.equals(current.toString())) {
					break;
				}
			}
			if (ourLog.isDebugEnabled() && !theFilter.toString().equals(current.toString())) {
				ourLog.debug("Optimized _filter [{}] to [{}]", theFilter, current);
			}
			return current;
		} catch (RuntimeException e) {
			ourLog.warn("Failed to optimize _filter, using it unchanged: {}", e.toString());
			return theFilter;
		}
	}

	private boolean isSupported(SearchFilterParser.BaseFilter theNode, int[] theLeafCount) {
		if (theNode instanceof SearchFilterParser.FilterParameter) {
			return ++theLeafCount[0] <= MAX_LEAVES;
		} else if (theNode instanceof SearchFilterParser.FilterParameterGroup) {
			return isSupported(((SearchFilterParser.FilterParameterGroup) theNode).getContained(), theLeafCount);
		} else if (theNode instanceof FilterParameterOrList) {
			theLeafCount[0] += ((FilterParameterOrList) theNode).getLeaves().size();
			return theLeafCount[0] <= MAX_LEAVES;
		} else if (theNode instanceof FilterNaryLogical) {
			FilterNaryLogical nary = (FilterNaryLogical) theNode;
			return nary.getOperation() != SearchFilterParser.FilterLogicalOperation.not
					&& nary.getOperands().stream().allMatch(t -> isSupported(t, theLeafCount));
		} else if (theNode instanceof SearchFilterParser.FilterLogical) {
			SearchFilterParser.FilterLogical logical = (SearchFilterParser.FilterLogical) theNode;
			return logical.getOperation() != SearchFilterParser.FilterLogicalOperation.not
					&& isSupported(logical.getFilter1(), theLeafCount)
					&& isSupported(logical.getFilter2(), theLeafCount);
		}
		return false;
	}

	private SearchFilterParser.BaseFilter rewrite(
			SearchFilterParser.BaseFilter theNode, FilterOptimizerContext theContext) {
		SearchFilterParser.BaseFilter node = theNode;

		// Children first
		if (node instanceof SearchFilterParser.FilterParameterGroup) {
			node = rewrite(((SearchFilterParser.FilterParameterGroup) node).getContained(), theContext);
		} else if (node instanceof SearchFilterParser.FilterLogical) {
			SearchFilterParser.FilterLogical logical = (SearchFilterParser.FilterLogical) node;
			List<SearchFilterParser.BaseFilter> operands = new ArrayList<>();
			operands.add(rewrite(logical.getFilter1(), theContext));
			operands.add(rewrite(logical.getFilter2(), theContext));
			node = new FilterNaryLogical(logical.getOperation(), operands);
		} else if (node instanceof FilterNaryLogical) {
			FilterNaryLogical nary = (FilterNaryLogical) node;
			List<SearchFilterParser.BaseFilter> operands = new ArrayList<>();
			for (SearchFilterParser.BaseFilter next : nary.getOperands()) {
				operands.add(rewrite(next, theContext));
			}
			node = new FilterNaryLogical(nary.getOperation(), operands);
		}

		// Then this node, until no rule applies
		boolean applied = true;
		for (int i = 0; applied && i < MAX_PASSES * 10; i++) {
			applied = false;
			for (SearchFilterRule rule : myRules) {
				Optional<SearchFilterParser.BaseFilter> result = rule.apply(node, theContext);
				if (result.isPresent()) {
					node = result.get();
					applied = true;
					break;
				}
			}
		}
		return node;
	}
}
