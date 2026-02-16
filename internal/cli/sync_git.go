package cli

import (
	"errors"
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"time"

	"kimen/internal/bundle"
)

var errGitPushRejected = errors.New("git push rejected")

type gitCommandError struct {
	Args   []string
	Output string
	Err    error
}

func (e *gitCommandError) Error() string {
	msg := strings.TrimSpace(e.Output)
	if msg == "" {
		msg = "git command failed"
	}
	return fmt.Sprintf("git %s: %s", strings.Join(e.Args, " "), msg)
}

func (e *gitCommandError) Unwrap() error {
	return e.Err
}

func gitRemoteRevision(r remoteConfig) (string, bool, string, error) {
	ref, branch, relPath, err := gitRemoteRef(r)
	if err != nil {
		return "", false, "", err
	}
	repoDir, cleanup, err := gitCloneNoCheckout(strings.TrimSpace(r.Path))
	if err != nil {
		return "", false, ref, err
	}
	defer cleanup()

	hasBranch, err := gitCheckoutRemoteBranch(repoDir, branch)
	if err != nil {
		return "", false, ref, err
	}
	if !hasBranch {
		return "", false, ref, nil
	}

	bundlePath := filepath.Join(repoDir, relPath)
	rev, ok, err := fileRevision(bundlePath)
	if err != nil {
		return "", false, ref, err
	}
	if !ok {
		return "", false, ref, nil
	}
	return rev, true, ref, nil
}

func materializeRemoteBundleForRead(r remoteConfig) (string, func(), error) {
	switch remoteType(r) {
	case "fs":
		bundlePath, err := remoteBundlePath(r)
		if err != nil {
			return "", nil, err
		}
		return bundlePath, func() {}, nil
	case "git":
		_, branch, relPath, err := gitRemoteRef(r)
		if err != nil {
			return "", nil, err
		}
		repoDir, cleanup, err := gitCloneNoCheckout(strings.TrimSpace(r.Path))
		if err != nil {
			return "", nil, err
		}
		hasBranch, err := gitCheckoutRemoteBranch(repoDir, branch)
		if err != nil {
			cleanup()
			return "", nil, err
		}
		if !hasBranch {
			cleanup()
			return "", nil, errors.New("remote bundle is missing")
		}
		bundlePath := filepath.Join(repoDir, relPath)
		if _, err := os.Stat(bundlePath); err != nil {
			cleanup()
			if errors.Is(err, os.ErrNotExist) {
				return "", nil, errors.New("remote bundle is missing")
			}
			return "", nil, err
		}
		return bundlePath, cleanup, nil
	default:
		return "", nil, fmt.Errorf("unsupported remote type %q", remoteType(r))
	}
}

func sealVaultToGitRemote(vaultPath, recipient string, remote remoteConfig) (string, string, error) {
	ref, branch, relPath, err := gitRemoteRef(remote)
	if err != nil {
		return "", "", err
	}

	tmpDir, err := os.MkdirTemp("", "kimen-sync-git-seal-*")
	if err != nil {
		return "", "", err
	}
	defer os.RemoveAll(tmpDir)

	tmpBundle := filepath.Join(tmpDir, "vault.age")
	if err := bundle.SealVaultFile(vaultPath, tmpBundle, []string{recipient}); err != nil {
		return "", "", err
	}
	if err := os.Chmod(tmpBundle, 0o600); err != nil {
		return "", "", err
	}

	newRev, err := gitPushBundleFile(strings.TrimSpace(remote.Path), branch, relPath, tmpBundle)
	if err != nil {
		return "", ref, err
	}
	return newRev, ref, nil
}

func gitRemoteRef(r remoteConfig) (string, string, string, error) {
	repoPath := strings.TrimSpace(r.Path)
	if repoPath == "" {
		return "", "", "", errors.New("remote path is empty")
	}
	branch := strings.TrimSpace(r.Branch)
	if branch == "" {
		branch = "main"
	}
	relPath := strings.TrimSpace(r.BundlePath)
	if relPath == "" {
		relPath = "vault.age"
	}
	relPath = filepath.Clean(relPath)
	if relPath == "." || relPath == "" || relPath == ".." {
		return "", "", "", errors.New("remote bundle_path is invalid")
	}
	if filepath.IsAbs(relPath) || strings.HasPrefix(relPath, ".."+string(filepath.Separator)) {
		return "", "", "", errors.New("remote bundle_path must be relative")
	}
	return fmt.Sprintf("%s@%s:%s", repoPath, branch, relPath), branch, relPath, nil
}

func gitPushBundleFile(repoPath, branch, relPath, sourceBundlePath string) (string, error) {
	repoDir, cleanup, err := gitCloneNoCheckout(repoPath)
	if err != nil {
		return "", err
	}
	defer cleanup()

	hasBranch, err := gitCheckoutRemoteBranch(repoDir, branch)
	if err != nil {
		return "", err
	}
	if !hasBranch {
		if _, err := gitRun(repoDir, "checkout", "--quiet", "--orphan", branch); err != nil {
			return "", err
		}
	}

	targetPath := filepath.Join(repoDir, relPath)
	if err := os.MkdirAll(filepath.Dir(targetPath), 0o700); err != nil {
		return "", err
	}
	if err := copyFileAtomic(sourceBundlePath, targetPath, 0o600); err != nil {
		return "", err
	}

	if _, err := gitRun(repoDir, "add", "--", relPath); err != nil {
		return "", err
	}
	changed, err := gitHasStagedChanges(repoDir)
	if err != nil {
		return "", err
	}
	if !changed {
		rev, _, err := fileRevision(targetPath)
		return rev, err
	}

	if _, err := gitRun(repoDir, "config", "user.name", "kimen"); err != nil {
		return "", err
	}
	if _, err := gitRun(repoDir, "config", "user.email", "kimen@localhost"); err != nil {
		return "", err
	}
	if _, err := gitRun(repoDir, "commit", "--quiet", "-m", fmt.Sprintf("kimen sync push %s", time.Now().UTC().Format(time.RFC3339))); err != nil {
		return "", err
	}
	if _, err := gitRun(repoDir, "push", "--quiet", "origin", fmt.Sprintf("HEAD:refs/heads/%s", branch)); err != nil {
		if isGitPushRejected(err) {
			return "", fmt.Errorf("%w: %s", errGitPushRejected, err.Error())
		}
		return "", err
	}
	rev, _, err := fileRevision(targetPath)
	return rev, err
}

func gitCloneNoCheckout(repoPath string) (string, func(), error) {
	repoDir, err := os.MkdirTemp("", "kimen-sync-git-*")
	if err != nil {
		return "", nil, err
	}
	cleanup := func() { _ = os.RemoveAll(repoDir) }
	if _, err := gitRun("", "clone", "--quiet", "--no-checkout", repoPath, repoDir); err != nil {
		cleanup()
		return "", nil, err
	}
	return repoDir, cleanup, nil
}

func gitCheckoutRemoteBranch(repoDir, branch string) (bool, error) {
	hasBranch, err := gitRefExists(repoDir, "refs/remotes/origin/"+branch)
	if err != nil {
		return false, err
	}
	if !hasBranch {
		return false, nil
	}
	if _, err := gitRun(repoDir, "checkout", "--quiet", "-B", branch, "refs/remotes/origin/"+branch); err != nil {
		return false, err
	}
	return true, nil
}

func gitRefExists(repoDir, ref string) (bool, error) {
	cmd := exec.Command("git", "rev-parse", "--verify", "--quiet", ref)
	cmd.Dir = repoDir
	cmd.Env = sanitizedGitEnv()
	if err := cmd.Run(); err != nil {
		if gitExitCode(err) == 1 {
			return false, nil
		}
		return false, err
	}
	return true, nil
}

func gitHasStagedChanges(repoDir string) (bool, error) {
	cmd := exec.Command("git", "diff", "--cached", "--quiet", "--exit-code")
	cmd.Dir = repoDir
	cmd.Env = sanitizedGitEnv()
	if err := cmd.Run(); err != nil {
		if gitExitCode(err) == 1 {
			return true, nil
		}
		return false, err
	}
	return false, nil
}

func gitRun(dir string, args ...string) (string, error) {
	cmd := exec.Command("git", args...)
	if strings.TrimSpace(dir) != "" {
		cmd.Dir = dir
	}
	cmd.Env = sanitizedGitEnv()
	out, err := cmd.CombinedOutput()
	if err != nil {
		return "", &gitCommandError{
			Args:   append([]string(nil), args...),
			Output: string(out),
			Err:    err,
		}
	}
	return string(out), nil
}

func gitExitCode(err error) int {
	var exitErr *exec.ExitError
	if errors.As(err, &exitErr) {
		return exitErr.ExitCode()
	}
	var runErr *gitCommandError
	if errors.As(err, &runErr) {
		return gitExitCode(runErr.Err)
	}
	return -1
}

func isGitPushRejected(err error) bool {
	var runErr *gitCommandError
	if !errors.As(err, &runErr) {
		return false
	}
	out := strings.ToLower(runErr.Output)
	return strings.Contains(out, "non-fast-forward") ||
		strings.Contains(out, "[rejected]") ||
		strings.Contains(out, "fetch first")
}

func sanitizedGitEnv() []string {
	env := os.Environ()
	skip := map[string]struct{}{
		"GIT_DIR":                          {},
		"GIT_WORK_TREE":                    {},
		"GIT_INDEX_FILE":                   {},
		"GIT_PREFIX":                       {},
		"GIT_OBJECT_DIRECTORY":             {},
		"GIT_ALTERNATE_OBJECT_DIRECTORIES": {},
		"GIT_COMMON_DIR":                   {},
	}
	out := make([]string, 0, len(env))
	for _, kv := range env {
		k, _, ok := strings.Cut(kv, "=")
		if !ok {
			continue
		}
		if _, blocked := skip[k]; blocked {
			continue
		}
		out = append(out, kv)
	}
	return out
}
