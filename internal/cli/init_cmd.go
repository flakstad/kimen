package cli

import (
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

const defaultCISyncGateWorkflowPath = ".github/workflows/kimen-sync-gate.yml"

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
	opts := initCISyncGateOptions{
		RemoteName:       "team",
		RemoteType:       "git",
		RemotePath:       "",
		RemoteBranch:     "main",
		RemoteBundlePath: "vault.age",
		LocalBundle:      "vault.age",
		Profile:          "",
		StaleThreshold:   "30m",
	}

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
			content := renderCISyncGateWorkflow(opts)
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

func renderCISyncGateWorkflow(opts initCISyncGateOptions) string {
	replacer := strings.NewReplacer(
		"__REMOTE_NAME_DEFAULT__", yamlQuoted(opts.RemoteName),
		"__REMOTE_TYPE_DEFAULT__", yamlQuoted(opts.RemoteType),
		"__REMOTE_PATH_DEFAULT__", yamlQuoted(opts.RemotePath),
		"__REMOTE_BRANCH_DEFAULT__", yamlQuoted(opts.RemoteBranch),
		"__REMOTE_BUNDLE_PATH_DEFAULT__", yamlQuoted(opts.RemoteBundlePath),
		"__LOCAL_BUNDLE_DEFAULT__", yamlQuoted(opts.LocalBundle),
		"__PROFILE_DEFAULT__", yamlQuoted(opts.Profile),
		"__STALE_THRESHOLD_DEFAULT__", yamlQuoted(opts.StaleThreshold),
	)
	return replacer.Replace(ciSyncGateWorkflowScaffold)
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

const ciSyncGateWorkflowScaffold = `name: kimen-sync-gate

on:
  workflow_dispatch:
    inputs:
      remote_name:
        description: "Configured remote name"
        required: false
        default: __REMOTE_NAME_DEFAULT__
      remote_type:
        description: "Remote type: git or fs"
        required: false
        default: __REMOTE_TYPE_DEFAULT__
      remote_path:
        description: "Git URL/path or fs path"
        required: true
        default: __REMOTE_PATH_DEFAULT__
      remote_branch:
        description: "Git branch (git remotes only)"
        required: false
        default: __REMOTE_BRANCH_DEFAULT__
      remote_bundle_path:
        description: "Bundle path in git repo (git remotes only)"
        required: false
        default: __REMOTE_BUNDLE_PATH_DEFAULT__
      local_bundle:
        description: "Local ciphertext bundle path used for push dry-run preflight"
        required: false
        default: __LOCAL_BUNDLE_DEFAULT__
      profile:
        description: "Optional Kimen profile name (without .kmap) for doctor"
        required: false
        default: __PROFILE_DEFAULT__
      stale_threshold:
        description: "Stale lock threshold passed to sync status/conflicts"
        required: false
        default: __STALE_THRESHOLD_DEFAULT__

jobs:
  sync-gate:
    runs-on: ubuntu-latest
    env:
      KIMEN_VAULT: ${{ runner.temp }}/kimen/vault.db
      KIMEN_CONFIG: ${{ runner.temp }}/kimen/config.toml
    steps:
      - uses: actions/checkout@v4

      - uses: actions/setup-go@v5
        with:
          go-version-file: go.mod
          cache: true

      - name: Build kimen
        run: go build -o kimen ./cmd/kimen

      - name: Template checklist
        shell: bash
        env:
          KIMEN_AGE_IDENTITY: ${{ secrets.KIMEN_AGE_IDENTITY }}
          KIMEN_AGE_RECIPIENT: ${{ secrets.KIMEN_AGE_RECIPIENT }}
          KIMEN_PASSPHRASE: ${{ secrets.KIMEN_PASSPHRASE }}
        run: |
          set -euo pipefail
          REMOTE_TYPE="${{ inputs.remote_type }}"
          REMOTE_PATH="${{ inputs.remote_path }}"
          LOCAL_BUNDLE="${{ inputs.local_bundle }}"
          PROFILE="${{ inputs.profile }}"

          if [[ "$REMOTE_TYPE" != "git" && "$REMOTE_TYPE" != "fs" ]]; then
            echo "::error::remote_type must be git or fs"
            exit 1
          fi
          if [[ -z "$REMOTE_PATH" ]]; then
            echo "::error::remote_path is required"
            exit 1
          fi
          if [[ -z "$LOCAL_BUNDLE" ]]; then
            echo "::error::local_bundle is required"
            exit 1
          fi
          if [[ ! -f "$LOCAL_BUNDLE" ]]; then
            echo "::error::Missing local bundle: $LOCAL_BUNDLE"
            exit 1
          fi
          if [[ -n "$PROFILE" ]]; then
            MAP_PATH=".kimen/profiles/${PROFILE}.kmap"
            if [[ ! -f "$MAP_PATH" ]]; then
              echo "::error::Missing map file: $MAP_PATH"
              exit 1
            fi
          fi
          if [[ -z "${KIMEN_AGE_IDENTITY:-}" ]]; then
            echo "::error::Missing secret KIMEN_AGE_IDENTITY"
            exit 1
          fi
          if [[ -z "${KIMEN_AGE_RECIPIENT:-}" ]]; then
            echo "::error::Missing secret KIMEN_AGE_RECIPIENT"
            exit 1
          fi
          if [[ -z "${KIMEN_PASSPHRASE:-}" ]]; then
            echo "::error::Missing secret KIMEN_PASSPHRASE"
            exit 1
          fi

      - name: Materialize CI identity file
        shell: bash
        env:
          KIMEN_AGE_IDENTITY: ${{ secrets.KIMEN_AGE_IDENTITY }}
        run: |
          set -euo pipefail
          ID_FILE="${RUNNER_TEMP}/kimen/ci.agekey"
          mkdir -p "$(dirname "$ID_FILE")"
          printf '%s\n' "$KIMEN_AGE_IDENTITY" > "$ID_FILE"
          chmod 600 "$ID_FILE"
          echo "KIMEN_IDENTITY_FILE=$ID_FILE" >> "$GITHUB_ENV"

      - name: Configure sync remote
        shell: bash
        env:
          KIMEN_AGE_RECIPIENT: ${{ secrets.KIMEN_AGE_RECIPIENT }}
        run: |
          set -euo pipefail
          REMOTE_NAME="${{ inputs.remote_name }}"
          REMOTE_TYPE="${{ inputs.remote_type }}"

          if [[ "$REMOTE_TYPE" == "git" ]]; then
            ./kimen remote add "$REMOTE_NAME" \
              --type git \
              --path "${{ inputs.remote_path }}" \
              --branch "${{ inputs.remote_branch }}" \
              --bundle-path "${{ inputs.remote_bundle_path }}" \
              --recipient "$KIMEN_AGE_RECIPIENT" \
              --identity "$KIMEN_IDENTITY_FILE" \
              --json | tee kimen-remote-add.json
          else
            ./kimen remote add "$REMOTE_NAME" \
              --type fs \
              --path "${{ inputs.remote_path }}" \
              --recipient "$KIMEN_AGE_RECIPIENT" \
              --identity "$KIMEN_IDENTITY_FILE" \
              --json | tee kimen-remote-add.json
          fi

      - name: Open local ciphertext bundle for push preflight
        shell: bash
        run: |
          set -euo pipefail
          mkdir -p "$(dirname "$KIMEN_VAULT")"
          ./kimen bundle open \
            --in "${{ inputs.local_bundle }}" \
            --identity "$KIMEN_IDENTITY_FILE" \
            --out-vault "$KIMEN_VAULT" \
            --overwrite \
            --json | tee kimen-bundle-open.json

      - name: Strict sync gate
        shell: bash
        env:
          KIMEN_PASSPHRASE: ${{ secrets.KIMEN_PASSPHRASE }}
        run: |
          set -uo pipefail
          REMOTE_NAME="${{ inputs.remote_name }}"
          PROFILE="${{ inputs.profile }}"
          STALE_THRESHOLD="${{ inputs.stale_threshold }}"
          overall_rc=0

          run_check() {
            local name="$1"
            shift
            "$@" | tee "kimen-${name}.json"
            local rc=${PIPESTATUS[0]}
            if [[ "$rc" -ne 0 ]]; then
              echo "::error::${name} failed with exit code ${rc}"
              if [[ "$overall_rc" -eq 0 ]]; then
                overall_rc="$rc"
              fi
            fi
          }

          if [[ -n "$PROFILE" ]]; then
            run_check doctor ./kimen doctor --profile "$PROFILE" --strict --json
          else
            run_check doctor ./kimen doctor --strict --json
          fi
          run_check sync-status ./kimen sync status --remote "$REMOTE_NAME" --stale-threshold "$STALE_THRESHOLD" --strict --json
          run_check sync-conflicts ./kimen sync conflicts --remote "$REMOTE_NAME" --stale-threshold "$STALE_THRESHOLD" --strict --json
          run_check sync-pull-dry-run ./kimen sync pull --remote "$REMOTE_NAME" --dry-run --json
          run_check sync-push-dry-run ./kimen sync push --remote "$REMOTE_NAME" --dry-run --json
          exit "$overall_rc"

      - name: Upload Kimen artifacts
        if: always()
        uses: actions/upload-artifact@v4
        with:
          name: kimen-sync-gate-${{ github.run_id }}
          if-no-files-found: warn
          path: |
            kimen-remote-add.json
            kimen-bundle-open.json
            kimen-doctor.json
            kimen-sync-status.json
            kimen-sync-conflicts.json
            kimen-sync-pull-dry-run.json
            kimen-sync-push-dry-run.json
`
