package ca.uhn.fhir.jpa.provider.r4;

import ca.uhn.fhir.jpa.graphql.DaoRegistryGraphQLStorageServices;
import org.hl7.fhir.utilities.graphql.IGraphQLStorageServices;
import org.springframework.test.util.AopTestUtils;

/**
 * Switches the reference-loading strategy of the GraphQL storage services bean (include vs per-type prefetch only).
 */
final class GraphQLIncludeTestUtil {
	private GraphQLIncludeTestUtil() {}

	static void setIncludeReferences(IGraphQLStorageServices theServices, boolean theEnabled) {
		DaoRegistryGraphQLStorageServices target = AopTestUtils.getUltimateTargetObject(theServices);
		target.setIncludeReferencesEnabled(theEnabled);
	}
}
