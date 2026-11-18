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

import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Optimizer output node: several leaves on the same parameter with the same operator that were joined by
 * <code>or</code>, to be lowered to a single multi-valued predicate (one join). Never produced by the parser.
 */
public class FilterParameterOrList extends SearchFilterParser.BaseFilter {

	private final List<SearchFilterParser.FilterParameter> myLeaves;

	public FilterParameterOrList(List<SearchFilterParser.FilterParameter> theLeaves) {
		if (theLeaves.size() < 2) {
			throw new IllegalArgumentException("Need at least two leaves");
		}
		myLeaves = Collections.unmodifiableList(theLeaves);
	}

	public List<SearchFilterParser.FilterParameter> getLeaves() {
		return myLeaves;
	}

	public String getParamName() {
		return myLeaves.get(0).getParamPath().getName();
	}

	public SearchFilterParser.CompareOperation getOperation() {
		return myLeaves.get(0).getOperation();
	}

	public List<String> getValues() {
		return myLeaves.stream()
				.map(SearchFilterParser.FilterParameter::getValue)
				.collect(Collectors.toList());
	}

	@Override
	public String toString() {
		return getParamName() + " " + getOperation() + " " + getValues();
	}
}
