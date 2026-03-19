package com.plugin.approvaljobstep;

import com.dtolabs.rundeck.core.execution.ExecutionListener;
import com.dtolabs.rundeck.core.plugins.PluginException;
import com.dtolabs.rundeck.core.storage.ResourceMeta;
import com.dtolabs.rundeck.core.execution.workflow.steps.StepException;
import com.dtolabs.rundeck.core.execution.workflow.steps.StepFailureReason;
import com.dtolabs.rundeck.core.plugins.Plugin;
import com.dtolabs.rundeck.core.plugins.PluginResourceLoader;
import com.dtolabs.rundeck.core.plugins.configuration.Description;
import com.dtolabs.rundeck.core.plugins.configuration.Describable;
import com.dtolabs.rundeck.core.plugins.configuration.DynamicProperties;
import com.dtolabs.rundeck.core.plugins.configuration.Property;
import com.dtolabs.rundeck.core.plugins.configuration.PropertyScope;
import com.dtolabs.rundeck.core.plugins.configuration.StringRenderingConstants;
import com.dtolabs.rundeck.plugins.descriptions.PluginDescription;
import com.dtolabs.rundeck.plugins.step.PluginStepContext;
import com.dtolabs.rundeck.plugins.step.StepPlugin;
import com.dtolabs.rundeck.plugins.util.DescriptionBuilder;
import com.dtolabs.rundeck.plugins.util.PropertyBuilder;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.rundeck.storage.api.PathUtil;
import org.rundeck.storage.api.Resource;
import org.rundeck.app.spi.Services;

import javax.mail.Message;
import javax.mail.PasswordAuthentication;
import javax.mail.Session;
import javax.mail.Transport;
import javax.mail.internet.MimeBodyPart;
import javax.mail.internet.InternetAddress;
import javax.mail.internet.MimeMessage;
import javax.mail.internet.MimeMultipart;
import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URL;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Date;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

@Plugin(service = "WorkflowStep", name = "approval-job-step")
@PluginDescription(
    title = "Approval Gate",
    description = "Adds an approval gate to a job workflow using centrally managed email or Slack settings."
)
public class ApprovalJobStep implements StepPlugin, Describable, DynamicProperties, PluginResourceLoader {
    private static final String PROP_APPROVAL_PROFILE = "approvalProfile";
    private static final String PROP_APPROVAL_MESSAGE = "approvalMessage";
    private static final String PROP_APPROVAL_TIMEOUT_MINUTES = "approvalTimeoutMinutes";
    private static final String PROP_AUTO_APPROVE_ON_TIMEOUT = "autoApproveOnTimeout";
    private static final String PROP_NOTIFICATION_METHOD = "notificationMethod";
    private static final String PROP_PRIMARY_APPROVER_EMAIL = "primaryApproverEmail";
    private static final String PROP_SECONDARY_APPROVER_EMAIL = "secondaryApproverEmail";
    private static final String PROP_ESCALATION_TIME_MINUTES = "escalationTimeMinutes";
    private static final String PROP_APPROVAL_ONE_PRIMARY_APPROVER_EMAIL = "approvalOnePrimaryApproverEmail";
    private static final String PROP_APPROVAL_ONE_SECONDARY_APPROVER_EMAIL = "approvalOneSecondaryApproverEmail";
    private static final String PROP_APPROVAL_ONE_PRIMARY_SLACK_USER_ID = "approvalOnePrimarySlackUserId";
    private static final String PROP_APPROVAL_ONE_SECONDARY_SLACK_USER_ID = "approvalOneSecondarySlackUserId";
    private static final String PROP_APPROVAL_ONE_DELIVERY_CHANNEL = "approvalOneDeliveryChannel";
    private static final String PROP_APPROVAL_ONE_ESCALATION_TIME_MINUTES = "approvalOneEscalationTimeMinutes";
    private static final String PROP_APPROVAL_TWO_PRIMARY_APPROVER_EMAIL = "approvalTwoPrimaryApproverEmail";
    private static final String PROP_APPROVAL_TWO_SECONDARY_APPROVER_EMAIL = "approvalTwoSecondaryApproverEmail";
    private static final String PROP_APPROVAL_TWO_PRIMARY_SLACK_USER_ID = "approvalTwoPrimarySlackUserId";
    private static final String PROP_APPROVAL_TWO_SECONDARY_SLACK_USER_ID = "approvalTwoSecondarySlackUserId";
    private static final String PROP_APPROVAL_TWO_DELIVERY_CHANNEL = "approvalTwoDeliveryChannel";
    private static final String PROP_APPROVAL_TWO_ESCALATION_TIME_MINUTES = "approvalTwoEscalationTimeMinutes";
    private static final String PROP_SMTP_SERVER = "smtpServer";
    private static final String PROP_SMTP_PORT = "smtpPort";
    private static final String PROP_SMTP_USERNAME = "smtpUsername";
    private static final String PROP_SMTP_PASSWORD_PATH = "smtpPasswordPath";
    private static final String PROP_FROM_EMAIL_ADDRESS = "fromEmailAddress";
    private static final String PROP_USE_TLS = "useTls";
    private static final String PROP_SLACK_BOT_TOKEN_PATH = "slackBotTokenPath";
    private static final String PROP_APPROVAL_URL_BASE = "approvalUrlBase";
    private static final String PROP_CHECK_INTERVAL_SECONDS = "checkIntervalSeconds";

    private static final int DEFAULT_CALLBACK_PORT = 5555;
    private static final Logger LOG = LoggerFactory.getLogger(ApprovalJobStep.class);
    private static final AtomicBoolean CALLBACK_SERVER_STARTED = new AtomicBoolean(false);
    private static volatile int CALLBACK_SERVER_PORT = -1;
    private static final Map<String, String> APPROVAL_RESULTS = new ConcurrentHashMap<>();
    private static final Map<String, String> APPROVAL_TOKENS = new ConcurrentHashMap<>();
    private static final Map<String, String> APPROVAL_APPROVER = new ConcurrentHashMap<>();
    private static final Map<String, Object> APPROVAL_MONITORS = new ConcurrentHashMap<>();
    private static final Object USER_CACHE_LOCK = new Object();
    private static final long USER_CACHE_TTL_MS = 60_000L;
    private static volatile UserSelectData USER_SELECT_CACHE;
    private static volatile long USER_SELECT_CACHE_TS;

    private String approvalMessage;

    private String approvalProfile;

    private Integer approvalTimeoutMinutes;

    private Boolean autoApproveOnTimeout;

    private String notificationMethod;

    private String primaryApproverEmail;

    private String secondaryApproverEmail;

    private Integer escalationTimeMinutes;

    private String approvalOnePrimaryApproverEmail;

    private String approvalOneSecondaryApproverEmail;

    private String approvalOnePrimarySlackUserId;

    private String approvalOneSecondarySlackUserId;

    private String approvalOneDeliveryChannel;

    private Integer approvalOneEscalationTimeMinutes;

    private String approvalTwoPrimaryApproverEmail;

    private String approvalTwoSecondaryApproverEmail;

    private String approvalTwoPrimarySlackUserId;

    private String approvalTwoSecondarySlackUserId;

    private String approvalTwoDeliveryChannel;

    private Integer approvalTwoEscalationTimeMinutes;

    private String smtpServer;

    private Integer smtpPort;

    private String smtpUsername;

    private String smtpPasswordPath;

    private String fromEmailAddress;

    private Boolean useTls;

    private String slackBotTokenPath;

    private String approvalUrlBase;

    private Integer checkIntervalSeconds;

    private String primarySlackUserId;

    private String secondarySlackUserId;

    private String deliveryChannel;

    @Override
    public Description getDescription() {
        DescriptionBuilder builder = DescriptionBuilder.builder();
        builder.name("approval-job-step")
            .title("Approval Gate")
            .description("Approval gate for workflow execution. Shared approvers, email/Slack delivery settings, escalation, and callback settings are configured centrally in project Approvals settings. WARNING: every pending approval keeps execution resources active until approved, denied, or timed out, and long waits can reduce platform performance.");

        builder.property(PropertyBuilder.builder()
            .select(PROP_APPROVAL_PROFILE)
            .title("Approval Profile")
            .description("Choose which centrally managed project approval profile this step should use.")
            .required(false)
            .defaultValue("approval1")
            .values(List.of("approval1", "approval2"))
            .labels(Map.of(
                "approval1", "Approval 1",
                "approval2", "Approval 2"
            ))
            .scope(PropertyScope.Instance)
            .renderingOption(StringRenderingConstants.GROUP_NAME, "Approval Configuration"));

        builder.property(PropertyBuilder.builder()
            .string(PROP_APPROVAL_MESSAGE)
            .title("Approval Message")
            .description("Message sent to approvers")
            .required(true)
            .scope(PropertyScope.Instance)
            .renderingOption(StringRenderingConstants.DISPLAY_TYPE_KEY, StringRenderingConstants.DisplayType.MULTI_LINE)
            .renderingOption(StringRenderingConstants.GROUP_NAME, "Approval Configuration"));

        builder.property(PropertyBuilder.builder()
            .booleanType(PROP_AUTO_APPROVE_ON_TIMEOUT)
            .title("Auto-approve on Timeout")
            .description("If true, timeout continues the workflow as approved. If false, timeout fails this step and terminates the job. Pending approvals keep execution resources active while they wait.")
            .defaultValue("false")
            .required(false)
            .scope(PropertyScope.Instance)
            .renderingOption(StringRenderingConstants.GROUP_NAME, "Approval Configuration"));

        return builder.build();
    }

    @Override
    public Map<String, Object> dynamicProperties(Map<String, Object> projectAndFrameworkValues, Services services) {
        return null;
    }

    @Override
    public List<String> listResources() throws PluginException, IOException {
        return List.of("WorkflowStep.approval-job-step.icon.png");
    }

    @Override
    public InputStream openResourceStreamFor(String name) throws PluginException, IOException {
        return this.getClass().getResourceAsStream("/" + name);
    }

    @Override
    public void executeStep(PluginStepContext context, Map<String, Object> configuration) throws StepException {
        final ExecutionListener logger = context.getExecutionContext().getExecutionListener();

        normalizeConfig(context, configuration);
        validateConfiguration();

        if (isBlank(this.approvalUrlBase)) {
            this.approvalUrlBase = "http://localhost:" + DEFAULT_CALLBACK_PORT;
        }
        int callbackPort = resolveCallbackPort(this.approvalUrlBase, DEFAULT_CALLBACK_PORT);
        startCallbackServerIfNeeded(logger, callbackPort);

        final String smtpPassword;
        final String slackBotToken;
        try {
            smtpPassword = "email".equalsIgnoreCase(this.deliveryChannel) ? getPasswordFromKeyStorage(context, this.smtpPasswordPath) : null;
            slackBotToken = "slack".equalsIgnoreCase(this.deliveryChannel) ? getPasswordFromKeyStorage(context, this.slackBotTokenPath) : null;
        } catch (Exception e) {
            throw new StepException("Error accessing approval delivery secret from key storage: " + e.getMessage(), e, StepFailureReason.ConfigurationFailure);
        }

        logger.log(2, "Starting approval workflow step");
        logger.log(1, "WARNING: pending approvals keep execution resources active while waiting and can exhaust worker threads at scale.");
        if (!Boolean.TRUE.equals(this.autoApproveOnTimeout)) {
            logger.log(1, "Notice: timeout without approval will fail this step and terminate the job execution.");
        }

        final String approvalId = UUID.randomUUID().toString();
        final String token = UUID.randomUUID().toString();
        APPROVAL_TOKENS.put(approvalId, token);
        APPROVAL_MONITORS.put(approvalId, new Object());

        final Map<String, Object> approvalData = new ConcurrentHashMap<>();
        approvalData.put("id", approvalId);
        approvalData.put("token", token);
        approvalData.put("message", this.approvalMessage);
        String projectName = context.getFrameworkProject();
        String jobName = "Unknown Job";
        String executionId = "unknown";
        String jobId = null;
        if (context.getDataContext() != null && context.getDataContext().get("job") != null) {
            Map<String, String> jobCtx = context.getDataContext().get("job");
            if (jobCtx.get("name") != null) {
                jobName = String.valueOf(jobCtx.get("name"));
            }
            if (jobCtx.get("execid") != null) {
                executionId = String.valueOf(jobCtx.get("execid"));
            }
            if (jobCtx.get("id") != null) {
                jobId = String.valueOf(jobCtx.get("id"));
            }
        }
        approvalData.put("jobName", jobName);
        approvalData.put("projectName", projectName);
        approvalData.put("executionId", executionId);
        approvalData.put("jobId", jobId);
        String rundeckBaseUrl = resolveRundeckBaseUrl(context);
        if (!isBlank(rundeckBaseUrl)) {
            approvalData.put("rundeckBaseUrl", rundeckBaseUrl);
            String executionUrl = rundeckBaseUrl + "/project/" + urlEncode(projectName) + "/execution/show/" + urlEncode(executionId);
            approvalData.put("executionUrl", executionUrl);
            if (!isBlank(jobId)) {
                String jobUrl = rundeckBaseUrl + "/project/" + urlEncode(projectName) + "/job/show/" + urlEncode(jobId);
                approvalData.put("jobUrl", jobUrl);
            }
        }
        approvalData.put("requestTime", new Date().toString());
        approvalData.put("status", "pending");
        approvalData.put("deliveryChannel", this.deliveryChannel);
        approvalData.put("currentApprover", "slack".equalsIgnoreCase(this.deliveryChannel) ? this.primarySlackUserId : this.primaryApproverEmail);

        try {
            sendApprovalNotification(smtpPassword, slackBotToken, approvalData, false, logger);
            logger.log(2, "Sent approval request to primary approver via " + this.deliveryChannel + ": " + currentPrimaryRecipientForLogs());
        } catch (Exception e) {
            throw new StepException("Failed to send approval notification: " + e.getMessage(), e, StepFailureReason.IOFailure);
        }

        final String result = waitForApproval(smtpPassword, slackBotToken, approvalData, logger);

        context.getExecutionContext().getOutputContext().addOutput("approval", "id", approvalId);
        context.getExecutionContext().getOutputContext().addOutput("approval", "result", result);
        context.getExecutionContext().getOutputContext().addOutput("approval", "approver", APPROVAL_APPROVER.getOrDefault(approvalId, "system"));

        APPROVAL_RESULTS.remove(approvalId);
        APPROVAL_TOKENS.remove(approvalId);
        APPROVAL_APPROVER.remove(approvalId);
        APPROVAL_MONITORS.remove(approvalId);

        if ("denied".equalsIgnoreCase(result)) {
            logger.log(0, "Approval denied by approver. Hard-stopping workflow execution.");
            context.getFlowControl().Halt(false);
            throw new StepException("Job execution denied by approver", StepFailureReason.PluginFailed);
        }
        if ("timeout".equalsIgnoreCase(result)) {
            throw new StepException("Approval request timed out", StepFailureReason.PluginFailed);
        }
    }

    private void normalizeConfig(PluginStepContext context, Map<String, Object> configuration) {
        Map<String, String> projectProps = loadProjectProperties(context);

        this.approvalProfile = stringOrDefault(configuration.get(PROP_APPROVAL_PROFILE), this.approvalProfile);
        this.approvalMessage = stringOrDefault(configuration.get(PROP_APPROVAL_MESSAGE), this.approvalMessage);
        this.notificationMethod = stringOrDefault(projectProps.get(projectPropKey(PROP_NOTIFICATION_METHOD)), this.notificationMethod);
        this.primaryApproverEmail = stringOrDefault(projectProps.get(projectPropKey(PROP_PRIMARY_APPROVER_EMAIL)), this.primaryApproverEmail);
        this.secondaryApproverEmail = stringOrDefault(projectProps.get(projectPropKey(PROP_SECONDARY_APPROVER_EMAIL)), this.secondaryApproverEmail);
        this.approvalOnePrimaryApproverEmail = stringOrDefault(projectProps.get(projectPropKey(PROP_APPROVAL_ONE_PRIMARY_APPROVER_EMAIL)), this.approvalOnePrimaryApproverEmail);
        this.approvalOneSecondaryApproverEmail = stringOrDefault(projectProps.get(projectPropKey(PROP_APPROVAL_ONE_SECONDARY_APPROVER_EMAIL)), this.approvalOneSecondaryApproverEmail);
        this.approvalOnePrimarySlackUserId = stringOrDefault(projectProps.get(projectPropKey(PROP_APPROVAL_ONE_PRIMARY_SLACK_USER_ID)), this.approvalOnePrimarySlackUserId);
        this.approvalOneSecondarySlackUserId = stringOrDefault(projectProps.get(projectPropKey(PROP_APPROVAL_ONE_SECONDARY_SLACK_USER_ID)), this.approvalOneSecondarySlackUserId);
        this.approvalOneDeliveryChannel = stringOrDefault(projectProps.get(projectPropKey(PROP_APPROVAL_ONE_DELIVERY_CHANNEL)), this.approvalOneDeliveryChannel);
        this.approvalTwoPrimaryApproverEmail = stringOrDefault(projectProps.get(projectPropKey(PROP_APPROVAL_TWO_PRIMARY_APPROVER_EMAIL)), this.approvalTwoPrimaryApproverEmail);
        this.approvalTwoSecondaryApproverEmail = stringOrDefault(projectProps.get(projectPropKey(PROP_APPROVAL_TWO_SECONDARY_APPROVER_EMAIL)), this.approvalTwoSecondaryApproverEmail);
        this.approvalTwoPrimarySlackUserId = stringOrDefault(projectProps.get(projectPropKey(PROP_APPROVAL_TWO_PRIMARY_SLACK_USER_ID)), this.approvalTwoPrimarySlackUserId);
        this.approvalTwoSecondarySlackUserId = stringOrDefault(projectProps.get(projectPropKey(PROP_APPROVAL_TWO_SECONDARY_SLACK_USER_ID)), this.approvalTwoSecondarySlackUserId);
        this.approvalTwoDeliveryChannel = stringOrDefault(projectProps.get(projectPropKey(PROP_APPROVAL_TWO_DELIVERY_CHANNEL)), this.approvalTwoDeliveryChannel);
        this.smtpServer = stringOrDefault(projectProps.get(projectPropKey(PROP_SMTP_SERVER)), this.smtpServer);
        this.smtpUsername = stringOrDefault(projectProps.get(projectPropKey(PROP_SMTP_USERNAME)), this.smtpUsername);
        this.smtpPasswordPath = stringOrDefault(projectProps.get(projectPropKey(PROP_SMTP_PASSWORD_PATH)), this.smtpPasswordPath);
        this.fromEmailAddress = stringOrDefault(projectProps.get(projectPropKey(PROP_FROM_EMAIL_ADDRESS)), this.fromEmailAddress);
        this.slackBotTokenPath = stringOrDefault(projectProps.get(projectPropKey(PROP_SLACK_BOT_TOKEN_PATH)), this.slackBotTokenPath);
        this.approvalUrlBase = stringOrDefault(projectProps.get(projectPropKey(PROP_APPROVAL_URL_BASE)), this.approvalUrlBase);

        this.approvalTimeoutMinutes = toInt(configuration.get(PROP_APPROVAL_TIMEOUT_MINUTES), valueOrDefault(this.approvalTimeoutMinutes, 60));
        this.escalationTimeMinutes = toInt(projectProps.get(projectPropKey(PROP_ESCALATION_TIME_MINUTES)), valueOrDefault(this.escalationTimeMinutes, 30));
        this.approvalOneEscalationTimeMinutes = toInt(projectProps.get(projectPropKey(PROP_APPROVAL_ONE_ESCALATION_TIME_MINUTES)), valueOrDefault(this.approvalOneEscalationTimeMinutes, 30));
        this.approvalTwoEscalationTimeMinutes = toInt(projectProps.get(projectPropKey(PROP_APPROVAL_TWO_ESCALATION_TIME_MINUTES)), valueOrDefault(this.approvalTwoEscalationTimeMinutes, 30));
        this.smtpPort = toInt(projectProps.get(projectPropKey(PROP_SMTP_PORT)), valueOrDefault(this.smtpPort, 587));
        this.checkIntervalSeconds = toInt(projectProps.get(projectPropKey(PROP_CHECK_INTERVAL_SECONDS)), valueOrDefault(this.checkIntervalSeconds, 30));
        this.useTls = toBool(projectProps.get(projectPropKey(PROP_USE_TLS)), this.useTls == null ? true : this.useTls);
        this.autoApproveOnTimeout = toBool(configuration.get(PROP_AUTO_APPROVE_ON_TIMEOUT), this.autoApproveOnTimeout == null ? false : this.autoApproveOnTimeout);
        applySelectedApprovalProfile();
    }

    private String projectPropKey(String propName) {
        return "project.plugin.WorkflowStep.approval-job-step." + propName;
    }

    private Map<String, String> loadProjectProperties(PluginStepContext context) {
        try {
            Map<String, String> props = context.getFramework().getFrameworkProjectMgr().getFrameworkProject(context.getFrameworkProject()).getProperties();
            if (props == null) {
                return Map.of();
            }
            LOG.info("ApprovalJobStep: loaded {} merged project properties for {}", props.size(), context.getFrameworkProject());
            return props;
        } catch (Exception e) {
            LOG.warn("ApprovalJobStep: unable to load project properties for {}: {}", context.getFrameworkProject(), e.getMessage());
            return Map.of();
        }
    }

    private void validateConfiguration() throws StepException {
        if (isBlank(approvalMessage)) throw new StepException("Approval message is required", StepFailureReason.ConfigurationFailure);
        if (isBlank(deliveryChannel)) {
            throw new StepException("Delivery channel is required", StepFailureReason.ConfigurationFailure);
        }
        if ("slack".equalsIgnoreCase(deliveryChannel)) {
            if (isBlank(primarySlackUserId)) throw new StepException("Primary Slack user ID is required", StepFailureReason.ConfigurationFailure);
            if (isBlank(slackBotTokenPath)) throw new StepException("Slack bot token path is required", StepFailureReason.ConfigurationFailure);
            if (!isBlank(secondaryApproverEmail) && !isBlank(secondarySlackUserId) && !secondarySlackUserId.startsWith("U") && !secondarySlackUserId.startsWith("W")) {
                throw new StepException("Secondary Slack user ID format looks invalid", StepFailureReason.ConfigurationFailure);
            }
            if (!primarySlackUserId.startsWith("U") && !primarySlackUserId.startsWith("W")) {
                throw new StepException("Primary Slack user ID format looks invalid", StepFailureReason.ConfigurationFailure);
            }
            return;
        }
        if (isBlank(primaryApproverEmail)) throw new StepException("Primary approver email is required", StepFailureReason.ConfigurationFailure);
        if (isBlank(smtpServer)) throw new StepException("SMTP server is required", StepFailureReason.ConfigurationFailure);
        if (isBlank(smtpUsername)) throw new StepException("SMTP username is required", StepFailureReason.ConfigurationFailure);
        if (isBlank(smtpPasswordPath)) throw new StepException("SMTP password path is required", StepFailureReason.ConfigurationFailure);
        if (isBlank(fromEmailAddress)) throw new StepException("From email address is required", StepFailureReason.ConfigurationFailure);
        if (!isValidEmail(primaryApproverEmail)) throw new StepException("Primary approver email format is invalid", StepFailureReason.ConfigurationFailure);
        if (!isBlank(secondaryApproverEmail) && !isValidEmail(secondaryApproverEmail)) throw new StepException("Secondary approver email format is invalid", StepFailureReason.ConfigurationFailure);
    }

    private void applySelectedApprovalProfile() {
        String selectedProfile = normalizeProfileSelection(this.approvalProfile);
        this.deliveryChannel = firstNonBlank(this.notificationMethod, "email");
        if ("custom".equals(selectedProfile)) {
            return;
        }
        if ("approval2".equals(selectedProfile)) {
            this.primaryApproverEmail = firstNonBlank(this.approvalTwoPrimaryApproverEmail, this.primaryApproverEmail);
            this.secondaryApproverEmail = firstNonBlank(this.approvalTwoSecondaryApproverEmail, this.secondaryApproverEmail);
            this.primarySlackUserId = firstNonBlank(this.approvalTwoPrimarySlackUserId, this.primarySlackUserId);
            this.secondarySlackUserId = firstNonBlank(this.approvalTwoSecondarySlackUserId, this.secondarySlackUserId);
            if (isBlank(this.notificationMethod)) {
                this.deliveryChannel = firstNonBlank(this.approvalTwoDeliveryChannel, this.deliveryChannel);
            }
            this.escalationTimeMinutes = valueOrDefault(this.approvalTwoEscalationTimeMinutes, valueOrDefault(this.escalationTimeMinutes, 30));
            return;
        }
        this.primaryApproverEmail = firstNonBlank(this.approvalOnePrimaryApproverEmail, this.primaryApproverEmail);
        this.secondaryApproverEmail = firstNonBlank(this.approvalOneSecondaryApproverEmail, this.secondaryApproverEmail);
        this.primarySlackUserId = firstNonBlank(this.approvalOnePrimarySlackUserId, this.primarySlackUserId);
        this.secondarySlackUserId = firstNonBlank(this.approvalOneSecondarySlackUserId, this.secondarySlackUserId);
        if (isBlank(this.notificationMethod)) {
            this.deliveryChannel = firstNonBlank(this.approvalOneDeliveryChannel, this.deliveryChannel);
        }
        this.escalationTimeMinutes = valueOrDefault(this.approvalOneEscalationTimeMinutes, valueOrDefault(this.escalationTimeMinutes, 30));
    }

    private String normalizeProfileSelection(String configuredProfile) {
        String normalized = trimOrNull(configuredProfile);
        if (normalized == null) {
            return hasLegacyOverrides() ? "custom" : "approval1";
        }
        switch (normalized.toLowerCase()) {
            case "approval2":
                return "approval2";
            case "custom":
                return "custom";
            case "approval1":
            default:
                return "approval1";
        }
    }

    private boolean hasLegacyOverrides() {
        return !isBlank(primaryApproverEmail)
            || !isBlank(secondaryApproverEmail);
    }

    private String waitForApproval(String smtpPassword, String slackBotToken, Map<String, Object> approvalData, ExecutionListener logger) throws StepException {
        final String id = String.valueOf(approvalData.get("id"));
        final long start = System.currentTimeMillis();
        final long timeoutMs = (long) valueOrDefault(this.approvalTimeoutMinutes, 60) * 60_000L;
        final long escalationMs = (long) valueOrDefault(this.escalationTimeMinutes, 30) * 60_000L;
        final long checkMs = (long) valueOrDefault(this.checkIntervalSeconds, 30) * 1000L;
        boolean escalated = false;
        final Object monitor = APPROVAL_MONITORS.computeIfAbsent(id, ignored -> new Object());

        while (true) {
            final String response = APPROVAL_RESULTS.get(id);
            if (response != null) {
                logger.log(2, "Received approval response: " + response);
                return response;
            }

            final long elapsed = System.currentTimeMillis() - start;

            if (hasSecondaryRecipientConfigured() && !escalated && elapsed >= escalationMs) {
                try {
                    sendEscalationNotification(smtpPassword, slackBotToken, approvalData, logger);
                    approvalData.put("currentApprover", "slack".equalsIgnoreCase(this.deliveryChannel) ? this.secondarySlackUserId : this.secondaryApproverEmail);
                    escalated = true;
                    logger.log(2, "Escalated approval request to secondary approver via " + this.deliveryChannel + ": " + currentSecondaryRecipientForLogs());
                } catch (Exception e) {
                    logger.log(1, "Failed to send escalation notification: " + e.getMessage());
                }
            }

            if (timeoutMs > 0 && elapsed >= timeoutMs) {
                if (Boolean.TRUE.equals(autoApproveOnTimeout)) {
                    APPROVAL_APPROVER.put(id, "system-timeout");
                    return "approved";
                }
                APPROVAL_APPROVER.put(id, "system-timeout");
                return "timeout";
            }

            try {
                synchronized (monitor) {
                    monitor.wait(checkMs);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new StepException("Approval wait interrupted", e, StepFailureReason.Interrupted);
            }
        }
    }

    private void sendApprovalNotification(String smtpPassword, String slackBotToken, Map<String, Object> approvalData, boolean escalation, ExecutionListener logger) throws Exception {
        if ("slack".equalsIgnoreCase(this.deliveryChannel)) {
            String slackUserId = escalation ? this.secondarySlackUserId : this.primarySlackUserId;
            sendApprovalSlackMessage(slackBotToken, slackUserId, approvalData, escalation, logger);
            return;
        }
        String recipientEmail = escalation ? this.secondaryApproverEmail : this.primaryApproverEmail;
        sendApprovalEmail(smtpPassword, recipientEmail, approvalData, escalation, logger);
    }

    private void sendEscalationNotification(String smtpPassword, String slackBotToken, Map<String, Object> approvalData, ExecutionListener logger) throws Exception {
        sendApprovalNotification(smtpPassword, slackBotToken, approvalData, true, logger);
    }

    private boolean hasSecondaryRecipientConfigured() {
        if ("slack".equalsIgnoreCase(this.deliveryChannel)) {
            return !isBlank(this.secondarySlackUserId);
        }
        return !isBlank(this.secondaryApproverEmail);
    }

    private String currentPrimaryRecipientForLogs() {
        if ("slack".equalsIgnoreCase(this.deliveryChannel)) {
            return this.primarySlackUserId;
        }
        return this.primaryApproverEmail;
    }

    private String currentSecondaryRecipientForLogs() {
        if ("slack".equalsIgnoreCase(this.deliveryChannel)) {
            return this.secondarySlackUserId;
        }
        return this.secondaryApproverEmail;
    }

    private void sendApprovalSlackMessage(String slackBotToken, String slackUserId, Map<String, Object> approvalData, boolean escalation, ExecutionListener logger) throws Exception {
        if (isBlank(slackBotToken)) {
            throw new IOException("Slack bot token could not be loaded from key storage");
        }
        if (isBlank(slackUserId)) {
            throw new IOException("Slack recipient user ID is not configured");
        }
        String conversationId = openSlackDirectConversation(slackBotToken, slackUserId);
        String message = buildSlackFallbackText(approvalData, escalation);
        String blocks = buildSlackBlocksJson(approvalData, escalation);
        logger.log(2, "Connecting to Slack API for DM delivery to " + slackUserId);
        Map<String, String> response = postSlackJson(
            "https://slack.com/api/chat.postMessage",
            slackBotToken,
            "{\"channel\":\"" + jsonEscape(conversationId) + "\",\"text\":\"" + jsonEscape(message) + "\",\"blocks\":" + blocks + "}"
        );
        if (!"true".equals(response.get("ok"))) {
            throw new IOException("Slack chat.postMessage failed: " + firstNonBlank(response.get("error"), "unknown_error"));
        }
        logger.log(2, "Slack DM accepted by Slack API for " + slackUserId);
    }

    private String openSlackDirectConversation(String slackBotToken, String slackUserId) throws Exception {
        Map<String, String> response = postSlackJson(
            "https://slack.com/api/conversations.open",
            slackBotToken,
            "{\"users\":\"" + jsonEscape(slackUserId) + "\"}"
        );
        if (!"true".equals(response.get("ok"))) {
            throw new IOException("Slack conversations.open failed: " + firstNonBlank(response.get("error"), "unknown_error"));
        }
        String channelId = response.get("channel.id");
        if (isBlank(channelId)) {
            throw new IOException("Slack conversations.open did not return a DM channel id");
        }
        return channelId;
    }

    private Map<String, String> postSlackJson(String endpoint, String slackBotToken, String jsonBody) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(endpoint).openConnection();
        connection.setRequestMethod("POST");
        connection.setDoOutput(true);
        connection.setConnectTimeout(10_000);
        connection.setReadTimeout(10_000);
        connection.setRequestProperty("Authorization", "Bearer " + slackBotToken);
        connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
        byte[] body = jsonBody.getBytes(StandardCharsets.UTF_8);
        connection.getOutputStream().write(body);

        int code = connection.getResponseCode();
        InputStream stream = code >= 400 ? connection.getErrorStream() : connection.getInputStream();
        String responseBody = "";
        if (stream != null) {
            try (InputStream input = stream; ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
                input.transferTo(baos);
                responseBody = baos.toString(StandardCharsets.UTF_8);
            }
        }
        connection.disconnect();

        Map<String, String> response = new LinkedHashMap<>();
        response.put("ok", extractJsonBoolean(responseBody, "ok"));
        response.put("error", extractJsonString(responseBody, "error"));
        response.put("channel.id", extractNestedJsonString(responseBody, "channel", "id"));
        if (code >= 400 && !"true".equals(response.get("ok"))) {
            throw new IOException("Slack API request failed (" + code + "): " + firstNonBlank(response.get("error"), responseBody));
        }
        return response;
    }

    private String buildSlackFallbackText(Map<String, Object> approvalData, boolean escalation) {
        String id = String.valueOf(approvalData.get("id"));
        String token = String.valueOf(approvalData.get("token"));
        String approveUrl = buildApprovalActionUrl(approvalData, "approve", id, token);
        String denyUrl = buildApprovalActionUrl(approvalData, "deny", id, token);
        String executionUrl = mapStringOrNull(approvalData, "executionUrl");
        String jobUrl = mapStringOrNull(approvalData, "jobUrl");

        StringBuilder body = new StringBuilder();
        body.append(escalation ? "*Escalated approval required*\n" : "*Approval required*\n");
        body.append("Project: ").append(String.valueOf(approvalData.get("projectName"))).append("\n");
        body.append("Job: ").append(String.valueOf(approvalData.get("jobName"))).append("\n");
        body.append("Message: ").append(String.valueOf(approvalData.get("message"))).append("\n");
        body.append("Timeout: ").append(valueOrDefault(this.approvalTimeoutMinutes, 60)).append(" minutes\n");
        if (!isBlank(jobUrl)) {
            body.append("Rundeck Job: ").append(jobUrl).append("\n");
        } else if (!isBlank(executionUrl)) {
            body.append("Rundeck Execution: ").append(executionUrl).append("\n");
        }
        body.append("Approve: ").append(approveUrl).append("\n");
        body.append("Deny: ").append(denyUrl);
        return body.toString();
    }

    private String buildSlackBlocksJson(Map<String, Object> approvalData, boolean escalation) {
        String id = String.valueOf(approvalData.get("id"));
        String token = String.valueOf(approvalData.get("token"));
        String approveUrl = buildApprovalActionUrl(approvalData, "approve", id, token);
        String denyUrl = buildApprovalActionUrl(approvalData, "deny", id, token);
        String executionUrl = mapStringOrNull(approvalData, "executionUrl");
        String jobUrl = mapStringOrNull(approvalData, "jobUrl");
        String primaryRundeckUrl = !isBlank(jobUrl) ? jobUrl : executionUrl;
        String primaryRundeckLabel = !isBlank(jobUrl) ? "Open Job in Rundeck" : "Open Execution in Rundeck";
        String title = escalation ? "Escalated Approval Required" : "Approval Required";
        String subtitle = escalation
            ? "No response yet. This request has been escalated."
            : "A workflow is waiting for your approval.";
        String timeout = String.valueOf(valueOrDefault(this.approvalTimeoutMinutes, 60));

        StringBuilder json = new StringBuilder();
        json.append("[");
        appendSlackBlock(json, sectionTextBlock("*Rundeck Approval*\n" + title));
        appendSlackBlock(json, contextBlock("This request expires in *" + timeout + " minutes*."));
        appendSlackBlock(json, sectionTextBlock("*" + subtitle + "*"));

        StringBuilder fields = new StringBuilder();
        fields.append("[");
        appendSlackJsonItem(fields, mrkdwnField("*Message*\n" + slackMrkdwn(String.valueOf(approvalData.get("message")))));
        appendSlackJsonItem(fields, mrkdwnField("*Job*\n" + slackMrkdwn(String.valueOf(approvalData.get("jobName")))));
        appendSlackJsonItem(fields, mrkdwnField("*Project*\n" + slackMrkdwn(String.valueOf(approvalData.get("projectName")))));
        appendSlackJsonItem(fields, mrkdwnField("*Requested*\n" + slackMrkdwn(String.valueOf(approvalData.get("requestTime")))));
        if (!isBlank(primaryRundeckUrl)) {
            appendSlackJsonItem(fields, mrkdwnField("*Rundeck Link*\n<" + slackMrkdwn(primaryRundeckUrl) + "|" + slackMrkdwn(primaryRundeckLabel) + ">"));
        }
        fields.append("]");
        appendSlackBlock(json, "{\"type\":\"section\",\"fields\":" + fields + "}");

        StringBuilder actions = new StringBuilder();
        actions.append("[");
        appendSlackJsonItem(actions, buttonElement("approve_action", "Approve", approveUrl, "primary"));
        appendSlackJsonItem(actions, buttonElement("deny_action", "Deny", denyUrl, "danger"));
        if (!isBlank(primaryRundeckUrl)) {
            appendSlackJsonItem(actions, buttonElement("rundeck_action", primaryRundeckLabel, primaryRundeckUrl, null));
        }
        actions.append("]");
        appendSlackBlock(json, "{\"type\":\"actions\",\"elements\":" + actions + "}");

        appendSlackBlock(json, contextBlock("If the buttons do not work, open the Rundeck link and use the approval page there."));
        json.append("]");
        return json.toString();
    }

    private static void appendSlackBlock(StringBuilder json, String blockJson) {
        appendSlackJsonItem(json, blockJson);
    }

    private static void appendSlackJsonItem(StringBuilder json, String itemJson) {
        if (json.length() > 1 && json.charAt(json.length() - 1) != '[') {
            json.append(",");
        }
        json.append(itemJson);
    }

    private static String sectionTextBlock(String text) {
        return "{\"type\":\"section\",\"text\":{\"type\":\"mrkdwn\",\"text\":\"" + jsonEscape(text) + "\"}}";
    }

    private static String contextBlock(String text) {
        return "{\"type\":\"context\",\"elements\":[{\"type\":\"mrkdwn\",\"text\":\"" + jsonEscape(text) + "\"}]}";
    }

    private static String mrkdwnField(String text) {
        return "{\"type\":\"mrkdwn\",\"text\":\"" + jsonEscape(text) + "\"}";
    }

    private static String buttonElement(String actionId, String text, String url, String style) {
        StringBuilder json = new StringBuilder();
        json.append("{\"type\":\"button\",\"action_id\":\"").append(jsonEscape(actionId))
            .append("\",\"text\":{\"type\":\"plain_text\",\"text\":\"").append(jsonEscape(text))
            .append("\",\"emoji\":true},\"url\":\"").append(jsonEscape(url)).append("\"");
        if (!isBlank(style)) {
            json.append(",\"style\":\"").append(jsonEscape(style)).append("\"");
        }
        json.append("}");
        return json.toString();
    }

    private static String slackMrkdwn(String input) {
        if (input == null) {
            return "";
        }
        return input.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static String jsonEscape(String value) {
        if (value == null) {
            return "";
        }
        return value
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t");
    }

    private static String extractJsonBoolean(String body, String key) {
        if (body == null) {
            return null;
        }
        String needle = "\"" + key + "\":";
        int start = body.indexOf(needle);
        if (start < 0) {
            return null;
        }
        int valueStart = start + needle.length();
        while (valueStart < body.length() && Character.isWhitespace(body.charAt(valueStart))) {
            valueStart++;
        }
        if (body.startsWith("true", valueStart)) {
            return "true";
        }
        if (body.startsWith("false", valueStart)) {
            return "false";
        }
        return null;
    }

    private static String extractJsonString(String body, String key) {
        if (body == null) {
            return null;
        }
        String needle = "\"" + key + "\":\"";
        int start = body.indexOf(needle);
        if (start < 0) {
            return null;
        }
        int valueStart = start + needle.length();
        StringBuilder out = new StringBuilder();
        boolean escaped = false;
        for (int i = valueStart; i < body.length(); i++) {
            char c = body.charAt(i);
            if (escaped) {
                out.append(c);
                escaped = false;
            } else if (c == '\\') {
                escaped = true;
            } else if (c == '"') {
                return out.toString();
            } else {
                out.append(c);
            }
        }
        return null;
    }

    private static String extractNestedJsonString(String body, String objectKey, String key) {
        if (body == null) {
            return null;
        }
        String objectNeedle = "\"" + objectKey + "\":{";
        int objectStart = body.indexOf(objectNeedle);
        if (objectStart < 0) {
            return null;
        }
        int sectionStart = objectStart + objectNeedle.length();
        int braceDepth = 1;
        int sectionEnd = sectionStart;
        while (sectionEnd < body.length() && braceDepth > 0) {
            char c = body.charAt(sectionEnd++);
            if (c == '{') {
                braceDepth++;
            } else if (c == '}') {
                braceDepth--;
            }
        }
        if (sectionEnd <= sectionStart) {
            return null;
        }
        return extractJsonString(body.substring(sectionStart, sectionEnd), key);
    }

    private void sendApprovalEmail(String smtpPassword, String toEmail, Map<String, Object> approvalData, boolean escalation, ExecutionListener logger) throws Exception {
        Properties props = new Properties();
        props.put("mail.smtp.host", smtpServer);
        props.put("mail.smtp.port", String.valueOf(valueOrDefault(smtpPort, 587)));
        props.put("mail.smtp.auth", "true");
        if (Boolean.TRUE.equals(useTls)) {
            props.put("mail.smtp.starttls.enable", "true");
            props.put("mail.smtp.starttls.required", "true");
        }

        Session session = Session.getInstance(props, new javax.mail.Authenticator() {
            @Override
            protected PasswordAuthentication getPasswordAuthentication() {
                return new PasswordAuthentication(smtpUsername, smtpPassword);
            }
        });

        Message message = new MimeMessage(session);
        message.setFrom(new InternetAddress(fromEmailAddress));
        message.setRecipients(Message.RecipientType.TO, InternetAddress.parse(toEmail));
        String subjectPrefix = escalation ? "[ESCALATED] " : "";
        String executionId = String.valueOf(approvalData.get("executionId"));
        message.setSubject(subjectPrefix + "Approval Required [Exec " + executionId + "]: " + approvalData.get("jobName"));

        String htmlBody = buildEmailBody(approvalData, escalation);
        String textBody = buildPlainTextBody(approvalData, escalation);
        MimeBodyPart textPart = new MimeBodyPart();
        textPart.setText(textBody, StandardCharsets.UTF_8.name());
        MimeBodyPart htmlPart = new MimeBodyPart();
        htmlPart.setContent(htmlBody, "text/html; charset=UTF-8");
        MimeMultipart mp = new MimeMultipart("alternative");
        mp.addBodyPart(textPart);
        mp.addBodyPart(htmlPart);
        message.setContent(mp);

        logger.log(2, "Connecting to SMTP server " + smtpServer + ":" + valueOrDefault(smtpPort, 587) + " as " + smtpUsername);
        Transport transport = session.getTransport("smtp");
        try {
            transport.connect();
            logger.log(2, "SMTP authentication succeeded for " + smtpUsername + " on " + smtpServer + ":" + valueOrDefault(smtpPort, 587));
            transport.sendMessage(message, message.getAllRecipients());
            logger.log(2, "Approval email delivered to " + toEmail + " from " + fromEmailAddress);
        } finally {
            try {
                transport.close();
            } catch (Exception ignored) {
            }
        }
    }

    private String buildEmailBody(Map<String, Object> approvalData, boolean escalation) {
        String id = String.valueOf(approvalData.get("id"));
        String token = String.valueOf(approvalData.get("token"));
        String approveUrl = buildApprovalActionUrl(approvalData, "approve", id, token);
        String denyUrl = buildApprovalActionUrl(approvalData, "deny", id, token);
        String title = escalation ? "Escalated Approval Required" : "Approval Required";
        String subtitle = escalation
            ? "No response yet. This request has been escalated."
            : "A workflow is waiting for your approval.";
        String timeout = String.valueOf(valueOrDefault(this.approvalTimeoutMinutes, 60));
        String executionUrl = mapStringOrNull(approvalData, "executionUrl");
        String jobUrl = mapStringOrNull(approvalData, "jobUrl");
        String primaryRundeckUrl = !isBlank(jobUrl) ? jobUrl : executionUrl;
        String primaryRundeckLabel = !isBlank(jobUrl) ? "Open Job in Rundeck" : "Open Execution in Rundeck";

        return "<!doctype html><html><head><meta charset=\"UTF-8\"/>"
            + "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\"/>"
            + "</head><body style=\"margin:0;background:#f5f7fa;font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,Arial,sans-serif;color:#1f2937;\">"
            + "<table role=\"presentation\" width=\"100%\" cellspacing=\"0\" cellpadding=\"0\" style=\"background:#f5f7fa;padding:24px 12px;\">"
            + "<tr><td align=\"center\">"
            + "<table role=\"presentation\" width=\"640\" cellspacing=\"0\" cellpadding=\"0\" style=\"max-width:640px;background:#ffffff;border:1px solid #e5e7eb;border-radius:12px;overflow:hidden;\">"
            + "<tr><td style=\"background:#06ac38;padding:18px 24px;color:#ffffff;\">"
            + "<div style=\"font-size:22px;font-weight:700;letter-spacing:.2px;\">Rundeck Approval</div>"
            + "<div style=\"opacity:.95;font-size:14px;margin-top:4px;\">" + escapeHtml(title) + "</div>"
            + "</td></tr>"
            + "<tr><td style=\"padding:24px;\">"
            + "<div style=\"font-size:18px;font-weight:700;color:#111827;margin-bottom:8px;\">" + escapeHtml(subtitle) + "</div>"
            + "<div style=\"font-size:14px;color:#4b5563;margin-bottom:20px;\">This request expires in <b>" + escapeHtml(timeout) + " minutes</b>.</div>"
            + "<table role=\"presentation\" width=\"100%\" cellspacing=\"0\" cellpadding=\"0\" style=\"border:1px solid #e5e7eb;border-radius:10px;background:#ffffff;\">"
            + "<tr><td style=\"padding:16px 18px;font-size:13px;color:#6b7280;text-transform:uppercase;letter-spacing:.08em;border-bottom:1px solid #eef2f7;\">Request Details</td></tr>"
            + "<tr><td style=\"padding:16px 18px;\">"
            + "<table role=\"presentation\" width=\"100%\" cellspacing=\"0\" cellpadding=\"0\" style=\"font-size:15px;line-height:1.6;\">"
            + row("Message", String.valueOf(approvalData.get("message")))
            + row("Job", String.valueOf(approvalData.get("jobName")))
            + row("Project", String.valueOf(approvalData.get("projectName")))
            + rowWithOptionalLink("Rundeck Link", primaryRundeckLabel, primaryRundeckUrl)
            + row("Requested", String.valueOf(approvalData.get("requestTime")))
            + "</table></td></tr></table>"
            + "<table role=\"presentation\" width=\"100%\" cellspacing=\"0\" cellpadding=\"0\" style=\"margin-top:22px;\"><tr>"
            + "<td style=\"padding-right:8px;\" width=\"50%\">"
            + "<a href=\"" + escapeHtml(approveUrl) + "\" style=\"display:block;text-align:center;background:#06ac38;color:#ffffff;text-decoration:none;font-weight:700;padding:12px 14px;border-radius:8px;\">Approve</a>"
            + "</td>"
            + "<td style=\"padding-left:8px;\" width=\"50%\">"
            + "<a href=\"" + escapeHtml(denyUrl) + "\" style=\"display:block;text-align:center;background:#ffffff;color:#111827;text-decoration:none;font-weight:700;padding:11px 14px;border-radius:8px;border:1px solid #d1d5db;\">Deny</a>"
            + "</td>"
            + "</tr></table>"
            + "<div style=\"font-size:12px;color:#6b7280;margin-top:16px;\">If the buttons do not work, copy and paste the links from the plain-text part of this email.</div>"
            + "</td></tr></table></td></tr></table></body></html>";
    }

    private String buildPlainTextBody(Map<String, Object> approvalData, boolean escalation) {
        StringBuilder body = new StringBuilder();
        if (escalation) {
            body.append("*** ESCALATED APPROVAL REQUEST ***\n\n");
        }
        String id = String.valueOf(approvalData.get("id"));
        String token = String.valueOf(approvalData.get("token"));
        body.append("Job Approval Required\n");
        body.append("====================\n\n");
        body.append("Approval Message:\n").append(approvalData.get("message")).append("\n\n");
        body.append("Job Name: ").append(approvalData.get("jobName")).append("\n");
        body.append("Project: ").append(approvalData.get("projectName")).append("\n");
        body.append("Request Time: ").append(approvalData.get("requestTime")).append("\n");
        String executionUrl = mapStringOrNull(approvalData, "executionUrl");
        String jobUrl = mapStringOrNull(approvalData, "jobUrl");
        String primaryRundeckUrl = !isBlank(jobUrl) ? jobUrl : executionUrl;
        if (!isBlank(primaryRundeckUrl)) {
            body.append("Rundeck Link: ").append(primaryRundeckUrl).append("\n");
        }
        body.append("\n");
        body.append("Approve: ").append(buildApprovalActionUrl(approvalData, "approve", id, token)).append("\n");
        body.append("Deny: ").append(buildApprovalActionUrl(approvalData, "deny", id, token)).append("\n\n");
        return body.toString();
    }

    private String buildApprovalActionUrl(Map<String, Object> approvalData, String action, String id, String token) {
        String appBase = mapStringOrNull(approvalData, "rundeckBaseUrl");
        String projectName = mapStringOrNull(approvalData, "projectName");
        if (!isBlank(appBase) && !isBlank(projectName)) {
            return appBase
                + "/menu/approval/" + action
                + "?project=" + urlEncode(projectName)
                + "&id=" + urlEncode(id)
                + "&token=" + urlEncode(token);
        }
        return approvalUrlBase + "/" + action + "?id=" + urlEncode(id) + "&token=" + urlEncode(token);
    }

    private static String urlEncode(String input) {
        if (input == null) {
            return "";
        }
        return URLEncoder.encode(input, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static String mapStringOrNull(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (value == null) {
            return null;
        }
        return trimOrNull(String.valueOf(value));
    }

    private static String rowWithOptionalLink(String label, String value, String href) {
        if (isBlank(href)) {
            return row(label, value);
        }
        return "<tr>"
            + "<td style=\"width:170px;color:#6b7280;vertical-align:top;padding:3px 0;\">" + escapeHtml(label) + "</td>"
            + "<td style=\"color:#111827;padding:3px 0;\"><a href=\"" + escapeHtml(href) + "\" style=\"color:#0f6a3f;text-decoration:underline;font-weight:600;\">" + escapeHtml(value) + "</a></td>"
            + "</tr>";
    }

    private static String row(String label, String value) {
        return "<tr>"
            + "<td style=\"width:170px;color:#6b7280;vertical-align:top;padding:3px 0;\">" + escapeHtml(label) + "</td>"
            + "<td style=\"color:#111827;padding:3px 0;\">" + escapeHtml(value) + "</td>"
            + "</tr>";
    }

    private static String resolveRundeckBaseUrl(PluginStepContext context) {
        String fromSystem = trimOrNull(System.getProperty("grails.serverURL"));
        if (!isBlank(fromSystem)) {
            return stripTrailingSlash(fromSystem);
        }
        try {
            if (context != null && context.getIFramework() != null && context.getIFramework().getPropertyLookup() != null) {
                String fromLookup = trimOrNull(context.getIFramework().getPropertyLookup().getProperty("framework.server.url"));
                if (!isBlank(fromLookup)) {
                    return stripTrailingSlash(fromLookup);
                }
            }
        } catch (Exception ignored) {
            // Ignore and continue with env fallback.
        }
        String fromEnv = trimOrNull(System.getenv("RUNDECK_GRAILS_URL"));
        if (!isBlank(fromEnv)) {
            return stripTrailingSlash(fromEnv);
        }
        return null;
    }

    private static String stripTrailingSlash(String input) {
        if (input == null) {
            return null;
        }
        String value = input.trim();
        while (value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        return value;
    }

    private static String escapeHtml(String input) {
        if (input == null) return "";
        return input
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&#39;");
    }

    private static void startCallbackServerIfNeeded(ExecutionListener logger, int callbackPort) throws StepException {
        if (!CALLBACK_SERVER_STARTED.compareAndSet(false, true)) {
            if (CALLBACK_SERVER_PORT == callbackPort) {
                return;
            }
            throw new StepException(
                "Approval callback server is already running on port " + CALLBACK_SERVER_PORT
                    + " and cannot switch to " + callbackPort + " in the same JVM",
                StepFailureReason.ConfigurationFailure
            );
        }
        CALLBACK_SERVER_PORT = callbackPort;
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("0.0.0.0", callbackPort), 0);

            server.createContext("/approve", ex -> handleCallback(ex, "approved"));
            server.createContext("/deny", ex -> handleCallback(ex, "denied"));
            server.setExecutor(null);
            server.start();
            logger.log(2, "Approval callback server started on port " + callbackPort);
        } catch (IOException e) {
            CALLBACK_SERVER_STARTED.set(false);
            CALLBACK_SERVER_PORT = -1;
            throw new StepException("Failed to start callback server on port " + callbackPort, e, StepFailureReason.IOFailure);
        }
    }

    private static int resolveCallbackPort(String baseUrl, int dflt) {
        if (isBlank(baseUrl)) {
            return dflt;
        }
        try {
            URI uri = URI.create(baseUrl.trim());
            return uri.getPort() > 0 ? uri.getPort() : dflt;
        } catch (IllegalArgumentException e) {
            return dflt;
        }
    }

    private static void handleCallback(HttpExchange ex, String result) throws IOException {
        Map<String, String> q = parseQuery(ex.getRequestURI().getRawQuery());
        String id = q.get("id");
        String token = q.get("token");
        String approver = q.getOrDefault("approver", "link-user");

        int code;
        String status;
        String body;
        boolean valid = !isBlank(id) && !isBlank(token) && token.equals(APPROVAL_TOKENS.get(id));
        if (!valid) {
            LOG.warn("Approval callback rejected: result={}, id={}, reason=invalid_or_expired_token", result, id);
            code = 403;
            status = "invalid";
            body = renderCallbackPage(
                status,
                "Invalid or expired approval token",
                "This link is not valid anymore (already used, old email, or wrong execution). Open the latest approval email for the current run."
            );
        } else {
            APPROVAL_RESULTS.put(id, result);
            APPROVAL_APPROVER.put(id, approver);
            LOG.info("Approval callback accepted: result={}, id={}, approver={}", result, id, approver);
            Object monitor = APPROVAL_MONITORS.get(id);
            if (monitor != null) {
                synchronized (monitor) {
                    monitor.notifyAll();
                }
            }
            code = 200;
            status = "approved".equalsIgnoreCase(result) ? "approved" : "denied";
            body = renderCallbackPage(
                status,
                "Approval recorded: " + result,
                "You can close this tab now. Rundeck has received your response."
            );
        }

        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
        ex.sendResponseHeaders(code, bytes.length);
        ex.getResponseBody().write(bytes);
        ex.close();
    }

    private static String renderCallbackPage(String status, String title, String message) {
        String accent;
        String chipBg;
        String chipText;
        String icon;
        if ("approved".equals(status)) {
            accent = "#0ea85d";
            chipBg = "rgba(14,168,93,.12)";
            chipText = "#06653a";
            icon = "Approved";
        } else if ("denied".equals(status)) {
            accent = "#d94848";
            chipBg = "rgba(217,72,72,.12)";
            chipText = "#8b1f1f";
            icon = "Denied";
        } else {
            accent = "#a06a00";
            chipBg = "rgba(160,106,0,.12)";
            chipText = "#6b4700";
            icon = "Invalid";
        }

        return "<!doctype html><html><head><meta charset=\"UTF-8\"/>"
            + "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\"/>"
            + "<title>Rundeck Approval</title>"
            + "</head><body style=\"margin:0;min-height:100vh;background:radial-gradient(circle at 10% 10%,#e8f6ec 0,#f6faf7 35%,#eef3ff 100%);font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,Arial,sans-serif;color:#1f2937;\">"
            + "<div style=\"max-width:680px;margin:0 auto;padding:48px 20px;\">"
            + "<div style=\"background:#fff;border:1px solid #e5e7eb;border-radius:16px;box-shadow:0 12px 32px rgba(15,23,42,.08);overflow:hidden;\">"
            + "<div style=\"height:8px;background:" + accent + ";\"></div>"
            + "<div style=\"padding:28px;\">"
            + "<div style=\"display:inline-block;padding:6px 12px;border-radius:999px;background:" + chipBg + ";color:" + chipText + ";font-weight:700;font-size:12px;letter-spacing:.05em;text-transform:uppercase;\">"
            + escapeHtml(icon) + "</div>"
            + "<h1 style=\"margin:14px 0 8px;font-size:32px;line-height:1.15;\">"
            + escapeHtml(title) + "</h1>"
            + "<p style=\"margin:0 0 14px;color:#4b5563;font-size:16px;line-height:1.6;\">"
            + escapeHtml(message) + "</p>"
            + "<p style=\"margin:22px 0 0;color:#9ca3af;font-size:12px;\">Rundeck Callback</p>"
            + "</div></div></div></body></html>";
    }

    private static Map<String, String> parseQuery(String query) {
        Map<String, String> out = new ConcurrentHashMap<>();
        if (query == null || query.isEmpty()) return out;
        String[] pairs = query.split("&");
        for (String p : pairs) {
            String[] kv = p.split("=", 2);
            String k = URLDecoder.decode(kv[0], StandardCharsets.UTF_8);
            String v = kv.length > 1 ? URLDecoder.decode(kv[1], StandardCharsets.UTF_8) : "";
            out.put(k, v);
        }
        return out;
    }

    private static String getPasswordFromKeyStorage(PluginStepContext context, String path) throws Exception {
        Resource<ResourceMeta> resource = context.getExecutionContext().getStorageTree().getResource(path);
        if (resource == null || resource.getContents() == null) {
            throw new IOException("Key not found: " + path);
        }
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            resource.getContents().writeContent(baos);
            return new String(baos.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private static boolean isValidEmail(String email) {
        return email != null && email.contains("@") && email.contains(".");
    }

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }

    private static int valueOrDefault(Integer val, int dflt) {
        return val == null ? dflt : val;
    }

    private static Integer toInt(Object val, int dflt) {
        if (val == null) return dflt;
        if (val instanceof Number) return ((Number) val).intValue();
        return Integer.parseInt(String.valueOf(val).trim());
    }

    private static Boolean toBool(Object val, boolean dflt) {
        if (val == null) return dflt;
        if (val instanceof Boolean) return (Boolean) val;
        return "true".equalsIgnoreCase(String.valueOf(val).trim());
    }

    private static String stringOrDefault(Object val, String dflt) {
        if (val == null) return dflt;
        String normalized = trimOrNull(String.valueOf(val));
        return normalized == null ? dflt : normalized;
    }

    private static String firstNonBlank(String preferred, String fallback) {
        return !isBlank(preferred) ? preferred : fallback;
    }

    private static UserSelectData loadUserSelectData() {
        long now = System.currentTimeMillis();
        UserSelectData cached = USER_SELECT_CACHE;
        if (cached != null && (now - USER_SELECT_CACHE_TS) < USER_CACHE_TTL_MS) {
            return cached;
        }
        synchronized (USER_CACHE_LOCK) {
            cached = USER_SELECT_CACHE;
            if (cached != null && (now - USER_SELECT_CACHE_TS) < USER_CACHE_TTL_MS) {
                return cached;
            }
            UserSelectData loaded = fetchUserSelectData();
            USER_SELECT_CACHE = loaded;
            USER_SELECT_CACHE_TS = now;
            return loaded;
        }
    }

    private static UserSelectData fetchUserSelectData() {
        String dbUrl = System.getenv("RUNDECK_DATABASE_URL");
        String dbUser = System.getenv("RUNDECK_DATABASE_USERNAME");
        String dbPass = System.getenv("RUNDECK_DATABASE_PASSWORD");
        String dbDriver = System.getenv("RUNDECK_DATABASE_DRIVER");

        if (isBlank(dbUrl)) {
            System.err.println("ApprovalJobStep: DB URL env not set for user dropdowns");
            LOG.warn("ApprovalJobStep: DB URL env not set for user dropdowns (RUNDECK_DATABASE_URL missing)");
            DbConfig fallback = readDbConfigFromFile();
            if (fallback == null) {
                return UserSelectData.empty();
            }
            dbUrl = fallback.url;
            dbUser = fallback.user;
            dbPass = fallback.pass;
            dbDriver = fallback.driver;
        }

        if (!isBlank(dbDriver)) {
            try {
                Class.forName(dbDriver);
            } catch (ClassNotFoundException ignored) {
                System.err.println("ApprovalJobStep: DB driver not found: " + dbDriver);
                LOG.warn("ApprovalJobStep: DB driver not found: {}", dbDriver);
            }
        }

        LinkedHashMap<String, String> labels = new LinkedHashMap<>();
        List<String> values = new ArrayList<>();

        String sql = "select login, email, first_name, last_name from rduser where email is not null and email <> '' order by last_name, first_name, login";
        try (Connection conn = openConnection(dbUrl, dbUser, dbPass);
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                String email = trimOrNull(rs.getString("email"));
                if (isBlank(email)) continue;
                String login = trimOrNull(rs.getString("login"));
                String first = trimOrNull(rs.getString("first_name"));
                String last = trimOrNull(rs.getString("last_name"));
                String displayName = buildDisplayName(login, first, last, email);
                if (!labels.containsKey(email)) {
                    labels.put(email, displayName);
                    values.add(email);
                }
            }
        } catch (SQLException e) {
            System.err.println("ApprovalJobStep: failed to load users: " + e.getMessage());
            LOG.warn("ApprovalJobStep: failed to load users for dropdowns: {}", e.getMessage());
            return UserSelectData.empty();
        }

        System.err.println("ApprovalJobStep: loaded " + values.size() + " users for dropdowns");
        LOG.debug("ApprovalJobStep: loaded {} user emails for dropdowns", values.size());
        return new UserSelectData(values, labels);
    }

    private static Connection openConnection(String dbUrl, String dbUser, String dbPass) throws SQLException {
        if (!isBlank(dbUser)) {
            return DriverManager.getConnection(dbUrl, dbUser, dbPass == null ? "" : dbPass);
        }
        if (dbUrl != null && dbUrl.startsWith("jdbc:h2:")) {
            try {
                return DriverManager.getConnection(dbUrl, "sa", "");
            } catch (SQLException ignored) {
                // Fall through for URL-only attempts.
            }
        }
        return DriverManager.getConnection(dbUrl);
    }

    private static DbConfig readDbConfigFromFile() {
        String base = System.getProperty("rdeck.base");
        if (isBlank(base)) {
            base = "/home/rundeck";
        }
        String path = base + "/server/config/rundeck-config.properties";
        Properties props = new Properties();
        try (InputStream in = new FileInputStream(path)) {
            props.load(in);
        } catch (IOException e) {
            System.err.println("ApprovalJobStep: failed to read " + path + ": " + e.getMessage());
            LOG.warn("ApprovalJobStep: failed to read {}: {}", path, e.getMessage());
            return null;
        }

        String url = trimOrNull(props.getProperty("dataSource.url"));
        String user = trimOrNull(props.getProperty("dataSource.username"));
        String pass = props.getProperty("dataSource.password");
        String driver = trimOrNull(props.getProperty("dataSource.driverClassName"));
        if (isBlank(url)) {
            System.err.println("ApprovalJobStep: dataSource.url missing in " + path);
            LOG.warn("ApprovalJobStep: dataSource.url missing in {}", path);
            return null;
        }
        System.err.println("ApprovalJobStep: using DB config from " + path);
        LOG.info("ApprovalJobStep: using DB config from {}", path);
        return new DbConfig(url, user, pass, driver);
    }

    private static String trimOrNull(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static String buildDisplayName(String login, String first, String last, String email) {
        String name;
        if (!isBlank(first) || !isBlank(last)) {
            name = String.format("%s %s", Objects.toString(first, ""), Objects.toString(last, "")).trim();
        } else if (!isBlank(login)) {
            name = login;
        } else {
            name = email;
        }
        return name + " <" + email + ">";
    }

    private static final class UserSelectData {
        private final List<String> values;
        private final Map<String, String> labels;

        private UserSelectData(List<String> values, Map<String, String> labels) {
            this.values = values;
            this.labels = labels;
        }

        private static UserSelectData empty() {
            return new UserSelectData(List.of(), Map.of());
        }
    }

    private static final class DbConfig {
        private final String url;
        private final String user;
        private final String pass;
        private final String driver;

        private DbConfig(String url, String user, String pass, String driver) {
            this.url = url;
            this.user = user;
            this.pass = pass;
            this.driver = driver;
        }
    }
}
