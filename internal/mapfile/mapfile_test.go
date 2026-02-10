package mapfile

import (
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func TestParse(t *testing.T) {
	t.Parallel()

	src := strings.NewReader(`
# comment
env FOO=foo.secret  # inline
file	key.json=gcp.key
envpath GOOGLE_APPLICATION_CREDENTIALS=key.json
`)

	m, err := Parse(src)
	if err != nil {
		t.Fatalf("Parse: %v", err)
	}
	if len(m.Request.Envs) != 1 || m.Request.Envs[0].Var != "FOO" || m.Request.Envs[0].Name != "foo.secret" {
		t.Fatalf("unexpected envs: %#v", m.Request.Envs)
	}
	if len(m.Request.Files) != 1 || m.Request.Files[0].RelPath != "key.json" || m.Request.Files[0].Name != "gcp.key" {
		t.Fatalf("unexpected files: %#v", m.Request.Files)
	}
	if len(m.EnvPaths) != 1 || m.EnvPaths[0].Var != "GOOGLE_APPLICATION_CREDENTIALS" || m.EnvPaths[0].RelPath != "key.json" {
		t.Fatalf("unexpected envpaths: %#v", m.EnvPaths)
	}
}

func TestResolveProfile_EnvDir(t *testing.T) {
	t.Parallel()

	dir := t.TempDir()
	if err := os.WriteFile(filepath.Join(dir, "linje-prod.kmap"), []byte("env X=x\n"), 0o600); err != nil {
		t.Fatalf("WriteFile: %v", err)
	}
	old := os.Getenv(envProfileDir)
	_ = os.Setenv(envProfileDir, dir)
	t.Cleanup(func() { _ = os.Setenv(envProfileDir, old) })

	p, err := ResolveProfile("linje-prod")
	if err != nil {
		t.Fatalf("ResolveProfile: %v", err)
	}
	if p != filepath.Join(dir, "linje-prod.kmap") {
		t.Fatalf("unexpected path: %q", p)
	}
}
