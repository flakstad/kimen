package projection

import (
	"bytes"
	"context"
	"errors"
	"fmt"
	"io"
	"os"
	"os/exec"
	"path/filepath"
	"runtime"
	"strings"

	"kimen/internal/exitcode"
	"kimen/internal/vault"
)

type RunSpec struct {
	Command  []string
	Request  Request
	EnvPaths []EnvPathMapping
	FilesDir string
	Stdin    io.Reader
	Stdout   io.Writer
	Stderr   io.Writer
	// BeforeExec runs after projection is fully resolved and before starting
	// the child process. Callers can use this to release resources such as
	// vault file locks for long-running commands.
	BeforeExec func() error
}

func RunCommand(ctx context.Context, r vault.Reader, spec RunSpec) error {
	if len(spec.Command) == 0 {
		return errors.New("missing command")
	}

	var cleanup func()
	filesDir := spec.FilesDir
	if len(spec.Request.Files) > 0 && filesDir == "" {
		dir, err := os.MkdirTemp("", "kimen-files-*")
		if err != nil {
			return err
		}
		if err := os.Chmod(dir, 0o700); err != nil {
			_ = os.RemoveAll(dir)
			return err
		}
		filesDir = dir
		cleanup = func() { _ = os.RemoveAll(dir) }
	}
	if cleanup != nil {
		defer cleanup()
	}

	envExtra := make(map[string]string)
	for _, m := range spec.Request.Envs {
		val, err := ResolveValue(ctx, r, m.Name)
		if err != nil {
			return err
		}
		envExtra[m.Var] = string(val)
		vault.Burn(val)
	}

	if len(spec.Request.Files) > 0 {
		if err := RenderDir(ctx, r, filesDir, spec.Request.Files); err != nil {
			return err
		}
		envExtra["KIMEN_FILES_DIR"] = filesDir
	}

	if len(spec.EnvPaths) > 0 {
		if filesDir == "" {
			return errors.New("envpath mappings require file projection (no files-dir)")
		}
		for _, m := range spec.EnvPaths {
			envExtra[m.Var] = filepath.Join(filesDir, filepath.FromSlash(m.RelPath))
		}
	}

	cmd := exec.CommandContext(ctx, spec.Command[0], spec.Command[1:]...)

	stdin := spec.Stdin
	if strings.TrimSpace(spec.Request.Stdin) != "" {
		val, err := ResolveValue(ctx, r, spec.Request.Stdin)
		if err != nil {
			return err
		}
		defer vault.Burn(val)
		stdin = bytes.NewReader(val)
	}

	cmd.Stdin = stdin
	cmd.Stdout = spec.Stdout
	cmd.Stderr = spec.Stderr

	env := os.Environ()
	if runtime.GOOS == "windows" {
		env = normalizeWindowsEnv(env)
	}
	cmd.Env = applyEnvOverrides(env, envExtra, runtime.GOOS == "windows")

	if spec.BeforeExec != nil {
		if err := spec.BeforeExec(); err != nil {
			return err
		}
	}

	err := cmd.Run()
	if err == nil {
		return nil
	}
	var ee *exec.ExitError
	if errors.As(err, &ee) {
		return exitcode.New(ee.ExitCode(), err)
	}
	return err
}

func normalizeWindowsEnv(env []string) []string {
	// On Windows, env var keys are case-insensitive; prefer last-wins behavior.
	m := make(map[string]string)
	for _, kv := range env {
		k, _, ok := strings.Cut(kv, "=")
		if !ok {
			continue
		}
		m[strings.ToUpper(k)] = kv
	}
	out := make([]string, 0, len(m))
	for _, kv := range m {
		out = append(out, kv)
	}
	return out
}

func applyEnvOverrides(env []string, overrides map[string]string, windows bool) []string {
	if len(overrides) == 0 {
		return env
	}

	keySet := make(map[string]struct{}, len(overrides))
	if windows {
		for k := range overrides {
			keySet[strings.ToUpper(k)] = struct{}{}
		}
	} else {
		for k := range overrides {
			keySet[k] = struct{}{}
		}
	}

	keep := env[:0]
	for _, kv := range env {
		k, _, ok := strings.Cut(kv, "=")
		if !ok {
			continue
		}
		if windows {
			k = strings.ToUpper(k)
		}
		if _, exists := keySet[k]; exists {
			continue
		}
		keep = append(keep, kv)
	}

	if windows {
		for k, v := range overrides {
			keep = append(keep, strings.ToUpper(k)+"="+v)
		}
		return keep
	}
	for k, v := range overrides {
		keep = append(keep, k+"="+v)
	}
	return keep
}

func RenderDir(ctx context.Context, r vault.Reader, dir string, files []FileMapping) error {
	_ = ctx
	if err := os.MkdirAll(dir, 0o700); err != nil {
		return err
	}
	if err := os.Chmod(dir, 0o700); err != nil {
		return err
	}
	for _, f := range files {
		if f.RelPath == "" {
			return errors.New("empty relpath")
		}
		full := filepath.Join(dir, filepath.FromSlash(f.RelPath))
		if !strings.HasPrefix(full, filepath.Clean(dir)+string(os.PathSeparator)) && filepath.Clean(full) != filepath.Clean(dir) {
			return fmt.Errorf("refusing to write outside dir: %s", f.RelPath)
		}
		if err := os.MkdirAll(filepath.Dir(full), 0o700); err != nil {
			return err
		}
		val, err := ResolveValue(ctx, r, f.Name)
		if err != nil {
			return err
		}
		if err := writeFile0600(full, val); err != nil {
			vault.Burn(val)
			return err
		}
		vault.Burn(val)
	}
	return nil
}

func writeFile0600(path string, b []byte) error {
	tmp := path + ".tmp"
	if err := os.WriteFile(tmp, b, 0o600); err != nil {
		return err
	}
	if err := os.Chmod(tmp, 0o600); err != nil {
		_ = os.Remove(tmp)
		return err
	}
	return os.Rename(tmp, path)
}
