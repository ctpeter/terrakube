package org.terrakube.api.plugin.vcs.provider.gitlab;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;
import org.terrakube.api.plugin.vcs.WebhookResult;
import org.terrakube.api.plugin.vcs.WebhookServiceBase;
import org.terrakube.api.rs.workspace.Workspace;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.extern.slf4j.Slf4j;

@Service
@Slf4j
public class GitLabWebhookService extends WebhookServiceBase {

    private final ObjectMapper objectMapper;

    @Value("${org.terrakube.hostname}")
    private String hostname;

    public GitLabWebhookService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public WebhookResult processWebhook(String jsonPayload, Map<String, String> headers, String token) {
        WebhookResult result = new WebhookResult();
        result.setBranch("");
        result.setVia("GitLab");
        try {
            String tokenHeader = headers.get("x-gitlab-token");
            if (tokenHeader == null || !tokenHeader.equals(token)) {
                log.error("X-Gitlab-Token header is missing or doesn't match!");
                result.setValid(false);
                return result;
            }

            result.setValid(true);
            log.info("Parsing GitLab webhook payload");

            JsonNode rootNode = objectMapper.readTree(jsonPayload);
            String event = rootNode.path("object_kind").asText();
            result.setEvent(event);

            if (event.equals("push")) {
                String[] ref = rootNode.path("ref").asText().split("/");
                String[] extractedBranch = Arrays.copyOfRange(ref, 2, ref.length);
                result.setBranch(String.join("/", extractedBranch));

                JsonNode userNode = rootNode.path("user_username");
                String user = userNode.asText();
                result.setCreatedBy(user);

                result.setFileChanges(new ArrayList<>());

                try {
                    GitlabWebhookModel gitlabWebhookModel = objectMapper.readValue(jsonPayload, GitlabWebhookModel.class);
                    result.setCommit(gitlabWebhookModel.getCheckoutSha());
                    gitlabWebhookModel.getCommits().forEach(commitData -> {
                        commitData.getModified().forEach(file -> {
                            result.getFileChanges().add(file);
                            log.info("Modified: {}", file);
                        });

                        commitData.getRemoved().forEach(file -> {
                            result.getFileChanges().add(file);
                            log.info("Removed: {}", file);
                        });

                        commitData.getAdded().forEach(file -> {
                            result.getFileChanges().add(file);
                            log.info("Added: {}", file);
                        });
                    });
                } catch (JsonProcessingException e) {
                    log.error("Failed to parse commit data", e);
                }
            }
        } catch (JsonProcessingException e) {
            log.error("Error parsing JSON payload", e);
        }

        return result;
    }

    public String createWebhook(Workspace workspace, String webhookId) {
        String id = "";
        String secret = Base64.getEncoder().encodeToString(workspace.getId().toString().getBytes(StandardCharsets.UTF_8));
        String ownerAndRepo = extractOwnerAndRepo(workspace.getSource())[0];
        String token = workspace.getVcs().getAccessToken();
        String webhookUrl = String.format("https://%s/webhook/v1/%s", hostname, webhookId);
        RestTemplate restTemplate = new RestTemplate();

        HttpHeaders headers = new HttpHeaders();
        headers.set("Accept", "application/json");
        headers.set("Content-Type", "application/json");
        headers.set("Authorization", "Bearer " + token);

        String body = String.format(
                "{\"url\":\"%s\",\"push_events\":true,\"enable_ssl_verification\":false,\"token\":\"%s\"}",
                webhookUrl, secret);

        log.info("Webhook body: {}", body);

        HttpEntity<String> entity = new HttpEntity<>(body, headers);
        String projectId = "";

        try {
            log.info("Searching GitLab project ID using: {} at {}", ownerAndRepo, workspace.getVcs().getApiUrl());
            projectId = getGitlabProjectId(ownerAndRepo, token, workspace.getVcs().getApiUrl());
        } catch (InterruptedException | IOException e) {
            log.error("Error getting GitLab project ID", e);
            Thread.currentThread().interrupt();
        }

        URI gitlabUri = UriComponentsBuilder
                .fromHttpUrl(workspace.getVcs().getApiUrl() + "/projects/" + projectId + "/hooks")
                .build(true).toUri();

        ResponseEntity<String> response = restTemplate.exchange(gitlabUri, HttpMethod.POST, entity, String.class);

        if (response.getStatusCode().value() == 201) {
            try {
                JsonNode rootNode = objectMapper.readTree(response.getBody());
                id = rootNode.path("id").asText();
            } catch (Exception e) {
                log.error("Error parsing webhook creation response", e);
            }

            log.info("GitLab webhook created for {}/{} with id {}", workspace.getOrganization().getName(),
                    workspace.getName(), id);
        }

        return id;
    }

    private String getGitlabProjectId(String fullPath, String accessToken, String gitlabBaseUrl)
            throws IOException, InterruptedException {
        String projectId = "";
        String encodedPath = java.net.URLEncoder.encode(fullPath, StandardCharsets.UTF_8);

        HttpClient client = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(gitlabBaseUrl + "/projects/" + encodedPath))
                .header("Authorization", "Bearer " + accessToken)
                .header("Content-Type", "application/json")
                .GET()
                .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() == 200) {
            JsonNode jsonNode = objectMapper.readTree(response.body());
            projectId = jsonNode.path("id").asText();
            log.info("GitLab project ID found: {}", projectId);
        } else {
            log.error("Failed to retrieve GitLab project. Status: {}", response.statusCode());
            log.error("Response: {}", response.body());
        }

        return projectId;
    }

    public void deleteWebhook(Workspace workspace, String webhookRemoteId) {
        String ownerAndRepo = extractOwnerAndRepo(workspace.getSource())[0];
        String apiUrl = workspace.getVcs().getApiUrl() + "/projects/" + ownerAndRepo + "/hooks/" + webhookRemoteId;

        ResponseEntity<String> response = callGitlabApi(workspace.getVcs().getAccessToken(), "", apiUrl, HttpMethod.DELETE);

        if (response.getStatusCode().value() == 204) {
            log.info("Deleted GitLab webhook {} for {}", webhookRemoteId, ownerAndRepo);
        } else {
            log.warn("Failed to delete GitLab webhook {}: {}", webhookRemoteId, response.getBody());
        }
    }

    private ResponseEntity<String> callGitlabApi(String token, String body, String apiUrl, HttpMethod httpMethod) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("Accept", "application/json");
        headers.set("Authorization", "Bearer " + token);
        headers.set("Content-Type", "application/json");

        HttpEntity<String> entity = new HttpEntity<>(body, headers);
        RestTemplate restTemplate = new RestTemplate();
        return restTemplate.exchange(apiUrl, httpMethod, entity, String.class);
    }

    @Override
    protected String[] extractOwnerAndRepo(String sourceUrl) {
        String cleanedUrl = sourceUrl;

        if (cleanedUrl.endsWith(".git")) {
            cleanedUrl = cleanedUrl.substring(0, cleanedUrl.length() - 4);
        }

        if (cleanedUrl.contains("@")) {
            cleanedUrl = cleanedUrl.substring(cleanedUrl.indexOf(":") + 1);
        } else {
            cleanedUrl = cleanedUrl.replaceAll("https?://[^/]+/", "");
        }

        return new String[] { cleanedUrl }; // e.g., group/subgroup/project
    }
}
