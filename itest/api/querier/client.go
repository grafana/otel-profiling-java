// Package querier contains the small subset of the Pyroscope querier API used
// by these integration tests. The request shapes mirror pyroscope/api v1.3.0.
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

const selectMergeSpanProfilePath = "/querier.v1.QuerierService/SelectMergeSpanProfile"

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

func (c *Client) SelectMergeSpanProfile(ctx context.Context, req *SelectMergeSpanProfileRequest) (*SelectMergeSpanProfileResponse, error) {
	body, err := json.Marshal(req)
	if err != nil {
		return nil, fmt.Errorf("marshal SelectMergeSpanProfile request: %w", err)
	}
	httpRequest, err := http.NewRequestWithContext(
		ctx,
		http.MethodPost,
		c.baseURL+selectMergeSpanProfilePath,
		bytes.NewReader(body),
	)
	if err != nil {
		return nil, fmt.Errorf("create SelectMergeSpanProfile request: %w", err)
	}
	httpRequest.Header.Set("Content-Type", "application/json")
	httpRequest.Header.Set("Accept", "application/json; allow-utf8-labelnames=true")

	resp, err := c.httpClient.Do(httpRequest)
	if err != nil {
		return nil, fmt.Errorf("execute SelectMergeSpanProfile request: %w", err)
	}
	defer resp.Body.Close()
	responseBody, err := io.ReadAll(resp.Body)
	if err != nil {
		return nil, fmt.Errorf("read SelectMergeSpanProfile response: %w", err)
	}
	if resp.StatusCode != http.StatusOK {
		return nil, fmt.Errorf("SelectMergeSpanProfile returned %s: %s", resp.Status, responseBody)
	}

	result := new(SelectMergeSpanProfileResponse)
	if err := json.Unmarshal(responseBody, result); err != nil {
		return nil, fmt.Errorf("decode SelectMergeSpanProfile response: %w", err)
	}
	return result, nil
}

type ProfileFormat int32

const (
	ProfileFormatUnspecified ProfileFormat = 0
	ProfileFormatFlamegraph  ProfileFormat = 1
	ProfileFormatTree        ProfileFormat = 2
)

type SelectMergeSpanProfileRequest struct {
	ProfileTypeID string        `json:"profile_typeID,omitempty"`
	LabelSelector string        `json:"label_selector,omitempty"`
	SpanSelector  []string      `json:"span_selector,omitempty"`
	Start         int64         `json:"start,omitempty"`
	End           int64         `json:"end,omitempty"`
	MaxNodes      *int64        `json:"max_nodes,omitempty"`
	Format        ProfileFormat `json:"format,omitempty"`
}

type SelectMergeSpanProfileResponse struct {
	Tree []byte `json:"tree,omitempty"`
}
