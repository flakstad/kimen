package cli

import (
	"errors"
	"os"
	"path/filepath"
	"strings"
)

const envVaultPath = "KIMEN_VAULT"

func defaultVaultPath() (string, error) {
	p, _, err := defaultVaultPathWithSource()
	if err != nil {
		return "", err
	}
	return p, nil
}

func defaultVaultPathWithSource() (string, string, error) {
	if p := strings.TrimSpace(os.Getenv(envVaultPath)); p != "" {
		return p, "env", nil
	}

	if p, ok, err := configVaultPath(); err == nil && ok {
		return p, "config", nil
	}

	cfgDir, err := os.UserConfigDir()
	if err != nil {
		return "", "", err
	}
	if cfgDir == "" {
		return "", "", errors.New("no user config dir")
	}
	return filepath.Join(cfgDir, "kimen", "vault.db"), "default", nil
}

func resolveVaultPath(explicitPath string) (string, string, error) {
	if p := strings.TrimSpace(explicitPath); p != "" {
		return p, "flag", nil
	}
	return defaultVaultPathWithSource()
}

func configVaultPath() (string, bool, error) {
	c, exists, err := loadConfig()
	if err != nil {
		return "", false, err
	}
	if !exists || c.Vault == nil {
		return "", false, nil
	}
	if p := strings.TrimSpace(c.Vault.Path); p != "" {
		return p, true, nil
	}
	return "", false, nil
}
