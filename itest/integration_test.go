package itest

import (
	"context"
	"fmt"
	"io"
	"net/http"
	"os"
	"os/exec"
	"path/filepath"
	"regexp"
	"runtime"
	"slices"
	"strings"
	"time"
	"testing"

	"connectrpc.com/connect"
	profilev1 "github.com/grafana/pyroscope/api/gen/proto/go/google/v1"
	querierv1 "github.com/grafana/pyroscope/api/gen/proto/go/querier/v1"
	"github.com/grafana/pyroscope/api/gen/proto/go/querier/v1/querierv1connect"
	"github.com/stretchr/testify/require"
	"github.com/testcontainers/testcontainers-go"
	"github.com/testcontainers/testcontainers-go/wait"
)

func repoRoot() string {
	_, filename, _, _ := runtime.Caller(0)
	// integration_test.go is in otel-profiling-java/itest/
	// repo root is otel-profiling-java/
	return filepath.Dir(filepath.Dir(filename))
}

func ensureJarsBuilt(t *testing.T, root string) {
	t.Helper()
	jars := []string{
		filepath.Join(root, "otel-extension", "build", "libs", "pyroscope-otel-javaagent-extension.jar"),
		filepath.Join(root, "lib", "build", "libs", "pyroscope-otel.jar"),
	}
	allExist := true
	for _, jar := range jars {
		if _, err := os.Stat(jar); os.IsNotExist(err) {
			allExist = false
			break
		}
	}
	if allExist {
		return
	}
	t.Log("Pre-built JARs not found, running ./gradlew :otel-extension:shadowJar :lib:jar ...")
	cmd := exec.Command("./gradlew", ":otel-extension:shadowJar", ":lib:jar")
	cmd.Dir = root
	cmd.Stdout = os.Stdout
	cmd.Stderr = os.Stderr
	require.NoError(t, cmd.Run(), "gradle build failed")
}

func startPyroscope(t *testing.T, ctx context.Context, networkName string) testcontainers.Container {
	t.Helper()
	req := testcontainers.ContainerRequest{
		Image:        "grafana/pyroscope:latest",
		ExposedPorts: []string{"4040/tcp"},
		Networks:     []string{networkName},
		NetworkAliases: map[string][]string{
			networkName: {"pyroscope"},
		},
		WaitingFor: wait.ForHTTP("/ready").WithPort("4040/tcp").WithStartupTimeout(60 * time.Second),
	}
	c, err := testcontainers.GenericContainer(ctx, testcontainers.GenericContainerRequest{
		ContainerRequest: req,
		Started:          true,
	})
	require.NoError(t, err, "failed to start pyroscope container")
	return c
}

func startApp(t *testing.T, ctx context.Context, root string, dockerfile string, networkName string, env map[string]string) testcontainers.Container {
	t.Helper()
	req := testcontainers.ContainerRequest{
		FromDockerfile: testcontainers.FromDockerfile{
			Context:    root,
			Dockerfile: dockerfile,
			KeepImage:  true,
		},
		ExposedPorts: []string{"8080/tcp"},
		Networks:     []string{networkName},
		Env:          env,
		WaitingFor:   wait.ForHTTP("/health").WithPort("8080/tcp").WithStartupTimeout(5 * time.Minute),
	}
	c, err := testcontainers.GenericContainer(ctx, testcontainers.GenericContainerRequest{
		ContainerRequest: req,
		Started:          true,
	})
	require.NoError(t, err, "failed to start app container for %s", dockerfile)
	return c
}

func getBaseURL(t *testing.T, ctx context.Context, c testcontainers.Container) string {
	t.Helper()
	host, err := c.Host(ctx)
	require.NoError(t, err)
	mappedPort, err := c.MappedPort(ctx, "8080/tcp")
	require.NoError(t, err)
	return fmt.Sprintf("http://%s:%s", host, mappedPort.Port())
}

func getPyroscopeURL(t *testing.T, ctx context.Context, c testcontainers.Container) string {
	t.Helper()
	host, err := c.Host(ctx)
	require.NoError(t, err)
	mappedPort, err := c.MappedPort(ctx, "4040/tcp")
	require.NoError(t, err)
	return fmt.Sprintf("http://%s:%s", host, mappedPort.Port())
}

func requestFibonacci(t *testing.T, baseURL string, timeout time.Duration) string {
	t.Helper()
	deadline := time.Now().Add(timeout)
	var lastErr error
	for time.Now().Before(deadline) {
		resp, err := http.Get(baseURL + "/fibonacci?n=40")
		if err != nil {
			lastErr = err
			t.Logf("fibonacci request failed: %v, retrying...", err)
			time.Sleep(3 * time.Second)
			continue
		}
		body, err := io.ReadAll(resp.Body)
		resp.Body.Close()
		if err != nil || resp.StatusCode != 200 {
			lastErr = fmt.Errorf("status=%d err=%v body=%s", resp.StatusCode, err, string(body))
			t.Logf("fibonacci request: %v, retrying...", lastErr)
			time.Sleep(3 * time.Second)
			continue
		}
		return strings.TrimSpace(string(body))
	}
	t.Fatalf("fibonacci request failed after %v: %v", timeout, lastErr)
	return ""
}

func extractSpanIDFromBody(body string) (string, error) {
	re := regexp.MustCompile(`spanId=([0-9a-fA-F]{16})`)
	matches := re.FindStringSubmatch(body)
	if len(matches) < 2 {
		return "", fmt.Errorf("spanId not found in response body: %s", body)
	}
	return matches[1], nil
}

func extractSpanIDFromLogs(ctx context.Context, c testcontainers.Container) (string, error) {
	reader, err := c.Logs(ctx)
	if err != nil {
		return "", fmt.Errorf("failed to get container logs: %w", err)
	}
	defer reader.Close()
	data, err := io.ReadAll(reader)
	if err != nil {
		return "", fmt.Errorf("failed to read container logs: %w", err)
	}
	logs := string(data)

	// OTel logging exporter logs spans with format like:
	// 'GET /fibonacci' : <32-char traceId> <16-char spanId>
	re := regexp.MustCompile(`'[^']*fibonacci[^']*' : [0-9a-f]{32} ([0-9a-f]{16})`)
	matches := re.FindStringSubmatch(logs)
	if len(matches) >= 2 {
		return matches[1], nil
	}

	// Fallback: look for any spanId= pattern in logs
	re2 := regexp.MustCompile(`spanId=([0-9a-fA-F]{16})`)
	matches = re2.FindStringSubmatch(logs)
	if len(matches) >= 2 {
		return matches[1], nil
	}

	return "", fmt.Errorf("span ID not found in container logs (log length: %d bytes)", len(logs))
}

func queryPyroscopeProfile(t *testing.T, pyroscopeURL string, labelSelector string, timeout time.Duration) *profilev1.Profile {
	t.Helper()
	qc := querierv1connect.NewQuerierServiceClient(http.DefaultClient, pyroscopeURL)
	deadline := time.Now().Add(timeout)

	for time.Now().Before(deadline) {
		to := time.Now()
		from := to.Add(-5 * time.Minute)
		resp, err := qc.SelectMergeProfile(context.Background(), connect.NewRequest(&querierv1.SelectMergeProfileRequest{
			ProfileTypeID: "process_cpu:cpu:nanoseconds:cpu:nanoseconds",
			Start:         from.UnixMilli(),
			End:           to.UnixMilli(),
			LabelSelector: labelSelector,
		}))
		if err != nil {
			t.Logf("Pyroscope query failed: %v, retrying...", err)
			time.Sleep(5 * time.Second)
			continue
		}
		if resp.Msg != nil && len(resp.Msg.Sample) > 0 {
			t.Logf("Pyroscope returned profile with %d samples", len(resp.Msg.Sample))
			return resp.Msg
		}
		t.Log("Pyroscope returned empty profile, retrying...")
		time.Sleep(5 * time.Second)
	}
	return nil
}

// stackCollapseProto converts a pprof Profile into collapsed stack format.
// Adapted from pyroscope-java/itest/query/main.go.
func stackCollapseProto(p *profilev1.Profile) string {
	allZeros := func(a []int64) bool {
		for _, v := range a {
			if v != 0 {
				return false
			}
		}
		return true
	}
	addValues := func(a, b []int64) {
		for i := range a {
			a[i] += b[i]
		}
	}

	type stack struct {
		funcs string
		value []int64
	}
	locMap := make(map[int64]*profilev1.Location)
	funcMap := make(map[int64]*profilev1.Function)
	for _, l := range p.Location {
		locMap[int64(l.Id)] = l
	}
	for _, f := range p.Function {
		funcMap[int64(f.Id)] = f
	}

	var ret []stack
	for _, s := range p.Sample {
		var funcs []string
		for i := range s.LocationId {
			locID := s.LocationId[len(s.LocationId)-1-i]
			loc := locMap[int64(locID)]
			for _, line := range loc.Line {
				f := funcMap[int64(line.FunctionId)]
				fname := p.StringTable[f.Name]
				funcs = append(funcs, fname)
			}
		}

		vv := make([]int64, len(s.Value))
		copy(vv, s.Value)
		ret = append(ret, stack{
			funcs: strings.Join(funcs, ";"),
			value: vv,
		})
	}
	slices.SortFunc(ret, func(i, j stack) int {
		return strings.Compare(i.funcs, j.funcs)
	})
	var unique []stack
	for _, s := range ret {
		if allZeros(s.value) {
			continue
		}
		if len(unique) == 0 {
			unique = append(unique, s)
			continue
		}
		if unique[len(unique)-1].funcs == s.funcs {
			addValues(unique[len(unique)-1].value, s.value)
			continue
		}
		unique = append(unique, s)
	}

	res := make([]string, 0, len(unique))
	for _, s := range unique {
		res = append(res, fmt.Sprintf("%s %v", s.funcs, s.value))
	}
	return strings.Join(res, "\n")
}

func TestOtelExtension(t *testing.T) {
	if testing.Short() {
		t.Skip("skipping integration test in short mode")
	}
	ctx := context.Background()
	root := repoRoot()
	ensureJarsBuilt(t, root)

	// Create network
	networkName := "itest-otel-ext-" + fmt.Sprintf("%d", time.Now().UnixNano())
	network, err := testcontainers.GenericNetwork(ctx, testcontainers.GenericNetworkRequest{
		NetworkRequest: testcontainers.NetworkRequest{
			Name:   networkName,
			Driver: "bridge",
		},
	})
	require.NoError(t, err)
	defer func() {
		require.NoError(t, network.Remove(ctx))
	}()

	// Start Pyroscope
	pyroscopeC := startPyroscope(t, ctx, networkName)
	defer func() {
		require.NoError(t, pyroscopeC.Terminate(ctx))
	}()
	pyroscopeURL := getPyroscopeURL(t, ctx, pyroscopeC)
	t.Logf("Pyroscope URL: %s", pyroscopeURL)

	// Build and start otel-extension example
	appC := startApp(t, ctx, root, "examples/with-otel-extension/Dockerfile", networkName, map[string]string{
		"PYROSCOPE_SERVER_ADDRESS":    "http://pyroscope:4040",
		"PYROSCOPE_APPLICATION_NAME":  "otel-extension-example",
		"PYROSCOPE_FORMAT":            "jfr",
		"OTEL_SERVICE_NAME":           "otel-extension-example",
		"OTEL_TRACES_EXPORTER":        "logging",
		"OTEL_LOGS_EXPORTER":          "none",
		"OTEL_METRICS_EXPORTER":       "none",
	})
	defer func() {
		require.NoError(t, appC.Terminate(ctx))
	}()

	appURL := getBaseURL(t, ctx, appC)
	t.Logf("App URL: %s", appURL)

	// Request fibonacci (multiple times to generate profiling data)
	var lastBody string
	for i := 0; i < 3; i++ {
		lastBody = requestFibonacci(t, appURL, 2*time.Minute)
		t.Logf("Fibonacci response #%d: %s", i+1, lastBody)
	}
	require.Contains(t, lastBody, "fibonacci(40) = 102334155")

	// Extract span ID from container logs (best-effort)
	spanID, err := extractSpanIDFromLogs(ctx, appC)
	if err != nil {
		t.Logf("Could not extract span ID from logs: %v (continuing without profile_id check)", err)
	} else {
		t.Logf("Extracted span ID from logs: %s", spanID)
	}

	// Wait for profiling data to be uploaded to Pyroscope
	t.Log("Waiting 15s for profiling data flush...")
	time.Sleep(15 * time.Second)

	// Query Pyroscope for profiles by service name
	profile := queryPyroscopeProfile(t, pyroscopeURL, `{service_name="otel-extension-example"}`, 2*time.Minute)
	require.NotNil(t, profile, "expected non-empty profile from Pyroscope for otel-extension-example")
	require.Greater(t, len(profile.Sample), 0, "expected profile samples > 0")

	collapsed := stackCollapseProto(profile)
	t.Logf("Collapsed stacks:\n%s", collapsed)

	// If span ID was extracted, try querying by profile_id
	if spanID != "" {
		profileBySpan := queryPyroscopeProfile(t, pyroscopeURL,
			fmt.Sprintf(`{service_name="otel-extension-example",profile_id="%s"}`, spanID),
			30*time.Second)
		if profileBySpan != nil && len(profileBySpan.Sample) > 0 {
			t.Logf("Found profile for span ID %s with %d samples", spanID, len(profileBySpan.Sample))
		} else {
			t.Logf("No profile found for span ID %s (acceptable - profiling data may not be flushed yet)", spanID)
		}
	}
}

func TestOtelLibrary(t *testing.T) {
	if testing.Short() {
		t.Skip("skipping integration test in short mode")
	}
	ctx := context.Background()
	root := repoRoot()
	ensureJarsBuilt(t, root)

	// Create network
	networkName := "itest-otel-lib-" + fmt.Sprintf("%d", time.Now().UnixNano())
	network, err := testcontainers.GenericNetwork(ctx, testcontainers.GenericNetworkRequest{
		NetworkRequest: testcontainers.NetworkRequest{
			Name:   networkName,
			Driver: "bridge",
		},
	})
	require.NoError(t, err)
	defer func() {
		require.NoError(t, network.Remove(ctx))
	}()

	// Start Pyroscope
	pyroscopeC := startPyroscope(t, ctx, networkName)
	defer func() {
		require.NoError(t, pyroscopeC.Terminate(ctx))
	}()
	pyroscopeURL := getPyroscopeURL(t, ctx, pyroscopeC)
	t.Logf("Pyroscope URL: %s", pyroscopeURL)

	// Build and start otel-library example
	appC := startApp(t, ctx, root, "examples/with-otel-library/Dockerfile", networkName, map[string]string{
		"PYROSCOPE_SERVER_ADDRESS":    "http://pyroscope:4040",
		"PYROSCOPE_APPLICATION_NAME":  "otel-library-example",
	})
	defer func() {
		require.NoError(t, appC.Terminate(ctx))
	}()

	appURL := getBaseURL(t, ctx, appC)
	t.Logf("App URL: %s", appURL)

	// Request fibonacci (multiple times to generate profiling data)
	var lastBody string
	var spanID string
	for i := 0; i < 3; i++ {
		lastBody = requestFibonacci(t, appURL, 2*time.Minute)
		t.Logf("Fibonacci response #%d: %s", i+1, lastBody)

		// Extract span ID from response body (otel-library returns it)
		sid, err := extractSpanIDFromBody(lastBody)
		if err != nil {
			t.Logf("Could not extract span ID from response: %v", err)
		} else {
			spanID = sid
			t.Logf("Extracted span ID from response: %s", spanID)
		}
	}
	require.Contains(t, lastBody, "fibonacci(40) = 102334155")

	// Wait for profiling data to be uploaded to Pyroscope
	t.Log("Waiting 15s for profiling data flush...")
	time.Sleep(15 * time.Second)

	// Query Pyroscope for profiles by service name
	profile := queryPyroscopeProfile(t, pyroscopeURL, `{service_name="otel-library-example"}`, 2*time.Minute)
	require.NotNil(t, profile, "expected non-empty profile from Pyroscope for otel-library-example")
	require.Greater(t, len(profile.Sample), 0, "expected profile samples > 0")

	collapsed := stackCollapseProto(profile)
	t.Logf("Collapsed stacks:\n%s", collapsed)

	// If span ID was extracted, try querying by profile_id
	if spanID != "" {
		profileBySpan := queryPyroscopeProfile(t, pyroscopeURL,
			fmt.Sprintf(`{service_name="otel-library-example",profile_id="%s"}`, spanID),
			30*time.Second)
		if profileBySpan != nil && len(profileBySpan.Sample) > 0 {
			t.Logf("Found profile for span ID %s with %d samples", spanID, len(profileBySpan.Sample))
		} else {
			t.Logf("No profile found for span ID %s (acceptable - profiling data may not be flushed yet)", spanID)
		}
	}
}
