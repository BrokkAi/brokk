package io.github.jbellis.brokk.issues;

import io.github.jbellis.brokk.MainProject;
import okhttp3.Credentials;
import okhttp3.Interceptor;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

import io.github.jbellis.brokk.IProject;
import io.github.jbellis.brokk.issues.IssueProviderType;
import io.github.jbellis.brokk.issues.IssuesProviderConfig;

public class JiraAuth {
    private static final Logger logger = LogManager.getLogger(JiraAuth.class);
    private final IProject project;

    public JiraAuth(IProject project) {
        this.project = project;
    }

    public OkHttpClient buildAuthenticatedClient() throws IOException {
        if (project == null) {
            String errorMessage = "Project is null, cannot retrieve Jira credentials.";
            logger.error(errorMessage);
            throw new IOException(errorMessage);
        }

        io.github.jbellis.brokk.IssueProvider provider = project.getIssuesProvider();
        if (provider.type() != IssueProviderType.JIRA || !(provider.config() instanceof IssuesProviderConfig.JiraConfig jiraConfig)) {
            String errorMessage = "Attempted to build Jira client for a non-Jira or misconfigured issue provider. Provider type: " + provider.type();
            logger.error(errorMessage);
            throw new IOException(errorMessage);
        }

        String baseUrl = jiraConfig.baseUrl();
        String apiToken = jiraConfig.apiToken();

        if (baseUrl == null || baseUrl.isBlank()) {
            String errorMessage = "Jira base URL not configured for the current project. Please set it in project settings.";
            logger.error(errorMessage);
            throw new IOException(errorMessage);
        }

        OkHttpClient.Builder builder = new OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(10, TimeUnit.SECONDS)
                .followRedirects(true);

        if (apiToken != null && !apiToken.isBlank()) {
            builder.addInterceptor(chain -> {
                Request originalRequest = chain.request();
                Request authenticatedRequest = originalRequest.newBuilder()
                        .header("Authorization", "Bearer " + apiToken)
                        .build();
                return chain.proceed(authenticatedRequest);
            });
            logger.debug("Authenticated OkHttpClient (Bearer Token) created for Jira for project: {}", project.getRoot().getFileName());
        } else {
            logger.warn("Jira API token (PAT) not configured for project: {}. Proceeding with unauthenticated client. Public Jira instances may still be accessible.", project.getRoot().getFileName());
        }
        return builder.build();
    }
}
