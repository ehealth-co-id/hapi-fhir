/*-
 * #%L
 * HAPI FHIR Storage api
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
package ca.uhn.fhir.jpa.graphql;

import ca.uhn.fhir.rest.api.server.RequestDetails;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import org.hl7.fhir.utilities.graphql.Argument;
import org.hl7.fhir.utilities.graphql.Document;
import org.hl7.fhir.utilities.graphql.Field;
import org.hl7.fhir.utilities.graphql.Fragment;
import org.hl7.fhir.utilities.graphql.NameValue;
import org.hl7.fhir.utilities.graphql.Operation;
import org.hl7.fhir.utilities.graphql.Package;
import org.hl7.fhir.utilities.graphql.Selection;
import org.hl7.fhir.utilities.graphql.StringValue;
import org.hl7.fhir.utilities.graphql.Value;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Static analysis of a parsed GraphQL query, computed once per request, that tells the storage layer which
 * reference-resolving fields ({@code resource(type: X)}) the query contains so that referenced resources
 * can be bulk-loaded before the engine asks for them one by one.
 * <p>
 * The GraphQL engine calls {@code lookup()} for every reference <i>before</i> it checks the {@code type}
 * argument, so the plan also records the union of requested types, which allows a lookup of a type that no
 * field asked for to be answered without a read.
 * </p>
 */
public class GraphQLReferencePlan {

	private static final String USER_DATA_KEY = GraphQLReferencePlan.class.getName() + "_PLAN";
	private static final String RESOURCE_FIELD = "resource";

	private final Map<String, Set<String>> myPathsByRootType = new HashMap<>();
	private final Map<String, Set<String>> myNestedPathsByType = new HashMap<>();
	private final Set<String> myTypedUnion = new HashSet<>();
	private boolean myAllFieldsTyped = true;

	/**
	 * @return The relative element paths (e.g. {@code Appointment.participant.actor}) that the query resolves
	 * references through, for resources of the given type
	 */
	@Nonnull
	public Set<String> getReferencePaths(String theResourceType) {
		Set<String> paths = myPathsByRootType.get(theResourceType);
		return paths != null ? paths : Collections.emptySet();
	}

	/**
	 * @return The reference paths (e.g. {@code Patient.managingOrganization}) that the query follows from resources
	 * it resolved with a single-typed {@code resource(type: X)}, by the type X of those resources
	 */
	@Nonnull
	public Map<String, Set<String>> getNestedReferencePaths() {
		return myNestedPathsByType;
	}

	public boolean hasReferencePaths() {
		return !myPathsByRootType.isEmpty();
	}

	/**
	 * @return True if every {@code resource} field in the query has a plain {@code type} argument, which is
	 * the precondition for answering lookups of un-requested types with an empty stub.
	 */
	public boolean isTypePushdownSafe() {
		return myAllFieldsTyped && !myTypedUnion.isEmpty();
	}

	/**
	 * @return True if some {@code resource(type:...)} field asks for the given type
	 */
	public boolean isTypeRequested(String theResourceType) {
		return myTypedUnion.contains(theResourceType);
	}

	public void storeIn(RequestDetails theRequestDetails) {
		theRequestDetails.getUserData().put(USER_DATA_KEY, this);
	}

	@Nullable
	public static GraphQLReferencePlan getFrom(@Nullable RequestDetails theRequestDetails) {
		if (theRequestDetails == null) {
			return null;
		}
		return (GraphQLReferencePlan) theRequestDetails.getUserData().get(USER_DATA_KEY);
	}

	/**
	 * Analyses the query. Never throws: an analysis that can not be trusted disables the optimisations instead.
	 *
	 * @param theFocusType The resource type of the instance-level focus ({@code /Patient/123/$graphql}), or null
	 */
	@Nonnull
	public static GraphQLReferencePlan build(Package thePackage, @Nullable String theFocusType) {
		GraphQLReferencePlan plan = new GraphQLReferencePlan();
		try {
			Document document = thePackage.getDocument();
			for (Operation operation : document.getOperations()) {
				for (Selection selection : operation.getSelectionSet()) {
					Context context = Context.top(theFocusType);
					plan.walk(document, selection, context, new HashSet<>());
				}
			}
		} catch (RuntimeException e) {
			plan.myAllFieldsTyped = false;
			plan.myPathsByRootType.clear();
			plan.myNestedPathsByType.clear();
		}
		return plan;
	}

	private void walk(Document theDocument, Selection theSelection, Context theContext, Set<String> theFragments) {
		if (theSelection.getInlineFragment() != null) {
			for (Selection child : theSelection.getInlineFragment().getSelectionSet()) {
				walk(theDocument, child, theContext, theFragments);
			}
			return;
		}
		if (theSelection.getFragmentSpread() != null) {
			String name = theSelection.getFragmentSpread().getName();
			Fragment fragment = theDocument.fragment(name);
			if (fragment == null) {
				myAllFieldsTyped = false;
			} else if (theFragments.add(name)) {
				for (Selection child : fragment.getSelectionSet()) {
					walk(theDocument, child, theContext, theFragments);
				}
				theFragments.remove(name);
			}
			return;
		}

		Field field = theSelection.getField();
		if (field == null) {
			return;
		}
		String name = field.getName();

		Context childContext;
		if (theContext.myConnectionType != null && "edges".equals(name)) {
			// {Type}Connection { edges { resource { ... } } }: "resource" here is the edge's resource, not a reference
			childContext = Context.edges(theContext.myConnectionType);
		} else if (theContext.myEdgesType != null && RESOURCE_FIELD.equals(name)) {
			childContext = Context.root(theContext.myEdgesType);
		} else if (RESOURCE_FIELD.equals(name)) {
			recordReferenceField(field, theContext);
			List<String> types = plainTypeArguments(field);
			if (types != null && types.size() == 1) {
				childContext = Context.nested(types.get(0));
			} else {
				// Multiple or no plain types: what's behind a resolved reference has an unknown type
				childContext = Context.unknown();
			}
		} else if (name.endsWith("Connection") && name.length() > "Connection".length()) {
			childContext = Context.connection(name.substring(0, name.length() - "Connection".length()));
		} else if (name.endsWith("List") && name.length() > "List".length()) {
			childContext = Context.root(name.substring(0, name.length() - "List".length()));
		} else if (theContext.myTop && theContext.myRootType == null && Character.isUpperCase(name.charAt(0))) {
			// System-level {Patient(id: 1) {...}}
			childContext = Context.root(name);
		} else {
			childContext = theContext.child(name);
		}

		for (Selection child : field.getSelectionSet()) {
			walk(theDocument, child, childContext, theFragments);
		}
	}

	private void recordReferenceField(Field theField, Context theContext) {
		List<String> types = plainTypeArguments(theField);
		if (types == null) {
			myAllFieldsTyped = false;
		} else {
			myTypedUnion.addAll(types);
		}
		if (theContext.myRootType != null && !theContext.myPath.isEmpty()) {
			String path = theContext.myRootType + "." + String.join(".", theContext.myPath);
			(theContext.myNested ? myNestedPathsByType : myPathsByRootType)
					.computeIfAbsent(theContext.myRootType, k -> new HashSet<>())
					.add(path);
		}
	}

	/**
	 * @return The literal values of the {@code type} argument, or null if there is none or it isn't a plain
	 * literal (e.g. a variable), in which case the field can't be reasoned about statically
	 */
	@Nullable
	private static List<String> plainTypeArguments(Field theField) {
		List<String> retVal = null;
		for (Argument argument : theField.getArguments()) {
			if (!"type".equals(argument.getName())) {
				continue;
			}
			retVal = new ArrayList<>();
			for (Value value : argument.getValues()) {
				if (value instanceof NameValue || value instanceof StringValue) {
					retVal.add(value.getValue());
				} else {
					return null;
				}
			}
		}
		return retVal == null || retVal.isEmpty() ? null : retVal;
	}

	private static final class Context {
		private final boolean myTop;
		private final String myRootType;
		private final List<String> myPath;
		private final String myConnectionType;
		private final String myEdgesType;
		private boolean myNested;

		private Context(
				boolean theTop,
				String theRootType,
				List<String> thePath,
				String theConnectionType,
				String theEdgesType) {
			myTop = theTop;
			myRootType = theRootType;
			myPath = thePath;
			myConnectionType = theConnectionType;
			myEdgesType = theEdgesType;
		}

		static Context top(@Nullable String theFocusType) {
			return new Context(true, theFocusType, Collections.emptyList(), null, null);
		}

		static Context root(String theType) {
			return new Context(false, theType, Collections.emptyList(), null, null);
		}

		/** What's behind a {@code resource(type: X)} field: resources of the known type X, reached through a reference */
		static Context nested(String theType) {
			Context retVal = new Context(false, theType, Collections.emptyList(), null, null);
			retVal.myNested = true;
			return retVal;
		}

		static Context unknown() {
			return new Context(false, null, Collections.emptyList(), null, null);
		}

		static Context connection(String theType) {
			return new Context(false, null, Collections.emptyList(), theType, null);
		}

		static Context edges(String theType) {
			return new Context(false, null, Collections.emptyList(), null, theType);
		}

		Context child(String theName) {
			if (myRootType == null) {
				return new Context(false, null, Collections.emptyList(), null, null);
			}
			List<String> path = new ArrayList<>(myPath);
			path.add(theName);
			Context retVal = new Context(false, myRootType, path, null, null);
			retVal.myNested = myNested;
			return retVal;
		}
	}
}
