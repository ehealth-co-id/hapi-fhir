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
import ca.uhn.fhir.context.RuntimeResourceDefinition;
import ca.uhn.fhir.context.RuntimeSearchParam;
import ca.uhn.fhir.i18n.Msg;
import ca.uhn.fhir.interceptor.model.RequestPartitionId;
import ca.uhn.fhir.jpa.api.dao.DaoRegistry;
import ca.uhn.fhir.jpa.api.dao.IFhirResourceDao;
import ca.uhn.fhir.jpa.api.svc.ISearchCoordinatorSvc;
import ca.uhn.fhir.jpa.partition.IRequestPartitionHelperSvc;
import ca.uhn.fhir.jpa.searchparam.SearchParameterMap;
import ca.uhn.fhir.model.api.IQueryParameterOr;
import ca.uhn.fhir.model.api.Include;
import ca.uhn.fhir.model.api.ResourceMetadataKeyEnum;
import ca.uhn.fhir.model.valueset.BundleEntrySearchModeEnum;
import ca.uhn.fhir.model.valueset.BundleTypeEnum;
import ca.uhn.fhir.rest.api.BundleLinks;
import ca.uhn.fhir.rest.api.CacheControlDirective;
import ca.uhn.fhir.rest.api.Constants;
import ca.uhn.fhir.rest.api.IVersionSpecificBundleFactory;
import ca.uhn.fhir.rest.api.RestSearchParameterTypeEnum;
import ca.uhn.fhir.rest.api.server.IBundleProvider;
import ca.uhn.fhir.rest.api.server.RequestDetails;
import ca.uhn.fhir.rest.param.DateOrListParam;
import ca.uhn.fhir.rest.param.DateParam;
import ca.uhn.fhir.rest.param.NumberOrListParam;
import ca.uhn.fhir.rest.param.NumberParam;
import ca.uhn.fhir.rest.param.QuantityOrListParam;
import ca.uhn.fhir.rest.param.QuantityParam;
import ca.uhn.fhir.rest.param.ReferenceOrListParam;
import ca.uhn.fhir.rest.param.ReferenceParam;
import ca.uhn.fhir.rest.param.SpecialOrListParam;
import ca.uhn.fhir.rest.param.SpecialParam;
import ca.uhn.fhir.rest.param.StringOrListParam;
import ca.uhn.fhir.rest.param.StringParam;
import ca.uhn.fhir.rest.param.TokenOrListParam;
import ca.uhn.fhir.rest.param.TokenParam;
import ca.uhn.fhir.rest.server.IPagingProvider;
import ca.uhn.fhir.rest.server.RestfulServerUtils;
import ca.uhn.fhir.rest.server.exceptions.InvalidRequestException;
import ca.uhn.fhir.rest.server.util.ISearchParamRegistry;
import ca.uhn.fhir.rest.server.util.ResourceSearchParams;
import jakarta.annotation.Nullable;
import org.apache.commons.lang3.Validate;
import org.hl7.fhir.exceptions.FHIRException;
import org.hl7.fhir.instance.model.api.IBaseBundle;
import org.hl7.fhir.instance.model.api.IBaseReference;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.instance.model.api.IIdType;
import org.hl7.fhir.utilities.graphql.Argument;
import org.hl7.fhir.utilities.graphql.IGraphQLStorageServices;
import org.hl7.fhir.utilities.graphql.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import static ca.uhn.fhir.rest.api.Constants.PARAM_COUNT;
import static ca.uhn.fhir.rest.api.Constants.PARAM_FILTER;

public class DaoRegistryGraphQLStorageServices implements IGraphQLStorageServices {

	// the constant hasn't already been defined in org.hl7.fhir.core so we define it here
	static final String SEARCH_ID_PARAM = "search-id";
	static final String SEARCH_OFFSET_PARAM = "search-offset";

	private static final int MAX_SEARCH_SIZE = 500;
	private static final String LOOKUP_CACHE_KEY = DaoRegistryGraphQLStorageServices.class.getName() + "_LOOKUP_CACHE";

	@Autowired
	private FhirContext myContext;

	@Autowired
	private DaoRegistry myDaoRegistry;

	@Autowired
	private ISearchParamRegistry mySearchParamRegistry;

	@Autowired
	protected ISearchCoordinatorSvc mySearchCoordinatorSvc;

	@Autowired
	private IRequestPartitionHelperSvc myPartitionHelperSvc;

	@Autowired
	private IPagingProvider myPagingProvider;

	private IFhirResourceDao<? extends IBaseResource> getDao(String theResourceType) {
		RuntimeResourceDefinition typeDef = myContext.getResourceDefinition(theResourceType);
		return myDaoRegistry.getResourceDaoOrNull(typeDef.getImplementingClass());
	}

	private String graphqlArgumentToSearchParam(String name) {
		if (name.startsWith("_")) {
			return name;
		} else {
			return name.replaceAll("_", "-");
		}
	}

	private String searchParamToGraphqlArgument(String name) {
		return name.replaceAll("-", "_");
	}

	private SearchParameterMap buildSearchParams(String theType, List<Argument> theSearchParams) {
		List<Argument> resourceSearchParam = theSearchParams.stream()
				.filter(it -> !PARAM_COUNT.equals(it.getName()))
				.collect(Collectors.toList());

		FhirContext fhirContext = myContext;
		RuntimeResourceDefinition typeDef = fhirContext.getResourceDefinition(theType);

		SearchParameterMap params = new SearchParameterMap();
		ResourceSearchParams searchParams = mySearchParamRegistry.getRuntimeSearchParams(typeDef.getName());

		for (Argument nextArgument : resourceSearchParam) {

			if (nextArgument.getName().equals(PARAM_FILTER)) {
				String value = nextArgument.getValues().get(0).getValue();
				params.add(PARAM_FILTER, new StringParam(value));
				continue;
			}

			String searchParamName = graphqlArgumentToSearchParam(nextArgument.getName());
			RuntimeSearchParam searchParam = searchParams.get(searchParamName);
			if (searchParam == null) {
				Set<String> graphqlArguments = searchParams.getSearchParamNames().stream()
						.map(this::searchParamToGraphqlArgument)
						.collect(Collectors.toSet());
				String msg = myContext
						.getLocalizer()
						.getMessageSanitized(
								DaoRegistryGraphQLStorageServices.class,
								"invalidGraphqlArgument",
								nextArgument.getName(),
								new TreeSet<>(graphqlArguments));
				throw new InvalidRequestException(Msg.code(1275) + msg);
			}

			IQueryParameterOr<?> queryParam;

			switch (searchParam.getParamType()) {
				case NUMBER:
					NumberOrListParam numberOrListParam = new NumberOrListParam();
					for (Value value : nextArgument.getValues()) {
						numberOrListParam.addOr(new NumberParam(value.getValue()));
					}
					queryParam = numberOrListParam;
					break;
				case DATE:
					DateOrListParam dateOrListParam = new DateOrListParam();
					for (Value value : nextArgument.getValues()) {
						dateOrListParam.addOr(new DateParam(value.getValue()));
					}
					queryParam = dateOrListParam;
					break;
				case STRING:
					StringOrListParam stringOrListParam = new StringOrListParam();
					for (Value value : nextArgument.getValues()) {
						stringOrListParam.addOr(new StringParam(value.getValue()));
					}
					queryParam = stringOrListParam;
					break;
				case TOKEN:
					TokenOrListParam tokenOrListParam = new TokenOrListParam();
					for (Value value : nextArgument.getValues()) {
						TokenParam tokenParam = new TokenParam();
						tokenParam.setValueAsQueryToken(fhirContext, searchParamName, null, value.getValue());
						tokenOrListParam.addOr(tokenParam);
					}
					queryParam = tokenOrListParam;
					break;
				case REFERENCE:
					ReferenceOrListParam referenceOrListParam = new ReferenceOrListParam();
					for (Value value : nextArgument.getValues()) {
						referenceOrListParam.addOr(new ReferenceParam(value.getValue()));
					}
					queryParam = referenceOrListParam;
					break;
				case QUANTITY:
					QuantityOrListParam quantityOrListParam = new QuantityOrListParam();
					for (Value value : nextArgument.getValues()) {
						quantityOrListParam.addOr(new QuantityParam(value.getValue()));
					}
					queryParam = quantityOrListParam;
					break;
				case SPECIAL:
					SpecialOrListParam specialOrListParam = new SpecialOrListParam();
					for (Value value : nextArgument.getValues()) {
						specialOrListParam.addOr(new SpecialParam().setValue(value.getValue()));
					}
					queryParam = specialOrListParam;
					break;
				case COMPOSITE:
				case URI:
				case HAS:
				default:
					throw new InvalidRequestException(Msg.code(1276)
							+ String.format(
									"%s parameters are not yet supported in GraphQL", searchParam.getParamType()));
			}

			params.add(searchParamName, queryParam);
		}

		return params;
	}

	@Transactional(propagation = Propagation.NEVER)
	@Override
	public void listResources(
			Object theAppInfo, String theType, List<Argument> theSearchParams, List<IBaseResource> theMatches)
			throws FHIRException {
		SearchParameterMap params = buildSearchParams(theType, theSearchParams);
		params.setLoadSynchronousUpTo(MAX_SEARCH_SIZE);

		RequestDetails requestDetails = (RequestDetails) theAppInfo;
		// Includes are deliberately not used here: the synchronous search window (0..size) cuts the included
		// resources off, so they never reach the cache and the prefetch ran anyway
		IBundleProvider response = getDao(theType).search(params, requestDetails);
		Integer size = response.size();
		// We set size to null in SearchCoordinatorSvcImpl.executeQuery() if matching results exceeds count
		// so don't throw here
		if ((response.preferredPageSize() != null && size != null && response.preferredPageSize() < size)
				|| size == null) {
			size = response.preferredPageSize();
		}

		Validate.notNull(size, "size is null");
		List<IBaseResource> resources = response.getResources(0, size);
		theMatches.addAll(resources);
		prefetchReferences(requestDetails, resources);
	}

	@Transactional(propagation = Propagation.REQUIRED)
	@Override
	public IBaseResource lookup(Object theAppInfo, String theType, String theId) throws FHIRException {
		IIdType refId = myContext.getVersion().newIdType();
		refId.setValue(theType + "/" + theId);
		return lookup(theAppInfo, refId);
	}

	private IBaseResource lookup(Object theAppInfo, IIdType theRefId) {
		RequestDetails requestDetails = (RequestDetails) theAppInfo;

		// The GraphQL engine resolves a reference before it checks the "type" argument, so the same target
		// is typically looked up once per alias. Resources are cached for the lifetime of this request only
		// (RequestDetails user data), so tenancy, partitioning and security are unaffected. The cache is also
		// where GraphQLReferencePrefetcher puts bulk-loaded resources.
		Map<String, IBaseResource> cache = null;
		String key = null;
		if (requestDetails != null) {
			key = GraphQLReferencePrefetcher.cacheKey(theRefId);
			if (key != null) {
				cache = GraphQLReferencePrefetcher.getCache(requestDetails);
				IBaseResource cached = cache.get(key);
				if (cached != null) {
					return cached;
				}
			}
		}

		IFhirResourceDao<? extends IBaseResource> dao = getDao(theRefId.getResourceType());
		IBaseResource retVal = dao.read(theRefId, requestDetails, false);
		if (cache != null && retVal != null) {
			cache.put(key, retVal);
		}
		return retVal;
	}

	/**
	 * The GraphQL engine looks a reference up before it checks the {@code type} argument of the field and
	 * discards the result when the type doesn't match. If the query is fully typed (every {@code resource}
	 * field has a plain {@code type} argument) and none of them asks for this reference's type, the lookup
	 * can't contribute to the output, so an empty placeholder of the right type is returned instead of
	 * reading the resource. The engine rejects it by type and emits nothing.
	 * <p>
	 * Note that this means a dangling or deleted reference of an un-requested type no longer fails the query.
	 * </p>
	 */
	private IBaseResource newStubIfTypeNotRequested(Object theAppInfo, IIdType theRefId) {
		if (!(theAppInfo instanceof RequestDetails) || !theRefId.hasResourceType() || theRefId.isLocal()) {
			return null;
		}
		GraphQLReferencePlan plan = GraphQLReferencePlan.getFrom((RequestDetails) theAppInfo);
		if (plan == null || !plan.isTypePushdownSafe() || plan.isTypeRequested(theRefId.getResourceType())) {
			return null;
		}
		try {
			IBaseResource stub =
					myContext.getResourceDefinition(theRefId.getResourceType()).newInstance();
			stub.setId(theRefId.toUnqualifiedVersionless());
			return stub;
		} catch (RuntimeException e) {
			// Unknown resource type etc: let the normal path produce its usual error
			return null;
		}
	}

	private boolean myIncludeReferencesEnabled = true;

	/**
	 * If enabled (the default), references that a query resolves with {@code resource(type: X)} are loaded by
	 * adding {@code _include} (and {@code _include:iterate} for references of those resources) to the primary
	 * search of a Connection query, instead of one search per referenced type. This needs a handful of selects
	 * regardless of the number of distinct targets, where the per-type search needs one select per distinct id.
	 * Anything the include does not load is still loaded by the per-type prefetch, then by a plain read.
	 */
	public boolean isIncludeReferencesEnabled() {
		return myIncludeReferencesEnabled;
	}

	public void setIncludeReferencesEnabled(boolean theIncludeReferencesEnabled) {
		myIncludeReferencesEnabled = theIncludeReferencesEnabled;
	}

	private void addReferenceIncludes(String theType, SearchParameterMap theParams, RequestDetails theRequestDetails) {
		if (!myIncludeReferencesEnabled) {
			return;
		}
		GraphQLReferencePlan plan = GraphQLReferencePlan.getFrom(theRequestDetails);
		if (plan == null) {
			return;
		}
		for (String path : plan.getReferencePaths(theType)) {
			RuntimeSearchParam param = findReferenceSearchParam(theType, path);
			if (param != null) {
				theParams.addInclude(new Include(theType + ":" + param.getName()));
			}
		}
		// References of resolved resources (e.g. Patient.managingOrganization behind actor.resource(type: Patient))
		// are followed with _include:iterate, so they don't need a read per distinct target either
		for (Map.Entry<String, Set<String>> nested :
				plan.getNestedReferencePaths().entrySet()) {
			for (String path : nested.getValue()) {
				RuntimeSearchParam param = findReferenceSearchParam(nested.getKey(), path);
				if (param != null) {
					theParams.addInclude(new Include(nested.getKey() + ":" + param.getName(), true));
				}
			}
		}
	}

	/**
	 * Several parameters can share a path (e.g. Coverage.beneficiary: beneficiary and patient). Prefer the one that
	 * covers the fewest paths, so the include does not load more than the query needs, and break ties by name so
	 * that the choice does not depend on map iteration order.
	 *
	 * @return The reference parameter of the type whose path is exactly the given one, or null if there is none, in
	 * which case the references on that path are loaded by the prefetch
	 */
	@Nullable
	private RuntimeSearchParam findReferenceSearchParam(String theType, String thePath) {
		RuntimeSearchParam best = null;
		for (RuntimeSearchParam searchParam :
				mySearchParamRegistry.getActiveSearchParams(theType).values()) {
			if (searchParam.getParamType() != RestSearchParameterTypeEnum.REFERENCE
					|| !searchParam.getPathsSplit().contains(thePath)) {
				continue;
			}
			if (best == null
					|| searchParam.getPathsSplit().size() < best.getPathsSplit().size()
					|| (searchParam.getPathsSplit().size()
									== best.getPathsSplit().size()
							&& searchParam.getName().compareTo(best.getName()) < 0)) {
				best = searchParam;
			}
		}
		return best;
	}

	/**
	 * Included resources come back mixed in with the matches. Move them into the per-request cache and return
	 * only the matches, so they don't show up as results (e.g. as extra connection edges).
	 */
	private List<IBaseResource> moveIncludesToCache(
			RequestDetails theRequestDetails, List<IBaseResource> theResources) {
		if (!myIncludeReferencesEnabled) {
			return theResources;
		}
		List<IBaseResource> matches = new ArrayList<>(theResources.size());
		Map<String, IBaseResource> cache = GraphQLReferencePrefetcher.getCache(theRequestDetails);
		for (IBaseResource resource : theResources) {
			if (ResourceMetadataKeyEnum.ENTRY_SEARCH_MODE.get(resource) == BundleEntrySearchModeEnum.INCLUDE) {
				String key = GraphQLReferencePrefetcher.cacheKey(resource.getIdElement());
				if (key != null && isReadablePartition(theRequestDetails, resource)) {
					cache.put(
							myContext.getResourceType(resource) + "/"
									+ resource.getIdElement().getIdPart(),
							resource);
				}
			} else {
				matches.add(resource);
			}
		}
		return matches;
	}

	/**
	 * An _include crosses partitions (it follows the reference index by row id), whereas a read or the prefetch
	 * search only sees the partition(s) the request may read. A resource from a partition this request can't read
	 * must not enter the per-request cache, otherwise {@code lookup()} would serve it from the cache and skip the
	 * partition check a plain read applies. Not caching it leaves it to the prefetch/read, which behave as on the
	 * default path.
	 */
	private boolean isReadablePartition(RequestDetails theRequestDetails, IBaseResource theResource) {
		Object partition = theResource.getUserData(Constants.RESOURCE_PARTITION_ID);
		if (!(partition instanceof RequestPartitionId) || !((RequestPartitionId) partition).hasPartitionIds()) {
			// partitioning is off (user data is only set when it is on)
			return true;
		}
		IIdType id = theResource.getIdElement().toUnqualifiedVersionless();
		RequestPartitionId readPartition = myPartitionHelperSvc.determineReadPartitionForRequestForRead(
				theRequestDetails, myContext.getResourceType(theResource), id);
		RequestPartitionId resourcePartition = (RequestPartitionId) partition;
		if (readPartition == null || readPartition.isAllPartitions()) {
			return true;
		}
		return resourcePartition.getPartitionIds().stream().allMatch(readPartition::isPartitionCovered);
	}

	private void prefetchReferences(RequestDetails theRequestDetails, List<? extends IBaseResource> theResources) {
		new GraphQLReferencePrefetcher(myContext, myDaoRegistry).prefetch(theRequestDetails, theResources);
	}

	@Transactional(propagation = Propagation.REQUIRED)
	@Override
	public ReferenceResolution lookup(Object theAppInfo, IBaseResource theContext, IBaseReference theReference)
			throws FHIRException {
		IBaseResource stub = newStubIfTypeNotRequested(theAppInfo, theReference.getReferenceElement());
		if (stub != null) {
			return new ReferenceResolution(theContext, stub);
		}
		IBaseResource outcome = lookup(theAppInfo, theReference.getReferenceElement());
		if (outcome == null) {
			return null;
		}
		return new ReferenceResolution(theContext, outcome);
	}

	private Optional<String> getArgument(List<Argument> params, String name) {
		return params.stream()
				.filter(it -> name.equals(it.getName()))
				.map(it -> it.getValues().get(0).getValue())
				.findAny();
	}

	@Transactional(propagation = Propagation.NEVER)
	@Override
	public IBaseBundle search(Object theAppInfo, String theType, List<Argument> theSearchParams) throws FHIRException {
		RequestDetails requestDetails = (RequestDetails) theAppInfo;

		Optional<String> searchIdArgument = getArgument(theSearchParams, SEARCH_ID_PARAM);
		Optional<String> searchOffsetArgument = getArgument(theSearchParams, SEARCH_OFFSET_PARAM);

		String searchId;
		int searchOffset;
		int pageSize;
		IBundleProvider response;

		if (searchIdArgument.isPresent() && searchOffsetArgument.isPresent()) {
			searchId = searchIdArgument.get();
			searchOffset = Integer.parseInt(searchOffsetArgument.get());

			response = Optional.ofNullable(myPagingProvider.retrieveResultList(requestDetails, searchId))
					.orElseThrow(() -> {
						String msg = myContext
								.getLocalizer()
								.getMessageSanitized(
										DaoRegistryGraphQLStorageServices.class,
										"invalidGraphqlCursorArgument",
										searchId);
						return new InvalidRequestException(Msg.code(2076) + msg);
					});

			pageSize =
					Optional.ofNullable(response.preferredPageSize()).orElseGet(myPagingProvider::getDefaultPageSize);
		} else {
			pageSize = getArgument(theSearchParams, "_count")
					.map(Integer::parseInt)
					.orElseGet(myPagingProvider::getDefaultPageSize);

			SearchParameterMap params = buildSearchParams(theType, theSearchParams);
			params.setCount(pageSize);
			addReferenceIncludes(theType, params, requestDetails);

			CacheControlDirective cacheControlDirective = new CacheControlDirective();
			cacheControlDirective.parse(requestDetails.getHeaders(Constants.HEADER_CACHE_CONTROL));

			RequestPartitionId requestPartitionId =
					myPartitionHelperSvc.determineReadPartitionForRequestForSearchType(requestDetails, theType, params);
			response = mySearchCoordinatorSvc.registerSearch(
					getDao(theType), params, theType, cacheControlDirective, requestDetails, requestPartitionId);

			searchOffset = 0;
			searchId = myPagingProvider.storeResultList(requestDetails, response);
		}

		// response.size() may return {@literal null}, in that case use pageSize
		String serverBase = requestDetails.getFhirServerBase();
		Optional<Integer> numTotalResults = Optional.ofNullable(response.size());
		int numToReturn = numTotalResults
				.map(integer -> Math.min(pageSize, integer - searchOffset))
				.orElse(pageSize);

		BundleLinks links = new BundleLinks(
				requestDetails.getServerBaseForRequest(),
				null,
				RestfulServerUtils.prettyPrintResponse(requestDetails.getServer(), requestDetails),
				BundleTypeEnum.SEARCHSET);

		// RestfulServerUtils.createLinkSelf not suitable here
		String linkFormat = "%s/%s?_format=application/json&search-id=%s&search-offset=%d&_count=%d";

		String linkSelf = String.format(linkFormat, serverBase, theType, searchId, searchOffset, pageSize);
		links.setSelf(linkSelf);

		boolean hasNext = numTotalResults
				.map(total -> (searchOffset + numToReturn) < total)
				.orElse(true);

		if (hasNext) {
			String linkNext =
					String.format(linkFormat, serverBase, theType, searchId, searchOffset + numToReturn, pageSize);
			links.setNext(linkNext);
		}

		if (searchOffset > 0) {
			String linkPrev = String.format(
					linkFormat, serverBase, theType, searchId, Math.max(0, searchOffset - pageSize), pageSize);
			links.setPrev(linkPrev);
		}

		List<IBaseResource> resourceList =
				moveIncludesToCache(requestDetails, response.getResources(searchOffset, numToReturn + searchOffset));
		prefetchReferences(requestDetails, resourceList);

		IVersionSpecificBundleFactory bundleFactory = myContext.newBundleFactory();
		bundleFactory.addRootPropertiesToBundle(response.getUuid(), links, response.size(), response.getPublished());
		bundleFactory.addResourcesToBundle(resourceList, BundleTypeEnum.SEARCHSET, serverBase, null, null);

		IBaseResource result = bundleFactory.getResourceBundle();
		return (IBaseBundle) result;
	}
}
