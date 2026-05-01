package main
import (
	"fmt"
	"os"
	"os/exec"
	"strings"
)
func main() {
	exePath := os.Args[1]
	if _, err := os.Stat(exePath); os.IsNotExist(err) {
		fmt.Fprintf(os.Stderr, "[GO-WRAPPER] FATAL ERROR: Binary not found at %s\n", exePath)
		os.Exit(1)
	}
	cmd := exec.Command(exePath, os.Args[2:]...)
	cmd.Stdout = os.Stdout
	cmd.Stderr = os.Stderr

	// Extract WIN_GOBIN to inject into child PATH
	winGobin := os.Getenv("WIN_GOBIN")
	var newEnv []string
	for _, env := range os.Environ() {
		// Filter out invalid Windows internal variables
		if !strings.HasPrefix(env, "=") {
			// Forcefully prepend the correct Go binary path to PATH
			if strings.HasPrefix(strings.ToUpper(env), "PATH=") && winGobin != "" {
				env = "PATH=" + winGobin + string(os.PathListSeparator) + env[5:]
			}
			newEnv = append(newEnv, env)
		}
	}
	cmd.Env = newEnv

	if err := cmd.Run(); err != nil {
		fmt.Fprintf(os.Stderr, "[GO-WRAPPER] EXECUTION ERROR: %v\n", err)
		os.Exit(1)
	}
}
