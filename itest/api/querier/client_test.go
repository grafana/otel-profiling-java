package querier

import (
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"reflect"
	"testing"
)

func TestSelectMergeStacktraces(t *testing.T) {
	var received SelectMergeStacktracesRequest
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != selectMergeStacktracesPath {
			t.Errorf("path = %q, want %q", r.URL.Path, selectMergeStacktracesPath)
		}
		if r.Header.Get("Content-Type") != "application/json" {
			t.Errorf("Content-Type = %q", r.Header.Get("Content-Type"))
		}
		if err := json.NewDecoder(r.Body).Decode(&received); err != nil {
			t.Errorf("decode request: %v", err)
		}
		_ = json.NewEncoder(w).Encode(SelectMergeStacktracesResponse{Tree: []byte{1, 2, 3}})
	}))
	t.Cleanup(server.Close)

	maxNodes := int64(42)
	request := &SelectMergeStacktracesRequest{
		ProfileTypeID: "process_cpu:cpu:nanoseconds:cpu:nanoseconds",
		LabelSelector: `{service_name="example"}`,
		SpanSelector:  []string{"0123456789abcdef"},
		Start:         10,
		End:           20,
		MaxNodes:      &maxNodes,
		Format:        ProfileFormatTree,
	}
	response, err := NewClient(server.Client(), server.URL+"/").SelectMergeStacktraces(context.Background(), request)
	if err != nil {
		t.Fatal(err)
	}
	if !reflect.DeepEqual(received, *request) {
		t.Errorf("received request = %+v, want %+v", received, *request)
	}
	if !reflect.DeepEqual(response.Tree, []byte{1, 2, 3}) {
		t.Errorf("tree = %v, want [1 2 3]", response.Tree)
	}
}
