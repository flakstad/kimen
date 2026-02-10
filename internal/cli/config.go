package cli

import (
	"encoding/json"
	"errors"
	"fmt"
	"io/fs"
	"os"
	"path/filepath"
)

const envConfigPath = "KIMEN_CONFIG"

type config struct {
	Unlock *unlockConfig `json:"unlock,omitempty"`
}

type unlockConfig struct {
	// Method chooses the default passphrase source when no explicit CLI flag is
	// provided. Supported values:
	// - "prompt" (default)
	// - "env"
	// - "stdin"
	// - "exec"
	Method string `json:"method,omitempty"`

	// Exec is the command+args to execute when method is "exec". It must print
	// the passphrase as a single line to stdout.
	Exec []string `json:"exec,omitempty"`
}

func defaultConfigPath() (string, error) {
	if p := os.Getenv(envConfigPath); p != "" {
		return p, nil
	}
	cfgDir, err := os.UserConfigDir()
	if err != nil {
		return "", err
	}
	if cfgDir == "" {
		return "", errors.New("no user config dir")
	}
	return filepath.Join(cfgDir, "kimen", "config.json"), nil
}

func loadConfig() (config, bool, error) {
	p, err := defaultConfigPath()
	if err != nil {
		return config{}, false, err
	}
	b, err := os.ReadFile(p)
	if err != nil {
		if errors.Is(err, fs.ErrNotExist) {
			return config{}, false, nil
		}
		return config{}, false, err
	}
	var c config
	if err := json.Unmarshal(b, &c); err != nil {
		return config{}, true, fmt.Errorf("invalid config JSON at %s: %w", p, err)
	}
	return c, true, nil
}

func saveConfig(c config) (string, error) {
	p, err := defaultConfigPath()
	if err != nil {
		return "", err
	}
	dir := filepath.Dir(p)
	if err := os.MkdirAll(dir, 0o700); err != nil {
		return "", err
	}
	if err := os.Chmod(dir, 0o700); err != nil {
		return "", err
	}
	b, err := json.MarshalIndent(c, "", "  ")
	if err != nil {
		return "", err
	}
	b = append(b, '\n')

	tmp, err := os.CreateTemp(dir, "config.*.tmp")
	if err != nil {
		return "", err
	}
	tmpName := tmp.Name()
	defer func() { _ = os.Remove(tmpName) }()

	if err := tmp.Chmod(0o600); err != nil {
		_ = tmp.Close()
		return "", err
	}
	if _, err := tmp.Write(b); err != nil {
		_ = tmp.Close()
		return "", err
	}
	if err := tmp.Close(); err != nil {
		return "", err
	}

	if err := renameOver(tmpName, p); err != nil {
		return "", err
	}
	return p, nil
}

func renameOver(from, to string) error {
	if err := os.Rename(from, to); err == nil {
		return nil
	}
	// Best-effort fallback for platforms where Rename won't replace.
	_ = os.Remove(to)
	return os.Rename(from, to)
}
