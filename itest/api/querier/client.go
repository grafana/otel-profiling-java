// Package querier contains the small subset of the Pyroscope querier API used
// by these integration tests. The request shapes mirror pyroscope/api
// querier.v1 protobuf definitions.
package querier

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"strings"
)

const selectMergeStacktracesPath = "/querier.v1.QuerierService/SelectMergeStacktraces"

type Client struct {
	httpClient *http.Client
	baseURL    string
}

func NewClient(httpClient *http.Client, baseURL string) *Client {
	return &Client{
		httpClient: httpClient,
		baseURL:    strings.TrimRight(baseURL, "/"),
	}
}

func (c *Client) SelectMergeStacktraces(ctx context.Context, req *SelectMergeStacktracesRequest) (*SelectMergeStacktracesResponse, error) {
	body, err := json.Marshal(req)
	if err != nil {
		return nil, fmt.Errorf("marshal SelectMergeStacktraces request: %w", err)
	}
	httpRequest, err := http.NewRequestWithContext(
		ctx,
		http.MethodPost,
		c.baseURL+selectMergeStacktracesPath,
		bytes.NewReader(body),
	)
	if err != nil {
		return nil, fmt.Errorf("create SelectMergeStacktraces request: %w", err)
	}
	httpRequest.Header.Set("Content-Type", "application/json")
	httpRequest.Header.Set("Accept", "application/json; allow-utf8-labelnames=true")

	resp, err := c.httpClient.Do(httpRequest)
	if err != nil {
		return nil, fmt.Errorf("execute SelectMergeStacktraces request: %w", err)
	}
	defer resp.Body.Close()
	responseBody, err := io.ReadAll(resp.Body)
	if err != nil {
		return nil, fmt.Errorf("read SelectMergeStacktraces response: %w", err)
	}
	if resp.StatusCode != http.StatusOK {
		return nil, fmt.Errorf("SelectMergeStacktraces returned %s: %s", resp.Status, responseBody)
	}

	result := new(SelectMergeStacktracesResponse)
	if err := json.Unmarshal(responseBody, result); err != nil {
		return nil, fmt.Errorf("decode SelectMergeStacktraces response: %w", err)
	}
	return result, nil
}

type ProfileFormat int32

const (
	ProfileFormatUnspecified ProfileFormat = 0
	ProfileFormatFlamegraph  ProfileFormat = 1
	ProfileFormatTree        ProfileFormat = 2
	ProfileFormatDot         ProfileFormat = 3
	ProfileFormatPprof       ProfileFormat = 4
)

type SelectMergeStacktracesRequest struct {
	ProfileTypeID string        `json:"profile_typeID,omitempty"`
	LabelSelector string        `json:"label_selector,omitempty"`
	Start         int64         `json:"start,omitempty"`
	End           int64         `json:"end,omitempty"`
	MaxNodes      *int64        `json:"max_nodes,omitempty"`
	Format        ProfileFormat `json:"format,omitempty"`
	// SpanSelector filters samples by span ID (16 hex characters, 64-bit).
	SpanSelector []string `json:"span_selector,omitempty"`
}

type SelectMergeStacktracesResponse struct {
	Tree []byte `json:"tree,omitempty"`
}
