package cli

import (
	"encoding/json"
	"errors"
	"fmt"
	"regexp"
	"sort"
	"strings"

	"github.com/spf13/cobra"

	"kimen/internal/exitcode"
)

var remoteNameRE = regexp.MustCompile(`^[A-Za-z0-9_.-]+$`)

type remoteResult struct {
	OK      bool           `json:"ok"`
	Action  string         `json:"action"`
	Name    string         `json:"name,omitempty"`
	Remote  *remoteConfig  `json:"remote,omitempty"`
	Remotes []remoteConfig `json:"remotes,omitempty"`
	Count   int            `json:"count,omitempty"`
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
	cmd.AddCommand(newRemoteListCommand())
	cmd.AddCommand(newRemoteRemoveCommand())
	return cmd
}

func newRemoteAddCommand() *cobra.Command {
	var remoteType string
	var path string
	var recipient string
	var identity string
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
			remoteType = strings.ToLower(strings.TrimSpace(remoteType))
			if remoteType == "" {
				remoteType = "fs"
			}
			if remoteType != "fs" {
				return remoteCommandError(cmd, jsonOut, fmt.Errorf("unsupported remote type %q (expected fs)", remoteType))
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

			r := remoteConfig{
				Name:      name,
				Type:      remoteType,
				Path:      path,
				Recipient: strings.TrimSpace(recipient),
				Identity:  strings.TrimSpace(identity),
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

	cmd.Flags().StringVar(&remoteType, "type", "fs", "remote type (currently: fs)")
	cmd.Flags().StringVar(&path, "path", "", "remote path (directory or .age file)")
	cmd.Flags().StringVar(&recipient, "recipient", "", "age recipient used for sync push")
	cmd.Flags().StringVar(&identity, "identity", "", "age identity file used for sync pull")
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
			if jsonOut {
				return json.NewEncoder(cmd.OutOrStdout()).Encode(remoteResult{
					OK:      true,
					Action:  "remote_list",
					Remotes: remotes,
					Count:   len(remotes),
				})
			}
			for _, r := range remotes {
				fmt.Fprintf(cmd.OutOrStdout(), "%s\t%s\t%s\n", r.Name, r.Type, r.Path)
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
