package itest

import (
	"bytes"
	"context"
	"fmt"
	"io"
	"math"
	"net/http"
	"path/filepath"
	"regexp"
	"runtime"
	"strconv"
	"strings"
	"testing"
	"time"

	"otel-profiling-java-itest/api/querier"
	"otel-profiling-java-itest/dockertest"
	"otel-profiling-java-itest/pyroscope/model"
)

func repoRoot() string {
	_, filename, _, _ := runtime.Caller(0)
	// integration_test.go is in otel-profiling-java/itest/
	// repo root is otel-profiling-java/
	return filepath.Dir(filepath.Dir(filename))
}

func startPyroscope(t *testing.T, network *dockertest.Network) *dockertest.Container {
	t.Helper()
	t.Logf("starting pyroscope...")
	return dockertest.StartContainer(t, dockertest.ContainerRequest{
		Image:          "grafana/pyroscope:latest",
		Cmd:            []string{"-segment-writer.min-ready-duration=0s"},
		ExposedPorts:   []string{"4040/tcp"},
		Network:        network.Name,
		NetworkAliases: []string{"pyroscope"},
		WaitFor:        dockertest.WaitForHTTP("/ready", "4040/tcp", 60*time.Second),
	})
}

func startApp(t *testing.T, root string, dockerfile string, network *dockertest.Network, env map[string]string) *dockertest.Container {
	t.Helper()
	t.Logf("starting example %s ...", dockerfile)
	image := dockertest.BuildImage(t, dockertest.BuildRequest{
		Context:    root,
		Dockerfile: filepath.Join(root, dockerfile),
		Tag:        fmt.Sprintf("otel-profiling-java-itest:%d", time.Now().UnixNano()),
	})
	return dockertest.StartContainer(t, dockertest.ContainerRequest{
		Image:        image,
		ExposedPorts: []string{"8080/tcp"},
		Env:          env,
		Network:      network.Name,
		WaitFor:      dockertest.WaitForHTTP("/health", "8080/tcp", 5*time.Minute),
	})
}

func getBaseURL(t *testing.T, c *dockertest.Container) string {
	t.Helper()
	return "http://" + c.HostPort(t, "8080/tcp")
}

func getPyroscopeURL(t *testing.T, c *dockertest.Container) string {
	t.Helper()
	return "http://" + c.HostPort(t, "4040/tcp")
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

func extractSpanIDFromLogs(c *dockertest.Container) (string, error) {
	data, err := c.Logs()
	if err != nil {
		return "", fmt.Errorf("failed to get container logs: %w", err)
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
	qc := querier.NewClient(http.DefaultClient, pyroscopeURL)

	to := time.Now()
	from := to.Add(-1 * time.Hour)
	maxNodes := int64(65536)
	resp, err := qc.SelectMergeStacktraces(context.Background(), &querier.SelectMergeStacktracesRequest{
		ProfileTypeID: "process_cpu:cpu:nanoseconds:cpu:nanoseconds",
		Start:         from.UnixMilli(),
		End:           to.UnixMilli(),
		LabelSelector: labelSelector,
		SpanSelector:  []string{span},
		MaxNodes:      &maxNodes,
		Format:        querier.ProfileFormatTree,
	})
	t.Logf("querySpanTree %s %s %s = err %+v", pyroscopeURL, labelSelector, span, err)
	if err != nil {
		return nil, err
	}
	return model.UnmarshalTree(resp.Tree)
}

func TestOtelExtension(t *testing.T) {
	const appName = "otel-extension-example"
	root := repoRoot()

	testNetwork := dockertest.CreateNetwork(t)
	pyroscopeC := startPyroscope(t, testNetwork)
	pyroscopeURL := getPyroscopeURL(t, pyroscopeC)
	t.Logf("Pyroscope URL: %s", pyroscopeURL)

	appC := startApp(t, root, "examples/with-otel-extension/Dockerfile", testNetwork, map[string]string{
		"PYROSCOPE_SERVER_ADDRESS":   "http://pyroscope:4040",
		"PYROSCOPE_APPLICATION_NAME": appName,
		"PYROSCOPE_FORMAT":           "jfr",
		"OTEL_SERVICE_NAME":          appName,
		"OTEL_TRACES_EXPORTER":       "logging",
		"OTEL_LOGS_EXPORTER":         "none",
		"OTEL_METRICS_EXPORTER":      "none",
	})

	appURL := getBaseURL(t, appC)
	t.Logf("App URL: %s", appURL)

	eventually(t, func() bool {
		lastBody := requestFibonacci(t, appURL)
		return strings.Contains(lastBody, "fibonacci(40) = 102334155")
	})

	var spanId string
	var err error
	eventually(t, func() bool {
		spanId, err = extractSpanIDFromLogs(appC)
		return err == nil && spanId != ""
	})

	t.Logf("Extracted span ID from logs: %s", spanId)

	// OTel Java agent injects OpenTelemetryHandlerMappingFilter into the filter chain
	const expected = ";java/lang/Thread.run;org/apache/tomcat/util/threads/TaskThread$WrappingRunnable.run;org/apache/tomcat/util/threads/ThreadPoolExecutor$Worker.run;org/apache/tomcat/util/threads/ThreadPoolExecutor.runWorker;org/apache/tomcat/util/net/SocketProcessorBase.run;org/apache/tomcat/util/net/NioEndpoint$SocketProcessor.doRun;org/apache/coyote/AbstractProtocol$ConnectionHandler.process;org/apache/coyote/AbstractProcessorLight.process;org/apache/coyote/http11/Http11Processor.service;org/apache/catalina/connector/CoyoteAdapter.service;org/apache/catalina/core/StandardEngineValve.invoke;org/apache/catalina/valves/ErrorReportValve.invoke;org/apache/catalina/core/StandardHostValve.invoke;org/apache/catalina/authenticator/AuthenticatorBase.invoke;org/apache/catalina/core/StandardContextValve.invoke;org/apache/catalina/core/StandardWrapperValve.invoke;org/apache/catalina/core/ApplicationFilterChain.doFilter;org/apache/catalina/core/ApplicationFilterChain.internalDoFilter;org/springframework/web/filter/OncePerRequestFilter.doFilter;org/springframework/web/filter/CharacterEncodingFilter.doFilterInternal;org/apache/catalina/core/ApplicationFilterChain.doFilter;org/apache/catalina/core/ApplicationFilterChain.internalDoFilter;org/springframework/web/servlet/v3_1/OpenTelemetryHandlerMappingFilter.doFilter;org/apache/catalina/core/ApplicationFilterChain.doFilter;org/apache/catalina/core/ApplicationFilterChain.internalDoFilter;org/springframework/web/filter/OncePerRequestFilter.doFilter;org/springframework/web/filter/FormContentFilter.doFilterInternal;org/apache/catalina/core/ApplicationFilterChain.doFilter;org/apache/catalina/core/ApplicationFilterChain.internalDoFilter;org/springframework/web/filter/OncePerRequestFilter.doFilter;org/springframework/web/filter/RequestContextFilter.doFilterInternal;org/apache/catalina/core/ApplicationFilterChain.doFilter;org/apache/catalina/core/ApplicationFilterChain.internalDoFilter;org/apache/tomcat/websocket/server/WsFilter.doFilter;org/apache/catalina/core/ApplicationFilterChain.doFilter;org/apache/catalina/core/ApplicationFilterChain.internalDoFilter;javax/servlet/http/HttpServlet.service;org/springframework/web/servlet/FrameworkServlet.service;javax/servlet/http/HttpServlet.service;org/springframework/web/servlet/FrameworkServlet.doGet;org/springframework/web/servlet/FrameworkServlet.processRequest;org/springframework/web/servlet/DispatcherServlet.doService;org/springframework/web/servlet/DispatcherServlet.doDispatch;org/springframework/web/servlet/mvc/method/AbstractHandlerMethodAdapter.handle;org/springframework/web/servlet/mvc/method/annotation/RequestMappingHandlerAdapter.handleInternal;org/springframework/web/servlet/mvc/method/annotation/RequestMappingHandlerAdapter.invokeHandlerMethod;org/springframework/web/servlet/mvc/method/annotation/ServletInvocableHandlerMethod.invokeAndHandle;org/springframework/web/method/support/InvocableHandlerMethod.invokeForRequest;org/springframework/web/method/support/InvocableHandlerMethod.doInvoke;java/lang/reflect/Method.invoke;jdk/internal/reflect/DelegatingMethodAccessorImpl.invoke;jdk/internal/reflect/NativeMethodAccessorImpl.invoke;jdk/internal/reflect/NativeMethodAccessorImpl.invoke0;io/pyroscope/example/WorkController.fibonacci;io/pyroscope/example/FibonacciService.compute;io/pyroscope/example/FibonacciService.compute;io/pyroscope/example/FibonacciService.compute;io/pyroscope/example/FibonacciService.compute;io/pyroscope/example/FibonacciService.compute;io/pyroscope/example/FibonacciService.compute;io/pyroscope/example/FibonacciService.compute;io/pyroscope/example/FibonacciService.compute;io/pyroscope/example/FibonacciService.compute;io/pyroscope/example/FibonacciService.compute;io/pyroscope/example/FibonacciService.compute;io/pyroscope/example/FibonacciService.compute;io/pyroscope/example/FibonacciService.compute;io/pyroscope/example/FibonacciService.compute;io/pyroscope/example/FibonacciService.compute;io/pyroscope/example/FibonacciService.compute;io/pyroscope/example/FibonacciService.compute;io/pyroscope/example/FibonacciService.compute;io/pyroscope/example/FibonacciService.compute;io/pyroscope/example/FibonacciService.compute;io/pyroscope/example/FibonacciService.compute;io/pyroscope/example/FibonacciService.compute;io/pyroscope/example/FibonacciService.compute;io/pyroscope/example/FibonacciService.compute"

	eventuallyProfile(t, pyroscopeURL, appName, spanId, expected)
}

func eventually(t *testing.T, condition func() bool) {
	t.Helper()
	if !waitFor(condition, 30*time.Second, time.Second) {
		t.Fatal("condition was not satisfied before timeout")
	}
}

func waitFor(condition func() bool, waitFor time.Duration, tick time.Duration) bool {
	result := make(chan bool, 1)
	check := func() { result <- condition() }

	timer := time.NewTimer(waitFor)
	defer timer.Stop()
	ticker := time.NewTicker(tick)
	defer ticker.Stop()

	go check()
	var tickC <-chan time.Time
	for {
		select {
		case <-timer.C:
			return false
		case <-tickC:
			tickC = nil
			go check()
		case ok := <-result:
			if ok {
				return true
			}
			tickC = ticker.C
		}
	}
}

func eventuallyProfile(t *testing.T, pyroscopeURL string, appName string, spanId string, expectedStack string) {
	t.Helper()
	var lastCollapsed string
	var lastErr error
	ok := waitFor(func() bool {
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
	root := repoRoot()

	testNetwork := dockertest.CreateNetwork(t)
	pyroscopeC := startPyroscope(t, testNetwork)
	pyroscopeURL := getPyroscopeURL(t, pyroscopeC)
	t.Logf("Pyroscope URL: %s", pyroscopeURL)

	appC := startApp(t, root, "examples/with-otel-library/Dockerfile", testNetwork, map[string]string{
		"PYROSCOPE_SERVER_ADDRESS":   "http://pyroscope:4040",
		"PYROSCOPE_APPLICATION_NAME": appName,
	})

	appURL := getBaseURL(t, appC)
	t.Logf("App URL: %s", appURL)

	var spanId string
	var err error
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
	root := repoRoot()

	testNetwork := dockertest.CreateNetwork(t)
	pyroscopeC := startPyroscope(t, testNetwork)
	pyroscopeURL := getPyroscopeURL(t, pyroscopeC)
	t.Logf("Pyroscope URL: %s", pyroscopeURL)

	appC := startApp(t, root, "examples/with-otel-extension-manual-start/Dockerfile", testNetwork, map[string]string{
		"PYROSCOPE_SERVER_ADDRESS":       "http://pyroscope:4040",
		"PYROSCOPE_APPLICATION_NAME":     appName,
		"OTEL_SERVICE_NAME":              appName,
		"OTEL_PYROSCOPE_START_PROFILING": "false",
		"OTEL_TRACES_EXPORTER":           "logging",
		"OTEL_LOGS_EXPORTER":             "none",
		"OTEL_METRICS_EXPORTER":          "none",
	})

	appURL := getBaseURL(t, appC)
	t.Logf("App URL: %s", appURL)

	eventually(t, func() bool {
		lastBody := requestFibonacci(t, appURL)
		return strings.Contains(lastBody, "fibonacci(40) = 102334155")
	})

	var spanId string
	var err error
	eventually(t, func() bool {
		spanId, err = extractSpanIDFromLogs(appC)
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
	root := repoRoot()

	testNetwork := dockertest.CreateNetwork(t)
	pyroscopeC := startPyroscope(t, testNetwork)
	pyroscopeURL := getPyroscopeURL(t, pyroscopeC)
	t.Logf("Pyroscope URL: %s", pyroscopeURL)

	appC := startApp(t, root, "examples/with-pyroscope-agent-first/Dockerfile", testNetwork, map[string]string{
		"PYROSCOPE_SERVER_ADDRESS":   "http://pyroscope:4040",
		"PYROSCOPE_APPLICATION_NAME": appName,
		"PYROSCOPE_FORMAT":           "jfr",
		"OTEL_SERVICE_NAME":          appName,
		"OTEL_TRACES_EXPORTER":       "logging",
		"OTEL_LOGS_EXPORTER":         "none",
		"OTEL_METRICS_EXPORTER":      "none",
	})

	appURL := getBaseURL(t, appC)
	t.Logf("App URL: %s", appURL)

	eventually(t, func() bool {
		lastBody := requestFibonacci(t, appURL)
		return strings.Contains(lastBody, "fibonacci(40) = 102334155")
	})

	var spanId string
	var err error
	eventually(t, func() bool {
		spanId, err = extractSpanIDFromLogs(appC)
		return err == nil && spanId != ""
	})

	t.Logf("Extracted span ID from logs: %s", spanId)

	// Same expected stack as TestOtelExtension — OTel agent is still instrumenting
	const expected = ";java/lang/Thread.run;org/apache/tomcat/util/threads/TaskThread$WrappingRunnable.run;org/apache/tomcat/util/threads/ThreadPoolExecutor$Worker.run;org/apache/tomcat/util/threads/ThreadPoolExecutor.runWorker;org/apache/tomcat/util/net/SocketProcessorBase.run;org/apache/tomcat/util/net/NioEndpoint$SocketProcessor.doRun;org/apache/coyote/AbstractProtocol$ConnectionHandler.process;org/apache/coyote/AbstractProcessorLight.process;org/apache/coyote/http11/Http11Processor.service;org/apache/catalina/connector/CoyoteAdapter.service;org/apache/catalina/core/StandardEngineValve.invoke;org/apache/catalina/valves/ErrorReportValve.invoke;org/apache/catalina/core/StandardHostValve.invoke;org/apache/catalina/authenticator/AuthenticatorBase.invoke;org/apache/catalina/core/StandardContextValve.invoke;org/apache/catalina/core/StandardWrapperValve.invoke;org/apache/catalina/core/ApplicationFilterChain.doFilter;org/apache/catalina/core/ApplicationFilterChain.internalDoFilter;org/springframework/web/filter/OncePerRequestFilter.doFilter;org/springframework/web/filter/CharacterEncodingFilter.doFilterInternal;org/apache/catalina/core/ApplicationFilterChain.doFilter;org/apache/catalina/core/ApplicationFilterChain.internalDoFilter;org/springframework/web/servlet/v3_1/OpenTelemetryHandlerMappingFilter.doFilter;org/apache/catalina/core/ApplicationFilterChain.doFilter;org/apache/catalina/core/ApplicationFilterChain.internalDoFilter;org/springframework/web/filter/OncePerRequestFilter.doFilter;org/springframework/web/filter/FormContentFilter.doFilterInternal;org/apache/catalina/core/ApplicationFilterChain.doFilter;org/apache/catalina/core/ApplicationFilterChain.internalDoFilter;org/springframework/web/filter/OncePerRequestFilter.doFilter;org/springframework/web/filter/RequestContextFilter.doFilterInternal;org/apache/catalina/core/ApplicationFilterChain.doFilter;org/apache/catalina/core/ApplicationFilterChain.internalDoFilter;org/apache/tomcat/websocket/server/WsFilter.doFilter;org/apache/catalina/core/ApplicationFilterChain.doFilter;org/apache/catalina/core/ApplicationFilterChain.internalDoFilter;javax/servlet/http/HttpServlet.service;org/springframework/web/servlet/FrameworkServlet.service;javax/servlet/http/HttpServlet.service;org/springframework/web/servlet/FrameworkServlet.doGet;org/springframework/web/servlet/FrameworkServlet.processRequest;org/springframework/web/servlet/DispatcherServlet.doService;org/springframework/web/servlet/DispatcherServlet.doDispatch;org/springframework/web/servlet/mvc/method/AbstractHandlerMethodAdapter.handle;org/springframework/web/servlet/mvc/method/annotation/RequestMappingHandlerAdapter.handleInternal;org/springframework/web/servlet/mvc/method/annotation/RequestMappingHandlerAdapter.invokeHandlerMethod;org/springframework/web/servlet/mvc/method/annotation/ServletInvocableHandlerMethod.invokeAndHandle;org/springframework/web/method/support/InvocableHandlerMethod.invokeForRequest;org/springframework/web/method/support/InvocableHandlerMethod.doInvoke;java/lang/reflect/Method.invoke;jdk/internal/reflect/DelegatingMethodAccessorImpl.invoke;jdk/internal/reflect/NativeMethodAccessorImpl.invoke;jdk/internal/reflect/NativeMethodAccessorImpl.invoke0;io/pyroscope/example/WorkController.fibonacci;io/pyroscope/example/FibonacciService.compute;io/pyroscope/example/FibonacciService.compute;io/pyroscope/example/FibonacciService.compute;io/pyroscope/example/FibonacciService.compute;io/pyroscope/example/FibonacciService.compute;io/pyroscope/example/FibonacciService.compute;io/pyroscope/example/FibonacciService.compute;io/pyroscope/example/FibonacciService.compute;io/pyroscope/example/FibonacciService.compute;io/pyroscope/example/FibonacciService.compute;io/pyroscope/example/FibonacciService.compute;io/pyroscope/example/FibonacciService.compute;io/pyroscope/example/FibonacciService.compute;io/pyroscope/example/FibonacciService.compute;io/pyroscope/example/FibonacciService.compute;io/pyroscope/example/FibonacciService.compute;io/pyroscope/example/FibonacciService.compute;io/pyroscope/example/FibonacciService.compute;io/pyroscope/example/FibonacciService.compute;io/pyroscope/example/FibonacciService.compute;io/pyroscope/example/FibonacciService.compute;io/pyroscope/example/FibonacciService.compute;io/pyroscope/example/FibonacciService.compute;io/pyroscope/example/FibonacciService.compute"

	eventuallyProfile(t, pyroscopeURL, appName, spanId, expected)
}

// sumCpuForFunction parses collapsed profile output and sums the self values
// of all stacks that contain the given function name.
// Collapsed format: "frame;frame;...;leaf <self_value>\n"
func sumCpuForFunction(collapsed string, funcName string) int64 {
	var total int64
	for _, line := range strings.Split(strings.TrimSpace(collapsed), "\n") {
		if line == "" {
			continue
		}
		// Split on last space: "stack_frames self_value"
		lastSpace := strings.LastIndex(line, " ")
		if lastSpace < 0 {
			continue
		}
		stack := line[:lastSpace]
		valueStr := line[lastSpace+1:]
		if !strings.Contains(stack, funcName) {
			continue
		}
		v, err := strconv.ParseInt(valueStr, 10, 64)
		if err != nil {
			continue
		}
		total += v
	}
	return total
}

func TestOtelLibraryChildSpans(t *testing.T) {
	const appName = "otel-library-child-spans-test"
	root := repoRoot()

	testNetwork := dockertest.CreateNetwork(t)
	pyroscopeC := startPyroscope(t, testNetwork)
	pyroscopeURL := getPyroscopeURL(t, pyroscopeC)
	t.Logf("Pyroscope URL: %s", pyroscopeURL)

	appC := startApp(t, root, "examples/with-otel-library/Dockerfile", testNetwork, map[string]string{
		"PYROSCOPE_SERVER_ADDRESS":   "http://pyroscope:4040",
		"PYROSCOPE_APPLICATION_NAME": appName,
	})

	appURL := getBaseURL(t, appC)
	t.Logf("App URL: %s", appURL)

	// Hit the /child-spans endpoint and extract the span ID.
	var spanId string
	var err error
	eventually(t, func() bool {
		body := requestChildSpans(t, appURL)
		spanId, err = extractSpanIDFromBody(body)
		return err == nil && spanId != ""
	})

	t.Logf("spanId=%s", spanId)

	ls := labelSelector(appName)

	// Poll until the span profile contains burnChild1 and burnChild2 stacks
	// with CPU times within 1s of the expected values (2s and 4s respectively).
	const ns = 1_000_000_000 // 1 second in nanoseconds
	var child1Total, child2Total int64
	var lastCollapsed string
	var lastErr error
	ok := waitFor(func() bool {
		collapsed, queryErr := querySpanPyroscopeProfile(t, pyroscopeURL, ls, spanId)
		lastErr = queryErr
		lastCollapsed = collapsed
		if queryErr != nil || collapsed == "" {
			return false
		}
		child1Total = sumCpuForFunction(collapsed, "WorkController.burnChild1")
		child2Total = sumCpuForFunction(collapsed, "WorkController.burnChild2")
		t.Logf("child1 total: %d ns (%.2f s), child2 total: %d ns (%.2f s)",
			child1Total, float64(child1Total)/1e9, child2Total, float64(child2Total)/1e9)
		child1InRange := math.Abs(float64(child1Total)-float64(2*ns)) <= float64(ns)
		child2InRange := math.Abs(float64(child2Total)-float64(4*ns)) <= float64(ns)
		return child1InRange && child2InRange
	}, 60*time.Second, 2*time.Second)
	if !ok {
		t.Logf("query error: %v", lastErr)
		t.Logf("last collapsed profile:\n%s", lastCollapsed)
		t.Logf("child1 (burnChild1) total: %d ns, child2 (burnChild2) total: %d ns", child1Total, child2Total)
		t.Fatalf("child1 expected ~2s (got %.2fs), child2 expected ~4s (got %.2fs)",
			float64(child1Total)/1e9, float64(child2Total)/1e9)
	}
}
