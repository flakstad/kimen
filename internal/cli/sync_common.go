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

func resolveRemote(c config, name string) (remoteConfig, error) {
	if strings.TrimSpace(name) != "" {
		for _, r := range c.Remotes {
			if r.Name == name {
				return r, nil
			}
		}
		return remoteConfig{}, fmt.Errorf("remote %q not found", name)
	}
	if len(c.Remotes) == 0 {
		return remoteConfig{}, errors.New("no remotes configured")
	}
	if len(c.Remotes) > 1 {
		return remoteConfig{}, errors.New("multiple remotes configured; use --remote")
	}
	return c.Remotes[0], nil
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
			Reason:      "remote_disappeared",
			Message:     fmt.Sprintf("remote bundle disappeared since last sync (expected rev %s)", lastSeen),
			ExpectedRev: lastSeen,
		}
	case hasRemote && lastSeen == "":
		return syncConflictDetails{
			HasConflict: true,
			Reason:      "no_local_baseline",
			Message:     fmt.Sprintf("remote has data (rev %s) but no local baseline; run `kimen sync pull` first", remoteRev),
			ActualRev:   remoteRev,
		}
	case hasRemote && lastSeen != remoteRev:
		return syncConflictDetails{
			HasConflict: true,
			Reason:      "remote_changed",
			Message:     fmt.Sprintf("remote changed (expected rev %s, found %s); run `kimen sync pull`, re-apply changes, then push", lastSeen, remoteRev),
			ExpectedRev: lastSeen,
			ActualRev:   remoteRev,
		}
	default:
		return syncConflictDetails{}
	}
}

func remoteBundlePath(r remoteConfig) (string, error) {
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
	bundlePath, err := remoteBundlePath(r)
	if err != nil {
		return "", false, "", err
	}
	rev, ok, err := fileRevision(bundlePath)
	if err != nil {
		return "", false, bundlePath, err
	}
	return rev, ok, bundlePath, nil
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
