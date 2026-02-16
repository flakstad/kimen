package cli

import (
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"runtime"
	"sort"
	"strings"

	"filippo.io/age"
	"github.com/spf13/cobra"
	"golang.org/x/term"

	"kimen/internal/bundle"
	"kimen/internal/exitcode"
	"kimen/internal/mapfile"
	"kimen/internal/vault"
)

const (
	doctorStatusOK      = "ok"
	doctorStatusWarning = "warning"
	doctorStatusError   = "error"
)

type doctorCheck struct {
	Name    string `json:"name"`
	Status  string `json:"status"`
	Message string `json:"message"`
}

type doctorReport struct {
	OK           bool          `json:"ok"`
	Strict       bool          `json:"strict"`
	ErrorCount   int           `json:"error_count"`
	WarningCount int           `json:"warning_count"`
	Checks       []doctorCheck `json:"checks"`
}

func newDoctorCommand() *cobra.Command {
	var mapPath string
	var profile string
	var bundleIn string
	var identityFile string
	var strict bool
	var jsonOut bool
	var allowMissingVault bool

	cmd := &cobra.Command{
		Use:   "doctor",
		Short: "Run local preflight checks for vault/config/maps",
		RunE: func(cmd *cobra.Command, args []string) error {
			report := runDoctorChecks(mapPath, profile, bundleIn, identityFile, allowMissingVault)
			report.Strict = strict
			report.OK = report.ErrorCount == 0 && (!strict || report.WarningCount == 0)
			return emitDoctorReport(cmd, report, jsonOut)
		},
	}

	cmd.Flags().StringVar(&mapPath, "map", "", "map file to validate")
	cmd.Flags().StringVar(&profile, "profile", "", "profile name to resolve and validate")
	cmd.Flags().StringVar(&bundleIn, "bundle-in", "", "bundle file to validate for CI open flows")
	cmd.Flags().StringVar(&identityFile, "identity", "", "age identity file used to validate bundle decryptability")
	cmd.Flags().BoolVar(&strict, "strict", false, "treat warnings as failures")
	cmd.Flags().BoolVar(&jsonOut, "json", false, "output JSON")
	cmd.Flags().BoolVar(&allowMissingVault, "allow-missing-vault", false, "downgrade missing vault to warning")
	return cmd
}

func runDoctorChecks(mapPath, profile, bundleIn, identityFile string, allowMissingVault bool) doctorReport {
	report := doctorReport{Checks: make([]doctorCheck, 0, 16)}
	add := func(name, status, message string) {
		report.Checks = append(report.Checks, doctorCheck{Name: name, Status: status, Message: message})
		switch status {
		case doctorStatusError:
			report.ErrorCount++
		case doctorStatusWarning:
			report.WarningCount++
		}
	}

	if strings.TrimSpace(mapPath) != "" && strings.TrimSpace(profile) != "" {
		add("mapping_input", doctorStatusError, "use only one of --map or --profile")
	}

	cfgPath, err := defaultConfigPath()
	if err != nil {
		add("config_path", doctorStatusError, err.Error())
	} else {
		add("config_path", doctorStatusOK, cfgPath)
	}

	cfg, configExists, cfgErr := loadConfig()
	if cfgErr != nil {
		add("config_json", doctorStatusError, cfgErr.Error())
	} else if configExists {
		add("config_json", doctorStatusOK, "config file parsed")
	} else {
		add("config_json", doctorStatusOK, "config file not found (defaults apply)")
	}

	checkDoctorPassphraseSource(add, cfg, configExists, cfgErr)
	checkDoctorVault(add, allowMissingVault)
	checkDoctorMapProfile(add, mapPath, profile)
	checkDoctorBundle(add, bundleIn, identityFile)
	checkDoctorRemotes(add, cfg, cfgErr)

	return report
}

func checkDoctorPassphraseSource(add func(name, status, message string), cfg config, configExists bool, cfgErr error) {
	if strings.TrimSpace(os.Getenv(envPassphrase)) != "" {
		add("passphrase_source", doctorStatusOK, "using KIMEN_PASSPHRASE")
		return
	}

	if cfgErr != nil {
		add("passphrase_source", doctorStatusError, "cannot determine passphrase source because config is invalid")
		return
	}

	// No config file means implicit prompt behavior.
	if !configExists || cfg.Unlock == nil {
		if term.IsTerminal(int(os.Stdin.Fd())) {
			add("passphrase_source", doctorStatusWarning, "no non-interactive passphrase source configured (TTY prompt fallback)")
		} else {
			add("passphrase_source", doctorStatusError, "no passphrase source configured for non-interactive usage")
		}
		return
	}

	method := strings.ToLower(strings.TrimSpace(cfg.Unlock.Method))
	switch method {
	case "", "prompt":
		if term.IsTerminal(int(os.Stdin.Fd())) {
			add("passphrase_source", doctorStatusWarning, "unlock.method=prompt requires interactive terminal")
		} else {
			add("passphrase_source", doctorStatusError, "unlock.method=prompt is not usable in non-interactive contexts")
		}
	case "env":
		if strings.TrimSpace(os.Getenv(envPassphrase)) == "" {
			add("passphrase_source", doctorStatusError, "unlock.method=env but KIMEN_PASSPHRASE is not set")
			return
		}
		add("passphrase_source", doctorStatusOK, "unlock.method=env with KIMEN_PASSPHRASE set")
	case "stdin":
		add("passphrase_source", doctorStatusWarning, "unlock.method=stdin cannot be pre-validated in doctor")
	case "exec":
		if len(cfg.Unlock.Exec) == 0 {
			add("passphrase_source", doctorStatusError, "unlock.method=exec but unlock.exec is empty")
			return
		}
		add("passphrase_source", doctorStatusOK, fmt.Sprintf("unlock.method=exec (%s)", strings.Join(cfg.Unlock.Exec, " ")))
	default:
		add("passphrase_source", doctorStatusError, fmt.Sprintf("unknown unlock.method %q", cfg.Unlock.Method))
	}
}

func checkDoctorVault(add func(name, status, message string), allowMissingVault bool) {
	vaultPath, err := defaultVaultPath()
	if err != nil {
		add("vault_path", doctorStatusError, err.Error())
		return
	}
	add("vault_path", doctorStatusOK, vaultPath)

	info, err := os.Stat(vaultPath)
	if err != nil {
		if errors.Is(err, os.ErrNotExist) && allowMissingVault {
			add("vault_file", doctorStatusOK, "vault file not found (allowed by --allow-missing-vault)")
			return
		}
		if errors.Is(err, os.ErrNotExist) {
			add("vault_file", doctorStatusError, "vault file not found")
			return
		}
		add("vault_file", doctorStatusError, err.Error())
		return
	}
	if !info.Mode().IsRegular() {
		add("vault_file", doctorStatusError, "vault path is not a regular file")
		return
	}

	meta, err := vault.ReadMetadata(vaultPath)
	if err != nil {
		add("vault_metadata", doctorStatusError, err.Error())
		return
	}
	add("vault_metadata", doctorStatusOK, fmt.Sprintf("format=%s kdf=%s", meta.FormatVersion, meta.KDF))

	if runtime.GOOS != "windows" {
		if info.Mode().Perm()&0o077 != 0 {
			add("vault_permissions", doctorStatusWarning, fmt.Sprintf("vault permissions are %04o (expected 0600)", info.Mode().Perm()))
			return
		}
	}
	add("vault_permissions", doctorStatusOK, fmt.Sprintf("%04o", info.Mode().Perm()))
}

func checkDoctorMapProfile(add func(name, status, message string), mapPath, profile string) {
	if strings.TrimSpace(mapPath) == "" && strings.TrimSpace(profile) == "" {
		add("mapping_spec", doctorStatusOK, "no --map/--profile provided; skipping mapping checks")
		return
	}

	resolvedPath := strings.TrimSpace(mapPath)
	if strings.TrimSpace(profile) != "" {
		p, err := mapfile.ResolveProfile(profile)
		if err != nil {
			add("mapping_spec", doctorStatusError, err.Error())
			return
		}
		resolvedPath = p
		add("mapping_profile", doctorStatusOK, fmt.Sprintf("%s -> %s", profile, resolvedPath))
	}

	m, err := mapfile.ParseFile(resolvedPath)
	if err != nil {
		add("mapping_parse", doctorStatusError, err.Error())
		return
	}
	add("mapping_parse", doctorStatusOK, resolvedPath)

	issues := lintMap(m)
	if strings.TrimSpace(profile) != "" {
		issues = append(issues, lintProfileResolutionWarnings(profile, resolvedPath)...)
	}
	sortMapLintIssues(issues)
	errorCount, warningCount := countIssuesBySeverity(issues)
	switch {
	case errorCount > 0:
		add("mapping_lint", doctorStatusError, summarizeLintIssues(errorCount, warningCount, issues))
	case warningCount > 0:
		add("mapping_lint", doctorStatusWarning, summarizeLintIssues(errorCount, warningCount, issues))
	default:
		add("mapping_lint", doctorStatusOK, "no lint issues")
	}
}

func checkDoctorBundle(add func(name, status, message string), bundleIn, identityFile string) {
	bundleIn = strings.TrimSpace(bundleIn)
	identityFile = strings.TrimSpace(identityFile)

	if bundleIn == "" && identityFile == "" {
		add("bundle_spec", doctorStatusOK, "no --bundle-in/--identity provided; skipping bundle checks")
		return
	}

	bundleExists := false
	if bundleIn != "" {
		info, err := os.Stat(bundleIn)
		if err != nil {
			if errors.Is(err, os.ErrNotExist) {
				add("bundle_in", doctorStatusError, fmt.Sprintf("bundle file not found: %s", bundleIn))
			} else {
				add("bundle_in", doctorStatusError, err.Error())
			}
		} else if !info.Mode().IsRegular() {
			add("bundle_in", doctorStatusError, fmt.Sprintf("bundle path is not a regular file: %s", bundleIn))
		} else {
			add("bundle_in", doctorStatusOK, bundleIn)
			bundleExists = true
		}
	}

	var id age.Identity
	identityValid := false
	if identityFile != "" {
		parsed, err := bundle.LoadIdentity(identityFile, false, nil)
		if err != nil {
			add("bundle_identity", doctorStatusError, err.Error())
		} else {
			id = parsed
			recipient, err := bundle.RecipientForIdentity(parsed)
			if err != nil {
				add("bundle_identity", doctorStatusError, err.Error())
			} else {
				add("bundle_identity", doctorStatusOK, fmt.Sprintf("%s (%s)", identityFile, recipient))
				identityValid = true
			}
		}
	}

	if bundleExists && !identityValid {
		add("bundle_decrypt", doctorStatusWarning, "bundle file present but no valid --identity provided; decryptability not verified")
		return
	}
	if !bundleExists && identityValid {
		add("bundle_decrypt", doctorStatusWarning, "identity validated but no --bundle-in provided")
		return
	}
	if !bundleExists || !identityValid {
		return
	}

	if err := bundle.ValidateBundleWithIdentity(bundleIn, id); err != nil {
		add("bundle_decrypt", doctorStatusError, err.Error())
		return
	}
	add("bundle_decrypt", doctorStatusOK, "bundle can be decrypted with identity")
}

func checkDoctorRemotes(add func(name, status, message string), cfg config, cfgErr error) {
	if cfgErr != nil {
		add("remote_config", doctorStatusError, "cannot validate remotes because config is invalid")
		return
	}
	if len(cfg.Remotes) == 0 {
		add("remote_config", doctorStatusOK, "no remotes configured; skipping remote checks")
		return
	}

	remotes := append([]remoteConfig(nil), cfg.Remotes...)
	sort.Slice(remotes, func(i, j int) bool { return remotes[i].Name < remotes[j].Name })
	for i := range remotes {
		r := remotes[i]
		applyRemoteDefaults(&r)
		checkDoctorRemote(add, r)
	}
}

func checkDoctorRemote(add func(name, status, message string), r remoteConfig) {
	base := fmt.Sprintf("remote_%s", r.Name)
	if err := validateRemoteConfig(r); err != nil {
		add(base+"_config", doctorStatusError, err.Error())
		return
	}
	add(base+"_config", doctorStatusOK, fmt.Sprintf("type=%s path=%s", remoteType(r), strings.TrimSpace(r.Path)))

	if strings.TrimSpace(r.Recipient) == "" {
		add(base+"_push", doctorStatusWarning, "recipient not configured; sync push will fail")
	} else {
		add(base+"_push", doctorStatusOK, "recipient configured")
	}
	if strings.TrimSpace(r.Identity) == "" {
		add(base+"_pull", doctorStatusWarning, "identity not configured; sync pull will fail")
	} else {
		if _, err := bundle.LoadIdentity(strings.TrimSpace(r.Identity), false, nil); err != nil {
			add(base+"_pull", doctorStatusError, err.Error())
		} else {
			add(base+"_pull", doctorStatusOK, "identity configured")
		}
	}

	switch remoteType(r) {
	case "fs":
		checkDoctorFSRemote(add, base, r)
	case "git":
		checkDoctorGitRemote(add, base, r)
	default:
		add(base+"_transport", doctorStatusError, fmt.Sprintf("unsupported remote type %q", r.Type))
	}
}

func checkDoctorFSRemote(add func(name, status, message string), base string, r remoteConfig) {
	bundlePath, err := remoteBundlePath(r)
	if err != nil {
		add(base+"_transport", doctorStatusError, err.Error())
		return
	}
	add(base+"_transport", doctorStatusOK, fmt.Sprintf("bundle path=%s", bundlePath))

	dir := filepath.Dir(bundlePath)
	info, err := os.Stat(dir)
	if err != nil {
		if errors.Is(err, os.ErrNotExist) {
			add(base+"_fs_dir", doctorStatusWarning, fmt.Sprintf("bundle directory does not exist yet: %s", dir))
			return
		}
		add(base+"_fs_dir", doctorStatusError, err.Error())
		return
	}
	if !info.IsDir() {
		add(base+"_fs_dir", doctorStatusError, fmt.Sprintf("bundle directory path is not a directory: %s", dir))
		return
	}
	add(base+"_fs_dir", doctorStatusOK, dir)
}

func checkDoctorGitRemote(add func(name, status, message string), base string, r remoteConfig) {
	_, branch, relPath, err := gitRemoteRef(r)
	if err != nil {
		add(base+"_transport", doctorStatusError, err.Error())
		return
	}
	add(base+"_transport", doctorStatusOK, fmt.Sprintf("branch=%s bundle_path=%s", branch, relPath))
	out, err := gitRun("", "ls-remote", "--heads", strings.TrimSpace(r.Path), branch)
	if err != nil {
		add(base+"_git_remote", doctorStatusError, err.Error())
		return
	}
	if strings.TrimSpace(out) == "" {
		add(base+"_git_branch", doctorStatusWarning, fmt.Sprintf("branch %q not found on remote (may be created on first push)", branch))
		return
	}
	add(base+"_git_branch", doctorStatusOK, fmt.Sprintf("branch %q found on remote", branch))
}

func summarizeLintIssues(errorCount, warningCount int, issues []mapLintIssue) string {
	if len(issues) == 0 {
		return "no lint issues"
	}
	limit := 3
	if len(issues) < limit {
		limit = len(issues)
	}
	parts := make([]string, 0, limit)
	for i := 0; i < limit; i++ {
		parts = append(parts, issues[i].Code)
	}
	extra := ""
	if len(issues) > limit {
		extra = fmt.Sprintf(", +%d more", len(issues)-limit)
	}
	return fmt.Sprintf("errors=%d warnings=%d (%s%s)", errorCount, warningCount, strings.Join(parts, ", "), extra)
}

func emitDoctorReport(cmd *cobra.Command, report doctorReport, jsonOut bool) error {
	if jsonOut {
		if err := json.NewEncoder(cmd.OutOrStdout()).Encode(report); err != nil {
			return exitcode.New(exitcode.CodeDoctorFailed, err)
		}
	} else {
		printDoctorHuman(cmd, report)
	}

	if !report.OK {
		if report.Strict && report.ErrorCount == 0 && report.WarningCount > 0 {
			return exitcode.New(exitcode.CodeDoctorFailed, errors.New("doctor found warnings and --strict is enabled"))
		}
		return exitcode.New(exitcode.CodeDoctorFailed, errors.New("doctor checks failed"))
	}
	return nil
}

func printDoctorHuman(cmd *cobra.Command, report doctorReport) {
	w := cmd.OutOrStdout()
	if report.OK {
		fmt.Fprintf(w, "doctor: ok (errors=%d warnings=%d)\n", report.ErrorCount, report.WarningCount)
	} else {
		fmt.Fprintf(w, "doctor: failed (errors=%d warnings=%d)\n", report.ErrorCount, report.WarningCount)
		if report.Strict && report.ErrorCount == 0 && report.WarningCount > 0 {
			fmt.Fprintln(w, "strict mode: warnings are treated as failures")
		}
	}
	for _, c := range report.Checks {
		fmt.Fprintf(w, "- [%s] %s: %s\n", c.Status, c.Name, c.Message)
	}
}
