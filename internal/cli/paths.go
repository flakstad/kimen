package cli

import (
	"errors"
	"os"
	"path/filepath"
)

const envVaultPath = "KIMEN_VAULT"

func defaultVaultPath() (string, error) {
	if p := os.Getenv(envVaultPath); p != "" {
		return p, nil
	}
	cfgDir, err := os.UserConfigDir()
	if err != nil {
		return "", err
	}
	if cfgDir == "" {
		return "", errors.New("no user config dir")
	}
	return filepath.Join(cfgDir, "kimen", "vault.db"), nil
}
