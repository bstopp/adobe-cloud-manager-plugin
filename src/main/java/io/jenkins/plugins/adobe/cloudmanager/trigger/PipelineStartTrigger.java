package io.jenkins.plugins.adobe.cloudmanager.trigger;

import java.nio.channels.Pipe;
import java.util.function.Predicate;
import javax.annotation.Nonnull;

import org.apache.commons.lang3.StringUtils;

import hudson.AbortException;
import hudson.Extension;
import hudson.model.Item;
import hudson.model.Job;
import hudson.triggers.Trigger;
import hudson.triggers.TriggerDescriptor;
import hudson.util.ListBoxModel;
import io.adobe.cloudmanager.CloudManagerApi;
import io.jenkins.plugins.adobe.cloudmanager.util.CloudManagerApiUtil;
import io.jenkins.plugins.adobe.cloudmanager.util.DescriptorHelper;
import jenkins.model.ParameterizedJobMixIn;
import org.jenkinsci.Symbol;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class PipelineStartTrigger extends Trigger<Job<?, ?>> {

  private static final Logger LOGGER = LoggerFactory.getLogger(PipelineStartTrigger.class);

  private final String aioProject;
  private final String programId;
  private final String pipelineId;

  @DataBoundConstructor
  public PipelineStartTrigger(String aioProject, String program, String pipeline) throws AbortException {

    CloudManagerApi api = createApi(aioProject);
    this.aioProject = aioProject;
    this.programId = getProgramId(api, program);
    this.pipelineId = getPipelineId(api, program, pipeline);
  }

  /**
   * Predicate for stream processing.
   */
  public static Predicate<PipelineStartTrigger> interestedIn(PipelineStartEvent event) {
    return (trigger) ->
        StringUtils.equals(trigger.getAioProject(), event.getAioProject()) &&
        StringUtils.equals(trigger.getProgramId(), event.getProgramId()) &&
        StringUtils.equals(trigger.getPipelineId(), event.getPipelineId());
  }


  @Nonnull
  private static CloudManagerApi createApi(String aioProject) throws AbortException {
    return CloudManagerApiUtil.createApi().apply(aioProject).orElseThrow(() -> new AbortException(Messages.PipelineStartTrigger_error_missingAioProject(aioProject)));
  }

  @Nonnull
  private static String getProgramId(CloudManagerApi api, String program) throws AbortException {
    try {
      return String.valueOf(Integer.parseInt(program));
    } catch (NumberFormatException e) {
      LOGGER.debug(io.jenkins.plugins.adobe.cloudmanager.builder.Messages.CloudManagerBuilder_debug_lookupProgramId(program));
      return CloudManagerApiUtil.getProgramId(api, program).orElseThrow(() -> new AbortException(Messages.PipelineStartTrigger_error_missingProgram(program)));
    }
  }

  @Nonnull
  private static String getPipelineId(CloudManagerApi api, String programId, String pipeline) throws AbortException {
    try {
      return String.valueOf(Integer.parseInt(pipeline));
    } catch (NumberFormatException e) {
      LOGGER.debug(io.jenkins.plugins.adobe.cloudmanager.builder.Messages.CloudManagerBuilder_debug_lookupPipelineId(programId));
      return CloudManagerApiUtil.getPipelineId(api, programId, pipeline).orElseThrow(() -> new AbortException(Messages.PipelineStartTrigger_error_missingPipeline(pipeline)));
    }
  }

  public String getAioProject() {
    return aioProject;
  }

  public String getProgramId() {
    return programId;
  }

  public String getPipelineId() {
    return pipelineId;
  }

  public void onEvent(PipelineStartEvent event) {
    if (job == null) {
      return; // nothing to do, no job to start.
    }
    if (!(job instanceof ParameterizedJobMixIn.ParameterizedJob)) {
      LOGGER.warn(Messages.PipelineStartTrigger_warn_invalidJob(job.getName()));
      return; // Invalid job type.
    }

    LOGGER.debug(Messages.PipelineStartTrigger_debug_startJob(event.getAioEventId()));
    ((ParameterizedJobMixIn.ParameterizedJob) job).scheduleBuild(0, new CMPipelineStartCause(event.getAioEventId()));
  }

  @Extension
  @Symbol("acmPipelineStart")
  public static final class DescriptorImpl extends TriggerDescriptor {

    @Override
    public boolean isApplicable(Item item) {
      return item instanceof Job && item instanceof ParameterizedJobMixIn.ParameterizedJob;
    }

    /**
     * Lists the Adobe IO Projects available.
     */
    public ListBoxModel doFillAioProjectItems() {
      return DescriptorHelper.fillAioProjectItems();
    }

    /**
     * List the Programs available based on the selected Adobe IO Project.
     */
    public ListBoxModel doFillProgramItems(@QueryParameter String aioProject) {
      return DescriptorHelper.fillProgramItems(aioProject);
    }

    /**
     * List the Pipelines associated with the selected Program.
     */
    public ListBoxModel doFillPipelineItems(@QueryParameter String aioProject, @QueryParameter String program) {
      return DescriptorHelper.fillPipelineItems(aioProject, program);
    }
  }
}