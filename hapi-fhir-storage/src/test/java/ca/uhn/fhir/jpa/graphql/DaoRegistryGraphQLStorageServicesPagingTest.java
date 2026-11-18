package ca.uhn.fhir.jpa.graphql;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The next-link decision of the GraphQL Connection paging, without a database. The first case is the production
 * failure: with 507 matches and a page size of 250, the third page (offset 500, 7 matches) must not point further.
 */
class DaoRegistryGraphQLStorageServicesPagingTest {

	@Test
	void totalKnown_lastPageHasNoNext() {
		assertThat(DaoRegistryGraphQLStorageServices.hasNextPage(507, 500, 7, 250)).isFalse();
		assertThat(DaoRegistryGraphQLStorageServices.hasNextPage(507, 250, 250, 250)).isTrue();
		assertThat(DaoRegistryGraphQLStorageServices.hasNextPage(500, 250, 250, 250)).isFalse();
	}

	@Test
	void totalKnown_cursorPastTheEnd_hasNoNext() {
		assertThat(DaoRegistryGraphQLStorageServices.hasNextPage(507, 750, 0, 250)).isFalse();
	}

	@Test
	void totalUnknown_fullPageMeansMoreMayFollow() {
		assertThat(DaoRegistryGraphQLStorageServices.hasNextPage(null, 0, 250, 250)).isTrue();
	}

	@Test
	void totalUnknown_shortPageIsTheLast_theProductionCase() {
		// Before the fix, next was emitted whenever the total was unknown, so this page pointed at offset 750
		assertThat(DaoRegistryGraphQLStorageServices.hasNextPage(null, 500, 7, 250)).isFalse();
		assertThat(DaoRegistryGraphQLStorageServices.hasNextPage(null, 0, 0, 250)).isFalse();
	}
}
