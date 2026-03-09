package itest

import (
	"bytes"
	"context"
	"fmt"
	"io"
	"net/http"
	"path/filepath"
	"regexp"
	"runtime"
	"strings"
	"testing"
	"time"

	"otel-profiling-java-itest/pyroscope/model"

	"connectrpc.com/connect"
	querierv1 "github.com/grafana/pyroscope/api/gen/proto/go/querier/v1"
	"github.com/grafana/pyroscope/api/gen/proto/go/querier/v1/querierv1connect"
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
	"github.com/testcontainers/testcontainers-go"
	"github.com/testcontainers/testcontainers-go/network"
	"github.com/testcontainers/testcontainers-go/wait"
)

func repoRoot() string {
	_, filename, _, _ := runtime.Caller(0)
	// integration_test.go is in otel-profiling-java/itest/
	// repo root is otel-profiling-java/
	return filepath.Dir(filepath.Dir(filename))
}

func startPyroscope(t *testing.T, ctx context.Context, net *testcontainers.DockerNetwork) testcontainers.Container {
	t.Helper()
	t.Logf("starting pyroscope...")
	req := testcontainers.GenericContainerRequest{
		ContainerRequest: testcontainers.ContainerRequest{
			Image:        "grafana/pyroscope:latest",
			ExposedPorts: []string{"4040/tcp"},
			WaitingFor:   wait.ForHTTP("/ready").WithPort("4040/tcp").WithStartupTimeout(60 * time.Second),
		},
		Started: true,
	}
	require.NoError(t, network.WithNetwork([]string{"pyroscope"}, net)(&req))
	c, err := testcontainers.GenericContainer(ctx, req)
	require.NoError(t, err, "failed to start pyroscope container")
	return c
}

func startApp(t *testing.T, ctx context.Context, root string, dockerfile string, net *testcontainers.DockerNetwork, env map[string]string) testcontainers.Container {
	t.Helper()
	t.Logf("starting example %s ...", dockerfile)

	req := testcontainers.GenericContainerRequest{
		ContainerRequest: testcontainers.ContainerRequest{
			FromDockerfile: testcontainers.FromDockerfile{
				Context:    root,
				Dockerfile: dockerfile,
				KeepImage:  true,
			},
			ExposedPorts: []string{"8080/tcp"},
			Env:          env,
			WaitingFor:   wait.ForHTTP("/health").WithPort("8080/tcp").WithStartupTimeout(5 * time.Minute),
		},
		Started: true,
	}
	require.NoError(t, network.WithNetwork(nil, net)(&req))
	c, err := testcontainers.GenericContainer(ctx, req)
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

func requestFibonacci(t *testing.T, baseURL string) string {
	t.Helper()
	u := baseURL + "/fibonacci?n=40"
	resp, err := http.Get(u)
	t.Logf("requesting fibonacci at %s", u)
	if err != nil {
		t.Fatalf("fibonacci request failed: %v, retrying...", err)
		return ""
	}
	body, err := io.ReadAll(resp.Body)
	_ = resp.Body.Close()
	res := strings.TrimSpace(string(body))
	t.Logf("Fibonacci response #%d: %s", 0, res)
	return res
}

func extractSpanIDFromBody(body string) (string, error) {
	re := regexp.MustCompile(`spanId=([0-9a-fA-F]{16})`)
	matches := re.FindStringSubmatch(body)
	if len(matches) < 2 {
		return "", fmt.Errorf("spanId not found in response body: %s", body)
	}
	return matches[1], nil
}

func extractNamedSpanID(body string, name string) (string, error) {
	re := regexp.MustCompile(name + `=([0-9a-fA-F]{16})`)
	matches := re.FindStringSubmatch(body)
	if len(matches) < 2 {
		return "", fmt.Errorf("%s not found in response body: %s", name, body)
	}
	return matches[1], nil
}

func requestChildSpans(t *testing.T, baseURL string) string {
	t.Helper()
	u := baseURL + "/child-spans"
	t.Logf("requesting child-spans at %s", u)
	resp, err := http.Get(u)
	if err != nil {
		t.Fatalf("child-spans request failed: %v", err)
		return ""
	}
	body, err := io.ReadAll(resp.Body)
	_ = resp.Body.Close()
	res := strings.TrimSpace(string(body))
	t.Logf("child-spans response: %s", res)
	return res
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

func labelSelector(appName string) string {
	return fmt.Sprintf(`{service_name="%s"}`, appName)
}

func querySpanPyroscopeProfile(t *testing.T, pyroscopeURL string, labelSelector string, span string) (string, error) {
	t.Helper()
	tree, err := querySpanTree(t, pyroscopeURL, labelSelector, span)
	if err != nil {
		return "", err
	}
	buf := bytes.NewBuffer(nil)
	tree.WriteCollapsed(buf)
	return buf.String(), nil
}

func querySpanTree(t *testing.T, pyroscopeURL string, labelSelector string, span string) (*model.Tree, error) {
	t.Helper()
	qc := querierv1connect.NewQuerierServiceClient(http.DefaultClient, pyroscopeURL)

	to := time.Now()
	from := to.Add(-1 * time.Hour)
	maxNodes := int64(65536)
	resp, err := qc.SelectMergeSpanProfile(context.Background(), connect.NewRequest(&querierv1.SelectMergeSpanProfileRequest{
		ProfileTypeID: "process_cpu:cpu:nanoseconds:cpu:nanoseconds",
		Start:         from.UnixMilli(),
		End:           to.UnixMilli(),
		LabelSelector: labelSelector,
		SpanSelector:  []string{span},
		MaxNodes:      &maxNodes,
		Format:        querierv1.ProfileFormat_PROFILE_FORMAT_TREE,
	}))
	t.Logf("querySpanTree %s %s %s = err %+v", pyroscopeURL, labelSelector, span, err)
	if err != nil {
		return nil, err
	}
	return model.UnmarshalTree(resp.Msg.Tree)
}

func TestOtelExtension(t *testing.T) {
	const appName = "otel-extension-example"
	ctx := context.Background()
	root := repoRoot()

	// Create network
	net, err := network.New(ctx)
	require.NoError(t, err)
	defer func() {
		require.NoError(t, net.Remove(ctx))
	}()

	pyroscopeC := startPyroscope(t, ctx, net)
	defer func() {
		require.NoError(t, pyroscopeC.Terminate(ctx))
	}()
	pyroscopeURL := getPyroscopeURL(t, ctx, pyroscopeC)
	t.Logf("Pyroscope URL: %s", pyroscopeURL)

	appC := startApp(t, ctx, root, "examples/with-otel-extension/Dockerfile", net, map[string]string{
		"PYROSCOPE_SERVER_ADDRESS":   "http://pyroscope:4040",
		"PYROSCOPE_APPLICATION_NAME": appName,
		"PYROSCOPE_FORMAT":           "jfr",
		"OTEL_SERVICE_NAME":          appName,
		"OTEL_TRACES_EXPORTER":       "logging",
		"OTEL_LOGS_EXPORTER":         "none",
		"OTEL_METRICS_EXPORTER":      "none",
	})
	defer func() {
		require.NoError(t, appC.Terminate(ctx))
	}()

	appURL := getBaseURL(t, ctx, appC)
	t.Logf("App URL: %s", appURL)

	eventually(t, func() bool {
		lastBody := requestFibonacci(t, appURL)
		return strings.Contains(lastBody, "fibonacci(40) = 102334155")
	})

	var spanId string
	eventually(t, func() bool {
		spanId, err = extractSpanIDFromLogs(ctx, appC)
		return err == nil && spanId != ""
	})

	t.Logf("Extracted span ID from logs: %s", spanId)

	// OTel Java agent injects OpenTelemetryHandlerMappingFilter into the filter chain
	const expected = ";java/lang/Thread.run;org/apache/tomcat/util/threads/TaskThread$WrappingRunnable.run;org/apache/tomcat/util/threads/ThreadPoolExecutor$Worker.run;org/apache/tomcat/util/threads/ThreadPoolExecutor.runWorker;org/apache/tomcat/util/net/SocketProcessorBase.run;org/apache/tomcat/util/net/NioEndpoint$SocketProcessor.doRun;org/apache/coyote/AbstractProtocol$ConnectionHandler.process;org/apache/coyote/AbstractProcessorLight.process;org/apache/coyote/http11/Http11Processor.service;org/apache/catalina/connector/CoyoteAdapter.service;org/apache/catalina/core/StandardEngineValve.invoke;org/apache/catalina/valves/ErrorReportValve.invoke;org/apache/catalina/core/StandardHostValve.invoke;org/apache/catalina/authenticator/AuthenticatorBase.invoke;org/apache/catalina/core/StandardContextValve.invoke;org/apache/catalina/core/StandardWrapperValve.invoke;org/apache/catalina/core/ApplicationFilterChain.doFilter;org/apache/catalina/core/ApplicationFilterChain.internalDoFilter;org/springframework/web/filter/OncePerRequestFilter.doFilter;org/springframework/web/filter/CharacterEncodingFilter.doFilterInternal;org/apache/catalina/core/ApplicationFilterChain.doFilter;org/apache/catalina/core/ApplicationFilterChain.internalDoFilter;org/springframework/web/servlet/v3_1/OpenTelemetryHandlerMappingFilter.doFilter;org/apache/catalina/core/ApplicationFilterChain.doFilter;org/apache/catalina/core/ApplicationFilterChain.internalDoFilter;org/springframework/web/filter/OncePerRequestFilter.doFilter;org/springframework/web/filter/FormContentFilter.doFilterInternal;org/apache/catalina/core/ApplicationFilterChain.doFilter;org/apache/catalina/core/ApplicationFilterChain.internalDoFilter;org/springframework/web/filter/OncePerRequestFilter.doFilter;org/springframework/web/filter/RequestContextFilter.doFilterInternal;org/apache/catalina/core/ApplicationFilterChain.doFilter;org/apache/catalina/core/ApplicationFilterChain.internalDoFilter;org/apache/tomcat/websocket/server/WsFilter.doFilter;org/apache/catalina/core/ApplicationFilterChain.doFilter;org/apache/catalina/core/ApplicationFilterChain.internalDoFilter;javax/servlet/http/HttpServlet.service;org/springframework/web/servlet/FrameworkServlet.service;javax/servlet/http/HttpServlet.service;org/springframework/web/servlet/FrameworkServlet.doGet;org/springframework/web/servlet/FrameworkServlet.processRequest;org/springframework/web/servlet/DispatcherServlet.doService;org/springframework/web/servlet/DispatcherServlet.doDispatch;org/springframework/web/servlet/mvc/method/AbstractHandlerMethodAdapter.handle;org/springframework/web/servlet/mvc/method/annotation/RequestMappingHandlerAdapter.handleInternal;org/springframework/web/servlet/mvc/method/annotation/RequestMappingHandlerAdapter.invokeHandlerMethod;org/springframework/web/servlet/mvc/method/annotation/ServletInvocableHandlerMethod.invokeAndHandle;org/springframework/web/method/support/InvocableHandlerMethod.invokeForRequest;org/springframework/web/method/support/InvocableHandlerMethod.doInvoke;java/lang/reflect/Method.invoke;jdk/internal/reflect/DelegatingMethodAccessorImpl.invoke;jdk/internal/reflect/NativeMethodAccessorImpl.invoke;jdk/internal/reflect/NativeMethodAccessorImpl.invoke0;io/pyroscope/example/WorkController.fibonacci;io/pyroscope/example/FibonacciService.compute;io/pyroscope/example/FibonacciService.compute;io/pyroscope/example/FibonacciService.compute;io/pyroscope/example/FibonacciService.compute;io/pyroscope/example/FibonacciService.compute;io/pyroscope/example/FibonacciService.compute;io/pyroscope/example/FibonacciService.compute;io/pyroscope/example/FibonacciService.compute;io/pyroscope/example/FibonacciService.compute;io/pyroscope/example/FibonacciService.compute;io/pyroscope/example/FibonacciService.compute;io/pyroscope/example/FibonacciService.compute;io/pyroscope/example/FibonacciService.compute;io/pyroscope/example/FibonacciService.compute;io/pyroscope/example/FibonacciService.compute;io/pyroscope/example/FibonacciService.compute;io/pyroscope/example/FibonacciService.compute;io/pyroscope/example/FibonacciService.compute;io/pyroscope/example/FibonacciService.compute;io/pyroscope/example/FibonacciService.compute;io/pyroscope/example/FibonacciService.compute;io/pyroscope/example/FibonacciService.compute;io/pyroscope/example/FibonacciService.compute;io/pyroscope/example/FibonacciService.compute"

	eventuallyProfile(t, pyroscopeURL, appName, spanId, expected)
}

func eventually(t *testing.T, condition func() bool) {
	require.Eventually(t, condition, 30*time.Second, time.Second)
}

func eventuallyProfile(t *testing.T, pyroscopeURL string, appName string, spanId string, expectedStack string) {
	t.Helper()
	var lastCollapsed string
	var lastErr error
	ok := assert.Eventually(t, func() bool {
		lastCollapsed, lastErr = querySpanPyroscopeProfile(t, pyroscopeURL,
			labelSelector(appName), spanId)
		return lastErr == nil && lastCollapsed != "" && strings.Contains(lastCollapsed, expectedStack)
	}, 30*time.Second, time.Second)
	if !ok {
		t.Logf("last profile query error: %v", lastErr)
		t.Logf("last collapsed profile:\n%s", lastCollapsed)
		t.FailNow()
	}
}

func TestOtelLibrary(t *testing.T) {
	const appName = "otel-library-example"
	ctx := context.Background()
	root := repoRoot()

	net, err := network.New(ctx)
	require.NoError(t, err)
	defer func() {
		require.NoError(t, net.Remove(ctx))
	}()

	pyroscopeC := startPyroscope(t, ctx, net)
	defer func() {
		require.NoError(t, pyroscopeC.Terminate(ctx))
	}()
	pyroscopeURL := getPyroscopeURL(t, ctx, pyroscopeC)
	t.Logf("Pyroscope URL: %s", pyroscopeURL)

	appC := startApp(t, ctx, root, "examples/with-otel-library/Dockerfile", net, map[string]string{
		"PYROSCOPE_SERVER_ADDRESS":   "http://pyroscope:4040",
		"PYROSCOPE_APPLICATION_NAME": appName,
	})
	defer func() {
		require.NoError(t, appC.Terminate(ctx))
	}()

	appURL := getBaseURL(t, ctx, appC)
	t.Logf("App URL: %s", appURL)

	var spanId string
	eventually(t, func() bool {
		lastBody := requestFibonacci(t, appURL)
		spanId, err = extractSpanIDFromBody(lastBody)
		return strings.Contains(lastBody, "fibonacci(40) = 102334155") && err == nil && spanId != ""
	})

	// No OTel Java agent — no OpenTelemetryHandlerMappingFilter in the filter chain
	const expected = ";java/lang/Thread.run;org/apache/tomcat/util/threads/TaskThread$WrappingRunnable.run;org/apache/tomcat/util/threads/ThreadPoolExecutor$Worker.run;org/apache/tomcat/util/threads/ThreadPoolExecutor.runWorker;org/apache/tomcat/util/net/SocketProcessorBase.run;org/apache/tomcat/util/net/NioEndpoint$SocketProcessor.doRun;org/apache/coyote/AbstractProtocol$ConnectionHandler.process;org/apache/coyote/AbstractProcessorLight.process;org/apache/coyote/http11/Http11Processor.service;org/apache/catalina/connector/CoyoteAdapter.service;org/apache/catalina/core/StandardEngineValve.invoke;org/apache/catalina/valves/ErrorReportValve.invoke;org/apache/catalina/core/StandardHostValve.invoke;org/apache/catalina/authenticator/AuthenticatorBase.invoke;org/apache/catalina/core/StandardContextValve.invoke;org/apache/catalina/core/StandardWrapperValve.invoke;org/apache/catalina/core/ApplicationFilterChain.doFilter;org/apache/catalina/core/ApplicationFilterChain.internalDoFilter;org/springframework/web/filter/OncePerRequestFilter.doFilter;org/springframework/web/filter/CharacterEncodingFilter.doFilterInternal;org/apache/catalina/core/ApplicationFilterChain.doFilter;org/apache/catalina/core/ApplicationFilterChain.internalDoFilter;org/springframework/web/filter/OncePerRequestFilter.doFilter;org/springframework/web/filter/FormContentFilter.doFilterInternal;org/apache/catalina/core/ApplicationFilterChain.doFilter;org/apache/catalina/core/ApplicationFilterChain.internalDoFilter;org/springframework/web/filter/OncePerRequestFilter.doFilter;org/springframework/web/filter/RequestContextFilter.doFilterInternal;org/apache/catalina/core/ApplicationFilterChain.doFilter;org/apache/catalina/core/ApplicationFilterChain.internalDoFilter;org/apache/tomcat/websocket/server/WsFilter.doFilter;org/apache/catalina/core/ApplicationFilterChain.doFilter;org/apache/catalina/core/ApplicationFilterChain.internalDoFilter;javax/servlet/http/HttpServlet.service;org/springframework/web/servlet/FrameworkServlet.service;javax/servlet/http/HttpServlet.service;org/springframework/web/servlet/FrameworkServlet.doGet;org/springframework/web/servlet/FrameworkServlet.processRequest;org/springframework/web/servlet/DispatcherServlet.doService;org/springframework/web/servlet/DispatcherServlet.doDispatch;org/springframework/web/servlet/mvc/method/AbstractHandlerMethodAdapter.handle;org/springframework/web/servlet/mvc/method/annotation/RequestMappingHandlerAdapter.handleInternal;org/springframework/web/servlet/mvc/method/annotation/RequestMappingHandlerAdapter.invokeHandlerMethod;org/springframework/web/servlet/mvc/method/annotation/ServletInvocableHandlerMethod.invokeAndHandle;org/springframework/web/method/support/InvocableHandlerMethod.invokeForRequest;org/springframework/web/method/support/InvocableHandlerMethod.doInvoke;java/lang/reflect/Method.invoke;jdk/internal/reflect/DelegatingMethodAccessorImpl.invoke;jdk/internal/reflect/NativeMethodAccessorImpl.invoke;jdk/internal/reflect/NativeMethodAccessorImpl.invoke0;io/pyroscope/example/WorkController.fibonacci;io/pyroscope/example/FibonacciService.compute;io/pyroscope/example/FibonacciService.compute;io/pyroscope/example/FibonacciService.compute;io/pyroscope/example/FibonacciService.compute;io/pyroscope/example/FibonacciService.compute;io/pyroscope/example/FibonacciService.compute;io/pyroscope/example/FibonacciService.compute;io/pyroscope/example/FibonacciService.compute;io/pyroscope/example/FibonacciService.compute;io/pyroscope/example/FibonacciService.compute;io/pyroscope/example/FibonacciService.compute;io/pyroscope/example/FibonacciService.compute;io/pyroscope/example/FibonacciService.compute;io/pyroscope/example/FibonacciService.compute;io/pyroscope/example/FibonacciService.compute;io/pyroscope/example/FibonacciService.compute;io/pyroscope/example/FibonacciService.compute;io/pyroscope/example/FibonacciService.compute;io/pyroscope/example/FibonacciService.compute;io/pyroscope/example/FibonacciService.compute;io/pyroscope/example/FibonacciService.compute;io/pyroscope/example/FibonacciService.compute;io/pyroscope/example/FibonacciService.compute"

	eventuallyProfile(t, pyroscopeURL, appName, spanId, expected)
}

func TestOtelExtensionManualStart(t *testing.T) {
	const appName = "otel-extension-manual-start-example"
	ctx := context.Background()
	root := repoRoot()

	net, err := network.New(ctx)
	require.NoError(t, err)
	defer func() {
		require.NoError(t, net.Remove(ctx))
	}()

	pyroscopeC := startPyroscope(t, ctx, net)
	defer func() {
		require.NoError(t, pyroscopeC.Terminate(ctx))
	}()
	pyroscopeURL := getPyroscopeURL(t, ctx, pyroscopeC)
	t.Logf("Pyroscope URL: %s", pyroscopeURL)

	appC := startApp(t, ctx, root, "examples/with-otel-extension-manual-start/Dockerfile", net, map[string]string{
		"PYROSCOPE_SERVER_ADDRESS":       "http://pyroscope:4040",
		"PYROSCOPE_APPLICATION_NAME":     appName,
		"OTEL_SERVICE_NAME":              appName,
		"OTEL_PYROSCOPE_START_PROFILING": "false",
		"OTEL_TRACES_EXPORTER":           "logging",
		"OTEL_LOGS_EXPORTER":             "none",
		"OTEL_METRICS_EXPORTER":          "none",
	})
	defer func() {
		require.NoError(t, appC.Terminate(ctx))
	}()

	appURL := getBaseURL(t, ctx, appC)
	t.Logf("App URL: %s", appURL)

	eventually(t, func() bool {
		lastBody := requestFibonacci(t, appURL)
		return strings.Contains(lastBody, "fibonacci(40) = 102334155")
	})

	var spanId string
	eventually(t, func() bool {
		spanId, err = extractSpanIDFromLogs(ctx, appC)
		return err == nil && spanId != ""
	})

	t.Logf("Extracted span ID from logs: %s", spanId)

	// OTel Java agent injects OpenTelemetryHandlerMappingFilter into the filter chain
	const expected = ";java/lang/Thread.run;org/apache/tomcat/util/threads/TaskThread$WrappingRunnable.run;org/apache/tomcat/util/threads/ThreadPoolExecutor$Worker.run;org/apache/tomcat/util/threads/ThreadPoolExecutor.runWorker;org/apache/tomcat/util/net/SocketProcessorBase.run;org/apache/tomcat/util/net/NioEndpoint$SocketProcessor.doRun;org/apache/coyote/AbstractProtocol$ConnectionHandler.process;org/apache/coyote/AbstractProcessorLight.process;org/apache/coyote/http11/Http11Processor.service;org/apache/catalina/connector/CoyoteAdapter.service;org/apache/catalina/core/StandardEngineValve.invoke;org/apache/catalina/valves/ErrorReportValve.invoke;org/apache/catalina/core/StandardHostValve.invoke;org/apache/catalina/authenticator/AuthenticatorBase.invoke;org/apache/catalina/core/StandardContextValve.invoke;org/apache/catalina/core/StandardWrapperValve.invoke;org/apache/catalina/core/ApplicationFilterChain.doFilter;org/apache/catalina/core/ApplicationFilterChain.internalDoFilter;org/springframework/web/filter/OncePerRequestFilter.doFilter;org/springframework/web/filter/CharacterEncodingFilter.doFilterInternal;org/apache/catalina/core/ApplicationFilterChain.doFilter;org/apache/catalina/core/ApplicationFilterChain.internalDoFilter;org/springframework/web/servlet/v3_1/OpenTelemetryHandlerMappingFilter.doFilter;org/apache/catalina/core/ApplicationFilterChain.doFilter;org/apache/catalina/core/ApplicationFilterChain.internalDoFilter;org/springframework/web/filter/OncePerRequestFilter.doFilter;org/springframework/web/filter/FormContentFilter.doFilterInternal;org/apache/catalina/core/ApplicationFilterChain.doFilter;org/apache/catalina/core/ApplicationFilterChain.internalDoFilter;org/springframework/web/filter/OncePerRequestFilter.doFilter;org/springframework/web/filter/RequestContextFilter.doFilterInternal;org/apache/catalina/core/ApplicationFilterChain.doFilter;org/apache/catalina/core/ApplicationFilterChain.internalDoFilter;org/apache/tomcat/websocket/server/WsFilter.doFilter;org/apache/catalina/core/ApplicationFilterChain.doFilter;org/apache/catalina/core/ApplicationFilterChain.internalDoFilter;javax/servlet/http/HttpServlet.service;org/springframework/web/servlet/FrameworkServlet.service;javax/servlet/http/HttpServlet.service;org/springframework/web/servlet/FrameworkServlet.doGet;org/springframework/web/servlet/FrameworkServlet.processRequest;org/springframework/web/servlet/DispatcherServlet.doService;org/springframework/web/servlet/DispatcherServlet.doDispatch;org/springframework/web/servlet/mvc/method/AbstractHandlerMethodAdapter.handle;org/springframework/web/servlet/mvc/method/annotation/RequestMappingHandlerAdapter.handleInternal;org/springframework/web/servlet/mvc/method/annotation/RequestMappingHandlerAdapter.invokeHandlerMethod;org/springframework/web/servlet/mvc/method/annotation/ServletInvocableHandlerMethod.invokeAndHandle;org/springframework/web/method/support/InvocableHandlerMethod.invokeForRequest;org/springframework/web/method/support/InvocableHandlerMethod.doInvoke;java/lang/reflect/Method.invoke;jdk/internal/reflect/DelegatingMethodAccessorImpl.invoke;jdk/internal/reflect/NativeMethodAccessorImpl.invoke;jdk/internal/reflect/NativeMethodAccessorImpl.invoke0;io/pyroscope/example/WorkController.fibonacci;io/pyroscope/example/FibonacciService.compute;io/pyroscope/example/FibonacciService.compute;io/pyroscope/example/FibonacciService.compute;io/pyroscope/example/FibonacciService.compute;io/pyroscope/example/FibonacciService.compute;io/pyroscope/example/FibonacciService.compute;io/pyroscope/example/FibonacciService.compute;io/pyroscope/example/FibonacciService.compute;io/pyroscope/example/FibonacciService.compute;io/pyroscope/example/FibonacciService.compute;io/pyroscope/example/FibonacciService.compute;io/pyroscope/example/FibonacciService.compute;io/pyroscope/example/FibonacciService.compute;io/pyroscope/example/FibonacciService.compute;io/pyroscope/example/FibonacciService.compute;io/pyroscope/example/FibonacciService.compute;io/pyroscope/example/FibonacciService.compute;io/pyroscope/example/FibonacciService.compute;io/pyroscope/example/FibonacciService.compute;io/pyroscope/example/FibonacciService.compute;io/pyroscope/example/FibonacciService.compute;io/pyroscope/example/FibonacciService.compute;io/pyroscope/example/FibonacciService.compute;io/pyroscope/example/FibonacciService.compute"

	// Manual-start mode needs extra requests to generate profiling data because
	// the profiler starts later (in @PostConstruct) than the OTel extension.
	// Send additional requests while polling for the profile.
	eventuallyProfile(t, pyroscopeURL, appName, spanId, expected)
}

func TestPyroscopeAgentFirst(t *testing.T) {
	const appName = "pyroscope-agent-first-test"
	ctx := context.Background()
	root := repoRoot()

	net, err := network.New(ctx)
	require.NoError(t, err)
	defer func() {
		require.NoError(t, net.Remove(ctx))
	}()

	pyroscopeC := startPyroscope(t, ctx, net)
	defer func() {
		require.NoError(t, pyroscopeC.Terminate(ctx))
	}()
	pyroscopeURL := getPyroscopeURL(t, ctx, pyroscopeC)
	t.Logf("Pyroscope URL: %s", pyroscopeURL)

	appC := startApp(t, ctx, root, "examples/with-pyroscope-agent-first/Dockerfile", net, map[string]string{
		"PYROSCOPE_SERVER_ADDRESS":   "http://pyroscope:4040",
		"PYROSCOPE_APPLICATION_NAME": appName,
		"PYROSCOPE_FORMAT":           "jfr",
		"OTEL_SERVICE_NAME":          appName,
		"OTEL_TRACES_EXPORTER":       "logging",
		"OTEL_LOGS_EXPORTER":         "none",
		"OTEL_METRICS_EXPORTER":      "none",
	})
	defer func() {
		require.NoError(t, appC.Terminate(ctx))
	}()

	appURL := getBaseURL(t, ctx, appC)
	t.Logf("App URL: %s", appURL)

	eventually(t, func() bool {
		lastBody := requestFibonacci(t, appURL)
		return strings.Contains(lastBody, "fibonacci(40) = 102334155")
	})

	var spanId string
	eventually(t, func() bool {
		spanId, err = extractSpanIDFromLogs(ctx, appC)
		return err == nil && spanId != ""
	})

	t.Logf("Extracted span ID from logs: %s", spanId)

	// Same expected stack as TestOtelExtension — OTel agent is still instrumenting
	const expected = ";java/lang/Thread.run;org/apache/tomcat/util/threads/TaskThread$WrappingRunnable.run;org/apache/tomcat/util/threads/ThreadPoolExecutor$Worker.run;org/apache/tomcat/util/threads/ThreadPoolExecutor.runWorker;org/apache/tomcat/util/net/SocketProcessorBase.run;org/apache/tomcat/util/net/NioEndpoint$SocketProcessor.doRun;org/apache/coyote/AbstractProtocol$ConnectionHandler.process;org/apache/coyote/AbstractProcessorLight.process;org/apache/coyote/http11/Http11Processor.service;org/apache/catalina/connector/CoyoteAdapter.service;org/apache/catalina/core/StandardEngineValve.invoke;org/apache/catalina/valves/ErrorReportValve.invoke;org/apache/catalina/core/StandardHostValve.invoke;org/apache/catalina/authenticator/AuthenticatorBase.invoke;org/apache/catalina/core/StandardContextValve.invoke;org/apache/catalina/core/StandardWrapperValve.invoke;org/apache/catalina/core/ApplicationFilterChain.doFilter;org/apache/catalina/core/ApplicationFilterChain.internalDoFilter;org/springframework/web/filter/OncePerRequestFilter.doFilter;org/springframework/web/filter/CharacterEncodingFilter.doFilterInternal;org/apache/catalina/core/ApplicationFilterChain.doFilter;org/apache/catalina/core/ApplicationFilterChain.internalDoFilter;org/springframework/web/servlet/v3_1/OpenTelemetryHandlerMappingFilter.doFilter;org/apache/catalina/core/ApplicationFilterChain.doFilter;org/apache/catalina/core/ApplicationFilterChain.internalDoFilter;org/springframework/web/filter/OncePerRequestFilter.doFilter;org/springframework/web/filter/FormContentFilter.doFilterInternal;org/apache/catalina/core/ApplicationFilterChain.doFilter;org/apache/catalina/core/ApplicationFilterChain.internalDoFilter;org/springframework/web/filter/OncePerRequestFilter.doFilter;org/springframework/web/filter/RequestContextFilter.doFilterInternal;org/apache/catalina/core/ApplicationFilterChain.doFilter;org/apache/catalina/core/ApplicationFilterChain.internalDoFilter;org/apache/tomcat/websocket/server/WsFilter.doFilter;org/apache/catalina/core/ApplicationFilterChain.doFilter;org/apache/catalina/core/ApplicationFilterChain.internalDoFilter;javax/servlet/http/HttpServlet.service;org/springframework/web/servlet/FrameworkServlet.service;javax/servlet/http/HttpServlet.service;org/springframework/web/servlet/FrameworkServlet.doGet;org/springframework/web/servlet/FrameworkServlet.processRequest;org/springframework/web/servlet/DispatcherServlet.doService;org/springframework/web/servlet/DispatcherServlet.doDispatch;org/springframework/web/servlet/mvc/method/AbstractHandlerMethodAdapter.handle;org/springframework/web/servlet/mvc/method/annotation/RequestMappingHandlerAdapter.handleInternal;org/springframework/web/servlet/mvc/method/annotation/RequestMappingHandlerAdapter.invokeHandlerMethod;org/springframework/web/servlet/mvc/method/annotation/ServletInvocableHandlerMethod.invokeAndHandle;org/springframework/web/method/support/InvocableHandlerMethod.invokeForRequest;org/springframework/web/method/support/InvocableHandlerMethod.doInvoke;java/lang/reflect/Method.invoke;jdk/internal/reflect/DelegatingMethodAccessorImpl.invoke;jdk/internal/reflect/NativeMethodAccessorImpl.invoke;jdk/internal/reflect/NativeMethodAccessorImpl.invoke0;io/pyroscope/example/WorkController.fibonacci;io/pyroscope/example/FibonacciService.compute;io/pyroscope/example/FibonacciService.compute;io/pyroscope/example/FibonacciService.compute;io/pyroscope/example/FibonacciService.compute;io/pyroscope/example/FibonacciService.compute;io/pyroscope/example/FibonacciService.compute;io/pyroscope/example/FibonacciService.compute;io/pyroscope/example/FibonacciService.compute;io/pyroscope/example/FibonacciService.compute;io/pyroscope/example/FibonacciService.compute;io/pyroscope/example/FibonacciService.compute;io/pyroscope/example/FibonacciService.compute;io/pyroscope/example/FibonacciService.compute;io/pyroscope/example/FibonacciService.compute;io/pyroscope/example/FibonacciService.compute;io/pyroscope/example/FibonacciService.compute;io/pyroscope/example/FibonacciService.compute;io/pyroscope/example/FibonacciService.compute;io/pyroscope/example/FibonacciService.compute;io/pyroscope/example/FibonacciService.compute;io/pyroscope/example/FibonacciService.compute;io/pyroscope/example/FibonacciService.compute;io/pyroscope/example/FibonacciService.compute;io/pyroscope/example/FibonacciService.compute"

	eventuallyProfile(t, pyroscopeURL, appName, spanId, expected)
}

func TestOtelLibraryChildSpans(t *testing.T) {
	const appName = "otel-library-child-spans-test"
	ctx := context.Background()
	root := repoRoot()

	net, err := network.New(ctx)
	require.NoError(t, err)
	defer func() {
		require.NoError(t, net.Remove(ctx))
	}()

	pyroscopeC := startPyroscope(t, ctx, net)
	defer func() {
		require.NoError(t, pyroscopeC.Terminate(ctx))
	}()
	pyroscopeURL := getPyroscopeURL(t, ctx, pyroscopeC)
	t.Logf("Pyroscope URL: %s", pyroscopeURL)

	appC := startApp(t, ctx, root, "examples/with-otel-library/Dockerfile", net, map[string]string{
		"PYROSCOPE_SERVER_ADDRESS":   "http://pyroscope:4040",
		"PYROSCOPE_APPLICATION_NAME": appName,
	})
	defer func() {
		require.NoError(t, appC.Terminate(ctx))
	}()

	appURL := getBaseURL(t, ctx, appC)
	t.Logf("App URL: %s", appURL)

	// Hit the /child-spans endpoint and extract all three span IDs.
	var rootSpanId, child1SpanId, child2SpanId string
	eventually(t, func() bool {
		body := requestChildSpans(t, appURL)
		rootSpanId, _ = extractNamedSpanID(body, "rootSpanId")
		child1SpanId, _ = extractNamedSpanID(body, "child1SpanId")
		child2SpanId, _ = extractNamedSpanID(body, "child2SpanId")
		return rootSpanId != "" && child1SpanId != "" && child2SpanId != ""
	})

	t.Logf("rootSpanId=%s child1SpanId=%s child2SpanId=%s", rootSpanId, child1SpanId, child2SpanId)

	ls := labelSelector(appName)

	// Poll until both child span profiles have non-zero totals.
	var child1Total, child2Total int64
	var lastErr1, lastErr2 error
	ok := assert.Eventually(t, func() bool {
		tree1, err1 := querySpanTree(t, pyroscopeURL, ls, child1SpanId)
		lastErr1 = err1
		if err1 == nil {
			child1Total = tree1.Total()
		}
		tree2, err2 := querySpanTree(t, pyroscopeURL, ls, child2SpanId)
		lastErr2 = err2
		if err2 == nil {
			child2Total = tree2.Total()
		}
		return err1 == nil && err2 == nil && child1Total > 0 && child2Total > 0
	}, 60*time.Second, 2*time.Second)
	if !ok {
		t.Logf("child1 query error: %v, total: %d ns", lastErr1, child1Total)
		t.Logf("child2 query error: %v, total: %d ns", lastErr2, child2Total)
		t.FailNow()
	}

	t.Logf("child1 total: %d ns (%.2f s)", child1Total, float64(child1Total)/1e9)
	t.Logf("child2 total: %d ns (%.2f s)", child2Total, float64(child2Total)/1e9)

	// child1 burns CPU for ~1s, child2 for ~2s.
	// Assert each is in a reasonable range (profiling has sampling jitter).
	const ns = 1_000_000_000 // 1 second in nanoseconds
	assert.Greater(t, child1Total, int64(ns/2), "child1 should have at least 0.5s of CPU")
	assert.Less(t, child1Total, int64(ns*3), "child1 should have less than 3s of CPU")
	assert.Greater(t, child2Total, int64(ns), "child2 should have at least 1s of CPU")
	assert.Less(t, child2Total, int64(ns*5), "child2 should have less than 5s of CPU")

	// child2 should be roughly 2x child1.
	ratio := float64(child2Total) / float64(child1Total)
	t.Logf("child2/child1 ratio: %.2f", ratio)
	assert.Greater(t, ratio, 1.3, "child2 should be at least 1.3x child1")
	assert.Less(t, ratio, 3.0, "child2 should be less than 3x child1")
}
