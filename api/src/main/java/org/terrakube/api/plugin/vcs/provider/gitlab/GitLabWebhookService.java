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
import java.util.List;
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

                String user = rootNode.path("user_username").asText();
                result.setCreatedBy(user);

                result.setFileChanges(new ArrayList<>());
                try {
                    GitlabWebhookModel gitlabWebhookModel = new ObjectMapper().readValue(jsonPayload, GitlabWebhookModel.class);
                    result.setCommit(gitlabWebhookModel.getCheckoutSha());

                    gitlabWebhookModel.getCommits().forEach(commitData -> {
                        for (String modified : commitData.getModified()) {
                            result.getFileChanges().add(modified);
                            log.info("Modified Gitlab Object: {}", modified);
                        }

                        for (String removed : commitData.getRemoved()) {
                            result.getFileChanges().add(removed);
                            log.info("Removed Gitlab Object: {}", removed);
                        }

                        for (String added : commitData.getAdded()) {
                            result.getFileChanges().add(added);
                            log.info("New Gitlab Object: {}", added);
                        }
                    });
                } catch (JsonProcessingException e) {
                    log.error(e.getMessage());
                }
            }
        } catch (JsonProcessingException e) {
            log.error("Error parsing JSON payload", e);
        }
        return result;
    }

    public String createWebhook(Workspace workspace, String webhookId) {
        String id = "";
        String secret = Base64.getEncoder()
                .encodeToString(workspace.getId().toString().getBytes(StandardCharsets.UTF_8));
        String ownerAndRepo = String.join("/", extractOwnerAndRepo(workspace.getSource()));
        String token = workspace.getVcs().getAccessToken();
        String webhookUrl = String.format("https://%s/webhook/v1/%s", hostname, webhookId);
        RestTemplate restTemplate = new RestTemplate();

        HttpHeaders headers = new HttpHeaders();
        headers.set("Accept", "application/json");
        headers.set("Content-Type", "application/json");
        headers.set("Authorization", "Bearer " + token);

        String body = "{\"url\":\"" + webhookUrl
                + "\",\"push_events\":\"true\",\"enable_ssl_verification\":\"false\",\"token\":\"" + secret + "\"}";

        log.info(body);

        HttpEntity<String> entity = new HttpEntity<>(body, headers);
        String projectId = "";
        try {
            log.info("Search gitlab project id using {}, {}", ownerAndRepo, workspace.getVcs().getApiUrl());
            projectId = getGitlabProjectId(ownerAndRepo, token, workspace.getVcs().getApiUrl());
        } catch (InterruptedException | IOException e) {
            log.error(e.getMessage());
            Thread.currentThread().interrupt();
        }

        URI gitlabUri = UriComponentsBuilder
                .fromHttpUrl(workspace.getVcs().getApiUrl() + "/projects/" + projectId + "/hooks")
                .build(true).toUri();

        ResponseEntity<String> response = restTemplate.exchange(
                gitlabUri, HttpMethod.POST, entity, String.class);

        if (response.getStatusCode().value() == 201) {
            try {
                JsonNode rootNode = objectMapper.readTree(response.getBody());
                id = rootNode.path("id").asText();
            } catch (Exception e) {
                log.error("Error parsing JSON response", e);
            }

            log.info("GitLab Hook created successfully for workspace {}/{} with id {}",
                    workspace.getOrganization().getName(), workspace.getName(), id);
        }

        return id;
    }

    private String getGitlabProjectId(String ownerAndRepo, String accessToken, String gitlabBaseUrl)
            throws IOException, InterruptedException {
        String projectId = "";
        HttpClient client = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(gitlabBaseUrl + "/search?scope=projects&search=" + ownerAndRepo))
                .header("Authorization", "Bearer " + accessToken)
                .header("Content-Type", "application/json")
                .GET()
                .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() == 200) {
            log.info("Response from Gitlab: {}", response.body());
            JsonNode jsonNode = objectMapper.readTree(response.body());

            projectId = jsonNode.get(0).get("id").asText();
            log.info("Parsed Project ID: {}", projectId);
        } else {
            log.error("Failed to retrieve project ID. HTTP Status: {}", response.statusCode());
            log.error("Response: {}", response.body());
        }
        return projectId;
    }

    public void deleteWebhook(Workspace workspace, String webhookRemoteId) {
        String ownerAndRepo = String.join("/", extractOwnerAndRepo(workspace.getSource()));
        String apiUrl = workspace.getVcs().getApiUrl() + "/projects/" + ownerAndRepo + "/hooks/" + webhookRemoteId;

        ResponseEntity<String> response = callGitlabApi(workspace.getVcs().getAccessToken(), "", apiUrl, HttpMethod.DELETE);
        if (response.getStatusCode().value() == 204) {
            log.info("Webhook with remote hook id {} on repository {} deleted successfully", webhookRemoteId, ownerAndRepo);
        } else {
            log.warn("Failed to delete webhook with remote hook id {} on repository {}, message {}",
                    webhookRemoteId, ownerAndRepo, response.getBody());
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
    protected List<String> extractOwnerAndRepo(String sourceUrl) {
        String[] parts = sourceUrl.replace(".git", "").split("/");
        return Arrays.asList(parts[parts.length - 2], parts[parts.length - 1]);
    }
}
