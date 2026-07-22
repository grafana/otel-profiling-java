package dockertest

import (
	"fmt"
	"net"
	"net/http"
	"os"
	"os/exec"
	"strings"
	"testing"
	"time"
)

// dockerCommand is overridable for environments where Docker is wrapped.
var dockerCommand = func() []string {
	if v := os.Getenv("DOCKERTEST_DOCKER_COMMAND"); v != "" {
		return strings.Fields(v)
	}
	return []string{"docker"}
}()

// hostAddr is the address used to reach published container ports.
var hostAddr = func() string {
	if v := os.Getenv("DOCKERTEST_HOST_IP"); v != "" {
		return v
	}
	return "localhost"
}()

type Network struct {
	Name string
}

type Container struct {
	ID string
}

type WaitStrategy struct {
	port     string
	httpPath string
	timeout  time.Duration
}

type ContainerRequest struct {
	Image          string
	Cmd            []string
	Env            map[string]string
	ExposedPorts   []string
	Network        string
	NetworkAliases []string
	WaitFor        *WaitStrategy
}

type BuildRequest struct {
	Context    string
	Dockerfile string
	Tag        string
}

func WaitForHTTP(path, port string, timeout time.Duration) *WaitStrategy {
	return &WaitStrategy{port: port, httpPath: path, timeout: timeout}
}

func CreateNetwork(t *testing.T) *Network {
	t.Helper()
	name := fmt.Sprintf("otel-profiling-java-itest-%d", time.Now().UnixNano())
	runDocker(t, "network", "create", name)
	t.Cleanup(func() { runDockerQuiet("network", "rm", name) })
	return &Network{Name: name}
}

func BuildImage(t *testing.T, req BuildRequest) string {
	t.Helper()
	args := []string{"build"}
	if req.Tag != "" {
		args = append(args, "-t", req.Tag)
	}
	if req.Dockerfile != "" {
		args = append(args, "-f", req.Dockerfile)
	}
	args = append(args, req.Context)
	runDocker(t, args...)
	return req.Tag
}

func StartContainer(t *testing.T, req ContainerRequest) *Container {
	t.Helper()
	args := []string{"run", "-d"}
	for _, port := range req.ExposedPorts {
		args = append(args, "-p", "0:"+normalizePort(port))
	}
	for key, value := range req.Env {
		args = append(args, "-e", key+"="+value)
	}
	if req.Network != "" {
		args = append(args, "--network", req.Network)
		for _, alias := range req.NetworkAliases {
			args = append(args, "--network-alias", alias)
		}
	}
	args = append(args, req.Image)
	args = append(args, req.Cmd...)

	id := strings.TrimSpace(runDocker(t, args...))
	container := &Container{ID: id}
	t.Cleanup(func() {
		if t.Failed() {
			if state, err := runDockerCommand("inspect", container.ID, "--format",
				"ExitCode={{.State.ExitCode}} OOMKilled={{.State.OOMKilled}} Running={{.State.Running}}"); err == nil {
				t.Logf("dockertest: container %s state: %s", shortID(container.ID), state)
			}
			if logs, err := container.Logs(); err == nil {
				t.Logf("dockertest: container %s logs:\n%s", shortID(container.ID), logs)
			}
		}
		runDockerQuiet("rm", "-f", container.ID)
	})

	if req.WaitFor != nil {
		waitReady(t, container, req.WaitFor)
	}
	return container
}

func (c *Container) MappedPort(t *testing.T, containerPort string) string {
	t.Helper()
	output := runDocker(t, "port", c.ID, normalizePort(containerPort))
	line := strings.TrimSpace(strings.Split(output, "\n")[0])
	_, hostPort, err := net.SplitHostPort(line)
	if err != nil {
		idx := strings.LastIndex(line, ":")
		if idx < 0 {
			t.Fatalf("dockertest: unexpected docker port output: %q", line)
		}
		hostPort = line[idx+1:]
	}
	return hostPort
}

func (c *Container) HostPort(t *testing.T, containerPort string) string {
	t.Helper()
	return net.JoinHostPort(hostAddr, c.MappedPort(t, containerPort))
}

func (c *Container) Logs() ([]byte, error) {
	name, args := dockerArgs("logs", c.ID)
	output, err := exec.Command(name, args...).CombinedOutput()
	if err != nil {
		return nil, fmt.Errorf("dockertest: reading logs for %s: %w: %s", shortID(c.ID), err, output)
	}
	return output, nil
}

func normalizePort(port string) string {
	port = strings.TrimSuffix(port, "/tcp")
	port = strings.TrimSuffix(port, "/udp")
	return port
}

func waitReady(t *testing.T, container *Container, strategy *WaitStrategy) {
	t.Helper()
	deadline := time.Now().Add(strategy.timeout)
	addr := container.HostPort(t, strategy.port)
	for time.Now().Before(deadline) {
		if tryHTTP(addr, strategy.httpPath) {
			return
		}
		time.Sleep(time.Second)
	}
	t.Fatalf("dockertest: container %s not ready after %v (port %s)",
		shortID(container.ID), strategy.timeout, strategy.port)
}

func tryHTTP(addr, path string) bool {
	resp, err := (&http.Client{Timeout: 2 * time.Second}).Get("http://" + addr + path)
	if err != nil {
		return false
	}
	_ = resp.Body.Close()
	return resp.StatusCode >= http.StatusOK && resp.StatusCode < http.StatusBadRequest
}

func dockerArgs(args ...string) (string, []string) {
	full := append(append([]string{}, dockerCommand[1:]...), args...)
	return dockerCommand[0], full
}

func runDocker(t *testing.T, args ...string) string {
	t.Helper()
	output, err := runDockerCommand(args...)
	if err != nil {
		t.Fatalf("%v", err)
	}
	return output
}

func runDockerCommand(args ...string) (string, error) {
	name, full := dockerArgs(args...)
	cmd := exec.Command(name, full...)
	var stdout, stderr strings.Builder
	cmd.Stdout = &stdout
	cmd.Stderr = &stderr
	if err := cmd.Run(); err != nil {
		return "", fmt.Errorf("dockertest: %s %s failed: %w\nstdout: %s\nstderr: %s",
			name, strings.Join(full, " "), err, stdout.String(), stderr.String())
	}
	return stdout.String(), nil
}

func runDockerQuiet(args ...string) {
	name, full := dockerArgs(args...)
	_ = exec.Command(name, full...).Run()
}

func shortID(id string) string {
	if len(id) <= 12 {
		return id
	}
	return id[:12]
}
