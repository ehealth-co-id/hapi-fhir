package ca.uhn.fhir.jpa.provider.r4;

import ca.uhn.fhir.interceptor.api.Hook;
import ca.uhn.fhir.interceptor.api.Pointcut;
import ca.uhn.fhir.jpa.model.config.PartitionSettings;
import ca.uhn.fhir.rest.api.server.IPreResourceShowDetails;
import ca.uhn.fhir.rest.server.interceptor.auth.RuleBuilder;
import ca.uhn.fhir.util.TestUtil;
import ca.uhn.fhir.util.UrlUtil;
import org.apache.commons.io.IOUtils;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.hl7.fhir.r4.model.Appointment;
import org.hl7.fhir.r4.model.Location;
import org.hl7.fhir.r4.model.Patient;
import org.hl7.fhir.r4.model.Practitioner;
import org.hl7.fhir.r4.model.Reference;
import org.hl7.fhir.utilities.graphql.IGraphQLStorageServices;
import org.junit.jupiter.api.AfterEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Compares the GraphQL Connection reference resolution with the _include path
 * ({@code DaoRegistryGraphQLStorageServices#setIncludeReferencesEnabled(true)}, the default) against the default (prefetch) path when
 * an authorization-style interceptor hides/denies a referenced type, and under partitioning.
 * Each scenario must give the identical HTTP status and body on both paths.
 */
public class GraphQLSecurityTenancyR4Test extends BaseMultitenantResourceProviderR4Test {
	private static final Logger ourLog = LoggerFactory.getLogger(GraphQLSecurityTenancyR4Test.class);
	@Autowired
	private IGraphQLStorageServices myGraphQLServices;

	private static final String ALL_THREE = "{AppointmentConnection(_count:%d){edges{resource{id participant{actor{"
		+ "patient:resource(type:Patient){id} "
		+ "practitioner:resource(type:Practitioner){id} "
		+ "location:resource(type:Location){id}}}}}}}";
	private static final String PRACTITIONER_ONLY = "{AppointmentConnection(_count:%d){edges{resource{id participant{actor{"
		+ "practitioner:resource(type:Practitioner){id}}}}}}}";

	private int myCountSeed = 200;
	private Object myHider;

	@AfterEach
	public void cleanup() {
		GraphQLIncludeTestUtil.setIncludeReferences(myGraphQLServices, true);
		myPartitionSettings.setAllowReferencesAcrossPartitions(PartitionSettings.CrossPartitionReferenceMode.NOT_ALLOWED);
		if (myHider != null) {
			myInterceptorRegistry.unregisterInterceptor(myHider);
			myHider = null;
		}
	}

	private record Result(int status, String body) {
		String normalized() {
			return TestUtil.stripWhitespace(body);
		}
	}

	private Result run(String theTenant, String theQueryTemplate, boolean theInclude) throws IOException {
		// distinct _count per run so HAPI's search cache can't serve the second run
		String query = String.format(theQueryTemplate, myCountSeed--);
		GraphQLIncludeTestUtil.setIncludeReferences(myGraphQLServices, theInclude);
		HttpGet get = new HttpGet(myServerBase + "/" + theTenant + "/$graphql?query=" + UrlUtil.escapeUrlParam(query));
		try (CloseableHttpResponse response = ourHttpClient.execute(get)) {
			String body = IOUtils.toString(response.getEntity().getContent(), StandardCharsets.UTF_8);
			return new Result(response.getStatusLine().getStatusCode(), body);
		}
	}

	/** Runs prefetch, include, prefetch (mixed order to expose cache effects) and asserts all equal. */
	private Result assertIdentical(String theTenant, String theQuery) throws IOException {
		Result prefetch = run(theTenant, theQuery, false);
		Result include = run(theTenant, theQuery, true);
		Result prefetch2 = run(theTenant, theQuery, false);
		ourLog.info("GRAPHQL-SEC prefetch status={} body={}", prefetch.status(), prefetch.normalized());
		ourLog.info("GRAPHQL-SEC include  status={} body={}", include.status(), include.normalized());
		assertThat(prefetch2.status()).isEqualTo(prefetch.status());
		assertThat(include.status()).as("include status; body=" + include.body()).isEqualTo(prefetch.status());
		assertThat(include.normalized()).as("include body").isEqualTo(prefetch.normalized());
		return prefetch;
	}

	private void allowAllButPatientReads() {
		setupAuthorizationInterceptorWithRules(() -> new RuleBuilder()
			.allow().operation().withAnyName().atAnyLevel().andAllowAllResponses().andThen()
			.allow().read().resourcesOfType(Appointment.class).withAnyId().andThen()
			.allow().read().resourcesOfType(Practitioner.class).withAnyId().andThen()
			.allow().read().resourcesOfType(Location.class).withAnyId().andThen()
			.denyAll()
			.build());
	}

	private void createFixture(String theTenant, String thePatientRef) {
		myTenantClientInterceptor.setTenantId(theTenant);
		Practitioner pr = new Practitioner();
		pr.setId("prac0");
		myClient.update().resource(pr).execute();
		Location l = new Location();
		l.setId("loc0");
		myClient.update().resource(l).execute();
		for (int i = 0; i < 3; i++) {
			Appointment a = new Appointment();
			a.setId("appt" + i);
			a.setStatus(Appointment.AppointmentStatus.BOOKED);
			a.addParticipant().setActor(new Reference(thePatientRef));
			a.addParticipant().setActor(new Reference("Practitioner/prac0"));
			a.addParticipant().setActor(new Reference("Location/loc0"));
			myClient.update().resource(a).execute();
		}
	}

	private String createPatientIn(String theTenant, String theId) {
		myTenantClientInterceptor.setTenantId(theTenant);
		Patient p = new Patient();
		p.setId(theId);
		myClient.update().resource(p).execute();
		return "Patient/" + theId;
	}

	@Test
	public void testAuthorizationDeniesPatient_AllTypesRequested() throws IOException {
		createFixture(TENANT_A, createPatientIn(TENANT_A, "pat0"));
		allowAllButPatientReads();
		Result r = assertIdentical(TENANT_A, ALL_THREE);
		ourLog.info("GRAPHQL-SEC denied-all-requested status={}", r.status());
	}

	@Test
	public void testAuthorizationDeniesPatient_PatientNotRequested() throws IOException {
		createFixture(TENANT_A, createPatientIn(TENANT_A, "pat0"));
		allowAllButPatientReads();
		Result r = assertIdentical(TENANT_A, PRACTITIONER_ONLY);
		ourLog.info("GRAPHQL-SEC denied-not-requested status={}", r.status());
	}

	@Test
	public void testPreShowHidesPatient() throws IOException {
		createFixture(TENANT_A, createPatientIn(TENANT_A, "pat0"));
		myHider = new Object() {
			@Hook(Pointcut.STORAGE_PRESHOW_RESOURCES)
			public void hide(IPreResourceShowDetails theDetails) {
				for (int i = 0; i < theDetails.size(); i++) {
					if (theDetails.getResource(i) instanceof Patient) {
						theDetails.setResource(i, null);
					}
				}
			}
		};
		myInterceptorRegistry.registerInterceptor(myHider);
		assertIdentical(TENANT_A, ALL_THREE);
		assertIdentical(TENANT_A, PRACTITIONER_ONLY);
	}

	@Test
	public void testCrossPartitionReference_NoAuth() throws IOException {
		myPartitionSettings.setAllowReferencesAcrossPartitions(PartitionSettings.CrossPartitionReferenceMode.ALLOWED_UNQUALIFIED);
		String patientInB = createPatientIn(TENANT_B, "patb");
		createFixture(TENANT_A, patientInB);
		Result r = assertIdentical(TENANT_A, ALL_THREE);
		// the other tenant's Patient must not be readable through the reference (the include must not leak it)
		assertThat(r.status()).isEqualTo(404);
		assertThat(r.body()).doesNotContain("Patient/patb/");
		ourLog.info("GRAPHQL-SEC xpartition-noauth status={}", r.status());
	}

	@Test
	public void testCrossPartitionReference_AuthorizedForTenantAOnly() throws IOException {
		myPartitionSettings.setAllowReferencesAcrossPartitions(PartitionSettings.CrossPartitionReferenceMode.ALLOWED_UNQUALIFIED);
		String patientInB = createPatientIn(TENANT_B, "patb");
		createFixture(TENANT_A, patientInB);
		setupAuthorizationInterceptorWithRules(() -> new RuleBuilder()
			.allow().operation().withAnyName().atAnyLevel().andAllowAllResponses().andThen()
			.allow().read().allResources().withAnyId().forTenantIds(TENANT_A)
			.build());
		Result r = assertIdentical(TENANT_A, ALL_THREE);
		ourLog.info("GRAPHQL-SEC xpartition-authA status={}", r.status());
	}

	@Test
	public void testCrossPartitionReference_SettingSwitchedOffAfterWrite() throws IOException {
		// The link is created while cross-partition references are allowed, then the setting is switched off
		myPartitionSettings.setAllowReferencesAcrossPartitions(PartitionSettings.CrossPartitionReferenceMode.ALLOWED_UNQUALIFIED);
		String patientInB = createPatientIn(TENANT_B, "pat0");
		createFixture(TENANT_A, patientInB);
		myPartitionSettings.setAllowReferencesAcrossPartitions(PartitionSettings.CrossPartitionReferenceMode.NOT_ALLOWED);
		Result r = assertIdentical(TENANT_A, ALL_THREE);
		ourLog.info("GRAPHQL-SEC xpartition-setting-off status={}", r.status());
	}
}
