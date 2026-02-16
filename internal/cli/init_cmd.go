package cli

import (
	_ "embed"
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"strconv"
	"strings"

	"github.com/spf13/cobra"

	"kimen/internal/exitcode"
)

const (
	defaultCISyncGateWorkflowPath = ".github/workflows/kimen-sync-gate.yml"
	ciSyncGateWorkflowName        = "kimen-sync-gate"
	ciSyncGateTemplateName        = "kimen-sync-gate-template"
)

type initResult struct {
	OK     bool   `json:"ok"`
	Action string `json:"action"`
	Out    string `json:"out"`
}

type initErrorResult struct {
	OK       bool   `json:"ok"`
	Error    string `json:"error"`
	ExitCode int    `json:"exit_code"`
}

type initCISyncGateOptions struct {
	RemoteName       string
	RemoteType       string
	RemotePath       string
	RemoteBranch     string
	RemoteBundlePath string
	LocalBundle      string
	Profile          string
	StaleThreshold   string
}

//go:embed scaffold_templates/kimen-sync-gate.yml.tmpl
var ciSyncGateWorkflowTemplate string

func defaultInitCISyncGateOptions() initCISyncGateOptions {
	return initCISyncGateOptions{
		RemoteName:       "team",
		RemoteType:       "git",
		RemotePath:       "",
		RemoteBranch:     "main",
		RemoteBundlePath: "vault.age",
		LocalBundle:      "vault.age",
		Profile:          "",
		StaleThreshold:   "30m",
	}
}

func newInitCommand() *cobra.Command {
	cmd := &cobra.Command{
		Use:   "init",
		Short: "Scaffold Kimen integration files",
	}
	cmd.AddCommand(newInitCISyncGateCommand())
	return cmd
}

func newInitCISyncGateCommand() *cobra.Command {
	var outPath string
	var force bool
	var jsonOut bool
	opts := defaultInitCISyncGateOptions()

	cmd := &cobra.Command{
		Use:   "ci-sync-gate",
		Short: "Create a strict Team Sync CI gating workflow",
		RunE: func(cmd *cobra.Command, args []string) error {
			opts.RemoteType = strings.ToLower(strings.TrimSpace(opts.RemoteType))
			if opts.RemoteType != "git" && opts.RemoteType != "fs" {
				return initCommandError(cmd, jsonOut, errors.New("--remote-type must be git or fs"))
			}
			outPath = strings.TrimSpace(outPath)
			if outPath == "" {
				return initCommandError(cmd, jsonOut, errors.New("--out cannot be empty"))
			}
			cleanOut := filepath.Clean(outPath)
			content := renderCISyncGateWorkflow(opts, ciSyncGateWorkflowName)
			if err := writeScaffoldFile(cleanOut, content, force); err != nil {
				return initCommandError(cmd, jsonOut, err)
			}
			if jsonOut {
				return json.NewEncoder(cmd.OutOrStdout()).Encode(initResult{
					OK:     true,
					Action: "init_ci_sync_gate",
					Out:    cleanOut,
				})
			}
			fmt.Fprintf(cmd.OutOrStdout(), "ok (%s)\n", cleanOut)
			return nil
		},
	}

	cmd.Flags().StringVar(&outPath, "out", defaultCISyncGateWorkflowPath, "output workflow file path")
	cmd.Flags().BoolVar(&force, "force", false, "overwrite existing output file")
	cmd.Flags().BoolVar(&jsonOut, "json", false, "output JSON")
	cmd.Flags().StringVar(&opts.RemoteName, "remote-name", opts.RemoteName, "default remote name in workflow inputs")
	cmd.Flags().StringVar(&opts.RemoteType, "remote-type", opts.RemoteType, "default remote type in workflow inputs (git|fs)")
	cmd.Flags().StringVar(&opts.RemotePath, "remote-path", opts.RemotePath, "default remote path in workflow inputs")
	cmd.Flags().StringVar(&opts.RemoteBranch, "remote-branch", opts.RemoteBranch, "default git branch in workflow inputs")
	cmd.Flags().StringVar(&opts.RemoteBundlePath, "remote-bundle-path", opts.RemoteBundlePath, "default git bundle path in workflow inputs")
	cmd.Flags().StringVar(&opts.LocalBundle, "local-bundle", opts.LocalBundle, "default local ciphertext bundle path in workflow inputs")
	cmd.Flags().StringVar(&opts.Profile, "profile", opts.Profile, "default profile name in workflow inputs")
	cmd.Flags().StringVar(&opts.StaleThreshold, "stale-threshold", opts.StaleThreshold, "default stale lock threshold in workflow inputs")
	return cmd
}

func writeScaffoldFile(path, content string, force bool) error {
	info, err := os.Stat(path)
	if err == nil {
		if info.IsDir() {
			return fmt.Errorf("output path is a directory: %s", path)
		}
		if !force {
			return fmt.Errorf("output file already exists: %s (use --force to overwrite)", path)
		}
	} else if !errors.Is(err, os.ErrNotExist) {
		return err
	}
	if err := os.MkdirAll(filepath.Dir(path), 0o755); err != nil {
		return err
	}
	return os.WriteFile(path, []byte(content), 0o644)
}

func renderCISyncGateWorkflow(opts initCISyncGateOptions, workflowName string) string {
	replacer := strings.NewReplacer(
		"__WORKFLOW_NAME__", strings.TrimSpace(workflowName),
		"__REMOTE_NAME_DEFAULT__", yamlQuoted(opts.RemoteName),
		"__REMOTE_TYPE_DEFAULT__", yamlQuoted(opts.RemoteType),
		"__REMOTE_PATH_DEFAULT__", yamlQuoted(opts.RemotePath),
		"__REMOTE_BRANCH_DEFAULT__", yamlQuoted(opts.RemoteBranch),
		"__REMOTE_BUNDLE_PATH_DEFAULT__", yamlQuoted(opts.RemoteBundlePath),
		"__LOCAL_BUNDLE_DEFAULT__", yamlQuoted(opts.LocalBundle),
		"__PROFILE_DEFAULT__", yamlQuoted(opts.Profile),
		"__STALE_THRESHOLD_DEFAULT__", yamlQuoted(opts.StaleThreshold),
	)
	return replacer.Replace(ciSyncGateWorkflowTemplate)
}

func renderDefaultCISyncGateTemplateWorkflow() string {
	return renderCISyncGateWorkflow(defaultInitCISyncGateOptions(), ciSyncGateTemplateName)
}

func yamlQuoted(s string) string {
	return strconv.Quote(strings.TrimSpace(s))
}

func initCommandError(cmd *cobra.Command, jsonOut bool, err error) error {
	if err == nil {
		return nil
	}
	if jsonOut {
		_ = json.NewEncoder(cmd.ErrOrStderr()).Encode(initErrorResult{
			OK:       false,
			Error:    err.Error(),
			ExitCode: exitcode.CodeInitFailed,
		})
	} else {
		fmt.Fprintln(cmd.ErrOrStderr(), err.Error())
	}
	return exitcode.New(exitcode.CodeInitFailed, err)
}
