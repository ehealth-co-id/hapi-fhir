package ca.uhn.fhir.jpa.provider.r4;

import static org.junit.jupiter.api.Assertions.assertEquals;
import ca.uhn.fhir.jpa.api.config.JpaStorageSettings;
import ca.uhn.fhir.jpa.provider.BaseResourceProviderR4Test;
import ca.uhn.fhir.rest.api.Constants;
import ca.uhn.fhir.util.FileUtil;
import ca.uhn.fhir.util.TestUtil;
import ca.uhn.fhir.test.utilities.HttpTestResponse;
import ca.uhn.fhir.util.UrlUtil;
import org.hl7.fhir.instance.model.api.IIdType;
import org.hl7.fhir.r4.model.DateType;
import org.hl7.fhir.r4.model.Appointment;
import org.hl7.fhir.r4.model.Coverage;
import org.hl7.fhir.r4.model.Device;
import org.hl7.fhir.r4.model.DeviceUseStatement;
import org.hl7.fhir.r4.model.Encounter;
import org.hl7.fhir.r4.model.Observation;
import org.hl7.fhir.r4.model.Location;
import org.hl7.fhir.r4.model.Patient;
import org.hl7.fhir.r4.model.Practitioner;
import org.hl7.fhir.r4.model.Reference;
import org.intellij.lang.annotations.Language;
import org.hl7.fhir.utilities.graphql.IGraphQLStorageServices;
import org.junit.jupiter.api.AfterEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.nio.charset.StandardCharsets;

import static ca.uhn.fhir.jpa.provider.GraphQLProviderTestUtil.DATA_PREFIX;
import static ca.uhn.fhir.jpa.provider.GraphQLProviderTestUtil.DATA_SUFFIX;
import static org.assertj.core.api.Assertions.assertThat;

@TestMethodOrder(MethodOrderer.MethodName.class)
public class GraphQLR4Test extends BaseResourceProviderR4Test {
	public static final String INTROSPECTION_QUERY = "{\"query\":\"\\n    query IntrospectionQuery {\\n      __schema {\\n        queryType { name }\\n        mutationType { name }\\n        subscriptionType { name }\\n        types {\\n          ...FullType\\n        }\\n        directives {\\n          name\\n          description\\n          locations\\n          args {\\n            ...InputValue\\n          }\\n        }\\n      }\\n    }\\n\\n    fragment FullType on __Type {\\n      kind\\n      name\\n      description\\n      fields(includeDeprecated: true) {\\n        name\\n        description\\n        args {\\n          ...InputValue\\n        }\\n        type {\\n          ...TypeRef\\n        }\\n        isDeprecated\\n        deprecationReason\\n      }\\n      inputFields {\\n        ...InputValue\\n      }\\n      interfaces {\\n        ...TypeRef\\n      }\\n      enumValues(includeDeprecated: true) {\\n        name\\n        description\\n        isDeprecated\\n        deprecationReason\\n      }\\n      possibleTypes {\\n        ...TypeRef\\n      }\\n    }\\n\\n    fragment InputValue on __InputValue {\\n      name\\n      description\\n      type { ...TypeRef }\\n      defaultValue\\n    }\\n\\n    fragment TypeRef on __Type {\\n      kind\\n      name\\n      ofType {\\n        kind\\n        name\\n        ofType {\\n          kind\\n          name\\n          ofType {\\n            kind\\n            name\\n            ofType {\\n              kind\\n              name\\n              ofType {\\n                kind\\n                name\\n                ofType {\\n                  kind\\n                  name\\n                  ofType {\\n                    kind\\n                    name\\n                  }\\n                }\\n              }\\n            }\\n          }\\n        }\\n      }\\n    }\\n  \",\"operationName\":\"IntrospectionQuery\"}";
	private Logger ourLog = LoggerFactory.getLogger(GraphQLR4Test.class);
	private IIdType myPatientId0;

	@Autowired
	private IGraphQLStorageServices myGraphQLServices;

	@AfterEach
	public void restoreStorageSettings() {
		GraphQLIncludeTestUtil.setIncludeReferences(myGraphQLServices, true);
		myStorageSettings.setEnforceReferentialIntegrityOnWrite(new JpaStorageSettings().isEnforceReferentialIntegrityOnWrite());
		myStorageSettings.setMaximumIncludesToLoadPerPage(JpaStorageSettings.DEFAULT_MAXIMUM_INCLUDES_TO_LOAD_PER_PAGE);
	}

	@Test
	public void testAppointmentList_MultiAliasReferenceResolution_QueryCount() throws IOException {
		List<String> patientIds = new ArrayList<>();
		for (int i = 0; i < 3; i++) {
			Patient p = new Patient();
			p.setId("pat" + i);
			myPatientDao.update(p, mySrd);
			patientIds.add("Patient/pat" + i);
		}
		List<String> practitionerIds = new ArrayList<>();
		for (int i = 0; i < 2; i++) {
			Practitioner p = new Practitioner();
			p.setId("prac" + i);
			myPractitionerDao.update(p, mySrd);
			practitionerIds.add("Practitioner/prac" + i);
		}
		List<String> locationIds = new ArrayList<>();
		for (int i = 0; i < 2; i++) {
			Location l = new Location();
			l.setId("loc" + i);
			myLocationDao.update(l, mySrd);
			locationIds.add("Location/loc" + i);
		}
		for (int i = 0; i < 20; i++) {
			Appointment a = new Appointment();
			a.setId("appt" + i);
			a.setStatus(Appointment.AppointmentStatus.BOOKED);
			a.addParticipant().setActor(new Reference(patientIds.get(i % 3)));
			a.addParticipant().setActor(new Reference(practitionerIds.get(i % 2)));
			a.addParticipant().setActor(new Reference(locationIds.get(i % 2)));
			myAppointmentDao.update(a, mySrd);
		}

		String query = "{AppointmentList(_count:250){id participant{actor{"
			+ "patient:resource(type:Patient){id} "
			+ "practitioner:resource(type:Practitioner){id} "
			+ "location:resource(type:Location){id}}}}}";

		myCaptureQueriesListener.clear();
		long start = System.currentTimeMillis();
		String resp = myServer.fhirRequest("/$graphql?query=" + UrlUtil.escapeUrlParam(query))
			.get()
			.assertStatus(200)
			.getBody();
		long elapsed = System.currentTimeMillis() - start;
		int selects = myCaptureQueriesListener.countSelectQueries();
		myCaptureQueriesListener.logSelectQueries(false, false); // TEMP-BREAKDOWN
		ourLog.info("GRAPHQL-QUERYCOUNT selects={} elapsedMs={} respLen={}", selects, elapsed, resp.length());

		// Every appointment's three references must be resolved to the right type, once each
		assertThat(resp).contains("Patient/pat0/").contains("Practitioner/prac1/").contains("Location/loc1/");

		// Before reference prefetching this was ~272 selects (one read per reference per alias), and 23 with
		// only the per-request cache. Now it is one search per type: appointments + patients + practitioners + locations.
		assertThat(selects).as("select count").isLessThanOrEqualTo(30);
	}

	@Test
	public void testAppointmentConnection_MultiAliasReferenceResolution_QueryCount() throws IOException {
		createAppointmentFixture();

		// This is the shape the web UI uses: the connection resolver with a large _count
		String query = "{AppointmentConnection(_count:250){edges{resource{id participant{actor{"
			+ "patient:resource(type:Patient){id} "
			+ "practitioner:resource(type:Practitioner){id} "
			+ "location:resource(type:Location){id}}}}}}}";
		myCaptureQueriesListener.clear();
		long start = System.currentTimeMillis();
		String resp = executeGraphQl(query, 200);
		long elapsed = System.currentTimeMillis() - start;
		int selects = myCaptureQueriesListener.countSelectQueries();
		ourLog.info("GRAPHQL-CONNECTION-QUERYCOUNT selects={} elapsedMs={} respHash={}", selects, elapsed, TestUtil.stripWhitespace(resp).hashCode());

		// All 20 appointments (unlike the List form, which returns the first page only) and every reference resolved by type
		for (int i = 0; i < 20; i++) {
			assertThat(resp).contains("Appointment/appt" + i + "/");
		}
		assertThat(resp).contains("Patient/pat2/").contains("Practitioner/prac1/").contains("Location/loc1/");
		// One search per type (the exact count varies by a couple of selects with cache warmth), instead of one read
		// per reference per alias, which was 544 selects for this query
		assertThat(selects).as("select count").isLessThanOrEqualTo(30);
	}

	@Test
	public void testAppointmentConnection_ManyDistinctTargets_PrefetchVsInclude() throws IOException {
		int count = 40;
		for (int i = 0; i < count; i++) {
			Patient p = new Patient();
			p.setId("mpat" + i);
			myPatientDao.update(p, mySrd);
		}
		for (int i = 0; i < 4; i++) {
			Practitioner pr = new Practitioner();
			pr.setId("mprac" + i);
			myPractitionerDao.update(pr, mySrd);
			Location l = new Location();
			l.setId("mloc" + i);
			myLocationDao.update(l, mySrd);
		}
		for (int i = 0; i < count; i++) {
			Appointment a = new Appointment();
			a.setId("mappt" + i);
			a.setStatus(Appointment.AppointmentStatus.BOOKED);
			a.addParticipant().setActor(new Reference("Patient/mpat" + i));
			a.addParticipant().setActor(new Reference("Practitioner/mprac" + (i % 4)));
			a.addParticipant().setActor(new Reference("Location/mloc" + (i % 4)));
			myAppointmentDao.update(a, mySrd);
		}

		// A different _count for every run, so no run can be answered from a cached search
		String prefetchResp = runConnection(250, false);
		int prefetchSelects1 = lastSelects;
		String includeResp = runConnection(249, true);
		int includeSelects1 = lastSelects;
		runConnection(248, true);
		int includeSelects2 = lastSelects;
		String prefetchResp2 = runConnection(247, false);
		int prefetchSelects2 = lastSelects;
		ourLog.info("GRAPHQL-INCLUDE distinct=40 prefetch={},{} include={},{}", prefetchSelects1, prefetchSelects2, includeSelects1, includeSelects2);
		assertEquals(TestUtil.stripWhitespace(prefetchResp2), TestUtil.stripWhitespace(prefetchResp));

		assertEquals(TestUtil.stripWhitespace(prefetchResp), TestUtil.stripWhitespace(includeResp));
		for (int i = 0; i < count; i++) {
			assertThat(includeResp).contains("Appointment/mappt" + i + "/");
		}
		// The include path needs a handful of selects regardless of the number of distinct targets, the prefetch one
		// select per distinct id. Loose thresholds: the exact counts vary by a couple of selects with cache warmth.
		assertThat(includeSelects1).as("include selects").isLessThanOrEqualTo(12);
		assertThat(includeSelects2).as("include selects").isLessThanOrEqualTo(12);
		assertThat(prefetchSelects1).as("prefetch selects").isGreaterThan(includeSelects1 * 4);
	}

	/**
	 * A reference of a resolved resource (Patient.managingOrganization) is followed with _include:iterate.
	 */
	@Test
	public void testAppointmentConnection_NestedReference_IterateInclude() throws IOException {
		int count = 40;
		for (int i = 0; i < 10; i++) {
			org.hl7.fhir.r4.model.Organization o = new org.hl7.fhir.r4.model.Organization();
			o.setId("norg" + i);
			o.setName("Org " + i);
			myOrganizationDao.update(o, mySrd);
		}
		for (int i = 0; i < count; i++) {
			Patient p = new Patient();
			p.setId("npat" + i);
			p.setManagingOrganization(new Reference("Organization/norg" + (i % 10)));
			myPatientDao.update(p, mySrd);
			Appointment a = new Appointment();
			a.setId("nappt" + i);
			a.setStatus(Appointment.AppointmentStatus.BOOKED);
			a.addParticipant().setActor(new Reference("Patient/npat" + i));
			myAppointmentDao.update(a, mySrd);
		}

		String query = "{AppointmentConnection(_count:%d){edges{resource{id participant{actor{"
			+ "patient:resource(type:Patient){id managingOrganization{org:resource(type:Organization){id name}}}}}}}}}";
		GraphQLIncludeTestUtil.setIncludeReferences(myGraphQLServices, false);
		myCaptureQueriesListener.clear();
		String prefetch = executeGraphQl(String.format(query, 250), 200);
		int prefetchSelects = myCaptureQueriesListener.countSelectQueries();
		GraphQLIncludeTestUtil.setIncludeReferences(myGraphQLServices, true);
		myCaptureQueriesListener.clear();
		String include = executeGraphQl(String.format(query, 249), 200);
		int includeSelects = myCaptureQueriesListener.countSelectQueries();
		ourLog.info("GRAPHQL-ITERATE prefetch={} include={}", prefetchSelects, includeSelects);

		assertEquals(TestUtil.stripWhitespace(prefetch), TestUtil.stripWhitespace(include));
		assertThat(include).contains("Organization/norg9/").contains("Org 9");
		assertThat(includeSelects).as("include selects").isLessThanOrEqualTo(15);
		assertThat(prefetchSelects).as("prefetch selects").isGreaterThan(includeSelects * 4);
	}

	@Test
	public void testAppointmentConnection_SingleTypeRequested_DoesNotReadOtherTypes() throws IOException {
		createAppointmentFixture();

		String query = "{AppointmentConnection(_count:250){edges{resource{id participant{actor{patient:resource(type:Patient){id}}}}}}}";
		myCaptureQueriesListener.clear();
		String resp = executeGraphQl(query, 200);
		int selects = myCaptureQueriesListener.countSelectQueries();

		assertThat(resp).contains("Patient/pat0/").doesNotContain("Practitioner/").doesNotContain("Location/");
		assertThat(selects).as("select count").isLessThanOrEqualTo(12);
	}

	@Test
	public void testAppointmentList_SingleTypeRequested_DoesNotReadOtherTypes() throws IOException {
		createAppointmentFixture();

		// Only Patient is requested: practitioner and location references must neither be read nor appear
		String query = "{AppointmentList(_count:250){id participant{actor{patient:resource(type:Patient){id}}}}}";
		myCaptureQueriesListener.clear();
		String resp = executeGraphQl(query, 200);
		int selects = myCaptureQueriesListener.countSelectQueries();

		assertThat(resp).contains("Patient/pat0/").doesNotContain("Practitioner/").doesNotContain("Location/");
		// appointments + patients only
		assertThat(selects).as("select count").isLessThanOrEqualTo(10);
	}

	@Test
	public void testAppointmentList_DanglingReferenceOfRequestedType_StillFails() throws IOException {
		myStorageSettings.setEnforceReferentialIntegrityOnWrite(false);
		// AppointmentList returns only the first page, so keep the data set small enough to always include the dangling one
		Patient patient = new Patient();
		patient.setId("pat0");
		myPatientDao.update(patient, mySrd);
		Appointment dangling = new Appointment();
		dangling.setId("appt-dangling");
		dangling.setStatus(Appointment.AppointmentStatus.BOOKED);
		dangling.addParticipant().setActor(new Reference("Patient/does-not-exist"));
		myAppointmentDao.update(dangling, mySrd);

		String query = "{AppointmentList(_count:250){id participant{actor{patient:resource(type:Patient){id}}}}}";
		String resp = executeGraphQl(query, -1);
		assertThat(resp).contains("Unable to execute GraphQL Expression");
	}

	@Test
	public void testAppointmentList_DanglingReferenceOfUnrequestedType_IsIgnored() throws IOException {
		myStorageSettings.setEnforceReferentialIntegrityOnWrite(false);
		// AppointmentList returns only the first page, so keep the data set small enough to always include the dangling one
		Patient patient = new Patient();
		patient.setId("pat0");
		myPatientDao.update(patient, mySrd);
		Appointment dangling = new Appointment();
		dangling.setId("appt-dangling");
		dangling.setStatus(Appointment.AppointmentStatus.BOOKED);
		dangling.addParticipant().setActor(new Reference("Location/does-not-exist"));
		myAppointmentDao.update(dangling, mySrd);

		// Only Patient is requested, so the dangling Location can't affect the result and is not read
		String query = "{AppointmentList(_count:250){id participant{actor{patient:resource(type:Patient){id}}}}}";
		String resp = executeGraphQl(query, 200);
		assertThat(resp).contains("Appointment/appt-dangling/");
	}


	/**
	 * With the per-page include limit exceeded (3 includes allowed, 30 distinct targets) the output must still be
	 * identical: whatever the include did not load is loaded by the prefetch fallback.
	 * <p>
	 * Follow-up pages (search_id / search_offset) are not reachable through $graphql over HTTP: those are not
	 * arguments of the generated schema (the engine answers HAPI-1275), so they are not tested here.
	 * </p>
	 */
	@Test
	public void testAppointmentConnection_IncludeLimitExceeded_SameOutputAsPrefetch() throws IOException {
		int count = 30;
		for (int i = 0; i < count; i++) {
			Patient p = new Patient();
			p.setId("ppat" + i);
			myPatientDao.update(p, mySrd);
			Appointment a = new Appointment();
			a.setId("pappt" + i);
			a.setStatus(Appointment.AppointmentStatus.BOOKED);
			a.addParticipant().setActor(new Reference("Patient/ppat" + i));
			myAppointmentDao.update(a, mySrd);
		}

		// different _count per run so no run is answered from a cached search
		String prefetch = runPaged(250, false);
		int prefetchSelects = lastSelects;
		String include = runPaged(249, true);
		int includeSelects = lastSelects;
		myStorageSettings.setMaximumIncludesToLoadPerPage(3);
		String limited = runPaged(248, true);
		int limitedSelects = lastSelects;
		ourLog.info("GRAPHQL-PAGING prefetch={} include={} includeLimit3={}", prefetchSelects, includeSelects, limitedSelects);

		assertEquals(TestUtil.stripWhitespace(prefetch), TestUtil.stripWhitespace(include));
		assertEquals(TestUtil.stripWhitespace(prefetch), TestUtil.stripWhitespace(limited));
		for (int i = 0; i < count; i++) {
			assertThat(limited).contains("Patient/ppat" + i + "/").contains("Appointment/pappt" + i + "/");
		}
		// the limit makes most targets fall back to the prefetch, which costs more selects but stays bounded
		assertThat(limitedSelects).isLessThan(prefetchSelects + 10);
	}

	private String runPaged(int theCount, boolean theInclude) throws IOException {
		String query = "{AppointmentConnection(_count:" + theCount + "){edges{resource{id participant{actor{patient:resource(type:Patient){id}}}}}}}";
		GraphQLIncludeTestUtil.setIncludeReferences(myGraphQLServices, theInclude);
		myCaptureQueriesListener.clear();
		String resp = executeGraphQl(query, 200);
		lastSelects = myCaptureQueriesListener.countSelectQueries();
		return resp;
	}

	/**
	 * Path-to-parameter matching: Encounter.subject / Encounter.participant.individual / Observation.subject
	 * are found through their search parameters, the output is the same as with the prefetch, and the
	 * reference index (HFJ_RES_LINK) is what loads the targets.
	 */
	@Test
	public void testIncludeMatching_EncounterAndObservation_SameOutputAsPrefetch() throws IOException {
		for (int i = 0; i < 3; i++) {
			Patient p = new Patient();
			p.setId("mpat" + i);
			myPatientDao.update(p, mySrd);
			Practitioner pr = new Practitioner();
			pr.setId("mprac" + i);
			myPractitionerDao.update(pr, mySrd);
		}
		Device device = new Device();
		device.setId("mdev0");
		myDeviceDao.update(device, mySrd);
		for (int i = 0; i < 6; i++) {
			Encounter e = new Encounter();
			e.setId("menc" + i);
			e.setSubject(new Reference("Patient/mpat" + (i % 3)));
			e.addParticipant().setIndividual(new Reference("Practitioner/mprac" + (i % 3)));
			myEncounterDao.update(e, mySrd);
			Observation o = new Observation();
			o.setId("mobs" + i);
			// a subject that is not a Patient: the "patient" parameter (subject.where(resolve() is Patient)) must not be what is used
			o.setSubject(new Reference(i == 5 ? "Device/mdev0" : "Patient/mpat" + (i % 3)));
			myObservationDao.update(o, mySrd);
		}

		String encounterQuery = "{EncounterConnection(_count:%d){edges{resource{id "
			+ "subject{patient:resource(type:Patient){id} other:resource(type:Practitioner){id}} "
			+ "participant{individual{practitioner:resource(type:Practitioner){id} patient:resource(type:Patient){id}}}}}}}";
		String observationQuery = "{ObservationConnection(_count:%d){edges{resource{id "
			+ "subject{patient:resource(type:Patient){id} device:resource(type:Device){id}}}}}}";

		String encPrefetch = runQuery(String.format(encounterQuery, 100), false);
		assertThat(lastLinkSelects).isZero();
		String encInclude = runQuery(String.format(encounterQuery, 99), true);
		assertThat(lastLinkSelects).as("include path used for Encounter").isPositive();
		assertEquals(TestUtil.stripWhitespace(encPrefetch), TestUtil.stripWhitespace(encInclude));
		assertThat(encInclude).contains("Patient/mpat2/").contains("Practitioner/mprac2/");

		String obsPrefetch = runQuery(String.format(observationQuery, 100), false);
		String obsInclude = runQuery(String.format(observationQuery, 99), true);
		assertThat(lastLinkSelects).as("include path used for Observation").isPositive();
		assertEquals(TestUtil.stripWhitespace(obsPrefetch), TestUtil.stripWhitespace(obsInclude));
		assertThat(obsInclude).contains("Patient/mpat2/").contains("Device/mdev0/");
	}

	/**
	 * Coverage.beneficiary has two parameters on exactly the same path (beneficiary and patient), and
	 * DeviceUseStatement.subject has one single-path parameter and one that is defined with 32 paths. Whichever is
	 * picked, the output must be identical to the prefetch.
	 */
	@Test
	public void testIncludeMatching_SeveralParametersOnOnePath_SameOutputAsPrefetch() throws IOException {
		for (int i = 0; i < 3; i++) {
			Patient p = new Patient();
			p.setId("spat" + i);
			myPatientDao.update(p, mySrd);
		}
		for (int i = 0; i < 6; i++) {
			Coverage c = new Coverage();
			c.setId("scov" + i);
			c.setBeneficiary(new Reference("Patient/spat" + (i % 3)));
			myCoverageDao.update(c, mySrd);
			DeviceUseStatement d = new DeviceUseStatement();
			d.setId("sdus" + i);
			d.setSubject(new Reference("Patient/spat" + (i % 3)));
			myDaoRegistry.getResourceDao("DeviceUseStatement").update(d, mySrd);
		}

		for (String type : new String[]{"Coverage", "DeviceUseStatement"}) {
			String field = type.equals("Coverage") ? "beneficiary" : "subject";
			String query = "{" + type + "Connection(_count:%d){edges{resource{id " + field + "{patient:resource(type:Patient){id}}}}}}";
			String prefetch = runQuery(String.format(query, 100), false);
			String include = runQuery(String.format(query, 99), true);
			assertThat(lastLinkSelects).as("include path used for " + type).isPositive();
			ourLog.info("GRAPHQL-LINKSQL {} {}", type, lastLinkSql);
			// the single-path parameter is the one used, not the 32-path "patient" parameter of DeviceUseStatement
			assertThat(lastLinkSql).doesNotContain("DeviceUseStatement.subject.where").doesNotContain("Account.subject");
			assertEquals(TestUtil.stripWhitespace(prefetch), TestUtil.stripWhitespace(include));
			assertThat(include).contains("Patient/spat2/");
		}
	}

	/**
	 * Encounter.hospitalization.origin has no search parameter, so no include can be built for it: it is resolved
	 * by the prefetch alone, with the same output and no use of the reference index.
	 */
	@Test
	public void testIncludeMatching_PathWithoutParameter_FallsBackToPrefetch() throws IOException {
		for (int i = 0; i < 3; i++) {
			Location l = new Location();
			l.setId("floc" + i);
			myLocationDao.update(l, mySrd);
		}
		for (int i = 0; i < 6; i++) {
			Encounter e = new Encounter();
			e.setId("fenc" + i);
			e.getHospitalization().setOrigin(new Reference("Location/floc" + (i % 3)));
			myEncounterDao.update(e, mySrd);
		}

		String query = "{EncounterConnection(_count:%d){edges{resource{id hospitalization{origin{loc:resource(type:Location){id}}}}}}}";
		String prefetch = runQuery(String.format(query, 100), false);
		int prefetchSelects = lastSelects;
		String include = runQuery(String.format(query, 99), true);
		assertThat(lastLinkSelects).as("no include query without a parameter").isZero();
		assertEquals(TestUtil.stripWhitespace(prefetch), TestUtil.stripWhitespace(include));
		assertThat(include).contains("Location/floc0/").contains("Location/floc2/");
		assertThat(lastSelects).isLessThanOrEqualTo(prefetchSelects + 2);

		// mixed: one path with a parameter (subject) and one without (hospitalization.origin) in the same query
		Patient p = new Patient();
		p.setId("fpat0");
		myPatientDao.update(p, mySrd);
		Encounter e = new Encounter();
		e.setId("fenc-mixed");
		e.setSubject(new Reference("Patient/fpat0"));
		e.getHospitalization().setOrigin(new Reference("Location/floc1"));
		myEncounterDao.update(e, mySrd);
		String mixed = "{EncounterConnection(_count:%d){edges{resource{id subject{pat:resource(type:Patient){id}} "
			+ "hospitalization{origin{loc:resource(type:Location){id}}}}}}}";
		String mixedPrefetch = runQuery(String.format(mixed, 98), false);
		String mixedInclude = runQuery(String.format(mixed, 97), true);
		assertThat(lastLinkSelects).isPositive();
		assertEquals(TestUtil.stripWhitespace(mixedPrefetch), TestUtil.stripWhitespace(mixedInclude));
		assertThat(mixedInclude).contains("Patient/fpat0/").contains("Location/floc1/");
	}

	/**
	 * The query only asks for Patients, but the actor references also point to Practitioners and Locations. The
	 * output must not change, and un-requested types must come back as empty stubs as in the prefetch path.
	 */
	@Test
	public void testIncludeMatching_QueryAsksForFewerTypes_SameOutputAsPrefetch() throws IOException {
		createAppointmentFixture();
		String query = "{AppointmentConnection(_count:%d){edges{resource{id participant{actor{patient:resource(type:Patient){id}}}}}}}";
		String prefetch = runQuery(String.format(query, 100), false);
		int prefetchSelects = lastSelects;
		String include = runQuery(String.format(query, 99), true);
		int includeSelects = lastSelects;
		ourLog.info("GRAPHQL-FEWERTYPES prefetch={} include={} linkSelects={}", prefetchSelects, includeSelects, lastLinkSelects);
		assertThat(lastLinkSelects).isPositive();
		assertEquals(TestUtil.stripWhitespace(prefetch), TestUtil.stripWhitespace(include));
		assertThat(include).contains("Patient/pat2/").doesNotContain("Practitioner/prac").doesNotContain("Location/loc");
		assertThat(includeSelects).isLessThanOrEqualTo(prefetchSelects + 2);
	}

	private int lastLinkSelects;
	private String lastLinkSql;

	private String runQuery(String theQuery, boolean theInclude) throws IOException {
		GraphQLIncludeTestUtil.setIncludeReferences(myGraphQLServices, theInclude);
		{
			myCaptureQueriesListener.clear();
			String resp = executeGraphQl(theQuery, 200);
			lastSelects = myCaptureQueriesListener.countSelectQueries();
			List<String> linkSql = myCaptureQueriesListener.getSelectQueries().stream()
				.map(q -> q.getSql(true, false))
				.filter(sql -> sql.toLowerCase().contains("hfj_res_link"))
				.toList();
			lastLinkSelects = linkSql.size();
			lastLinkSql = String.join("\n", linkSql);
			return resp;
		}
	}

	/**
	 * Follows the {@code next} cursor of a Connection page by page ({@code cursor: "searchId:offset"}). The last page
	 * must not point to a further page, and a cursor beyond the end of the results must not fail the query.
	 */
	@Test
	public void testPatientConnection_FollowNextCursor_ToTheEnd() throws IOException {
		int total = 507;
		for (int i = 0; i < total; i++) {
			Patient p = new Patient();
			p.setId("cpat" + i);
			myPatientDao.update(p, mySrd);
		}

		java.util.Set<String> ids = new java.util.HashSet<>();
		String cursor = null;
		String lastCursor = null;
		int pages = 0;
		do {
			String args = cursor == null ? "_count:250" : "_count:250 cursor:\"" + cursor + "\"";
			String resp = executeGraphQl("{PatientConnection(" + args + "){next edges{resource{id}}}}", 200);
			java.util.regex.Matcher id = java.util.regex.Pattern.compile("Patient/(cpat\\d+)/").matcher(resp);
			while (id.find()) {
				ids.add(id.group(1));
			}
			java.util.regex.Matcher next = java.util.regex.Pattern.compile("\"next\":\"([^\"]+)\"").matcher(resp);
			lastCursor = cursor;
			cursor = next.find() ? next.group(1) : null;
			pages++;
			ourLog.info("GRAPHQL-CURSOR page={} next={} ids={}", pages, cursor, ids.size());
		} while (cursor != null && pages < 10);

		assertThat(ids).hasSize(total);
		assertThat(cursor).as("the last page has no next cursor").isNull();
		assertThat(pages).isEqualTo(3);

		// A cursor past the end (e.g. one followed after the total became known) yields an empty page, not an error
		String searchId = lastCursor.substring(0, lastCursor.indexOf(':'));
		String beyond = executeGraphQl("{PatientConnection(_count:250 cursor:\"" + searchId + ":750\"){next edges{resource{id}}}}", 200);
		assertThat(beyond).doesNotContain("cpat").doesNotContain("\"next\"");
	}

	/**
	 * A bad cursor offset or page size is a client error (400), not a failure (500), and a page size below 1 must not
	 * produce a next cursor that never advances.
	 */
	@Test
	public void testPatientConnection_InvalidCursorOffsetOrCount_Is400() throws IOException {
		for (int i = 0; i < 3; i++) {
			Patient p = new Patient();
			p.setId("bpat" + i);
			myPatientDao.update(p, mySrd);
		}
		String first = executeGraphQl("{PatientConnection(_count:2){next edges{resource{id}}}}", 200);
		java.util.regex.Matcher m = java.util.regex.Pattern.compile("\"next\":\"([^:\"]+):2\"").matcher(first);
		assertThat(m.find()).isTrue();
		String searchId = m.group(1);

		String negative = executeGraphQl("{PatientConnection(_count:2 cursor:\"" + searchId + ":-5\"){next edges{resource{id}}}}", 400);
		assertThat(negative).contains("search-offset").contains("at least 0");
		String nonNumeric = executeGraphQl("{PatientConnection(_count:2 cursor:\"" + searchId + ":abc\"){next edges{resource{id}}}}", 400);
		assertThat(nonNumeric).contains("search-offset").contains("must be an integer");

		assertThat(executeGraphQl("{PatientConnection(_count:0){next edges{resource{id}}}}", 400)).contains("_count").contains("at least 1");
		assertThat(executeGraphQl("{PatientConnection(_count:-1){next edges{resource{id}}}}", 400)).contains("_count").contains("at least 1");
	}

	private int lastSelects;

	private String runConnection(int theCount, boolean theInclude) throws IOException {
		String query = "{AppointmentConnection(_count:" + theCount + "){edges{resource{id participant{actor{"
			+ "patient:resource(type:Patient){id} "
			+ "practitioner:resource(type:Practitioner){id} "
			+ "location:resource(type:Location){id}}}}}}}";
		GraphQLIncludeTestUtil.setIncludeReferences(myGraphQLServices, theInclude);
		{
			myCaptureQueriesListener.clear();
			String resp = executeGraphQl(query, 200);
			lastSelects = myCaptureQueriesListener.countSelectQueries();
			return resp;
		}
	}

	private String executeGraphQl(String theQuery, int theExpectedStatus) throws IOException {
		HttpTestResponse response = myServer.fhirRequest("/$graphql?query=" + UrlUtil.escapeUrlParam(theQuery)).get();
		String resp = response.getBody();
		if (theExpectedStatus > 0) {
			assertEquals(theExpectedStatus, response.getStatusCode(), resp);
		} else {
			assertThat(response.getStatusCode()).isNotEqualTo(200);
		}
		return resp;
	}

	private void createAppointmentFixture() {
		for (int i = 0; i < 3; i++) {
			Patient p = new Patient();
			p.setId("pat" + i);
			myPatientDao.update(p, mySrd);
		}
		for (int i = 0; i < 2; i++) {
			Practitioner p = new Practitioner();
			p.setId("prac" + i);
			myPractitionerDao.update(p, mySrd);
			Location l = new Location();
			l.setId("loc" + i);
			myLocationDao.update(l, mySrd);
		}
		for (int i = 0; i < 20; i++) {
			Appointment a = new Appointment();
			a.setId("appt" + i);
			a.setStatus(Appointment.AppointmentStatus.BOOKED);
			a.addParticipant().setActor(new Reference("Patient/pat" + (i % 3)));
			a.addParticipant().setActor(new Reference("Practitioner/prac" + (i % 2)));
			a.addParticipant().setActor(new Reference("Location/loc" + (i % 2)));
			myAppointmentDao.update(a, mySrd);
		}
	}

	@Test
	public void testInstance_Read_Patient() throws IOException {
		initTestPatients();

		String query = "{name{family,given}}";

		String resp = myServer.fhirRequest("/Patient/" + myPatientId0.getIdPart() + "/$graphql?query=" + UrlUtil.escapeUrlParam(query))
			.get()
			.getBody();
		@Language("json")
		String expected = """
			{
			  "name":[{
			    "family":"FAM",
			    "given":["GIVEN1","GIVEN2"]
			  },{
			    "given":["GivenOnly1","GivenOnly2"]
			  }]
			}""";
		assertThat(TestUtil.stripWhitespace(resp)).isEqualTo(TestUtil.stripWhitespace(DATA_PREFIX + expected + DATA_SUFFIX));

	}

	@Test
	public void testInstance_Patient_Birthdate() throws IOException {
		initTestPatients();

		String query = "{birthDate}";

		String resp = myServer.fhirRequest("/Patient/" + myPatientId0.getIdPart() + "/$graphql?query=" + UrlUtil.escapeUrlParam(query))
			.get()
			.getBody();
		@Language("json")
		String expected = """
            {
		    "birthDate": "1965-08-09"
			}""";
		assertThat(TestUtil.stripWhitespace(resp)).isEqualTo(TestUtil.stripWhitespace(DATA_PREFIX + expected + DATA_SUFFIX));

	}

	@Test
	public void testType_Introspect_Patient() throws IOException {
		initTestPatients();

		// Repeat a couple of times to make sure it doesn't fail after the first one. At one point
		// the generator polluted the structure userdata and failed the second time
		for (int i = 0; i < 3; i++) {
			String resp = myServer.fhirRequest("/Patient/$graphql")
				.post(INTROSPECTION_QUERY, Constants.CT_JSON)
				.assertStatus(200)
				.getBody();
			assertThat(resp).contains("{\"kind\":\"OBJECT\",\"name\":\"Patient\",");
			assertThat(resp).doesNotContain("{\"kind\":\"OBJECT\",\"name\":\"Observation\",");
			assertThat(resp).doesNotContain("\"name\":\"Observation\",\"args\":[{\"name\":\"id\"");
			assertThat(resp).doesNotContain("\"name\":\"ObservationList\",\"args\":[{\"name\":\"_filter\"");
			assertThat(resp).doesNotContain("\"name\":\"ObservationConnection\",\"fields\":[{\"name\":\"count\"");
			assertThat(resp).contains("\"name\":\"Patient\",\"args\":[{\"name\":\"id\"");
			assertThat(resp).contains("\"name\":\"PatientList\",\"args\":[{\"name\":\"_filter\"");
		}
	}

	@Test
	public void testType_Introspect_Observation() throws IOException {
		initTestPatients();

		// Repeat a couple of times to make sure it doesn't fail after the first one. At one point
		// the generator polluted the structure userdata and failed the second time
		for (int i = 0; i < 3; i++) {
			String resp = myServer.fhirRequest("/Observation/$graphql")
				.post(INTROSPECTION_QUERY, Constants.CT_JSON)
				.assertStatus(200)
				.getBody();
			assertThat(resp).doesNotContain("{\"kind\":\"OBJECT\",\"name\":\"Patient\",");
			assertThat(resp).contains("{\"kind\":\"OBJECT\",\"name\":\"Observation\",");
			assertThat(resp).doesNotContain("{\"kind\":\"OBJECT\",\"name\":\"Query\",\"fields\":[{\"name\":\"PatientList\"");
			assertThat(resp).contains("\"name\":\"Observation\",\"args\":[{\"name\":\"id\"");
			assertThat(resp).contains("\"name\":\"ObservationList\",\"args\":[{\"name\":\"_filter\"");
			assertThat(resp).contains("\"name\":\"ObservationConnection\",\"fields\":[{\"name\":\"count\"");
			assertThat(resp).doesNotContain("\"name\":\"Patient\",\"args\":[{\"name\":\"id\"");
			assertThat(resp).doesNotContain("\"name\":\"PatientList\",\"args\":[{\"name\":\"_filter\"");
		}
	}

	@Test
	public void testRoot_Introspect() throws IOException {
		initTestPatients();

		// Repeat a couple of times to make sure it doesn't fail after the first one. At one point
		// the generator polluted the structure userdata and failed the second time
		for (int i = 0; i < 3; i++) {
			String resp = myServer.fhirRequest("/$graphql")
				.post(INTROSPECTION_QUERY, Constants.CT_JSON)
				.assertStatus(200)
				.getBody();
			ourLog.info("Response has size: {}", FileUtil.formatFileSize(resp.length()));
			assertThat(resp).contains("{\"kind\":\"OBJECT\",\"name\":\"Patient\",");
			assertThat(resp).contains("{\"kind\":\"OBJECT\",\"name\":\"Observation\",");
			assertThat(resp).contains("\"name\":\"Observation\",\"args\":[{\"name\":\"id\"");
			assertThat(resp).contains("\"name\":\"ObservationList\",\"args\":[{\"name\":\"_filter\"");
			assertThat(resp).contains("\"name\":\"ObservationConnection\",\"fields\":[{\"name\":\"count\"");
			assertThat(resp).contains("\"name\":\"Patient\",\"args\":[{\"name\":\"id\"");
			assertThat(resp).contains("\"name\":\"PatientList\",\"args\":[{\"name\":\"_filter\"");
		}
	}

	@Test
	public void testRoot_Read_Patient() throws IOException {
		initTestPatients();

		String query = "{Patient(id:\"" + myPatientId0.getIdPart() + "\"){name{family,given}}}";

		String resp = myServer.fhirRequest("/$graphql?query=" + UrlUtil.escapeUrlParam(query)).get().getBody();

		@Language("json")
		String expected = """
			{
			"Patient":{
			"name":[{
			"family":"FAM",
			"given":["GIVEN1","GIVEN2"]
			},{
			"given":["GivenOnly1","GivenOnly2"]
			}]
			}
			}""";
		assertThat(TestUtil.stripWhitespace(resp)).isEqualTo(TestUtil.stripWhitespace(DATA_PREFIX +
			expected +
			DATA_SUFFIX));

	}


	@Test
	public void testRoot_Search_Patient() throws IOException {
		initTestPatients();

		String query = "{PatientList(given:\"given\"){name{family,given}}}";

		String resp = myServer.fhirRequest("/$graphql?query=" + UrlUtil.escapeUrlParam(query)).get().getBody();
		@Language("json")
		String expected = """
			{
			  "PatientList":[{
			    "name":[{
			      "family":"FAM",
			      "given":["GIVEN1","GIVEN2"]
			    },{
			      "given":["GivenOnly1","GivenOnly2"]
			    }]
			  },{
			    "name":[{
			      "given":["GivenOnlyB1","GivenOnlyB2"]
			    }]
			  }]
			}""";
		assertThat(TestUtil.stripWhitespace(resp)).isEqualTo(TestUtil.stripWhitespace(DATA_PREFIX + expected + DATA_SUFFIX));

	}

	@Test
	public void testRoot_Search_Observation() throws IOException {
		initTestPatients();

		String query = "{ObservationList(date: \"2022\") {id}}";

		myCaptureQueriesListener.clear();
		myServer.fhirRequest("/$graphql?query=" + UrlUtil.escapeUrlParam(query)).get();
		myCaptureQueriesListener.logSelectQueries();
	}

	@Test
	public void testId_Search_Patient() throws IOException {
		initTestPatients();

		String query = "{PatientList(_id: " + myPatientId0.getIdPart() + ") {id}}";

		String resp = myServer.fhirRequest("/$graphql?query=" + UrlUtil.escapeUrlParam(query)).get().getBody();

		@Language("json")
		String expected = """
			{
			"PatientList":[{
			"id":" """ + myPatientId0 + """
			/_history/1"
			}]
			}""";
		assertThat(TestUtil.stripWhitespace(resp)).isEqualTo(TestUtil.stripWhitespace(DATA_PREFIX +
			expected +
			DATA_SUFFIX));
	}

	private void initTestPatients() {
		Patient p = new Patient();
		p.addName()
			.setFamily("FAM")
			.addGiven("GIVEN1")
			.addGiven("GIVEN2");
		p.addName()
			.addGiven("GivenOnly1")
			.addGiven("GivenOnly2");
		p.setBirthDateElement(new DateType("1965-08-09"));
		myPatientId0 = myClient.create().resource(p).execute().getId().toUnqualifiedVersionless();

		p = new Patient();
		p.addName()
			.addGiven("GivenOnlyB1")
			.addGiven("GivenOnlyB2");
		myClient.create().resource(p).execute();
	}


}
