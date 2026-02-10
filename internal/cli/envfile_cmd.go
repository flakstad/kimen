package cli

import (
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"regexp"
	"sort"
	"strings"

	"github.com/spf13/cobra"

	"kimen/internal/projection"
	"kimen/internal/vault"
)

var simpleEnvValueRE = regexp.MustCompile(`^[A-Za-z0-9_./:@+\-]*$`)

func newEnvfileCommand() *cobra.Command {
	var vaultPath string
	var passphraseCmd string
	var passphraseStdin bool

	var mapPath string
	var profile string
	var envMappings []string
	var fileMappings []string
	var envPathMappings []string

	var outPath string
	var filesDir string

	cmd := &cobra.Command{
		Use:   "envfile --out <path>",
		Short: "Write a KEY=VALUE envfile from secret mappings (no stdout secrets)",
		RunE: func(cmd *cobra.Command, args []string) error {
			if strings.TrimSpace(outPath) == "" {
				return errors.New("--out is required")
			}

			if vaultPath == "" {
				p, err := defaultVaultPath()
				if err != nil {
					return err
				}
				vaultPath = p
			}

			pp, err := resolvePassphrase(passphraseCmd, passphraseStdin)
			if err != nil {
				return err
			}
			defer vault.Burn(pp)

			v, err := vault.Open(vaultPath, pp)
			if err != nil {
				return err
			}
			defer v.Close()

			req, envPaths, err := resolveRunMappings(mapPath, profile, envMappings, fileMappings, envPathMappings, "")
			if err != nil {
				return err
			}
			if strings.TrimSpace(req.Stdin) != "" {
				return errors.New("stdin projection is only supported for `kimen run`")
			}
			if err := validateEnvPaths(req, envPaths); err != nil {
				return err
			}
			if len(req.Envs) == 0 && len(envPaths) == 0 {
				return errors.New("no env mappings provided (use --env/--map/--profile)")
			}
			if len(envPaths) > 0 && strings.TrimSpace(filesDir) == "" {
				return errors.New("--files-dir is required when using envpath mappings")
			}

			lines, err := buildEnvfileLines(cmd, v, req, envPaths, filesDir)
			if err != nil {
				return err
			}
			return writeEnvfileAtomic(outPath, lines)
		},
	}

	cmd.Flags().StringVar(&vaultPath, "vault", "", "vault path (defaults to $KIMEN_VAULT or user config dir)")
	cmd.Flags().BoolVar(&passphraseStdin, "passphrase-stdin", false, "read passphrase from stdin (single line)")
	cmd.Flags().StringVar(&passphraseCmd, "passphrase-cmd", "", "execute command to obtain passphrase (reads one line from stdout)")
	cmd.Flags().StringVar(&mapPath, "map", "", "map file with env/file mappings")
	cmd.Flags().StringVar(&profile, "profile", "", "named profile resolving to a map file")
	cmd.Flags().StringArrayVar(&envMappings, "env", nil, "env mapping VAR=<value> (repeatable; <value> is secret name or exec:<command...>)")
	cmd.Flags().StringArrayVar(&fileMappings, "file", nil, "file mapping relpath=<value> (repeatable; <value> is secret name or exec:<command...>; used for envpath validation)")
	cmd.Flags().StringArrayVar(&envPathMappings, "envpath", nil, "envpath mapping VAR=relpath (repeatable)")
	cmd.Flags().StringVar(&outPath, "out", "", "output envfile path")
	cmd.Flags().StringVar(&filesDir, "files-dir", "", "base directory used to resolve envpath values")
	return cmd
}

func buildEnvfileLines(cmd *cobra.Command, v *vault.Vault, req projection.Request, envPaths []projection.EnvPathMapping, filesDir string) ([]string, error) {
	envOut := make(map[string]string)

	for _, m := range req.Envs {
		val, err := projection.ResolveValue(cmd.Context(), v, m.Name)
		if err != nil {
			return nil, err
		}
		envOut[m.Var] = string(val)
		vault.Burn(val)
	}

	for _, ep := range envPaths {
		envOut[ep.Var] = filepath.Join(filesDir, filepath.FromSlash(ep.RelPath))
	}

	keys := make([]string, 0, len(envOut))
	for k := range envOut {
		keys = append(keys, k)
	}
	sort.Strings(keys)

	lines := make([]string, 0, len(keys))
	for _, k := range keys {
		v := envOut[k]
		encoded, err := encodeEnvfileValue(v)
		if err != nil {
			return nil, fmt.Errorf("%s: %w", k, err)
		}
		lines = append(lines, fmt.Sprintf("%s=%s", k, encoded))
	}
	return lines, nil
}

func encodeEnvfileValue(v string) (string, error) {
	if strings.Contains(v, "\x00") {
		return "", errors.New("value contains NUL")
	}
	if strings.Contains(v, "\n") || strings.Contains(v, "\r") {
		return "", errors.New("value contains newline")
	}
	if v == "" {
		return `""`, nil
	}
	if simpleEnvValueRE.MatchString(v) {
		return v, nil
	}
	escaped := strings.ReplaceAll(v, `\`, `\\`)
	escaped = strings.ReplaceAll(escaped, `"`, `\"`)
	return `"` + escaped + `"`, nil
}

func writeEnvfileAtomic(path string, lines []string) error {
	dir := filepath.Dir(path)
	if err := os.MkdirAll(dir, 0o700); err != nil {
		return err
	}
	if err := os.Chmod(dir, 0o700); err != nil {
		return err
	}
	body := strings.Join(lines, "\n") + "\n"
	tmp := path + ".tmp"
	if err := os.WriteFile(tmp, []byte(body), 0o600); err != nil {
		return err
	}
	if err := os.Chmod(tmp, 0o600); err != nil {
		_ = os.Remove(tmp)
		return err
	}
	return os.Rename(tmp, path)
}
