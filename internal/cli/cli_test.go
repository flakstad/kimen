package cli

import (
	"bytes"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func TestCLI_VaultInit_SecretSet_List_Render(t *testing.T) {
	dir := t.TempDir()
	vaultPath := filepath.Join(dir, "vault.db")
	outDir := filepath.Join(dir, "out")

	restore := withEnv(map[string]string{
		envVaultPath:   vaultPath,
		envPassphrase:  "pass",
		"KIMEN_UNUSED": "1",
	})
	defer restore()

	out, errBuf, err := runCLI([]string{"vault", "init"}, nil)
	if err != nil {
		t.Fatalf("vault init: %v (stderr=%s)", err, errBuf)
	}
	_ = out

	_, errBuf, err = runCLI([]string{"secret", "set", "foo", "--stdin"}, strings.NewReader("mysecret"))
	if err != nil {
		t.Fatalf("secret set: %v (stderr=%s)", err, errBuf)
	}

	out, errBuf, err = runCLI([]string{"secret", "list", "--json"}, nil)
	if err != nil {
		t.Fatalf("secret list: %v (stderr=%s)", err, errBuf)
	}
	if !strings.Contains(out, "foo") {
		t.Fatalf("expected foo in output, got %q", out)
	}

	_, errBuf, err = runCLI([]string{"project", "render", "--dir", outDir, "--file", "config.txt=foo"}, nil)
	if err != nil {
		t.Fatalf("project render: %v (stderr=%s)", err, errBuf)
	}
	b, err := os.ReadFile(filepath.Join(outDir, "config.txt"))
	if err != nil {
		t.Fatalf("read rendered file: %v", err)
	}
	if string(b) != "mysecret" {
		t.Fatalf("unexpected rendered content: %q", string(b))
	}
}

func TestCLI_SecretGetIsUnsafeByDefault(t *testing.T) {
	dir := t.TempDir()
	vaultPath := filepath.Join(dir, "vault.db")

	restore := withEnv(map[string]string{
		envVaultPath:  vaultPath,
		envPassphrase: "pass",
	})
	defer restore()

	_, _, err := runCLI([]string{"vault", "init"}, nil)
	if err != nil {
		t.Fatalf("vault init: %v", err)
	}

	_, _, err = runCLI([]string{"secret", "set", "x", "--stdin"}, strings.NewReader("v"))
	if err != nil {
		t.Fatalf("secret set: %v", err)
	}

	_, _, err = runCLI([]string{"secret", "get", "x"}, nil)
	if err == nil {
		t.Fatalf("expected error")
	}
}

func withEnv(kv map[string]string) func() {
	orig := make(map[string]*string, len(kv))
	for k, v := range kv {
		if old, ok := os.LookupEnv(k); ok {
			cp := old
			orig[k] = &cp
		} else {
			orig[k] = nil
		}
		_ = os.Setenv(k, v)
	}
	return func() {
		for k, v := range orig {
			if v == nil {
				_ = os.Unsetenv(k)
			} else {
				_ = os.Setenv(k, *v)
			}
		}
	}
}

func runCLI(args []string, stdin *strings.Reader) (string, string, error) {
	root := NewRootCommand()
	var out bytes.Buffer
	var errBuf bytes.Buffer
	root.SetOut(&out)
	root.SetErr(&errBuf)
	if stdin != nil {
		root.SetIn(stdin)
	}
	root.SetArgs(args)
	err := root.Execute()
	return out.String(), errBuf.String(), err
}
