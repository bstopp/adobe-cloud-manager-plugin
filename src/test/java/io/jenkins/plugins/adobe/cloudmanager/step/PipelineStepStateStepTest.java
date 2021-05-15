package io.jenkins.plugins.adobe.cloudmanager.step;

import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.commons.io.FileUtils;

import com.gargoylesoftware.htmlunit.html.HtmlPage;
import hudson.model.Executor;
import hudson.model.Result;
import hudson.util.Secret;
import io.adobe.cloudmanager.CloudManagerApi;
import io.adobe.cloudmanager.CloudManagerApiException;
import io.adobe.cloudmanager.PipelineExecution;
import io.adobe.cloudmanager.PipelineExecutionStepState;
import io.adobe.cloudmanager.StepAction;
import io.jenkins.plugins.adobe.cloudmanager.action.CloudManagerBuildAction;
import io.jenkins.plugins.adobe.cloudmanager.action.PipelineStepStateAction;
import io.jenkins.plugins.adobe.cloudmanager.config.AdobeIOConfig;
import io.jenkins.plugins.adobe.cloudmanager.config.AdobeIOProjectConfig;
import io.jenkins.plugins.adobe.cloudmanager.step.execution.Messages;
import io.jenkins.plugins.adobe.cloudmanager.step.execution.PipelineStepStateExecution;
import jenkins.util.Timer;
import mockit.Expectations;
import mockit.Mock;
import mockit.MockUp;
import mockit.Mocked;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.cps.CpsFlowExecution;
import org.jenkinsci.plugins.workflow.graph.FlowGraphWalker;
import org.jenkinsci.plugins.workflow.graph.FlowNode;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.jenkinsci.plugins.workflow.steps.StepExecution;
import org.jenkinsci.plugins.workflow.support.actions.PauseAction;
import org.jenkinsci.plugins.workflow.test.steps.SemaphoreStep;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.BuildWatcher;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.RestartableJenkinsRule;
import static io.jenkins.plugin.adobe.cloudmanager.test.TestHelper.*;
import static org.junit.Assert.*;

public class PipelineStepStateStepTest {

  @ClassRule
  public static BuildWatcher buildWatcher = new BuildWatcher();
  @Rule
  public RestartableJenkinsRule story = new RestartableJenkinsRule();
  @Mocked
  private PipelineExecution pipelineExecution;
  @Mocked
  private PipelineExecutionStepState stepState;
  @Mocked
  private AdobeIOProjectConfig projectConfig;
  @Mocked
  private CloudManagerApi api;

  private WorkflowRun setupRun(JenkinsRule rule) throws Exception {
    WorkflowJob job = rule.jenkins.createProject(WorkflowJob.class, "test");
    CpsFlowDefinition flow = new CpsFlowDefinition(
        "node('master') {\n" +
            "    semaphore 'before'\n" +
            "    acmPipelineStepState()\n" +
            "}",
        true);
    job.setDefinition(flow);
    WorkflowRun run = job.scheduleBuild2(0).waitForStart();
    SemaphoreStep.waitForStart("before/1", run);
    run.addAction(new CloudManagerBuildAction(AIO_PROJECT_NAME, "1", "1", "1"));
    SemaphoreStep.success("before/1", true);
    CpsFlowExecution cfe = (CpsFlowExecution) run.getExecutionPromise().get();
    while (run.getAction(PipelineStepStateAction.class) == null) {
      cfe.waitForSuspension();
    }
    // Now we're waiting for input.
    PipelineStepStateAction action = run.getAction(PipelineStepStateAction.class);
    assertEquals(1, action.getExecutions().size());
    return run;
  }

  @Test
  public void noBuildData() {
    story.then(rule -> {
      WorkflowJob job = rule.jenkins.createProject(WorkflowJob.class, "test");
      CpsFlowDefinition flow = new CpsFlowDefinition(
          "node('master') {\n" +
              "    acmPipelineStepState()\n" +
              "}",
          true);
      job.setDefinition(flow);
      WorkflowRun run = job.scheduleBuild2(0).get();
      rule.waitForMessage(Messages.AbstractStepExecution_error_missingBuildData(), run);
      rule.waitForCompletion(run);
      rule.assertBuildStatus(Result.FAILURE, run);
    });
  }

  @Test
  public void notificationEvent() {

    story.then(rule -> {
      new Expectations() {{

        pipelineExecution.getId();
        result = "ExecutionId";
        stepState.getAction();
        result = StepAction.build.name();
        stepState.getStatusState();
        result = PipelineExecutionStepState.Status.RUNNING;
      }};

      WorkflowRun run = setupRun(rule);

      // Poke the Execution to make it move.
      StepExecution.applyAll(PipelineStepStateExecution.class, execution -> {
        try {
          execution.occurred(pipelineExecution, stepState);
        } catch (IOException | InterruptedException e) {
          // do nothing
        }
        return null;
      }).get();
      sanityChecks(rule, run, Result.SUCCESS);
      assertTrue(run.getLog().contains(Messages.PipelineStepStateExecution_event_occurred("ExecutionId", "build", "RUNNING")));
    });
  }

  @Test
  public void codeQualityProceed() {
    story.then(rule -> {

      new MockUp<AdobeIOConfig>() {
        @Mock
        public AdobeIOProjectConfig projectConfigFor(String name) {
          return projectConfig;
        }
      };
      new MockUp<CloudManagerApi>() {
        @Mock
        public CloudManagerApi create(String org, String apiKey, String token, String baseUrl) {
          return api;
        }
      };

      new Expectations() {{
        pipelineExecution.getId();
        result = "ExecutionId";
        stepState.getAction();
        result = StepAction.codeQuality.name();
        stepState.getStatusState();
        result = PipelineExecutionStepState.Status.WAITING;
        projectConfig.authenticate();
        result = Secret.fromString(ACCESS_TOKEN);
        api.advanceExecution("1", "1", "1");
      }};

      WorkflowRun run = setupRun(rule);

      PipelineStepStateAction action = run.getAction(PipelineStepStateAction.class);
      PipelineStepStateExecution execution = action.getExecutions().get(0);
      // Poke the Execution to make it move.
      StepExecution.applyAll(PipelineStepStateExecution.class, ex -> {
        try {
          ex.waiting(pipelineExecution, stepState);
        } catch (IOException | InterruptedException e) {
          // do nothing
        }
        return null;
      }).get();
      rule.waitForMessage(Messages.PipelineStepStateExecution_event_occurred("ExecutionId", "codeQuality", "WAITING"), run);
      assertFalse(execution.isProcessed());
      assertEquals(StepAction.codeQuality, execution.getReason());
      rule.waitForMessage(Messages.PipelineStepStateExecution_prompt_waitingApproval(), run);
      JenkinsRule.WebClient client = rule.createWebClient();
      HtmlPage page = client.getPage(run, action.getUrlName());
      rule.submit(page.getFormByName(execution.getId()), "proceed");
      sanityChecks(rule, run, Result.SUCCESS);
    });
  }

  @Test
  public void approvalProceed() {
    story.then(rule -> {

      new MockUp<AdobeIOConfig>() {
        @Mock
        public AdobeIOProjectConfig projectConfigFor(String name) {
          return projectConfig;
        }
      };
      new MockUp<CloudManagerApi>() {
        @Mock
        public CloudManagerApi create(String org, String apiKey, String token, String baseUrl) {
          return api;
        }
      };

      new Expectations() {{
        pipelineExecution.getId();
        result = "ExecutionId";
        stepState.getAction();
        result = StepAction.approval.name();
        stepState.getStatusState();
        result = PipelineExecutionStepState.Status.WAITING;
        projectConfig.authenticate();
        result = Secret.fromString(ACCESS_TOKEN);
        api.advanceExecution("1", "1", "1");
      }};

      WorkflowRun run = setupRun(rule);

      PipelineStepStateAction action = run.getAction(PipelineStepStateAction.class);
      PipelineStepStateExecution execution = action.getExecutions().get(0);
      // Poke the Execution to make it move.
      StepExecution.applyAll(PipelineStepStateExecution.class, ex -> {
        try {
          ex.waiting(pipelineExecution, stepState);
        } catch (IOException | InterruptedException e) {
          // do nothing
        }
        return null;
      }).get();
      rule.waitForMessage(Messages.PipelineStepStateExecution_event_occurred("ExecutionId", "approval", "WAITING"), run);
      assertFalse(execution.isProcessed());
      assertEquals(StepAction.approval, execution.getReason());
      rule.waitForMessage(Messages.PipelineStepStateExecution_prompt_waitingApproval(), run);
      JenkinsRule.WebClient client = rule.createWebClient();
      HtmlPage page = client.getPage(run, action.getUrlName());
      rule.submit(page.getFormByName(execution.getId()), "proceed");
      sanityChecks(rule, run, Result.SUCCESS);
    });
  }

  @Test
  public void proceedApiFailure() {
    story.then(rule -> {

      new MockUp<AdobeIOConfig>() {
        @Mock
        public AdobeIOProjectConfig projectConfigFor(String name) {
          return projectConfig;
        }
      };
      new MockUp<CloudManagerApi>() {
        @Mock
        public CloudManagerApi create(String org, String apiKey, String token, String baseUrl) {
          return api;
        }
      };

      new Expectations() {{
        pipelineExecution.getId();
        result = "ExecutionId";
        stepState.getAction();
        result = StepAction.codeQuality.name();
        stepState.getStatusState();
        result = PipelineExecutionStepState.Status.WAITING;
        projectConfig.authenticate();
        result = Secret.fromString(ACCESS_TOKEN);
        api.advanceExecution("1", "1", "1");
        result = new CloudManagerApiException(CloudManagerApiException.ErrorType.FIND_PROGRAM, "1");
      }};

      WorkflowRun run = setupRun(rule);

      PipelineStepStateAction action = run.getAction(PipelineStepStateAction.class);
      PipelineStepStateExecution execution = action.getExecutions().get(0);
      // Poke the Execution to make it move.
      StepExecution.applyAll(PipelineStepStateExecution.class, ex -> {
        try {
          ex.waiting(pipelineExecution, stepState);
        } catch (IOException | InterruptedException e) {
          // do nothing
        }
        return null;
      }).get();
      rule.waitForMessage(Messages.PipelineStepStateExecution_event_occurred("ExecutionId", "codeQuality", "WAITING"), run);
      assertFalse(execution.isProcessed());
      assertEquals(StepAction.codeQuality, execution.getReason());
      rule.waitForMessage(Messages.PipelineStepStateExecution_prompt_waitingApproval(), run);
      JenkinsRule.WebClient client = rule.createWebClient();
      HtmlPage page = client.getPage(run, action.getUrlName());
      rule.submit(page.getFormByName(execution.getId()), "proceed");
      sanityChecks(rule, run, Result.FAILURE);
    });
  }

  @Test
  public void codeQualityCancel() {
    story.then(rule -> {

      new MockUp<AdobeIOConfig>() {
        @Mock
        public AdobeIOProjectConfig projectConfigFor(String name) {
          return projectConfig;
        }
      };
      new MockUp<CloudManagerApi>() {
        @Mock
        public CloudManagerApi create(String org, String apiKey, String token, String baseUrl) {
          return api;
        }
      };

      new Expectations() {{
        pipelineExecution.getId();
        result = "ExecutionId";
        stepState.getAction();
        result = StepAction.codeQuality.name();
        stepState.getStatusState();
        result = PipelineExecutionStepState.Status.WAITING;
        projectConfig.authenticate();
        result = Secret.fromString(ACCESS_TOKEN);
        api.cancelExecution("1", "1", "1");
      }};

      WorkflowRun run = setupRun(rule);

      PipelineStepStateAction action = run.getAction(PipelineStepStateAction.class);
      PipelineStepStateExecution execution = action.getExecutions().get(0);
      // Poke the Execution to make it move.
      StepExecution.applyAll(PipelineStepStateExecution.class, ex -> {
        try {
          ex.waiting(pipelineExecution, stepState);
        } catch (IOException | InterruptedException e) {
          // do nothing
        }
        return null;
      }).get();
      rule.waitForMessage(Messages.PipelineStepStateExecution_event_occurred("ExecutionId", "codeQuality", "WAITING"), run);
      assertFalse(execution.isProcessed());
      assertEquals(StepAction.codeQuality, execution.getReason());
      rule.waitForMessage(Messages.PipelineStepStateExecution_prompt_waitingApproval(), run);
      JenkinsRule.WebClient client = rule.createWebClient();
      HtmlPage page = client.getPage(run, action.getUrlName());
      rule.submit(page.getFormByName(execution.getId()), "cancel");
      sanityChecks(rule, run, Result.ABORTED);
    });
  }

  @Test
  public void approvalCancel() {
    story.then(rule -> {

      new MockUp<AdobeIOConfig>() {
        @Mock
        public AdobeIOProjectConfig projectConfigFor(String name) {
          return projectConfig;
        }
      };
      new MockUp<CloudManagerApi>() {
        @Mock
        public CloudManagerApi create(String org, String apiKey, String token, String baseUrl) {
          return api;
        }
      };

      new Expectations() {{
        pipelineExecution.getId();
        result = "ExecutionId";
        stepState.getAction();
        result = StepAction.approval.name();
        stepState.getStatusState();
        result = PipelineExecutionStepState.Status.WAITING;
        projectConfig.authenticate();
        result = Secret.fromString(ACCESS_TOKEN);
        api.cancelExecution("1", "1", "1");
      }};

      WorkflowRun run = setupRun(rule);

      PipelineStepStateAction action = run.getAction(PipelineStepStateAction.class);
      PipelineStepStateExecution execution = action.getExecutions().get(0);
      // Poke the Execution to make it move.
      StepExecution.applyAll(PipelineStepStateExecution.class, ex -> {
        try {
          ex.waiting(pipelineExecution, stepState);
        } catch (IOException | InterruptedException e) {
          // do nothing
        }
        return null;
      }).get();
      rule.waitForMessage(Messages.PipelineStepStateExecution_event_occurred("ExecutionId", "approval", "WAITING"), run);
      assertFalse(execution.isProcessed());
      assertEquals(StepAction.approval, execution.getReason());
      rule.waitForMessage(Messages.PipelineStepStateExecution_prompt_waitingApproval(), run);
      JenkinsRule.WebClient client = rule.createWebClient();
      HtmlPage page = client.getPage(run, action.getUrlName());
      rule.submit(page.getFormByName(execution.getId()), "cancel");
      sanityChecks(rule, run, Result.ABORTED);
    });
  }

  @Test
  public void cancelApiFailure() {
    story.then(rule -> {

      new MockUp<AdobeIOConfig>() {
        @Mock
        public AdobeIOProjectConfig projectConfigFor(String name) {
          return projectConfig;
        }
      };
      new MockUp<CloudManagerApi>() {
        @Mock
        public CloudManagerApi create(String org, String apiKey, String token, String baseUrl) {
          return api;
        }
      };

      new Expectations() {{
        pipelineExecution.getId();
        result = "ExecutionId";
        stepState.getAction();
        result = StepAction.codeQuality.name();
        stepState.getStatusState();
        result = PipelineExecutionStepState.Status.WAITING;
        projectConfig.authenticate();
        result = Secret.fromString(ACCESS_TOKEN);
        api.cancelExecution("1", "1", "1");
        result = new CloudManagerApiException(CloudManagerApiException.ErrorType.FIND_PROGRAM, "1");
      }};

      WorkflowRun run = setupRun(rule);

      PipelineStepStateAction action = run.getAction(PipelineStepStateAction.class);
      PipelineStepStateExecution execution = action.getExecutions().get(0);
      // Poke the Execution to make it move.
      StepExecution.applyAll(PipelineStepStateExecution.class, ex -> {
        try {
          ex.waiting(pipelineExecution, stepState);
        } catch (IOException | InterruptedException e) {
          // do nothing
        }
        return null;
      }).get();
      rule.waitForMessage(Messages.PipelineStepStateExecution_event_occurred("ExecutionId", "codeQuality", "WAITING"), run);
      assertFalse(execution.isProcessed());
      assertEquals(StepAction.codeQuality, execution.getReason());
      rule.waitForMessage(Messages.PipelineStepStateExecution_prompt_waitingApproval(), run);
      JenkinsRule.WebClient client = rule.createWebClient();
      HtmlPage page = client.getPage(run, action.getUrlName());
      rule.submit(page.getFormByName(execution.getId()), "cancel");
      sanityChecks(rule, run, Result.FAILURE);
    });
  }

  @Test
  public void doesNotFailWaitingOnUnknownStepAction() {
    story.then(rule -> {
      new Expectations() {{
        pipelineExecution.getId();
        result = "ExecutionId";
        stepState.getAction();
        result = "Unknown";
        stepState.getStatusState();
        result = PipelineExecutionStepState.Status.WAITING;
      }};

      WorkflowRun run = setupRun(rule);
      // Poke the Execution to make it move.
      AtomicReference<IllegalArgumentException> iae = new AtomicReference<>();
      StepExecution.applyAll(PipelineStepStateExecution.class, execution -> {
        try {
          execution.waiting(pipelineExecution, stepState);
        } catch (IOException | InterruptedException e) {
          // do nothing
        } catch (IllegalArgumentException e) {
          iae.set(e);
        }
        return null;
      }).get();
      assertNotNull(iae.get());
      assertTrue(run.getLog().contains(Messages.PipelineStepStateExecution_error_unknownStepAction("Unknown")));
      List<PauseAction> pauses = new ArrayList<>();
      for (FlowNode n : new FlowGraphWalker(run.getExecution())) {
        pauses.addAll(PauseAction.getPauseActions(n));
      }
      assertEquals(1, pauses.size());
      assertTrue(pauses.get(0).isPaused());
    });
  }

  @Test
  public void doesNotFailWaitingOnNotWaitingStepAction() {
    story.then(rule -> {
      new Expectations() {{
        pipelineExecution.getId();
        result = "ExecutionId";
        stepState.getAction();
        result = StepAction.build.name();
        stepState.getStatusState();
        result = PipelineExecutionStepState.Status.WAITING;
      }};

      WorkflowRun run = setupRun(rule);

      // Poke the Execution to make it move.
      AtomicReference<IllegalArgumentException> iae = new AtomicReference<>();
      StepExecution.applyAll(PipelineStepStateExecution.class, execution -> {
        try {
          execution.waiting(pipelineExecution, stepState);
        } catch (IOException | InterruptedException e) {
          // do nothing
        } catch (IllegalArgumentException e) {
          iae.set(e);
        }
        return null;
      }).get();
      assertNotNull(iae.get());
      assertTrue(run.getLog().contains(Messages.PipelineStepStateExecution_error_unknownStepAction("build")));
      List<PauseAction> pauses = new ArrayList<>();
      for (FlowNode n : new FlowGraphWalker(run.getExecution())) {
        pauses.addAll(PauseAction.getPauseActions(n));
      }
      assertEquals(1, pauses.size());
      assertTrue(pauses.get(0).isPaused());
    });
  }

  @Test
  public void remoteEventAdvancesWaiting() {
    story.then(rule -> {

      new Expectations() {{
        pipelineExecution.getId();
        result = "ExecutionId";
        stepState.getAction();
        result = StepAction.codeQuality.name();
        stepState.getStatusState();
        result = PipelineExecutionStepState.Status.WAITING;
      }};

      WorkflowRun run = setupRun(rule);

      PipelineStepStateAction action = run.getAction(PipelineStepStateAction.class);
      PipelineStepStateExecution execution = action.getExecutions().get(0);
      // Poke the Execution to make it move.
      StepExecution.applyAll(PipelineStepStateExecution.class, ex -> {
        try {
          ex.waiting(pipelineExecution, stepState);
        } catch (IOException | InterruptedException e) {
          // do nothing
        }
        return null;
      }).get();
      rule.waitForMessage(Messages.PipelineStepStateExecution_event_occurred("ExecutionId", "codeQuality", "WAITING"), run);
      assertFalse(execution.isProcessed());
      assertEquals(StepAction.codeQuality, execution.getReason());
      rule.waitForMessage(Messages.PipelineStepStateExecution_prompt_waitingApproval(), run);

      Timer.get().submit(() -> {
        try {
          execution.doEndQuietly();
        } catch (IOException | InterruptedException e) {
          e.printStackTrace();
        }
      });
      sanityChecks(rule, run, Result.SUCCESS);
      rule.waitForMessage(Messages.PipelineStepStateExecution_info_endQuietly(), run);
    });
  }

  @Test
  public void survivesRestart() {

    story.then(this::setupRun);

    story.then(rule -> {
      new MockUp<AdobeIOConfig>() {
        @Mock
        public AdobeIOProjectConfig projectConfigFor(String name) {
          return projectConfig;
        }
      };
      new Expectations() {{
        pipelineExecution.getId();
        result = "ExecutionId";
        stepState.getAction();
        result = StepAction.build.name();
        stepState.getStatusState();
        result = PipelineExecutionStepState.Status.RUNNING;
      }};

      WorkflowRun run = rule.jenkins.getItemByFullName("test", WorkflowJob.class).getBuildByNumber(1);
      // Poke the Execution to make it move.
      StepExecution.applyAll(PipelineStepStateExecution.class, execution -> {
        try {
          execution.occurred(pipelineExecution, stepState);
        } catch (IOException | InterruptedException e) {
          // do nothing
        }
        return null;
      }).get();
      sanityChecks(rule, run, Result.SUCCESS);
      assertTrue(run.getLog().contains(Messages.PipelineStepStateExecution_event_occurred("ExecutionId", "build", "RUNNING")));
    });
  }

  @Test
  public void handlesAbort() {

    story.then(this::setupRun);

    story.then(rule -> {
      new MockUp<AdobeIOConfig>() {
        @Mock
        public AdobeIOProjectConfig projectConfigFor(String name) {
          return projectConfig;
        }
      };
      new Expectations() {{
        pipelineExecution.getId();
        result = "ExecutionId";
        stepState.getAction();
        result = StepAction.build.name();
        stepState.getStatusState();
        result = PipelineExecutionStepState.Status.RUNNING;
      }};

      WorkflowRun run = rule.jenkins.getItemByFullName("test", WorkflowJob.class).getBuildByNumber(1);
      Executor executor;
      do {
        Thread.sleep(1000); // Take a beat, race condition for interrupting job before it's actually resumed.
      } while ((executor = run.getExecutor()) == null);
      executor.interrupt();
      sanityChecks(rule, run, Result.ABORTED);
    });
  }

  @Test
  public void handlesActionSubset() {
    story.then(rule -> {
      new Expectations() {{

        pipelineExecution.getId();
        result = "ExecutionId";
        times = 2;
        stepState.getAction();
        result = StepAction.build.name();
        stepState.getStatusState();
        result = PipelineExecutionStepState.Status.RUNNING;
      }};

      WorkflowJob job = rule.jenkins.createProject(WorkflowJob.class, "test");
      CpsFlowDefinition flow = new CpsFlowDefinition(
          "node('master') {\n" +
              "    semaphore 'before'\n" +
              "    acmPipelineStepState(actions: ['codeQuality', 'build', 'approval'])\n" +
              "}",
          true);
      job.setDefinition(flow);
      WorkflowRun run = job.scheduleBuild2(0).waitForStart();
      SemaphoreStep.waitForStart("before/1", run);
      run.addAction(new CloudManagerBuildAction(AIO_PROJECT_NAME, "1", "1", "1"));
      SemaphoreStep.success("before/1", true);
      CpsFlowExecution cfe = (CpsFlowExecution) run.getExecutionPromise().get();
      while (run.getAction(PipelineStepStateAction.class) == null) {
        cfe.waitForSuspension();
      }
      // Now we're waiting for input.
      PipelineStepStateAction action = run.getAction(PipelineStepStateAction.class);
      assertEquals(1, action.getExecutions().size());

      // Poke the Execution to make it move.
      StepExecution.applyAll(PipelineStepStateExecution.class, execution -> {
        try {
          execution.occurred(pipelineExecution, stepState);
        } catch (IOException | InterruptedException e) {
          // do nothing
        }
        return null;
      }).get();
      sanityChecks(rule, run, Result.SUCCESS);
      assertTrue(run.getLog().contains(Messages.PipelineStepStateExecution_event_occurred("ExecutionId", "build", "RUNNING")));
    });
  }


  private void sanityChecks(JenkinsRule rule, WorkflowRun run, Result result) throws Exception {
    rule.waitForCompletion(run);
    List<PauseAction> pauses = new ArrayList<>();
    for (FlowNode n : new FlowGraphWalker(run.getExecution())) {
      pauses.addAll(PauseAction.getPauseActions(n));
    }
    assertEquals(1, pauses.size());
    assertFalse(pauses.get(0).isPaused());
    assertTrue(run.getLog().contains(Messages.PipelineStepStateExecution_info_waiting()));
    assertFalse(run.getLog().contains(Messages.PipelineStepStateExecution_warn_endPause()));
    assertFalse(run.getLog().contains(Messages.PipelineStepStateExecution_warn_actionRemoval()));
    String xml = FileUtils.readFileToString(new File(run.getRootDir(), "build.xml"), Charset.defaultCharset());
    assertFalse(xml.contains(PipelineStepStateExecution.class.getName()));
    rule.assertBuildStatus(result, run);

  }
}