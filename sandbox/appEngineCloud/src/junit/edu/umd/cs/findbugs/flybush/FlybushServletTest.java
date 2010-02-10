package edu.umd.cs.findbugs.flybush;

import com.google.appengine.api.datastore.Key;
import com.google.appengine.api.datastore.dev.LocalDatastoreService;
import com.google.appengine.api.users.User;
import com.google.appengine.tools.development.ApiProxyLocalImpl;
import com.google.apphosting.api.ApiProxy;
import edu.umd.cs.findbugs.cloud.appEngine.protobuf.AppEngineProtoUtil;
import edu.umd.cs.findbugs.cloud.appEngine.protobuf.ProtoClasses.Evaluation;
import edu.umd.cs.findbugs.cloud.appEngine.protobuf.ProtoClasses.FindIssues;
import edu.umd.cs.findbugs.cloud.appEngine.protobuf.ProtoClasses.FindIssuesResponse;
import edu.umd.cs.findbugs.cloud.appEngine.protobuf.ProtoClasses.GetEvaluations;
import edu.umd.cs.findbugs.cloud.appEngine.protobuf.ProtoClasses.GetRecentEvaluations;
import edu.umd.cs.findbugs.cloud.appEngine.protobuf.ProtoClasses.Issue;
import edu.umd.cs.findbugs.cloud.appEngine.protobuf.ProtoClasses.LogIn;
import edu.umd.cs.findbugs.cloud.appEngine.protobuf.ProtoClasses.RecentEvaluations;
import edu.umd.cs.findbugs.cloud.appEngine.protobuf.ProtoClasses.UploadEvaluation;
import edu.umd.cs.findbugs.cloud.appEngine.protobuf.ProtoClasses.UploadIssues;
import junit.framework.TestCase;

import javax.jdo.JDOHelper;
import javax.jdo.PersistenceManager;
import javax.jdo.PersistenceManagerFactory;
import javax.jdo.Query;
import javax.servlet.ServletInputStream;
import javax.servlet.ServletOutputStream;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.UnsupportedEncodingException;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import static edu.umd.cs.findbugs.cloud.appEngine.protobuf.AppEngineProtoUtil.decodeHash;
import static edu.umd.cs.findbugs.cloud.appEngine.protobuf.AppEngineProtoUtil.encodeHash;
import static edu.umd.cs.findbugs.cloud.appEngine.protobuf.AppEngineProtoUtil.encodeHashes;
import static org.mockito.Matchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class FlybushServletTest extends TestCase {

    private FlybushServlet servlet;
	private HttpServletRequest mockRequest;
	private HttpServletResponse mockResponse;
	private ByteArrayOutputStream outputCollector;
	private TestEnvironment testEnvironment;
	private PersistenceManager persistenceManager;
	private PersistenceManager actualPersistenceManager;

	@Override
	protected void setUp() throws Exception {
		super.setUp();
		testEnvironment = new TestEnvironment();
		ApiProxy.setEnvironmentForCurrentThread(testEnvironment);
		ApiProxy.setDelegate(new ApiProxyLocalImpl(new File(".")){});
        ApiProxyLocalImpl proxy = (ApiProxyLocalImpl) ApiProxy.getDelegate();
        proxy.setProperty(LocalDatastoreService.NO_STORAGE_PROPERTY, Boolean.TRUE.toString());
        initPersistenceManager();

		initServletAndMocks();
	}

    @Override
    public void tearDown() throws Exception {
    	actualPersistenceManager.close();
        ApiProxyLocalImpl proxy = (ApiProxyLocalImpl) ApiProxy.getDelegate();
        LocalDatastoreService datastoreService =
            (LocalDatastoreService) proxy.getService(LocalDatastoreService.PACKAGE);
        datastoreService.clearProfiles();
        ApiProxy.setDelegate(null);
        ApiProxy.setEnvironmentForCurrentThread(null);
        super.tearDown();
    }

    public void testBrowserAuthLoginRedirect() throws IOException {
    	executeGet("/browser-auth/100");
    	verify(mockResponse).sendRedirect(anyString());
    }

    public void testBrowserAuthWhenLoggedIn() throws IOException {
    	testEnvironment.setEmail("my@email.com");
    	executeGet("/browser-auth/100");
    	verify(mockResponse).setStatus(200);
    	verify(mockResponse).setContentType("text/html");
    	String outputString = new String(outputCollector.toByteArray(), "UTF-8");
		assertTrue("Should contain 'now signed in': " + outputString,
				   outputString.contains("now signed in"));
    }

	public void testCheckAuthForValidId() throws IOException {
		SqlCloudSession session = new SqlCloudSession(new User("my", "email.com"), 100, new Date(200));
		persistenceManager.makePersistent(session);

		executeGet("/check-auth/100");

		checkResponse(200, "OK\n100\nmy\n");
	}

	public void testCheckAuthForNonexistentId() throws IOException {
		executeGet("/check-auth/100");

		checkResponse(418, "FAIL\n");
	}

	public void testLogOut() throws IOException {
		// authenticate
    	createCloudSession(555);

    	executePost("/log-in", createAuthenticatedLogInMsg().build().toByteArray());
		checkResponse(200);
		FindIssuesResponse result = FindIssuesResponse.parseFrom(outputCollector.toByteArray());
		assertEquals(0, result.getFoundIssuesCount());

		initServletAndMocks();

		// log out
		executePost("/log-out/555", new byte[0]);
		checkResponse(200);

		initServletAndMocks();

		// make sure login no longer works
		executePost("/log-in", createAuthenticatedLogInMsg().build().toByteArray());
		checkResponse(403, "not authenticated");
	}

	public void testClearAllData() throws IOException {
    	createCloudSession(555);

		DbIssue foundIssue = createDbIssue("fad1");
		DbEvaluation eval1 = createEvaluation(foundIssue, "first", 100);
		DbEvaluation eval2 = createEvaluation(foundIssue, "second", 200);
		DbEvaluation eval3 = createEvaluation(foundIssue, "first", 300);
		foundIssue.addEvaluation(eval1);
		foundIssue.addEvaluation(eval2);
		foundIssue.addEvaluation(eval3);

		// apparently the evaluation is automatically persisted. throws
		// exception when attempting to persist the eval with the issue.
		persistenceManager.makePersistent(foundIssue);

    	executePost("/clear-all-data", new byte[0]);
		checkResponse(200);

        try {
            assertEquals(0, ((List<DbIssue>) persistenceManager.newQuery("select from " + DbIssue.class).execute()).size());
            fail();
        } catch (Exception e) {
        }
        try {
            assertEquals(0, ((List<DbEvaluation>) persistenceManager.newQuery("select from " + DbEvaluation.class).execute()).size());
            fail();
        } catch (Exception e) {
        }
        try {
            assertEquals(0, ((List<DbInvocation>) persistenceManager.newQuery("select from " + DbInvocation.class).execute()).size());
            fail();
        } catch (Exception e) {
        }
        try {
            assertEquals(0, ((List<SqlCloudSession>) persistenceManager.newQuery("select from " + SqlCloudSession.class).execute()).size());
            fail();
        } catch (Exception e) {
        }
	}

	public void testLogInUnauthenticated() throws IOException {
		executePost("/log-in", createAuthenticatedLogInMsg().build().toByteArray());
		checkResponse(403, "not authenticated");
	}

	public void testLogInNoneFound() throws IOException {
    	createCloudSession(555);
		executePost("/log-in", createAuthenticatedLogInMsg().build().toByteArray());
		checkResponse(200);
		FindIssuesResponse result = FindIssuesResponse.parseFrom(outputCollector.toByteArray());
		assertEquals(0, result.getFoundIssuesCount());
	}

	@SuppressWarnings("unchecked")
	public void testLogInStoresInvocation() throws IOException {
    	createCloudSession(555);
		executePost("/log-in", createAuthenticatedLogInMsg().build().toByteArray());
		checkResponse(200);
		FindIssuesResponse result = FindIssuesResponse.parseFrom(outputCollector.toByteArray());
		assertEquals(0, result.getFoundIssuesCount());
		Query query = persistenceManager.newQuery("select from " + DbInvocation.class.getName());
		List<DbInvocation> invocations = (List<DbInvocation>) query.execute();
		assertEquals(1, invocations.size());
		assertEquals("my@email.com", invocations.get(0).getWho());
		query.closeAll();
	}

	public void testFindIssuesOneFoundNoEvaluations() throws IOException {
    	createCloudSession(555);

		DbIssue foundIssue = createDbIssue("FAD1");
		persistenceManager.makePersistent(foundIssue);

		FindIssues findIssuesMsg = createAuthenticatedFindIssues("FAD1", "FAD2").build();
		executePost("/find-issues", findIssuesMsg.toByteArray());
		FindIssuesResponse result = FindIssuesResponse.parseFrom(outputCollector.toByteArray());
		assertEquals(2, result.getFoundIssuesCount());

        Issue protoIssue0 = result.getFoundIssues(0);
        assertEquals(100, protoIssue0.getFirstSeen());
        assertEquals(200, protoIssue0.getLastSeen());
        assertEquals(0, protoIssue0.getEvaluationsCount());
        assertFalse(protoIssue0.hasBugPattern());
        assertFalse(protoIssue0.hasHash());
        assertFalse(protoIssue0.hasPriority());
        assertFalse(protoIssue0.hasPrimaryClass());

        Issue protoIssue1 = result.getFoundIssues(1);
        checkAllFieldsAreBlank(protoIssue1);
    }

    public void testFindIssuesWithEvaluations() throws IOException {
    	createCloudSession(555);

		DbIssue foundIssue = createDbIssue("fad2");
		DbEvaluation eval = createEvaluation(foundIssue, "someone", 100);
		foundIssue.addEvaluation(eval);

		// apparently the evaluation is automatically persisted. throws
		// exception when attempting to persist the eval with the issue.
		persistenceManager.makePersistent(foundIssue);

		FindIssues findIssues = createAuthenticatedFindIssues("fad1", "fad2").build();
		executePost("/find-issues", findIssues.toByteArray());
		FindIssuesResponse result = FindIssuesResponse.parseFrom(outputCollector.toByteArray());
		assertEquals(2, result.getFoundIssuesCount());

		// check issues
        checkAllFieldsAreBlank(result.getFoundIssues(0));

		Issue foundissueProto = result.getFoundIssues(1);
        assertEquals(100, foundissueProto.getFirstSeen());
        assertEquals(200, foundissueProto.getLastSeen());

		// check evaluations
		assertEquals(1, foundissueProto.getEvaluationsCount());
		checkEvaluationsEqual(eval, foundissueProto.getEvaluations(0));
	}

	public void testFindIssuesOnlyShowsLatestEvaluationFromEachPerson() throws Exception {
    	createCloudSession(555);

		DbIssue foundIssue = createDbIssue("fad1");
		DbEvaluation eval1 = createEvaluation(foundIssue, "first", 100);
		DbEvaluation eval2 = createEvaluation(foundIssue, "second", 200);
		DbEvaluation eval3 = createEvaluation(foundIssue, "first", 300);
		foundIssue.addEvaluation(eval1);
		foundIssue.addEvaluation(eval2);
		foundIssue.addEvaluation(eval3);

		// apparently the evaluation is automatically persisted. throws
		// exception when attempting to persist the eval with the issue.
		persistenceManager.makePersistent(foundIssue);

		FindIssues hashesToFind = createAuthenticatedFindIssues("fad2", "fad1").build();
		executePost("/find-issues", hashesToFind.toByteArray());
		FindIssuesResponse result = FindIssuesResponse.parseFrom(outputCollector.toByteArray());
		assertEquals(2, result.getFoundIssuesCount());

		// check issues
        checkAllFieldsAreBlank(result.getFoundIssues(0));
        
		Issue foundIssueProto = result.getFoundIssues(1);
		assertEquals(100, foundIssueProto.getFirstSeen());
		assertEquals(200, foundIssueProto.getLastSeen());

		// check evaluations
		assertEquals(2, foundIssueProto.getEvaluationsCount());
		checkEvaluationsEqual(eval2, foundIssueProto.getEvaluations(0));
		checkEvaluationsEqual(eval3, foundIssueProto.getEvaluations(1));
	}

	public void testGetRecentEvaluationsNoAuth() throws IOException {
		executePost("/get-recent-evaluations", createRecentEvalsRequest(100).toByteArray());
		checkResponse(403, "not authenticated");
	}

	public void testGetRecentEvaluations() throws IOException {
		createCloudSession(555);

		DbIssue issue = createDbIssue("fad");
		DbEvaluation eval1 = createEvaluation(issue, "someone1", 100);
		DbEvaluation eval2 = createEvaluation(issue, "someone2", 200);
		DbEvaluation eval3 = createEvaluation(issue, "someone3", 300);
		issue.addEvaluations(eval1, eval2, eval3);

		persistenceManager.makePersistent(issue);

		executePost("/get-recent-evaluations", createRecentEvalsRequest(150).toByteArray());
		checkResponse(200);
		RecentEvaluations result = RecentEvaluations.parseFrom(outputCollector.toByteArray());
		assertEquals(1, result.getIssuesCount());

		// check issues
		Issue foundissueProto = result.getIssues(0);
		checkIssuesEqualExceptTimestamps(issue, foundissueProto);

		// check evaluations
		assertEquals(2, foundissueProto.getEvaluationsCount());
		checkEvaluationsEqual(eval2, foundissueProto.getEvaluations(0));
		checkEvaluationsEqual(eval3, foundissueProto.getEvaluations(1));
	}

	public void testGetRecentEvaluationsOnlyShowsLatestFromEachPerson() throws IOException {
		createCloudSession(555);

		DbIssue issue = createDbIssue("fad");
		DbEvaluation eval1 = createEvaluation(issue, "first",  100);
		DbEvaluation eval2 = createEvaluation(issue, "second", 200);
		DbEvaluation eval3 = createEvaluation(issue, "first",  300);
		DbEvaluation eval4 = createEvaluation(issue, "second", 400);
		DbEvaluation eval5 = createEvaluation(issue, "first",  500);
		issue.addEvaluations(eval1, eval2, eval3, eval4, eval5);

		persistenceManager.makePersistent(issue);

		executePost("/get-recent-evaluations", createRecentEvalsRequest(150).toByteArray());
		checkResponse(200);
		RecentEvaluations result = RecentEvaluations.parseFrom(outputCollector.toByteArray());
		assertEquals(1, result.getIssuesCount());

		// check issues
		Issue foundissueProto = result.getIssues(0);
		checkIssuesEqualExceptTimestamps(issue, foundissueProto);

		// check evaluations
		assertEquals(2, foundissueProto.getEvaluationsCount());
		checkEvaluationsEqual(eval4, foundissueProto.getEvaluations(0));
		checkEvaluationsEqual(eval5, foundissueProto.getEvaluations(1));
	}

	public void testGetRecentEvaluationsNoneFound() throws IOException {
		createCloudSession(555);

		DbIssue issue = createDbIssue("fad");
		DbEvaluation eval1 = createEvaluation(issue, "someone", 100);
		DbEvaluation eval2 = createEvaluation(issue, "someone", 200);
		DbEvaluation eval3 = createEvaluation(issue, "someone", 300);
		issue.addEvaluations(eval1, eval2, eval3);

		persistenceManager.makePersistent(issue);

		executePost("/get-recent-evaluations", createRecentEvalsRequest(300).toByteArray());
		checkResponse(200);
		RecentEvaluations result = RecentEvaluations.parseFrom(outputCollector.toByteArray());
		assertEquals(0, result.getIssuesCount());
	}

	public void testUploadIssueWithoutAuthenticating() throws IOException {
		Issue issue = createProtoIssue("fad");
		UploadIssues issuesToUpload = UploadIssues.newBuilder().setSessionId(555).addNewIssues(issue).build();
		executePost("/upload-issues", issuesToUpload.toByteArray());
		checkResponse(403);
	}

	@SuppressWarnings("unchecked")
	public void testUploadIssue() throws IOException {
    	createCloudSession(555);
		Issue issue = createProtoIssue("fad");
		UploadIssues issuesToUpload = UploadIssues.newBuilder().setSessionId(555).addNewIssues(issue).build();
		executePost("/upload-issues", issuesToUpload.toByteArray());
		checkResponse(200, "");
		List<DbIssue> dbIssues = (List<DbIssue>) persistenceManager
				.newQuery("select from " + DbIssue.class.getName()).execute();
		assertEquals(1, dbIssues.size());

        DbIssue dbIssue = dbIssues.get(0);
        checkIssuesEqualExceptTimestamps(dbIssue, issue);
        assertEquals(issue.getFirstSeen(), dbIssue.getFirstSeen());
        assertEquals(issue.getFirstSeen(), dbIssue.getLastSeen()); // upon initial upload, should be identical
	}

	@SuppressWarnings("unchecked")
	public void testUploadMultipleIssues() throws IOException {
    	createCloudSession(555);
		Issue issue1 = createProtoIssue("fad1");
		Issue issue2 = createProtoIssue("fad2");
		UploadIssues issuesToUpload = UploadIssues.newBuilder()
				.setSessionId(555).addNewIssues(issue1).addNewIssues(issue2)
				.build();
		executePost("/upload-issues", issuesToUpload.toByteArray());
		checkResponse(200, "");
		List<DbIssue> dbIssues = (List<DbIssue>) persistenceManager
				.newQuery("select from " + DbIssue.class.getName()).execute();
		assertEquals(2, dbIssues.size());

		checkIssuesEqualExceptTimestamps(dbIssues.get(0), issue1);
		checkIssuesEqualExceptTimestamps(dbIssues.get(1), issue2);
	}

	@SuppressWarnings("unchecked")
	public void testUploadIssuesWhichAlreadyExist() throws IOException {
    	createCloudSession(555);
		DbIssue oldDbIssue = createDbIssue("fad1");
		persistenceManager.makePersistent(oldDbIssue);
		Issue oldIssue = createProtoIssue("fad1");
		Issue newIssue = createProtoIssue("fad2");
		UploadIssues issuesToUpload = UploadIssues.newBuilder()
				.setSessionId(555)
				.addNewIssues(oldIssue)
				.addNewIssues(newIssue)
				.build();
		executePost("/upload-issues", issuesToUpload.toByteArray());
		checkResponse(200, "");
		List<DbIssue> dbIssues = (List<DbIssue>) persistenceManager
				.newQuery("select from " + DbIssue.class.getName() + " order by hash").execute();
		assertEquals(2, dbIssues.size());

		assertEquals("fad1", dbIssues.get(0).getHash());
		assertEquals("fad2", dbIssues.get(1).getHash());
	}

	public void testUploadEvaluationNoAuth() throws IOException {
		executePost("/upload-evaluation", UploadEvaluation.newBuilder()
				.setSessionId(555)
				.setHash(encodeHash("fad"))
				.setEvaluation(createProtoEvaluation())
				.build().toByteArray());
		checkResponse(403, "not authenticated");
	}

	public void testUploadEvaluationWithoutFindIssuesFirst() throws IOException {
		createCloudSession(555);

		DbIssue dbIssue = createDbIssue("fad");
		persistenceManager.makePersistent(dbIssue);
		Evaluation protoEval = createProtoEvaluation();
		executePost("/upload-evaluation", UploadEvaluation.newBuilder()
				.setSessionId(555)
				.setHash(encodeHash("fad"))
				.setEvaluation(protoEval)
				.build().toByteArray());
		checkResponse(200);
		persistenceManager.refresh(dbIssue);
		assertEquals(1, dbIssue.getEvaluations().size());
		DbEvaluation dbEval = dbIssue.getEvaluations().iterator().next();
		assertEquals(protoEval.getComment(), dbEval.getComment());
		assertEquals(protoEval.getDesignation(), dbEval.getDesignation());
		assertEquals(protoEval.getWhen(), dbEval.getWhen());
		assertEquals("my@email.com", dbEval.getWho());
		assertNull(dbEval.getInvocation());
	}

	public void testUploadEvaluationWithFindIssuesFirst() throws IOException {
		createCloudSession(555);

		executePost("/log-in", LogIn.newBuilder()
				.setSessionId(555)
                .setAnalysisTimestamp(100)
				.build().toByteArray());
		checkResponse(200);
		initServletAndMocks();

		executePost("/find-issues", FindIssues.newBuilder()
				.setSessionId(555)
				.addMyIssueHashes(encodeHash("abc"))
				.build().toByteArray());
		checkResponse(200);
		initServletAndMocks();

		DbIssue dbIssue = createDbIssue("fad");
		persistenceManager.makePersistent(dbIssue);
		Evaluation protoEval = createProtoEvaluation();
		executePost("/upload-evaluation", UploadEvaluation.newBuilder()
				.setSessionId(555)
				.setHash(encodeHash("fad"))
				.setEvaluation(protoEval)
				.build().toByteArray());
		checkResponse(200);
		assertEquals(1, dbIssue.getEvaluations().size());
		DbEvaluation dbEval = dbIssue.getEvaluations().iterator().next();
		assertEquals(protoEval.getComment(), dbEval.getComment());
		assertEquals(protoEval.getDesignation(), dbEval.getDesignation());
		assertEquals(protoEval.getWhen(), dbEval.getWhen());
		assertEquals("my@email.com", dbEval.getWho());
		Key invocationId = dbEval.getInvocation();
		assertNotNull(invocationId);
		DbInvocation invocation = persistenceManager.getObjectById(DbInvocation.class, invocationId);
		assertEquals("my@email.com", invocation.getWho());
		assertEquals(100, invocation.getStartTime());
	}

	public void testUploadEvaluationNonexistentIssue() throws IOException {
		createCloudSession(555);

		Evaluation protoEval = createProtoEvaluation();
		executePost("/upload-evaluation", UploadEvaluation.newBuilder()
				.setSessionId(555)
				.setHash(encodeHash("faf"))
				.setEvaluation(protoEval)
				.build().toByteArray());
		checkResponse(404, "no such issue faf\n");
	}

	public void testGetEvaluationsNotAuthenticated() throws IOException {
		executePost("/get-evaluations", GetEvaluations.newBuilder()
				.setSessionId(555)
				.addHashes(encodeHash("fad"))
				.build().toByteArray());
		checkResponse(403, "not authenticated");
	}

	public void testGetEvaluationsForNonexistentIssue() throws IOException {
		createCloudSession(555);

		executePost("/get-evaluations", GetEvaluations.newBuilder()
				.setSessionId(555)
				.addHashes(encodeHash("fad"))
				.build().toByteArray());
		checkResponse(200);
		RecentEvaluations evals = RecentEvaluations.parseFrom(outputCollector.toByteArray());
		assertEquals(0, evals.getIssuesCount());
	}

	public void testGetEvaluationsForSomeNonexistentIssues() throws IOException {
		createCloudSession(555);

		DbIssue issue1 = createDbIssue("fad1");
		issue1.addEvaluations(createEvaluation(issue1, "someone1", 100),
							 createEvaluation(issue1, "someone2", 200),
							 createEvaluation(issue1, "someone3", 300));

		persistenceManager.makePersistentAll(issue1);

		executePost("/get-evaluations", GetEvaluations.newBuilder()
				.setSessionId(555)
				.addHashes(encodeHash("fad1"))
				.addHashes(encodeHash("fad2"))
				.build().toByteArray());
		checkResponse(200);
		RecentEvaluations evals = RecentEvaluations.parseFrom(outputCollector.toByteArray());
		assertEquals(1, evals.getIssuesCount());
		Issue protoIssue1 = evals.getIssues(0);
		assertEquals(3, protoIssue1.getEvaluationsCount());
		assertEquals(100, protoIssue1.getEvaluations(0).getWhen());
		assertEquals(200, protoIssue1.getEvaluations(1).getWhen());
		assertEquals(300, protoIssue1.getEvaluations(2).getWhen());
	}

	public void testGetEvaluations() throws Exception {
		createCloudSession(555);

		DbIssue issue1 = createDbIssue("fad1");
		issue1.addEvaluations(createEvaluation(issue1, "someone1", 100),
							 createEvaluation(issue1, "someone2", 200),
							 createEvaluation(issue1, "someone3", 300));

		DbIssue issue2 = createDbIssue("fad2");
		issue2.addEvaluations(createEvaluation(issue2, "someone1", 2100),
							 createEvaluation(issue2, "someone2", 2200),
							 createEvaluation(issue2, "someone3", 2300));

		persistenceManager.makePersistentAll(issue1, issue2);

		executePost("/get-evaluations",
				GetEvaluations.newBuilder()
					.setSessionId(555)
					.addHashes(encodeHash("fad1"))
					.addHashes(encodeHash("fad2"))
					.build()
				.toByteArray());
		checkResponse(200);
		RecentEvaluations evals = RecentEvaluations.parseFrom(outputCollector.toByteArray());
		assertEquals(2, evals.getIssuesCount());
		Issue protoIssue1 = evals.getIssues(1);
		assertEquals("fad1", decodeHash(protoIssue1.getHash()));
		assertEquals(3, protoIssue1.getEvaluationsCount());
		assertEquals(100, protoIssue1.getEvaluations(0).getWhen());
		assertEquals(200, protoIssue1.getEvaluations(1).getWhen());
		assertEquals(300, protoIssue1.getEvaluations(2).getWhen());

		Issue protoIssue2 = evals.getIssues(0);
		assertEquals("fad2", decodeHash(protoIssue2.getHash()));
		assertEquals(3, protoIssue2.getEvaluationsCount());
		assertEquals(2100, protoIssue2.getEvaluations(0).getWhen());
		assertEquals(2200, protoIssue2.getEvaluations(1).getWhen());
		assertEquals(2300, protoIssue2.getEvaluations(2).getWhen());
	}

	public void testGetEvaluationsOnlyShowsLatestFromEachPerson()
			throws Exception {
		createCloudSession(555);

		DbIssue issue = createDbIssue("fad1");
		issue.addEvaluations(createEvaluation(issue, "first", 100),
							 createEvaluation(issue, "second", 200),
							 createEvaluation(issue, "first", 300));

		persistenceManager.makePersistentAll(issue);

		executePost("/get-evaluations", GetEvaluations.newBuilder()
				.setSessionId(555)
				.addHashes(encodeHash("fad1"))
				.build().toByteArray());
		checkResponse(200);
		RecentEvaluations evals = RecentEvaluations.parseFrom(outputCollector.toByteArray());
		assertEquals(1, evals.getIssuesCount());
		Issue protoIssue = evals.getIssues(0);
		assertEquals(2, protoIssue.getEvaluationsCount());
		assertEquals(200, protoIssue.getEvaluations(0).getWhen());
		assertEquals(300, protoIssue.getEvaluations(1).getWhen());
	}

	// ========================= end of tests ================================

    private void checkAllFieldsAreBlank(Issue protoIssue1) {
        assertFalse(protoIssue1.hasFirstSeen());
        assertFalse(protoIssue1.hasLastSeen());
        assertEquals(0, protoIssue1.getEvaluationsCount());
        assertFalse(protoIssue1.hasBugPattern());
        assertFalse(protoIssue1.hasHash());
        assertFalse(protoIssue1.hasPriority());
        assertFalse(protoIssue1.hasPrimaryClass());
    }

	private FindIssues.Builder createAuthenticatedFindIssues(String... hashes) {
		return createAuthenticatedFindIssues().addAllMyIssueHashes(encodeHashes(Arrays.asList(hashes)));
	}

	private FindIssues.Builder createAuthenticatedFindIssues() {
		return FindIssues.newBuilder().setSessionId(555);
	}

	private LogIn.Builder createAuthenticatedLogInMsg() {
		return LogIn.newBuilder().setSessionId(555).setAnalysisTimestamp(100);
	}

	private GetRecentEvaluations createRecentEvalsRequest(int timestamp) {
		return GetRecentEvaluations.newBuilder()
				.setSessionId(555)
				.setTimestamp(timestamp)
				.build();
	}

	private Evaluation createProtoEvaluation() {
        return Evaluation.newBuilder()
                .setDesignation("MUST_FIX")
                .setComment("my comment")
                .setWhen(100)
                .build();
	}

	private void initServletAndMocks() throws IOException {
		servlet = new FlybushServlet(persistenceManager);
		mockRequest = mock(HttpServletRequest.class);
		mockResponse = mock(HttpServletResponse.class);
		outputCollector = new ByteArrayOutputStream();
		ServletOutputStream servletOutputStream = new ServletOutputStream() {
			public void write(int b) throws IOException {
				outputCollector.write(b);
			}
		};
		when(mockResponse.getOutputStream()).thenReturn(servletOutputStream);
		when(mockResponse.getWriter()).thenReturn(new PrintWriter(servletOutputStream, true));
	}

	private void createCloudSession(long sessionId) throws IOException {
		testEnvironment.setEmail("my@email.com");
    	executeGet("/browser-auth/" + sessionId);
    	initServletAndMocks();
	}

	private void checkEvaluationsEqual(DbEvaluation dbEval, Evaluation protoEval) {
		assertEquals(dbEval.getComment(), protoEval.getComment());
		assertEquals(dbEval.getDesignation(), protoEval.getDesignation());
		assertEquals(dbEval.getWhen(), protoEval.getWhen());
		assertEquals(dbEval.getWho(), protoEval.getWho());
	}

	private DbEvaluation createEvaluation(DbIssue issue, String who, int when) {
		DbEvaluation eval = new DbEvaluation();
		eval.setComment("my comment");
		eval.setDesignation("MUST_FIX");
		eval.setIssue(issue);
		eval.setWhen(when);
		eval.setWho(who);
		return eval;
	}

	private DbIssue createDbIssue(String patternAndHash) {
		patternAndHash = AppEngineProtoUtil.normalizeHash(patternAndHash);
		DbIssue foundIssue = new DbIssue();
		foundIssue.setHash(patternAndHash);
		foundIssue.setBugPattern(patternAndHash);
		foundIssue.setPriority(2);
		foundIssue.setPrimaryClass("my.class");
		foundIssue.setFirstSeen(100);
		foundIssue.setLastSeen(200);
		return foundIssue;
	}

	private Issue createProtoIssue(String patternAndHash) {
		patternAndHash = AppEngineProtoUtil.normalizeHash(patternAndHash);
		Issue.Builder issueBuilder = Issue.newBuilder();
		issueBuilder.setHash(encodeHash(patternAndHash));
		issueBuilder.setBugPattern(patternAndHash);
		issueBuilder.setPriority(2);
		issueBuilder.setPrimaryClass("my.class");
		issueBuilder.setFirstSeen(100);
		issueBuilder.setLastSeen(200);

		return issueBuilder.build();
	}

	private void checkIssuesEqualExceptTimestamps(DbIssue dbIssue, Issue protoIssue) {
		assertEquals(dbIssue.getHash(), decodeHash(protoIssue.getHash()));
		assertEquals(dbIssue.getBugPattern(), protoIssue.getBugPattern());
		assertEquals(dbIssue.getPriority(), protoIssue.getPriority());
		assertEquals(dbIssue.getPrimaryClass(), protoIssue.getPrimaryClass());
	}

	/**
     * Creates a PersistenceManagerFactory on the fly, with the exact same information
     * stored in the /WEB-INF/META-INF/jdoconfig.xml file.
     */
    private void initPersistenceManager() {
        Properties newProperties = new Properties();
        newProperties.put("javax.jdo.PersistenceManagerFactoryClass",
                "org.datanucleus.store.appengine.jdo.DatastoreJDOPersistenceManagerFactory");
        newProperties.put("javax.jdo.option.ConnectionURL", "appengine");
        newProperties.put("javax.jdo.option.NontransactionalRead", "true");
        newProperties.put("javax.jdo.option.NontransactionalWrite", "true");
        newProperties.put("javax.jdo.option.RetainValues", "true");
        newProperties.put("datanucleus.appengine.autoCreateDatastoreTxns", "true");
        PersistenceManagerFactory pmf = JDOHelper.getPersistenceManagerFactory(newProperties);
		actualPersistenceManager = pmf.getPersistenceManager();
		persistenceManager = spy(actualPersistenceManager);

		doNothing().when(persistenceManager).close();
    }


	private void executeGet(String requestUri) throws IOException {
		executeGet(requestUri, null);
	}

	private void executeGet(String requestUri, byte[] input) throws IOException {
		prepareRequestAndResponse(requestUri, input);

		servlet.doGet(mockRequest, mockResponse);
	}

	private void executePost(String requestUri, byte[] input)
			throws IOException {
		prepareRequestAndResponse(requestUri, input);

		servlet.doPost(mockRequest, mockResponse);
	}

	private void prepareRequestAndResponse(String requestUri, byte[] input)
			throws IOException {
		when(mockRequest.getPathInfo()).thenReturn(requestUri);
		if (input != null) {
			final ByteArrayInputStream inputStream = new ByteArrayInputStream(input);
			when(mockRequest.getInputStream()).thenReturn(new ServletInputStream() {
				public int read() throws IOException {
					return inputStream.read();
				}
			});
		}
	}

	private void checkResponse(int responseCode, String expectedOutput)
			throws UnsupportedEncodingException {
		checkResponse(responseCode);
		verify(mockResponse, atLeastOnce()).setContentType("text/plain");
		String output = new String(outputCollector.toByteArray(), "UTF-8");
		assertEquals(expectedOutput.trim(), output.replaceAll("\r", "").trim());
	}

	private void checkResponse(int responseCode) {
		verify(mockResponse).setStatus(responseCode);
	}

}

class TestEnvironment implements ApiProxy.Environment {
	private String email;

	public void setEmail(String email) {
		this.email = email;
	}

	public String getAppId() {
		return "test";
	}

	public String getVersionId() {
		return "1.0";
	}

	public String getEmail() {
		return email;
	}

	public boolean isLoggedIn() {
		return email != null;
	}

	public boolean isAdmin() {
		throw new UnsupportedOperationException();
	}

	public String getAuthDomain() {
		return email == null ? null : "domain.com";
	}

	public String getRequestNamespace() {
		return "";
	}

	public Map<String, Object> getAttributes() {
		Map<String, Object> map = new HashMap<String, Object>();
		map.put("com.google.appengine.server_url_key", "http://localhost:8080");
		return map;
	}
}