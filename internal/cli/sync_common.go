package cli

import (
	"crypto/sha256"
	"encoding/hex"
	"errors"
	"fmt"
	"io"
	"os"
	"path/filepath"
	"strings"
)

var errSyncConflict = errors.New("sync conflict")
var errRemotePushLockExists = errors.New("remote push lock exists")

const envRemoteName = "KIMEN_REMOTE"

type syncConflictDetails struct {
	HasConflict bool
	Reason      string
	Message     string
	ExpectedRev string
	ActualRev   string
}

type syncConflictError struct {
	Details syncConflictDetails
}

func (e *syncConflictError) Error() string {
	msg := strings.TrimSpace(e.Details.Message)
	if msg == "" {
		msg = "remote state differs from local sync baseline"
	}
	return fmt.Sprintf("sync conflict: %s", msg)
}

func (e *syncConflictError) Unwrap() error {
	return errSyncConflict
}

func syncConflictDetailsFromError(err error) (syncConflictDetails, bool) {
	var conflictErr *syncConflictError
	if errors.As(err, &conflictErr) {
		return conflictErr.Details, true
	}
	return syncConflictDetails{}, false
}

type syncConditionError struct {
	Reason            string
	Message           string
	RecommendedAction string
}

func (e *syncConditionError) Error() string {
	msg := strings.TrimSpace(e.Message)
	if msg == "" {
		msg = "sync precondition failed"
	}
	return msg
}

func syncConditionDetailsFromError(err error) (syncConditionError, bool) {
	var condErr *syncConditionError
	if errors.As(err, &condErr) {
		return *condErr, true
	}
	return syncConditionError{}, false
}

func resolveRemote(c config, name string) (remoteConfig, error) {
	if explicit := strings.TrimSpace(name); explicit != "" {
		for _, r := range c.Remotes {
			if r.Name == explicit {
				return r, nil
			}
		}
		return remoteConfig{}, fmt.Errorf("remote %q not found", explicit)
	}
	if len(c.Remotes) == 0 {
		return remoteConfig{}, errors.New("no remotes configured")
	}
	if len(c.Remotes) == 1 {
		return c.Remotes[0], nil
	}

	if envRemote := strings.TrimSpace(os.Getenv(envRemoteName)); envRemote != "" {
		i := findRemoteIndex(c.Remotes, envRemote)
		if i < 0 {
			return remoteConfig{}, fmt.Errorf("remote %q not found (from %s)", envRemote, envRemoteName)
		}
		return c.Remotes[i], nil
	}

	if inferred, ok := inferRemoteFromSyncState(c); ok {
		return inferred, nil
	}

	if i := findRemoteIndex(c.Remotes, "origin"); i >= 0 {
		return c.Remotes[i], nil
	}

	return remoteConfig{}, errors.New("multiple remotes configured; use --remote or set KIMEN_REMOTE")
}

func inferRemoteFromSyncState(c config) (remoteConfig, bool) {
	if len(c.Sync) == 0 {
		return remoteConfig{}, false
	}
	var selected remoteConfig
	found := false
	for _, r := range c.Remotes {
		state, ok := c.Sync[r.Name]
		if !ok {
			continue
		}
		if strings.TrimSpace(state.LastSeenRev) == "" &&
			strings.TrimSpace(state.LastLocalRev) == "" &&
			len(state.BaselineSecretHashes) == 0 {
			continue
		}
		if found {
			return remoteConfig{}, false
		}
		selected = r
		found = true
	}
	if !found {
		return remoteConfig{}, false
	}
	return selected, true
}

func findRemoteIndex(remotes []remoteConfig, name string) int {
	for i, r := range remotes {
		if r.Name == name {
			return i
		}
	}
	return -1
}

func detectSyncConflict(lastSeen, remoteRev string, hasRemote bool) syncConflictDetails {
	switch {
	case !hasRemote && lastSeen == "":
		return syncConflictDetails{}
	case !hasRemote && lastSeen != "":
		return syncConflictDetails{
			HasConflict: true,
			Reason:      reasonRemoteDisappeared,
			Message:     fmt.Sprintf("remote bundle disappeared since last sync (expected rev %s)", lastSeen),
			ExpectedRev: lastSeen,
		}
	case hasRemote && lastSeen == "":
		return syncConflictDetails{
			HasConflict: true,
			Reason:      reasonNoLocalBaseline,
			Message:     fmt.Sprintf("remote has data (rev %s) but no local baseline; run `kimen sync pull` first", remoteRev),
			ActualRev:   remoteRev,
		}
	case hasRemote && lastSeen != remoteRev:
		return syncConflictDetails{
			HasConflict: true,
			Reason:      reasonRemoteChanged,
			Message:     fmt.Sprintf("remote changed (expected rev %s, found %s); run `kimen sync pull`, re-apply changes, then push", lastSeen, remoteRev),
			ExpectedRev: lastSeen,
			ActualRev:   remoteRev,
		}
	default:
		return syncConflictDetails{}
	}
}

func remoteBundlePath(r remoteConfig) (string, error) {
	if remoteType(r) != "fs" {
		return "", fmt.Errorf("remote type %q does not expose a filesystem bundle path", remoteType(r))
	}
	path := strings.TrimSpace(r.Path)
	if path == "" {
		return "", errors.New("remote path is empty")
	}
	if strings.HasSuffix(strings.ToLower(path), ".age") {
		return path, nil
	}
	return filepath.Join(path, "vault.age"), nil
}

func localVaultRevision(vaultPath string) (string, bool, error) {
	p := strings.TrimSpace(vaultPath)
	if p == "" {
		return "", false, errors.New("empty vault path")
	}
	return fileRevision(p)
}

func remoteRevision(r remoteConfig) (string, bool, string, error) {
	switch remoteType(r) {
	case "fs":
		bundlePath, err := remoteBundlePath(r)
		if err != nil {
			return "", false, "", err
		}
		rev, ok, err := fileRevision(bundlePath)
		if err != nil {
			return "", false, bundlePath, err
		}
		return rev, ok, bundlePath, nil
	case "git":
		return gitRemoteRevision(r)
	default:
		return "", false, "", fmt.Errorf("unsupported remote type %q", remoteType(r))
	}
}

func fileRevision(path string) (string, bool, error) {
	f, err := os.Open(path)
	if err != nil {
		if errors.Is(err, os.ErrNotExist) {
			return "", false, nil
		}
		return "", false, err
	}
	defer f.Close()

	h := sha256.New()
	if _, err := io.Copy(h, f); err != nil {
		return "", false, err
	}
	return hex.EncodeToString(h.Sum(nil)), true, nil
}

func remoteType(r remoteConfig) string {
	t := strings.ToLower(strings.TrimSpace(r.Type))
	if t == "" {
		return "fs"
	}
	return t
}

func remoteSupportsPushLock(r remoteConfig) bool {
	return remoteType(r) == "fs"
}
