package githubclient

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"strings"
	"time"
)

type Client struct {
	baseURL    string
	token      string
	httpClient *http.Client
}

type PRDetails struct {
	BaseBranch string
	HeadSHA    string
	HeadRef    string
	Title      string
	Body       string
}

type ReviewComment struct {
	Path string
	Line int
}

func New(baseURL string, token string) *Client {
	trimmed := strings.TrimRight(strings.TrimSpace(baseURL), "/")
	return &Client{
		baseURL: trimmed,
		token:   token,
		httpClient: &http.Client{
			Timeout: 15 * time.Second,
		},
	}
}

func (c *Client) Enabled() bool {
	return c.baseURL != "" && c.token != ""
}

func (c *Client) FetchPRDetails(ctx context.Context, owner string, repo string, prNumber int) (PRDetails, error) {
	var response struct {
		Title string `json:"title"`
		Body  string `json:"body"`
		Base  struct {
			Ref string `json:"ref"`
		} `json:"base"`
		Head struct {
			SHA string `json:"sha"`
			Ref string `json:"ref"`
		} `json:"head"`
	}

	if err := c.doJSON(ctx, http.MethodGet, fmt.Sprintf("/repos/%s/%s/pulls/%d", owner, repo, prNumber), nil, &response); err != nil {
		return PRDetails{}, err
	}
	return PRDetails{
		BaseBranch: response.Base.Ref,
		HeadSHA:    response.Head.SHA,
		HeadRef:    response.Head.Ref,
		Title:      response.Title,
		Body:       response.Body,
	}, nil
}

func (c *Client) PostIssueComment(ctx context.Context, owner string, repo string, issueNumber int, body string) error {
	payload := map[string]any{"body": body}
	return c.doJSON(ctx, http.MethodPost, fmt.Sprintf("/repos/%s/%s/issues/%d/comments", owner, repo, issueNumber), payload, nil)
}

func (c *Client) ListReviewComments(ctx context.Context, owner string, repo string, prNumber int) ([]ReviewComment, error) {
	var response []struct {
		Path string `json:"path"`
		Line int    `json:"line"`
	}
	if err := c.doJSON(ctx, http.MethodGet, fmt.Sprintf("/repos/%s/%s/pulls/%d/comments", owner, repo, prNumber), nil, &response); err != nil {
		return nil, err
	}

	comments := make([]ReviewComment, 0, len(response))
	for _, item := range response {
		comments = append(comments, ReviewComment{Path: item.Path, Line: item.Line})
	}
	return comments, nil
}

func (c *Client) PostLineComment(ctx context.Context, owner string, repo string, prNumber int, path string, line int, body string, commitID string) error {
	payload := map[string]any{
		"body":      body,
		"commit_id": commitID,
		"path":      path,
		"line":      line,
	}
	err := c.doJSON(ctx, http.MethodPost, fmt.Sprintf("/repos/%s/%s/pulls/%d/comments", owner, repo, prNumber), payload, nil)
	if apiErr, ok := err.(*APIError); ok && apiErr.StatusCode == http.StatusUnprocessableEntity {
		fallbackBody := fmt.Sprintf("**Comment on `%s` line %d:**\n\n%s", path, line, body)
		return c.PostIssueComment(ctx, owner, repo, prNumber, fallbackBody)
	}
	return err
}

func (c *Client) doJSON(ctx context.Context, method string, path string, payload any, out any) error {
	var body io.Reader
	if payload != nil {
		encoded, err := json.Marshal(payload)
		if err != nil {
			return err
		}
		body = bytes.NewReader(encoded)
	}

	req, err := http.NewRequestWithContext(ctx, method, c.baseURL+path, body)
	if err != nil {
		return err
	}
	req.Header.Set("Accept", "application/vnd.github+json")
	req.Header.Set("Authorization", "Bearer "+c.token)
	if payload != nil {
		req.Header.Set("Content-Type", "application/json")
	}

	resp, err := c.httpClient.Do(req)
	if err != nil {
		return err
	}
	defer resp.Body.Close()

	responseBody, err := io.ReadAll(resp.Body)
	if err != nil {
		return err
	}
	if resp.StatusCode < 200 || resp.StatusCode >= 300 {
		return &APIError{StatusCode: resp.StatusCode, Body: string(responseBody)}
	}
	if out == nil || len(responseBody) == 0 {
		return nil
	}
	return json.Unmarshal(responseBody, out)
}

type APIError struct {
	StatusCode int
	Body       string
}

func (e *APIError) Error() string {
	return fmt.Sprintf("GitHub API request failed with status %d: %s", e.StatusCode, strings.TrimSpace(e.Body))
}
