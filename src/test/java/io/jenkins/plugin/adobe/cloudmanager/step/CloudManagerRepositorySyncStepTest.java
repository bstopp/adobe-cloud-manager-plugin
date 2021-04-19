package io.jenkins.plugin.adobe.cloudmanager.step;

import org.apache.commons.lang3.StringUtils;

import com.cloudbees.plugins.credentials.Credentials;
import com.cloudbees.plugins.credentials.CredentialsProvider;
import com.cloudbees.plugins.credentials.CredentialsScope;
import com.cloudbees.plugins.credentials.CredentialsStore;
import com.cloudbees.plugins.credentials.domains.Domain;
import com.cloudbees.plugins.credentials.impl.UsernamePasswordCredentialsImpl;
import hudson.model.Label;
import hudson.model.Result;
import hudson.model.queue.QueueTaskFuture;
import hudson.plugins.git.GitSCM;
import hudson.scm.SCM;
import io.jenkins.plugins.adobe.cloudmanager.step.CloudManagerRepositorySyncStep;
import io.jenkins.plugins.adobe.cloudmanager.step.Messages;
import jenkins.plugins.git.CliGitCommand;
import jenkins.plugins.git.GitSampleRepoRule;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.servlet.DefaultServlet;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.cps.CpsScmFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.jenkinsci.plugins.workflow.steps.Step;
import org.jenkinsci.plugins.workflow.steps.StepConfigTester;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.JenkinsRule;
import static org.junit.Assert.*;

public class CloudManagerRepositorySyncStepTest {

  @Rule
  public JenkinsRule rule = new JenkinsRule();

  @Rule
  public GitSampleRepoRule srcRepo = new GitSampleRepoRule();

  @Rule
  public GitSampleRepoRule bareDestRepo = new GitSampleRepoRule();

  @Rule
  public GitSampleRepoRule destRepo = new GitSampleRepoRule();

  @Rule
  public GitSampleRepoRule updatedDestRepo = new GitSampleRepoRule();

  @BeforeClass
  public static void before() throws Exception {
    CliGitCommand cmd = new CliGitCommand(null);
    cmd.setDefaults();
  }

  @Before
  public void beforeEach() throws Exception {
    CredentialsStore store = CredentialsProvider.lookupStores(rule.jenkins).iterator().next();
    Credentials credentials = new UsernamePasswordCredentialsImpl(CredentialsScope.SYSTEM, "credentials", "", "username", "test-password");
    store.addCredentials(Domain.global(), credentials);
  }

  @Test
  public void roundtrip() throws Exception {
    CloudManagerRepositorySyncStep step = new CloudManagerRepositorySyncStep("https://git.cloudmanager.adobe.com/weretail/weretail", "weretail-credentials");
    Step roundtrip = new StepConfigTester(rule).configRoundTrip(step);
    rule.assertEqualDataBoundBeans(step, roundtrip);
  }

  @Test
  public void noSCMFailure() throws Exception {
    WorkflowJob job = rule.jenkins.createProject(WorkflowJob.class, "test");
    rule.createOnlineSlave(Label.get("runner"));
    CpsFlowDefinition flow = new CpsFlowDefinition(
        "node('runner') {\n" +
            "  ws {\n" +
            "    cloudManagerRepoSync(url: '" + srcRepo + "', credentialsId: 'credentials')\n" +
            "  }\n" +
            "}",
        true);
    job.setDefinition(flow);

    QueueTaskFuture<WorkflowRun> run = job.scheduleBuild2(0);
    rule.assertBuildStatus(Result.FAILURE, run);
    assertNotNull(run);
    assertTrue(StringUtils.contains(run.get().getLog(), Messages.CloudManagerRepositorySyncStep_error_missingGitRepository()));
  }

  @Test
  public void noLocalGitRepo() throws Exception {
    srcRepo.init();
    WorkflowJob job = rule.jenkins.createProject(WorkflowJob.class, "test");
    rule.createOnlineSlave(Label.get("runner"));
    CpsFlowDefinition flow = new CpsFlowDefinition(
        "node('runner') {\n" +
            "  ws {\n" +
            "    git($/" + srcRepo + "/$)\n" +
            "    dir('subdir') {\n" +
            "      cloudManagerRepoSync(url: '" + destRepo + "', credentialsId: 'credentials')\n" +
            "    }\n" +
            "  }\n" +
            "}",
        true);
    job.setDefinition(flow);

    QueueTaskFuture<WorkflowRun> run = job.scheduleBuild2(0);
    rule.assertBuildStatus(Result.FAILURE, run);
    assertTrue(StringUtils.contains(run.get().getLog(), Messages.CloudManagerRepositorySyncStep_error_missingGitRepository()));
  }

  @Test
  public void conflictNoForce() throws Exception {
    srcRepo.init();
    srcRepo.write("newfile", "filecontents");
    srcRepo.git("add", "newfile");
    srcRepo.git("commit", "--message=file");
    bareDestRepo.git("--bare", "init");
    destRepo.git("clone", bareDestRepo.toString(), ".");
    destRepo.git("config", "user.name", "Git SampleRepoRule");
    destRepo.git("config", "user.email", "gits@mplereporule");
    destRepo.write("testfile", "testfilecontents");
    destRepo.git("add", "testfile");
    destRepo.git("commit","--message=testfile");
    destRepo.git("push");
    WorkflowJob job = rule.jenkins.createProject(WorkflowJob.class, "test");
    rule.createOnlineSlave(Label.get("runner"));
    CpsFlowDefinition flow = new CpsFlowDefinition(
        "node('runner') {\n" +
            "  ws {\n" +
            "    git(url: $/" + srcRepo + "/$,)\n" +
            "    cloudManagerRepoSync(url: '" + bareDestRepo + "', credentialsId: 'credentials')\n" +
            "  }\n" +
            "}",
        true);
    job.setDefinition(flow);

    QueueTaskFuture<WorkflowRun> run = job.scheduleBuild2(0);
    rule.assertBuildStatus(Result.FAILURE, run);
    assertTrue(StringUtils.contains(run.get().getLog(), "Push to Cloud Manager remote failed"));
  }

  @Test
  public void conflictWithForce() throws Exception {
    srcRepo.init();
    srcRepo.write("newfile", "filecontents");
    srcRepo.git("add", "newfile");
    srcRepo.git("commit", "--message=file");
    bareDestRepo.git("--bare", "init");
    destRepo.git("clone", bareDestRepo.toString(), ".");
    destRepo.git("config", "user.name", "Git SampleRepoRule");
    destRepo.git("config", "user.email", "gits@mplereporule");
    destRepo.write("testfile", "testfilecontents");
    destRepo.git("add", "testfile");
    destRepo.git("commit","--message=testfile");
    destRepo.git("push");
    WorkflowJob job = rule.jenkins.createProject(WorkflowJob.class, "test");
    rule.createOnlineSlave(Label.get("runner"));
    CpsFlowDefinition flow = new CpsFlowDefinition(
        "node('runner') {\n" +
            "  ws {\n" +
            "    git(url: $/" + srcRepo + "/$,)\n" +
            "    cloudManagerRepoSync(url: '" + bareDestRepo + "', credentialsId: 'credentials', force: true)\n" +
            "  }\n" +
            "}",
        true);
    job.setDefinition(flow);

    QueueTaskFuture<WorkflowRun> run = job.scheduleBuild2(0);
    rule.assertBuildStatus(Result.SUCCESS, run);
    updatedDestRepo.git("clone", bareDestRepo.toString(), ".");
    assertEquals(srcRepo.head(), updatedDestRepo.head());

  }

  @Test
  public void missingTargetBranch() throws Exception {
    srcRepo.init();
    srcRepo.git("checkout", "-b", "notdefault");
    srcRepo.write("newfile", "filecontents");
    srcRepo.git("add", "newfile");
    srcRepo.git("commit", "--message=file");
    bareDestRepo.git("--bare", "init");
    destRepo.git("clone", bareDestRepo.toString(), ".");
    destRepo.git("config", "user.name", "Git SampleRepoRule");
    destRepo.git("config", "user.email", "gits@mplereporule");
    destRepo.write("testfile", "testfilecontents");
    destRepo.git("add", "testfile");
    destRepo.git("commit","--message=testfile");
    destRepo.git("push");
    WorkflowJob job = rule.jenkins.createProject(WorkflowJob.class, "test");
    rule.createOnlineSlave(Label.get("runner"));
    CpsFlowDefinition flow = new CpsFlowDefinition(
        "node('runner') {\n" +
            "  ws {\n" +
            "    git(url: $/" + srcRepo + "/$, branch: 'notdefault')\n" +
            "    cloudManagerRepoSync(url: '" + bareDestRepo + "', credentialsId: 'credentials')\n" +
            "  }\n" +
            "}",
        true);
    job.setDefinition(flow);

    QueueTaskFuture<WorkflowRun> run = job.scheduleBuild2(0);
    rule.assertBuildStatus(Result.SUCCESS, run);
    destRepo.git("fetch");
    destRepo.git("checkout", "notdefault");
    assertEquals(srcRepo.head(), destRepo.head());
  }

  @Test
  public void multipleScmsWarning() throws Exception {
    srcRepo.init();
    destRepo.init();
    bareDestRepo.git("--bare", "init");
    WorkflowJob job = rule.jenkins.createProject(WorkflowJob.class, "test");
    rule.createOnlineSlave(Label.get("runner"));
    CpsFlowDefinition flow = new CpsFlowDefinition(
        "node('runner') {\n" +
            "  ws {\n" +
            "    git($/" + srcRepo + "/$)\n" +
            "    git($/" + destRepo + "/$)\n" +
            "    cloudManagerRepoSync(url: '" + bareDestRepo + "', credentialsId: 'credentials')\n" +
            "  }\n" +
            "}",
        true);
    job.setDefinition(flow);

    QueueTaskFuture<WorkflowRun> run = job.scheduleBuild2(0);
    rule.assertBuildStatus(Result.SUCCESS, run);
    String log = run.get().getLog();
    assertTrue(StringUtils.contains(log, Messages.CloudManagerRepositorySyncStep_warning_multipleScms(srcRepo.toString())));
  }

  @Test
  public void scriptSuccess() throws Exception {
    srcRepo.init();
    bareDestRepo.git("--bare", "init");
    WorkflowJob job = rule.jenkins.createProject(WorkflowJob.class, "test");
    rule.createOnlineSlave(Label.get("runner"));
    CpsFlowDefinition flow = new CpsFlowDefinition(
        "node('runner') {\n" +
            "  ws {\n" +
            "    git($/" + srcRepo + "/$)\n" +
            "    cloudManagerRepoSync(url: '" + bareDestRepo + "', credentialsId: 'credentials')\n" +
            "  }\n" +
            "}",
        true);
    job.setDefinition(flow);

    QueueTaskFuture<WorkflowRun> run = job.scheduleBuild2(0);
    rule.assertBuildStatus(Result.SUCCESS, run);
    destRepo.git("clone", bareDestRepo.toString(), ".");
    assertEquals(srcRepo.head(), destRepo.head());
  }

  @Test
  public void pipelineSuccess() throws Exception {

    String pipeline = "" +
        "pipeline {\n" +
        "    agent { label 'runner' }\n" +
        "    stages {\n" +
        "        stage('sync') {\n" +
        "            steps {\n" +
        "                cloudManagerRepoSync(url: '" + bareDestRepo + "', credentialsId: 'credentials')\n" +
        "            }\n" +
        "        }\n" +
        "    }\n" +
        "}\n";
    srcRepo.init();
    srcRepo.write("Jenkinsfile", pipeline);
    srcRepo.git("add", "Jenkinsfile");
    srcRepo.git("commit", "--message=Jenkinsfile");

    bareDestRepo.git("--bare", "init");

    WorkflowJob job = rule.jenkins.createProject(WorkflowJob.class, "test");
    rule.createOnlineSlave(Label.get("runner"));
    SCM scm = new GitSCM(srcRepo.toString());
    CpsScmFlowDefinition flow = new CpsScmFlowDefinition(scm, "Jenkinsfile");
    job.setDefinition(flow);

    QueueTaskFuture<WorkflowRun> run = job.scheduleBuild2(0);
    rule.assertBuildStatus(Result.SUCCESS, run);
    destRepo.git("clone", bareDestRepo.toString(), ".");
    assertEquals(srcRepo.head(), destRepo.head());
  }

  @Test
  public void pipelineCredentialedSuccess() throws Exception {
    Server server = new Server();
    ServerConnector connector = new ServerConnector(server);
    server.addConnector(connector);
    ServletContextHandler handler = new ServletContextHandler(ServletContextHandler.NO_SESSIONS);
    handler.setContextPath("/*");
    ServletHolder holder = new ServletHolder(new DefaultServlet());
    handler.addServlet(holder, "/*");
    server.setHandler(handler);
    server.start();


    String host = connector.getHost() == null ? "localhost" : connector.getHost();
    String repoUrl = "http://" + host + ":" + connector.getLocalPort() + "/repo";

    String pipeline = "" +
        "pipeline {\n" +
        "    agent { label 'master' }\n" +
        "    stages {\n" +
        "        stage('sync') {\n" +
        "            steps {\n" +
        "                cloudManagerRepoSync(url: '" + repoUrl + "', credentialsId: 'credentials')\n" +
        "            }\n" +
        "        }\n" +
        "    }\n" +
        "}\n";
    srcRepo.init();
    srcRepo.write("Jenkinsfile", pipeline);
    srcRepo.git("add", "Jenkinsfile");
    srcRepo.git("commit", "--message=Jenkinsfile");

    WorkflowJob job = rule.jenkins.createProject(WorkflowJob.class, "test");
    rule.createOnlineSlave(Label.get("runner"));
    SCM scm = new GitSCM(srcRepo.toString());
    CpsScmFlowDefinition flow = new CpsScmFlowDefinition(scm, "Jenkinsfile");
    job.setDefinition(flow);


    QueueTaskFuture<WorkflowRun> future = job.scheduleBuild2(0);
    WorkflowRun run = rule.assertBuildStatus(Result.FAILURE, future);
    rule.waitForMessage("using GIT_ASKPASS to set credentials ", run);
    assertTrue(StringUtils.contains(run.getLog(), "using GIT_ASKPASS to set credentials "));
  }

}
