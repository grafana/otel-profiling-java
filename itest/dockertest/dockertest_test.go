package dockertest

import (
	"net"
	"net/http"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

func TestNormalizePort(t *testing.T) {
	tests := []struct {
		input string
		want  string
	}{
		{input: "8080/tcp", want: "8080"},
		{input: "8080/udp", want: "8080"},
		{input: "8080", want: "8080"},
	}
	for _, test := range tests {
		if got := normalizePort(test.input); got != test.want {
			t.Errorf("normalizePort(%q) = %q, want %q", test.input, got, test.want)
		}
	}
}

func TestWaitForHTTP(t *testing.T) {
	strategy := WaitForHTTP("/ready", "4040/tcp", 30*time.Second)
	if strategy.httpPath != "/ready" {
		t.Errorf("httpPath = %q, want /ready", strategy.httpPath)
	}
	if strategy.port != "4040/tcp" {
		t.Errorf("port = %q, want 4040/tcp", strategy.port)
	}
	if strategy.timeout != 30*time.Second {
		t.Errorf("timeout = %v, want 30s", strategy.timeout)
	}
}

func TestTryHTTP(t *testing.T) {
	mux := http.NewServeMux()
	mux.HandleFunc("/ready", func(w http.ResponseWriter, _ *http.Request) {
		w.WriteHeader(http.StatusOK)
	})
	mux.HandleFunc("/error", func(w http.ResponseWriter, _ *http.Request) {
		w.WriteHeader(http.StatusInternalServerError)
	})
	listener, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	server := &http.Server{Handler: mux}
	go func() { _ = server.Serve(listener) }()
	t.Cleanup(func() { _ = server.Close() })

	if !tryHTTP(listener.Addr().String(), "/ready") {
		t.Error("tryHTTP returned false for ready endpoint")
	}
	if tryHTTP(listener.Addr().String(), "/error") {
		t.Error("tryHTTP returned true for error endpoint")
	}
	if tryHTTP("127.0.0.1:1", "/ready") {
		t.Error("tryHTTP returned true for unreachable endpoint")
	}
}

func TestShortID(t *testing.T) {
	if got := shortID("1234567890123456"); got != "123456789012" {
		t.Errorf("shortID returned %q", got)
	}
	if got := shortID("short"); got != "short" {
		t.Errorf("shortID returned %q", got)
	}
}

func TestDockerLifecycleCommands(t *testing.T) {
	dir := t.TempDir()
	logPath := filepath.Join(dir, "commands.log")
	scriptPath := filepath.Join(dir, "docker")
	script := `#!/bin/sh
printf '%s\n' "$*" >> "$DOCKERTEST_LOG"
case "$1" in
  run) printf '%s\n' '12345678901234567890' ;;
  port) printf '%s\n' '0.0.0.0:49152' ;;
  logs) printf '%s\n' 'application log output' ;;
esac
`
	if err := os.WriteFile(scriptPath, []byte(script), 0o755); err != nil {
		t.Fatal(err)
	}
	t.Setenv("DOCKERTEST_LOG", logPath)

	originalCommand := dockerCommand
	dockerCommand = []string{scriptPath}
	t.Cleanup(func() { dockerCommand = originalCommand })

	t.Run("lifecycle", func(t *testing.T) {
		network := CreateNetwork(t)
		image := BuildImage(t, BuildRequest{
			Context:    "/repo",
			Dockerfile: "/repo/Dockerfile",
			Tag:        "test-image:latest",
		})
		container := StartContainer(t, ContainerRequest{
			Image:          image,
			Cmd:            []string{"serve"},
			Env:            map[string]string{"KEY": "value"},
			ExposedPorts:   []string{"8080/tcp"},
			Network:        network.Name,
			NetworkAliases: []string{"app"},
		})
		if got := container.HostPort(t, "8080/tcp"); got != "localhost:49152" {
			t.Errorf("HostPort = %q, want localhost:49152", got)
		}
		logs, err := container.Logs()
		if err != nil {
			t.Fatal(err)
		}
		if string(logs) != "application log output\n" {
			t.Errorf("Logs = %q", logs)
		}
	})

	commandLog, err := os.ReadFile(logPath)
	if err != nil {
		t.Fatal(err)
	}
	commands := string(commandLog)
	for _, expected := range []string{
		"network create otel-profiling-java-itest-",
		"build -t test-image:latest -f /repo/Dockerfile /repo",
		"run -d -p 0:8080 -e KEY=value --network otel-profiling-java-itest-",
		"--network-alias app test-image:latest serve",
		"port 12345678901234567890 8080",
		"logs 12345678901234567890",
		"rm -f 12345678901234567890",
		"network rm otel-profiling-java-itest-",
	} {
		if !strings.Contains(commands, expected) {
			t.Errorf("command log does not contain %q:\n%s", expected, commands)
		}
	}
}
