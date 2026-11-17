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

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.jpa.api.dao.DaoRegistry;
import ca.uhn.fhir.jpa.api.dao.IFhirResourceDao;
import ca.uhn.fhir.jpa.searchparam.SearchParameterMap;
import ca.uhn.fhir.rest.api.server.IBundleProvider;
import ca.uhn.fhir.rest.api.server.RequestDetails;
import ca.uhn.fhir.rest.param.TokenOrListParam;
import ca.uhn.fhir.rest.param.TokenParam;
import ca.uhn.fhir.util.FhirTerser;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import org.hl7.fhir.instance.model.api.IAnyResource;
import org.hl7.fhir.instance.model.api.IBaseReference;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.instance.model.api.IIdType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Bulk-loads the resources a GraphQL query is going to reference (one search per resource type instead of one
 * read per reference per alias) into a per-request cache, which
 * {@link DaoRegistryGraphQLStorageServices#lookup(Object, IBaseResource, IBaseReference)} then serves from.
 * <p>
 * This is purely an optimisation: anything that isn't prefetched (deleted, filtered by an interceptor,
 * dangling, versioned, external) simply falls through to the normal {@code read} and behaves as before.
 * The cache lives in {@link RequestDetails#getUserData()}, so it is never shared across requests,
 * tenants or users.
 * </p>
 */
public class GraphQLReferencePrefetcher {
	private static final Logger ourLog = LoggerFactory.getLogger(GraphQLReferencePrefetcher.class);

	static final int CHUNK_SIZE = 250;
	static final int MAX_PREFETCH = 5000;
	private static final String CACHE_KEY = GraphQLReferencePrefetcher.class.getName() + "_CACHE";

	private final FhirContext myContext;
	private final DaoRegistry myDaoRegistry;

	public GraphQLReferencePrefetcher(FhirContext theContext, DaoRegistry theDaoRegistry) {
		myContext = theContext;
		myDaoRegistry = theDaoRegistry;
	}

	/**
	 * @return The per-request cache of resolved references, never null
	 */
	@Nonnull
	@SuppressWarnings("unchecked")
	static Map<String, IBaseResource> getCache(@Nonnull RequestDetails theRequestDetails) {
		return (Map<String, IBaseResource>)
				theRequestDetails.getUserData().computeIfAbsent(CACHE_KEY, k -> new HashMap<String, IBaseResource>());
	}

	/**
	 * @return The cache key for a reference ({@code Type/id} or {@code Type/id/_history/v}), or null if the
	 * reference must not be cached (no type or id, or it points at another server)
	 */
	@Nullable
	static String cacheKey(@Nonnull IIdType theId) {
		if (!theId.hasResourceType() || !theId.hasIdPart() || theId.hasBaseUrl()) {
			return null;
		}
		return theId.toUnqualified().getValue();
	}

	/**
	 * Loads the references reachable from the given resources via the plan's paths. Never throws.
	 */
	public void prefetch(
			@Nullable RequestDetails theRequestDetails, @Nullable List<? extends IBaseResource> theResources) {
		if (theRequestDetails == null || theResources == null || theResources.isEmpty()) {
			return;
		}
		GraphQLReferencePlan plan = GraphQLReferencePlan.getFrom(theRequestDetails);
		if (plan == null || !plan.hasReferencePaths()) {
			return;
		}
		try {
			doPrefetch(theRequestDetails, plan, theResources);
		} catch (RuntimeException e) {
			// An optimisation must never break the query: the engine will read what's missing
			ourLog.warn("GraphQL reference prefetch failed, falling back to individual reads: {}", e.toString());
		}
	}

	private void doPrefetch(
			RequestDetails theRequestDetails,
			GraphQLReferencePlan thePlan,
			List<? extends IBaseResource> theResources) {
		Map<String, IBaseResource> cache = getCache(theRequestDetails);
		FhirTerser terser = myContext.newTerser();

		Map<String, Set<String>> idsByType = new TreeMap<>();
		int total = 0;
		for (IBaseResource resource : theResources) {
			String resourceType = myContext.getResourceType(resource);
			for (String path : thePlan.getReferencePaths(resourceType)) {
				List<IBaseReference> references;
				try {
					references = terser.getValues(resource, path, IBaseReference.class);
				} catch (RuntimeException e) {
					continue;
				}
				for (IBaseReference reference : references) {
					IIdType id = reference.getReferenceElement();
					if (id == null || !id.hasResourceType() || !id.hasIdPart()) {
						continue;
					}
					if (id.isLocal() || id.hasBaseUrl() || id.hasVersionIdPart()) {
						continue;
					}
					if (thePlan.isTypePushdownSafe() && !thePlan.isTypeRequested(id.getResourceType())) {
						// Lookups of un-requested types are answered without a read, see the storage services
						continue;
					}
					String key = cacheKey(id);
					if (key == null || cache.containsKey(key)) {
						continue;
					}
					if (idsByType
							.computeIfAbsent(id.getResourceType(), k -> new LinkedHashSet<>())
							.add(id.getIdPart())) {
						total++;
					}
				}
			}
			if (total >= MAX_PREFETCH) {
				break;
			}
		}

		for (Map.Entry<String, Set<String>> entry : idsByType.entrySet()) {
			String type = entry.getKey();
			if (!myDaoRegistry.isResourceTypeSupported(type)) {
				continue;
			}
			IFhirResourceDao<? extends IBaseResource> dao = myDaoRegistry.getResourceDao(type);
			List<String> ids = new ArrayList<>(entry.getValue());
			for (int from = 0; from < ids.size(); from += CHUNK_SIZE) {
				List<String> chunk = ids.subList(from, Math.min(ids.size(), from + CHUNK_SIZE));
				loadChunk(theRequestDetails, dao, type, chunk, cache);
			}
		}
	}

	private void loadChunk(
			RequestDetails theRequestDetails,
			IFhirResourceDao<? extends IBaseResource> theDao,
			String theType,
			List<String> theIds,
			Map<String, IBaseResource> theCache) {
		TokenOrListParam idParam = new TokenOrListParam();
		theIds.forEach(id -> idParam.addOr(new TokenParam(id)));
		SearchParameterMap map = new SearchParameterMap();
		map.add(IAnyResource.SP_RES_ID, idParam);
		map.setLoadSynchronousUpTo(theIds.size());

		IBundleProvider results = theDao.search(map, theRequestDetails);
		for (IBaseResource resource : results.getResources(0, theIds.size())) {
			IIdType resourceId = resource.getIdElement();
			if (resourceId != null && resourceId.hasIdPart()) {
				theCache.put(theType + "/" + resourceId.getIdPart(), resource);
			}
		}
	}
}
