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
 * Optimizer output node: an n-ary <code>and</code> / <code>or</code> (the parser produces right-nested
 * binary nodes). Never produced by the parser.
 */
public class FilterNaryLogical extends SearchFilterParser.BaseFilter {

	private final SearchFilterParser.FilterLogicalOperation myOperation;
	private final List<SearchFilterParser.BaseFilter> myOperands;

	public FilterNaryLogical(
			SearchFilterParser.FilterLogicalOperation theOperation, List<SearchFilterParser.BaseFilter> theOperands) {
		myOperation = theOperation;
		myOperands = Collections.unmodifiableList(theOperands);
	}

	public SearchFilterParser.FilterLogicalOperation getOperation() {
		return myOperation;
	}

	public List<SearchFilterParser.BaseFilter> getOperands() {
		return myOperands;
	}

	@Override
	public String toString() {
		return myOperands.stream().map(t -> "(" + t + ")").collect(Collectors.joining(" " + myOperation.name() + " "));
	}
}
