package projection

import (
	"bytes"
	"context"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"testing"

	"kimen/internal/exitcode"
	"kimen/internal/vault"
)

type fakeVault struct {
	m map[string][]byte
}

func (f fakeVault) GetSecret(_ context.Context, name string) (vault.Secret, error) {
	b, ok := f.m[name]
	if !ok {
		return vault.Secret{}, vault.ErrSecretNotFound
	}
	return vault.Secret{Name: name, Value: append([]byte(nil), b...)}, nil
}

func TestParseRequestValidation(t *testing.T) {
	t.Parallel()

	if _, err := ParseRequest([]string{"1BAD=x"}, nil); err == nil {
		t.Fatalf("expected error")
	}
	if _, err := ParseRequest(nil, []string{"../x=y"}); err == nil {
		t.Fatalf("expected error")
	}
	if _, err := ParseRequest(nil, []string{"/abs=y"}); err == nil {
		t.Fatalf("expected error")
	}
}

func TestRunCommand_EnvAndFiles(t *testing.T) {
	t.Parallel()

	req, err := ParseRequest([]string{"FOO=foo"}, []string{"cfg.txt=bar"})
	if err != nil {
		t.Fatalf("ParseRequest: %v", err)
	}

	v := fakeVault{m: map[string][]byte{
		"foo": []byte("hello"),
		"bar": []byte("world"),
	}}

	var out bytes.Buffer
	var errBuf bytes.Buffer

	restore := setEnv("GO_WANT_HELPER_PROCESS", "1")
	defer restore()

	cmd := []string{os.Args[0], "-test.run=TestHelperProcess", "--", "envfile"}
	err = RunCommand(context.Background(), v, RunSpec{
		Command: cmd,
		Request: req,
		Stdin:   strings.NewReader(""),
		Stdout:  &out,
		Stderr:  &errBuf,
	})
	if err != nil {
		t.Fatalf("RunCommand: %v (stderr=%s)", err, errBuf.String())
	}

	lines := strings.Split(strings.TrimSpace(out.String()), "\n")
	got := make(map[string]string)
	for _, ln := range lines {
		k, v, ok := strings.Cut(ln, "=")
		if ok {
			got[k] = v
		}
	}
	if got["FOO"] != "hello" {
		t.Fatalf("FOO mismatch: %q", got["FOO"])
	}
	if got["FILE"] != "world" {
		t.Fatalf("FILE mismatch: %q", got["FILE"])
	}
	if dir := got["DIR"]; dir == "" {
		t.Fatalf("missing DIR")
	} else if _, err := os.Stat(dir); !errors.Is(err, os.ErrNotExist) {
		t.Fatalf("expected temp dir removed, stat=%v", err)
	}
}

func TestRunCommand_ExitCodeForwarding(t *testing.T) {
	t.Parallel()

	v := fakeVault{m: map[string][]byte{}}
	req, err := ParseRequest(nil, nil)
	if err != nil {
		t.Fatalf("ParseRequest: %v", err)
	}

	var out bytes.Buffer
	restore := setEnv("GO_WANT_HELPER_PROCESS", "1")
	defer restore()

	err = RunCommand(context.Background(), v, RunSpec{
		Command: []string{os.Args[0], "-test.run=TestHelperProcess", "--", "exit", "7"},
		Request: req,
		Stdout:  &out,
		Stderr:  &out,
	})
	if err == nil {
		t.Fatalf("expected error")
	}
	var ec *exitcode.Error
	if !errors.As(err, &ec) {
		t.Fatalf("expected exitcode.Error, got %T", err)
	}
	if ec.Code != 7 {
		t.Fatalf("expected code 7, got %d", ec.Code)
	}
}

// TestHelperProcess is a common pattern to test os/exec behavior without depending on /bin/sh.
// It runs in the test binary itself.
func TestHelperProcess(t *testing.T) {
	if os.Getenv("GO_WANT_HELPER_PROCESS") != "1" {
		return
	}
	args := os.Args
	i := 0
	for ; i < len(args); i++ {
		if args[i] == "--" {
			i++
			break
		}
	}
	if i >= len(args) {
		fmt.Fprintln(os.Stderr, "missing --")
		os.Exit(2)
	}

	switch args[i] {
	case "envfile":
		dir := os.Getenv("KIMEN_FILES_DIR")
		if dir == "" {
			fmt.Fprintln(os.Stderr, "missing KIMEN_FILES_DIR")
			os.Exit(2)
		}
		b, err := os.ReadFile(filepath.Join(dir, "cfg.txt"))
		if err != nil {
			fmt.Fprintln(os.Stderr, err)
			os.Exit(2)
		}
		fmt.Printf("FOO=%s\n", os.Getenv("FOO"))
		fmt.Printf("FILE=%s\n", string(b))
		fmt.Printf("DIR=%s\n", dir)
		os.Exit(0)
	case "exit":
		if i+1 >= len(args) {
			os.Exit(2)
		}
		code, _ := strconv.Atoi(args[i+1])
		os.Exit(code)
	default:
		fmt.Fprintln(os.Stderr, "unknown helper mode")
		os.Exit(2)
	}
}

func setEnv(key, value string) func() {
	old, ok := os.LookupEnv(key)
	_ = os.Setenv(key, value)
	return func() {
		if ok {
			_ = os.Setenv(key, old)
		} else {
			_ = os.Unsetenv(key)
		}
	}
}
