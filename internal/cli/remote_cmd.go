package cli

import (
	"encoding/json"
	"errors"
	"fmt"
	"regexp"
	"sort"
	"strings"

	"github.com/spf13/cobra"

	"kimen/internal/bundle"
	"kimen/internal/exitcode"
)

var remoteNameRE = regexp.MustCompile(`^[A-Za-z0-9_.-]+$`)

type remoteResult struct {
	OK            bool           `json:"ok"`
	Action        string         `json:"action"`
	Name          string         `json:"name,omitempty"`
	Remote        *remoteConfig  `json:"remote,omitempty"`
	Remotes       []remoteConfig `json:"remotes,omitempty"`
	Count         int            `json:"count,omitempty"`
	BaselineReset bool           `json:"baseline_reset,omitempty"`
}

type remoteErrorResult struct {
	OK       bool   `json:"ok"`
	Error    string `json:"error"`
	ExitCode int    `json:"exit_code"`
}

func newRemoteCommand() *cobra.Command {
	cmd := &cobra.Command{
		Use:   "remote",
		Short: "Manage sync remotes",
	}
	cmd.AddCommand(newRemoteAddCommand())
	cmd.AddCommand(newRemoteGetCommand())
	cmd.AddCommand(newRemoteSetCommand())
	cmd.AddCommand(newRemoteListCommand())
	cmd.AddCommand(newRemoteRemoveCommand())
	return cmd
}

func newRemoteGetCommand() *cobra.Command {
	var jsonOut bool
	cmd := &cobra.Command{
		Use:   "get <name>",
		Short: "Show a configured remote",
		Args:  cobra.ExactArgs(1),
		RunE: func(cmd *cobra.Command, args []string) error {
			name := strings.TrimSpace(args[0])
			if name == "" {
				return remoteCommandError(cmd, jsonOut, errors.New("empty remote name"))
			}
			c, _, err := loadConfig()
			if err != nil {
				return remoteCommandError(cmd, jsonOut, err)
			}
			i := findRemoteIndex(c.Remotes, name)
			if i < 0 {
				return remoteCommandError(cmd, jsonOut, fmt.Errorf("remote %q not found", name))
			}
			r := c.Remotes[i]
			applyRemoteDefaults(&r)
			if jsonOut {
				return json.NewEncoder(cmd.OutOrStdout()).Encode(remoteResult{
					OK:     true,
					Action: "remote_get",
					Name:   name,
					Remote: &r,
				})
			}
			fmt.Fprintf(cmd.OutOrStdout(), "name: %s\n", r.Name)
			fmt.Fprintf(cmd.OutOrStdout(), "type: %s\n", r.Type)
			fmt.Fprintf(cmd.OutOrStdout(), "path: %s\n", r.Path)
			if remoteType(r) == "git" {
				branch := strings.TrimSpace(r.Branch)
				if branch == "" {
					branch = "main"
				}
				bundlePath := strings.TrimSpace(r.BundlePath)
				if bundlePath == "" {
					bundlePath = "vault.age"
				}
				fmt.Fprintf(cmd.OutOrStdout(), "branch: %s\n", branch)
				fmt.Fprintf(cmd.OutOrStdout(), "bundle_path: %s\n", bundlePath)
			}
			if r.Recipient == "" {
				fmt.Fprintln(cmd.OutOrStdout(), "recipient: (none)")
			} else {
				fmt.Fprintf(cmd.OutOrStdout(), "recipient: %s\n", r.Recipient)
			}
			if r.Identity == "" {
				fmt.Fprintln(cmd.OutOrStdout(), "identity: (none)")
			} else {
				fmt.Fprintf(cmd.OutOrStdout(), "identity: %s\n", r.Identity)
			}
			return nil
		},
	}
	cmd.Flags().BoolVar(&jsonOut, "json", false, "output JSON")
	return cmd
}

func newRemoteSetCommand() *cobra.Command {
	var remoteTypeFlag string
	var path string
	var recipient string
	var identity string
	var branch string
	var bundlePath string
	var deriveRecipient bool
	var jsonOut bool

	cmd := &cobra.Command{
		Use:   "set <name>",
		Short: "Update an existing remote configuration",
		Args:  cobra.ExactArgs(1),
		RunE: func(cmd *cobra.Command, args []string) error {
			name := strings.TrimSpace(args[0])
			if name == "" {
				return remoteCommandError(cmd, jsonOut, errors.New("empty remote name"))
			}
			typeChanged := cmd.Flags().Changed("type")
			pathChanged := cmd.Flags().Changed("path")
			recipientChanged := cmd.Flags().Changed("recipient")
			identityChanged := cmd.Flags().Changed("identity")
			branchChanged := cmd.Flags().Changed("branch")
			bundlePathChanged := cmd.Flags().Changed("bundle-path")
			if !typeChanged && !pathChanged && !recipientChanged && !identityChanged && !branchChanged && !bundlePathChanged {
				if !deriveRecipient {
					return remoteCommandError(cmd, jsonOut, errors.New("set at least one of --type, --path, --recipient, --identity, --branch, --bundle-path, --derive-recipient"))
				}
			}
			if deriveRecipient && recipientChanged {
				return remoteCommandError(cmd, jsonOut, errors.New("--derive-recipient cannot be combined with --recipient"))
			}

			c, _, err := loadConfig()
			if err != nil {
				return remoteCommandError(cmd, jsonOut, err)
			}
			i := findRemoteIndex(c.Remotes, name)
			if i < 0 {
				return remoteCommandError(cmd, jsonOut, fmt.Errorf("remote %q not found", name))
			}
			r := c.Remotes[i]
			if typeChanged {
				newType := normalizeRemoteType(remoteTypeFlag)
				if newType != "fs" && newType != "git" {
					return remoteCommandError(cmd, jsonOut, fmt.Errorf("unsupported remote type %q (expected fs or git)", newType))
				}
				r.Type = newType
			}
			if pathChanged {
				newPath := strings.TrimSpace(path)
				if newPath == "" {
					return remoteCommandError(cmd, jsonOut, errors.New("--path cannot be empty"))
				}
				r.Path = newPath
			}
			if recipientChanged {
				r.Recipient = strings.TrimSpace(recipient)
			}
			if identityChanged {
				r.Identity = strings.TrimSpace(identity)
			}
			if deriveRecipient {
				identityPath := strings.TrimSpace(r.Identity)
				if identityPath == "" {
					return remoteCommandError(cmd, jsonOut, errors.New("--derive-recipient requires --identity (or existing remote identity)"))
				}
				derived, err := deriveRecipientFromIdentityFile(identityPath)
				if err != nil {
					return remoteCommandError(cmd, jsonOut, err)
				}
				r.Recipient = derived
			}
			if branchChanged {
				r.Branch = strings.TrimSpace(branch)
			}
			if bundlePathChanged {
				r.BundlePath = strings.TrimSpace(bundlePath)
			}
			if remoteType(r) != "git" && (branchChanged || bundlePathChanged) {
				return remoteCommandError(cmd, jsonOut, errors.New("--branch/--bundle-path are only valid for --type git"))
			}
			applyRemoteDefaults(&r)
			if err := validateRemoteConfig(r); err != nil {
				return remoteCommandError(cmd, jsonOut, err)
			}

			baselineReset := false
			if c.Sync != nil && (typeChanged || pathChanged || branchChanged || bundlePathChanged) {
				if _, ok := c.Sync[name]; ok {
					delete(c.Sync, name)
					baselineReset = true
				}
				if len(c.Sync) == 0 {
					c.Sync = nil
				}
			}

			c.Remotes[i] = r
			if _, err := saveConfig(c); err != nil {
				return remoteCommandError(cmd, jsonOut, err)
			}

			if jsonOut {
				return json.NewEncoder(cmd.OutOrStdout()).Encode(remoteResult{
					OK:            true,
					Action:        "remote_set",
					Name:          name,
					Remote:        &r,
					BaselineReset: baselineReset,
				})
			}
			fmt.Fprintf(cmd.OutOrStdout(), "ok (remote %s updated)\n", name)
			if baselineReset {
				fmt.Fprintln(cmd.OutOrStdout(), "sync baseline reset (remote endpoint changed)")
			}
			return nil
		},
	}

	cmd.Flags().StringVar(&remoteTypeFlag, "type", "", "remote type (fs or git)")
	cmd.Flags().StringVar(&path, "path", "", "remote path (fs dir/.age path or git repo URL/path)")
	cmd.Flags().StringVar(&recipient, "recipient", "", "age recipient used for sync push (set empty string to clear)")
	cmd.Flags().StringVar(&identity, "identity", "", "age identity file used for sync pull (set empty string to clear)")
	cmd.Flags().StringVar(&branch, "branch", "", "git branch used for sync (set empty string for default)")
	cmd.Flags().StringVar(&bundlePath, "bundle-path", "", "git-relative bundle path (set empty string for default)")
	cmd.Flags().BoolVar(&deriveRecipient, "derive-recipient", false, "derive recipient from identity file (requires --identity or existing identity)")
	cmd.Flags().BoolVar(&jsonOut, "json", false, "output JSON")
	return cmd
}

func newRemoteAddCommand() *cobra.Command {
	var remoteTypeFlag string
	var path string
	var recipient string
	var identity string
	var branch string
	var bundlePath string
	var deriveRecipient bool
	var jsonOut bool

	cmd := &cobra.Command{
		Use:   "add <name>",
		Short: "Add a remote configuration",
		Args:  cobra.ExactArgs(1),
		RunE: func(cmd *cobra.Command, args []string) error {
			name := strings.TrimSpace(args[0])
			if name == "" {
				return remoteCommandError(cmd, jsonOut, errors.New("empty remote name"))
			}
			if !remoteNameRE.MatchString(name) {
				return remoteCommandError(cmd, jsonOut, fmt.Errorf("invalid remote name %q", name))
			}
			remoteTypeFlag = normalizeRemoteType(remoteTypeFlag)
			if remoteTypeFlag != "fs" && remoteTypeFlag != "git" {
				return remoteCommandError(cmd, jsonOut, fmt.Errorf("unsupported remote type %q (expected fs or git)", remoteTypeFlag))
			}
			if deriveRecipient && strings.TrimSpace(recipient) != "" {
				return remoteCommandError(cmd, jsonOut, errors.New("--derive-recipient cannot be combined with --recipient"))
			}
			branchChanged := cmd.Flags().Changed("branch")
			bundlePathChanged := cmd.Flags().Changed("bundle-path")
			if remoteTypeFlag != "git" && (branchChanged || bundlePathChanged) {
				return remoteCommandError(cmd, jsonOut, errors.New("--branch/--bundle-path are only valid for --type git"))
			}
			path = strings.TrimSpace(path)
			if path == "" {
				return remoteCommandError(cmd, jsonOut, errors.New("--path is required"))
			}

			c, _, err := loadConfig()
			if err != nil {
				return remoteCommandError(cmd, jsonOut, err)
			}
			if findRemoteIndex(c.Remotes, name) >= 0 {
				return remoteCommandError(cmd, jsonOut, fmt.Errorf("remote %q already exists", name))
			}
			if deriveRecipient {
				identityPath := strings.TrimSpace(identity)
				if identityPath == "" {
					return remoteCommandError(cmd, jsonOut, errors.New("--derive-recipient requires --identity"))
				}
				derived, err := deriveRecipientFromIdentityFile(identityPath)
				if err != nil {
					return remoteCommandError(cmd, jsonOut, err)
				}
				recipient = derived
			}

			r := remoteConfig{
				Name:       name,
				Type:       remoteTypeFlag,
				Path:       path,
				Recipient:  strings.TrimSpace(recipient),
				Identity:   strings.TrimSpace(identity),
				Branch:     strings.TrimSpace(branch),
				BundlePath: strings.TrimSpace(bundlePath),
			}
			applyRemoteDefaults(&r)
			if err := validateRemoteConfig(r); err != nil {
				return remoteCommandError(cmd, jsonOut, err)
			}
			c.Remotes = append(c.Remotes, r)
			sort.Slice(c.Remotes, func(i, j int) bool { return c.Remotes[i].Name < c.Remotes[j].Name })

			if _, err := saveConfig(c); err != nil {
				return remoteCommandError(cmd, jsonOut, err)
			}
			if jsonOut {
				return json.NewEncoder(cmd.OutOrStdout()).Encode(remoteResult{
					OK:     true,
					Action: "remote_add",
					Name:   name,
					Remote: &r,
				})
			}
			fmt.Fprintf(cmd.OutOrStdout(), "ok (remote %s)\n", name)
			return nil
		},
	}

	cmd.Flags().StringVar(&remoteTypeFlag, "type", "fs", "remote type (fs or git)")
	cmd.Flags().StringVar(&path, "path", "", "remote path (fs dir/.age path or git repo URL/path)")
	cmd.Flags().StringVar(&recipient, "recipient", "", "age recipient used for sync push")
	cmd.Flags().StringVar(&identity, "identity", "", "age identity file used for sync pull")
	cmd.Flags().StringVar(&branch, "branch", "", "git branch used for sync (default: main)")
	cmd.Flags().StringVar(&bundlePath, "bundle-path", "", "git-relative bundle path (default: vault.age)")
	cmd.Flags().BoolVar(&deriveRecipient, "derive-recipient", false, "derive recipient from identity file (requires --identity)")
	cmd.Flags().BoolVar(&jsonOut, "json", false, "output JSON")
	return cmd
}

func newRemoteListCommand() *cobra.Command {
	var jsonOut bool
	cmd := &cobra.Command{
		Use:   "list",
		Short: "List configured remotes",
		RunE: func(cmd *cobra.Command, args []string) error {
			c, _, err := loadConfig()
			if err != nil {
				return remoteCommandError(cmd, jsonOut, err)
			}
			remotes := append([]remoteConfig(nil), c.Remotes...)
			sort.Slice(remotes, func(i, j int) bool { return remotes[i].Name < remotes[j].Name })
			for i := range remotes {
				applyRemoteDefaults(&remotes[i])
			}
			if jsonOut {
				return json.NewEncoder(cmd.OutOrStdout()).Encode(remoteResult{
					OK:      true,
					Action:  "remote_list",
					Remotes: remotes,
					Count:   len(remotes),
				})
			}
			for _, r := range remotes {
				t := remoteType(r)
				if t == "git" {
					branch := strings.TrimSpace(r.Branch)
					if branch == "" {
						branch = "main"
					}
					bundlePath := strings.TrimSpace(r.BundlePath)
					if bundlePath == "" {
						bundlePath = "vault.age"
					}
					fmt.Fprintf(cmd.OutOrStdout(), "%s\t%s\t%s@%s:%s\n", r.Name, t, r.Path, branch, bundlePath)
					continue
				}
				fmt.Fprintf(cmd.OutOrStdout(), "%s\t%s\t%s\n", r.Name, t, r.Path)
			}
			return nil
		},
	}
	cmd.Flags().BoolVar(&jsonOut, "json", false, "output JSON")
	return cmd
}

func newRemoteRemoveCommand() *cobra.Command {
	var jsonOut bool
	cmd := &cobra.Command{
		Use:     "rm <name>",
		Aliases: []string{"remove", "delete"},
		Short:   "Remove a remote configuration",
		Args:    cobra.ExactArgs(1),
		RunE: func(cmd *cobra.Command, args []string) error {
			name := strings.TrimSpace(args[0])
			if name == "" {
				return remoteCommandError(cmd, jsonOut, errors.New("empty remote name"))
			}
			c, _, err := loadConfig()
			if err != nil {
				return remoteCommandError(cmd, jsonOut, err)
			}
			i := findRemoteIndex(c.Remotes, name)
			if i < 0 {
				return remoteCommandError(cmd, jsonOut, fmt.Errorf("remote %q not found", name))
			}
			c.Remotes = append(c.Remotes[:i], c.Remotes[i+1:]...)
			if c.Sync != nil {
				delete(c.Sync, name)
				if len(c.Sync) == 0 {
					c.Sync = nil
				}
			}
			if _, err := saveConfig(c); err != nil {
				return remoteCommandError(cmd, jsonOut, err)
			}
			if jsonOut {
				return json.NewEncoder(cmd.OutOrStdout()).Encode(remoteResult{
					OK:     true,
					Action: "remote_rm",
					Name:   name,
				})
			}
			fmt.Fprintf(cmd.OutOrStdout(), "ok (remote %s removed)\n", name)
			return nil
		},
	}
	cmd.Flags().BoolVar(&jsonOut, "json", false, "output JSON")
	return cmd
}

func remoteCommandError(cmd *cobra.Command, jsonOut bool, err error) error {
	if err == nil {
		return nil
	}
	if jsonOut {
		_ = json.NewEncoder(cmd.ErrOrStderr()).Encode(remoteErrorResult{
			OK:       false,
			Error:    err.Error(),
			ExitCode: exitcode.CodeRemoteFailed,
		})
	} else {
		fmt.Fprintln(cmd.ErrOrStderr(), err.Error())
	}
	return exitcode.New(exitcode.CodeRemoteFailed, err)
}

func normalizeRemoteType(raw string) string {
	t := strings.ToLower(strings.TrimSpace(raw))
	if t == "" {
		return "fs"
	}
	return t
}

func deriveRecipientFromIdentityFile(identityPath string) (string, error) {
	id, err := bundle.LoadIdentity(strings.TrimSpace(identityPath), false, nil)
	if err != nil {
		return "", fmt.Errorf("derive recipient from identity: %w", err)
	}
	recipient, err := bundle.RecipientForIdentity(id)
	if err != nil {
		return "", fmt.Errorf("derive recipient from identity: %w", err)
	}
	return strings.TrimSpace(recipient), nil
}

func applyRemoteDefaults(r *remoteConfig) {
	if r == nil {
		return
	}
	r.Type = normalizeRemoteType(r.Type)
	if r.Type != "git" {
		r.Branch = ""
		r.BundlePath = ""
		return
	}
	if strings.TrimSpace(r.Branch) == "" {
		r.Branch = "main"
	}
	if strings.TrimSpace(r.BundlePath) == "" {
		r.BundlePath = "vault.age"
	}
}

func validateRemoteConfig(r remoteConfig) error {
	if strings.TrimSpace(r.Path) == "" {
		return errors.New("--path is required")
	}
	switch remoteType(r) {
	case "fs":
		if strings.TrimSpace(r.Branch) != "" || strings.TrimSpace(r.BundlePath) != "" {
			return errors.New("--branch/--bundle-path are only valid for --type git")
		}
		return nil
	case "git":
		if _, _, _, err := gitRemoteRef(r); err != nil {
			return err
		}
		return nil
	default:
		return fmt.Errorf("unsupported remote type %q (expected fs or git)", r.Type)
	}
}
