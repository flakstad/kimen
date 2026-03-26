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
	dir := t.TempDir()
	if err := os.WriteFile(filepath.Join(dir, "linje-prod.kmap"), []byte("env X=x\n"), 0o600); err != nil {
		t.Fatalf("WriteFile: %v", err)
	}
	t.Setenv(envProfileDir, dir)

	p, err := ResolveProfile("linje-prod")
	if err != nil {
		t.Fatalf("ResolveProfile: %v", err)
	}
	if p != filepath.Join(dir, "linje-prod.kmap") {
		t.Fatalf("unexpected path: %q", p)
	}
}

func TestResolveProfile_InvalidName(t *testing.T) {
	t.Parallel()

	for _, tc := range []string{"../prod", "prod/dev", "prod dev"} {
		tc := tc
		t.Run(tc, func(t *testing.T) {
			t.Parallel()
			_, err := ResolveProfile(tc)
			if err == nil {
				t.Fatalf("expected invalid profile name error for %q", tc)
			}
			if !strings.Contains(err.Error(), "invalid profile name") {
				t.Fatalf("unexpected error for %q: %v", tc, err)
			}
		})
	}
}

func TestResolveProfile_ChecksUserHomeConfigDirWhenXDGConfigHomeMisses(t *testing.T) {
	homeDir := t.TempDir()
	xdgDir := t.TempDir()
	profilePath := filepath.Join(homeDir, ".config", "kimen", "profiles", "linje-prod.kmap")
	if err := os.MkdirAll(filepath.Dir(profilePath), 0o700); err != nil {
		t.Fatalf("MkdirAll: %v", err)
	}
	if err := os.WriteFile(profilePath, []byte("env X=x\n"), 0o600); err != nil {
		t.Fatalf("WriteFile: %v", err)
	}

	t.Setenv(envProfileDir, "")
	t.Setenv("HOME", homeDir)
	t.Setenv("XDG_CONFIG_HOME", xdgDir)

	p, err := ResolveProfile("linje-prod")
	if err != nil {
		t.Fatalf("ResolveProfile: %v", err)
	}
	if p != profilePath {
		t.Fatalf("unexpected path: %q", p)
	}
}
