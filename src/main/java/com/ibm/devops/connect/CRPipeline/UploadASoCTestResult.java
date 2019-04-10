/**
 * (c) Copyright IBM Corporation 2018.
 * This is licensed under the following license.
 * The Apache License, Version 2.0 (http://www.apache.org/licenses/LICENSE-2.0)
 * U.S. Government Users Restricted Rights:  Use, duplication or disclosure restricted by GSA ADP Schedule Contract with IBM Corp.
 */

package com.ibm.devops.connect.CRPipeline;

import hudson.AbortException;
import hudson.Extension;
import hudson.FilePath;
import hudson.FilePath.FileCallable;
import hudson.Launcher;
import hudson.model.AbstractProject;
import hudson.remoting.VirtualChannel;
import hudson.model.Result;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.tasks.Builder;
import hudson.tasks.BuildStepDescriptor;
import hudson.model.Job;
import hudson.model.Build;
import hudson.EnvVars;

import java.lang.reflect.Method;
import hudson.model.Action;
import java.util.List;
import java.lang.reflect.InvocationTargetException;

import jenkins.tasks.SimpleBuildStep;

import java.io.File;
import java.io.IOException;
import net.sf.json.JSONObject;

import org.jenkinsci.remoting.RoleChecker;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.StaplerRequest;

import java.util.Map;

import org.apache.http.entity.mime.MultipartEntityBuilder;
import org.apache.http.HttpEntity;
import org.apache.http.entity.ContentType;

import com.ibm.devops.connect.CloudPublisher;

public class UploadASoCTestResult extends Builder implements SimpleBuildStep {

    private String tenantId;
    private String environment;
    private String appId;
    private String appExtId;
    private String appName;
    private String buildUrl;
    private String metricDefinition;
    private String recordName;
    private String commitId;

    @DataBoundConstructor
    public UploadASoCTestResult(
        String tenantId,
        String environment,
        String appId,
        String appExtId,
        String appName,
        String buildUrl,
        String metricDefinition,
        String recordName,
        String commitId
    ) {
        this.tenantId = tenantId;
        this.environment = environment;
        this.appId = appId;
        this.appExtId = appExtId;
        this.appName = appName;
        this.buildUrl = buildUrl;
        this.metricDefinition = metricDefinition;
        this.recordName = recordName;
        this.commitId = commitId;
    }

    public String getTenantId() { return tenantId; }
    public String getEnvironment() { return environment; }
    public String getAppId() { return appId; }
    public String getAppExtId() { return appExtId; }
    public String getAppName() { return appName; }
    public String getBuildUrl() { return buildUrl; }
    public String getMetricDefinition() { return metricDefinition; }
    public String getRecordName() { return recordName; }
    public String getCommitId() { return commitId; }

    @Override
    public void perform(final Run<?, ?> build, FilePath workspace, Launcher launcher, final TaskListener listener)
            throws AbortException, InterruptedException, IOException {

        EnvVars envVars = build.getEnvironment(listener);
        // Resolving all passed ${VARIABLES}
        String tenantIdValue = envVars.expand(this.tenantId);
        String environmentValue = envVars.expand(this.environment);
        String appIdValue = envVars.expand(this.appId);
        String appExtIdValue = envVars.expand(this.appExtId);
        String appNameValue = envVars.expand(this.appName);
        String buildUrlValue = envVars.expand(this.buildUrl);
        String metricDefinitionValue = envVars.expand(this.metricDefinition);
        String recordNameValue = envVars.expand(this.recordName);
        String commitIdValue = envVars.expand(this.commitId);

        // Job parentJob = (Job)build.getParent();
        // Run firstBuild = parentJob.getBuildByNumber(1);
        List<Action> actions = buil.getActions();

        for(Action action : actions) {
            if(action.getClass().getName().equals("com.ibm.appscan.jenkins.plugin.actions.ScanResults")) {
                listener.getLogger().println("We have found the Scan Results from AppScan");

                try {
                    Class scanResultClass = action.getClass();
                    Method getTotalFindingsMethod = scanResultClass.getDeclaredMethod("getTotalFindings");
                    Method getInfoCountMethod = scanResultClass.getDeclaredMethod("getInfoCount");
                    Method getLowCountMethod = scanResultClass.getDeclaredMethod("getLowCount");
                    Method getMediumCountMethod = scanResultClass.getDeclaredMethod("getMediumCount");
                    Method getHighCountMethod = scanResultClass.getDeclaredMethod("getHighCount");

                    int totalFindings = (int)getTotalFindingsMethod.invoke(action);
                    int lowFindings = (int)getLowCountMethod.invoke(action);
                    int mediumFindings = (int)getMediumCountMethod.invoke(action);
                    int highFindings = (int)getHighCountMethod.invoke(action);
                    int infoFindings = (int)getInfoCountMethod.invoke(action);

                    // The ASoC plugin has a bug where the info count is 0.  That is not accurate, so we adjust
                    if(infoFindings == 0 && totalFindings != (lowFindings + mediumFindings + highFindings + infoFindings)) {
                        infoFindings = totalFindings - (lowFindings + mediumFindings + highFindings);
                    }

                    listener.getLogger().println("Total ----> " + totalFindings);
                    listener.getLogger().println("info ----> " + infoFindings);
                    listener.getLogger().println("low ----> " + lowFindings);
                    listener.getLogger().println("medium ----> " + mediumFindings);
                    listener.getLogger().println("high ----> " + highFindings);

                    JSONObject payload = new JSONObject();
                    JSONObject application = new JSONObject();
                    JSONObject record = new JSONObject();
                    JSONObject value = new JSONObject();
                    JSONObject buildObj = new JSONObject();

                    buildObj.put("url", buildUrlValue);

                    application.put("id", appIdValue);
                    application.put("name", appNameValue);
                    application.put("externalId", appExtIdValue);

                    value.put("High", highFindings);
                    value.put("Medium", mediumFindings);
                    value.put("Low", lowFindings);
                    value.put("Info", infoFindings);

                    record.put("metricDefinitionId", metricDefinitionValue);
                    record.put("dataFormat", "json");
                    record.put("recordName", recordNameValue);
                    record.put("pluginType", "templatePlugin");
                    record.put("value", value);

                    payload.put("dataSet", "AppScan on Cloud Scan Results");
                    payload.put("environment", environmentValue);
                    payload.put("tenantId", tenantIdValue);
                    payload.put("record", record);
                    payload.put("application", application);
                    payload.put("build", buildObj);

                    listener.getLogger().println("Payload Doc To Upload: " + payload.toString());
                    listener.getLogger().println("Uploading Payload Doc");
                    try {
                        CloudPublisher.uploadQualityDataRaw(payload.toString());
                        listener.getLogger().println("Upload Complete");
                    } catch (Exception ex) {
                        listener.error("Error uploading ASoC data: " + ex.getClass() + " - " + ex.getMessage());
                        build.setResult(Result.FAILURE);
                    }

                } catch (NoSuchMethodException e1) {
                    listener.getLogger().println("Could not find method on the ScanResult Object.  Is this running the proper version of AppScan on Cloud plugin?");
                } catch (IllegalAccessException e2) {
                    listener.getLogger().println("Could not acces the method on the ScanResult Object.  Is this running the proper version of AppScan on Cloud plugin?");
                } catch (InvocationTargetException e3) {
                    listener.getLogger().println("Could not invoke the target on the ScanResult Object.  Is this running the proper version of AppScan on Cloud plugin?");
                }
            }
        }

    }

    @Extension
    public static class UploadJUnitTestResultDescriptor extends BuildStepDescriptor<Builder> {

        public UploadJUnitTestResultDescriptor() {
            load();
        }

        @Override
        public String getHelpFile() {
            return "/plugin/ibm-ucdeploy-build-steps/publish.html";
        }

        @Override
        public boolean configure(StaplerRequest req, JSONObject formData) throws FormException {
            req.bindJSON(this, formData);
            save();
            return super.configure(req, formData);
        }

        /**
         * {@inheritDoc}
         *
         * @return {@inheritDoc}
         */
        @Override
        public String getDisplayName() {
            return "UCV - Upload ASoC Scan Results to Velocity";
        }

        @Override
        public boolean isApplicable(Class<? extends AbstractProject> jobType) {
            return true;
        }
    }
}