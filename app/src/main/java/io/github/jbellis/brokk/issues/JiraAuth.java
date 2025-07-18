package io.github.jbellis.brokk.issues;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

import io.github.jbellis.brokk.IProject;
import io.github.jbellis.brokk.IssueProvider;

public class JiraAuth {
    private static final Logger logger = LogManager.getLogger(JiraAuth.class);
    private final IssueProvider provider;
    private final @Nullable IProject project; // For logging context if available

    public JiraAuth(IProject project) {
        this(project.getIssuesProvider(), project);
    }

    public JiraAuth(IssueProvider provider, @Nullable IProject project) {
        this.provider = provider;
        this.project = project;
    }

    public OkHttpClient buildAuthenticatedClient() throws IOException {
        if (this.provider.type() != IssueProviderType.JIRA || !(this.provider.config() instanceof IssuesProviderConfig.JiraConfig jiraConfig)) {
            String errorMessage = "Attempted to build Jira client for a non-Jira or misconfigured issue provider. Provider type: " + this.provider.type();
            logger.error(errorMessage);
            throw new IOException(errorMessage);
        }

        String baseUrl = jiraConfig.baseUrl();
        String apiToken = jiraConfig.apiToken();

        if (baseUrl.isBlank()) {
            String errorMessage = "Jira base URL not configured. Please set it in the Jira provider configuration.";
            logger.error(errorMessage);
            throw new IOException(errorMessage);
        }

        OkHttpClient.Builder builder = new OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(10, TimeUnit.SECONDS)
                .followRedirects(true);

        String projectName = this.project != null ? this.project.getRoot().getFileName().toString() : "Unknown Project";

        if (!apiToken.isBlank()) {
            builder.addInterceptor(chain -> {
                Request originalRequest = chain.request();
                Request authenticatedRequest = originalRequest.newBuilder()
                        .header("Authorization", "Bearer " + apiToken)
                        .build();
                return chain.proceed(authenticatedRequest);
            });
            logger.debug("Authenticated OkHttpClient (Bearer Token) created for Jira for project: {}", projectName);
        } else {
            logger.warn("Jira API token (PAT) not configured for project: {}. Proceeding with unauthenticated client. Public Jira instances may still be accessible.", projectName);
        }
        return builder.build();
    }
}
