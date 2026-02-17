package cli

import (
	"encoding/json"
	"errors"
	"fmt"
	"strings"

	"github.com/spf13/cobra"

	"kimen/internal/exitcode"
)

type configResult struct {
	OK       bool     `json:"ok"`
	Action   string   `json:"action"`
	ExitCode int      `json:"exit_code"`
	Path     string   `json:"path,omitempty"`
	Method   string   `json:"method,omitempty"`
	Exec     []string `json:"exec,omitempty"`
}

type configErrorResult struct {
	OK       bool   `json:"ok"`
	Error    string `json:"error"`
	ExitCode int    `json:"exit_code"`
	Reason   string `json:"reason,omitempty"`
}

func newConfigCommand() *cobra.Command {
	cmd := &cobra.Command{
		Use:   "config",
		Short: "Manage Kimen user configuration",
	}
	cmd.AddCommand(newConfigPathCommand())
	cmd.AddCommand(newConfigShowCommand())
	cmd.AddCommand(newConfigUnlockCommand())
	return cmd
}

func newConfigPathCommand() *cobra.Command {
	var jsonOut bool
	cmd := &cobra.Command{
		Use:   "path",
		Short: "Print the config file path",
		RunE: func(cmd *cobra.Command, args []string) error {
			p, err := defaultConfigPath()
			if err != nil {
				return configCommandError(cmd, jsonOut, err)
			}
			if jsonOut {
				return json.NewEncoder(cmd.OutOrStdout()).Encode(configResult{
					OK:     true,
					Action: "config_path",
					Path:   p,
				})
			}
			fmt.Fprintln(cmd.OutOrStdout(), p)
			return nil
		},
	}
	cmd.Flags().BoolVar(&jsonOut, "json", false, "output JSON")
	return cmd
}

func newConfigShowCommand() *cobra.Command {
	var pretty bool
	cmd := &cobra.Command{
		Use:   "show",
		Short: "Show the current config as JSON",
		RunE: func(cmd *cobra.Command, args []string) error {
			c, _, err := loadConfig()
			if err != nil {
				return configCommandError(cmd, true, err)
			}
			var b []byte
			if pretty {
				b, err = json.MarshalIndent(c, "", "  ")
			} else {
				b, err = json.Marshal(c)
			}
			if err != nil {
				return configCommandError(cmd, true, err)
			}
			b = append(b, '\n')
			_, err = cmd.OutOrStdout().Write(b)
			if err != nil {
				return configCommandError(cmd, true, err)
			}
			return nil
		},
	}
	cmd.Flags().BoolVar(&pretty, "pretty", true, "pretty-print JSON")
	return cmd
}

func newConfigUnlockCommand() *cobra.Command {
	cmd := &cobra.Command{
		Use:   "unlock",
		Short: "Configure how Kimen obtains the vault passphrase",
	}
	cmd.AddCommand(newConfigUnlockShowCommand())
	cmd.AddCommand(newConfigUnlockClearCommand())
	cmd.AddCommand(newConfigUnlockSetCommand())
	return cmd
}

func newConfigUnlockShowCommand() *cobra.Command {
	var jsonOut bool
	cmd := &cobra.Command{
		Use:   "show",
		Short: "Show the configured unlock method",
		RunE: func(cmd *cobra.Command, args []string) error {
			c, _, err := loadConfig()
			if err != nil {
				return configCommandError(cmd, jsonOut, err)
			}
			if c.Unlock == nil {
				if jsonOut {
					return json.NewEncoder(cmd.OutOrStdout()).Encode(configResult{
						OK:     true,
						Action: "config_unlock_show",
						Method: "prompt",
					})
				}
				fmt.Fprintln(cmd.OutOrStdout(), "method: prompt (default)")
				return nil
			}
			if jsonOut {
				return json.NewEncoder(cmd.OutOrStdout()).Encode(configResult{
					OK:     true,
					Action: "config_unlock_show",
					Method: c.Unlock.Method,
					Exec:   c.Unlock.Exec,
				})
			}
			fmt.Fprintf(cmd.OutOrStdout(), "method: %s\n", c.Unlock.Method)
			if c.Unlock.Method == "exec" {
				fmt.Fprintf(cmd.OutOrStdout(), "exec: %s\n", strings.Join(c.Unlock.Exec, " "))
			}
			return nil
		},
	}
	cmd.Flags().BoolVar(&jsonOut, "json", false, "output JSON")
	return cmd
}

func newConfigUnlockClearCommand() *cobra.Command {
	var jsonOut bool
	cmd := &cobra.Command{
		Use:   "clear",
		Short: "Remove unlock configuration (revert to prompt)",
		RunE: func(cmd *cobra.Command, args []string) error {
			c, _, err := loadConfig()
			if err != nil {
				return configCommandError(cmd, jsonOut, err)
			}
			c.Unlock = nil
			p, err := saveConfig(c)
			if err != nil {
				return configCommandError(cmd, jsonOut, err)
			}
			if jsonOut {
				return json.NewEncoder(cmd.OutOrStdout()).Encode(configResult{
					OK:     true,
					Action: "config_unlock_clear",
					Path:   p,
					Method: "prompt",
				})
			}
			fmt.Fprintf(cmd.OutOrStdout(), "ok (%s)\n", p)
			return nil
		},
	}
	cmd.Flags().BoolVar(&jsonOut, "json", false, "output JSON")
	return cmd
}

func newConfigUnlockSetCommand() *cobra.Command {
	var jsonOut bool
	cmd := &cobra.Command{
		Use:   "set <prompt|env|stdin|exec> [-- <command> [args...]]",
		Short: "Set the default unlock method",
		Args:  cobra.MinimumNArgs(1),
		RunE: func(cmd *cobra.Command, args []string) error {
			method := strings.ToLower(strings.TrimSpace(args[0]))
			var uc unlockConfig
			switch method {
			case "prompt", "":
				uc.Method = "prompt"
			case "env":
				uc.Method = "env"
			case "stdin":
				uc.Method = "stdin"
			case "exec":
				uc.Method = "exec"
				uc.Exec = args[1:]
				if len(uc.Exec) == 0 {
					return configCommandError(cmd, jsonOut, errors.New("exec method requires a command (use: kimen config unlock set exec -- <command> [args...])"))
				}
			default:
				return configCommandError(cmd, jsonOut, fmt.Errorf("unknown unlock method %q", method))
			}

			c, _, err := loadConfig()
			if err != nil {
				return configCommandError(cmd, jsonOut, err)
			}
			c.Unlock = &uc
			p, err := saveConfig(c)
			if err != nil {
				return configCommandError(cmd, jsonOut, err)
			}
			if jsonOut {
				return json.NewEncoder(cmd.OutOrStdout()).Encode(configResult{
					OK:     true,
					Action: "config_unlock_set",
					Path:   p,
					Method: uc.Method,
					Exec:   uc.Exec,
				})
			}
			fmt.Fprintf(cmd.OutOrStdout(), "ok (%s)\n", p)
			return nil
		},
	}
	cmd.Flags().BoolVar(&jsonOut, "json", false, "output JSON")
	return cmd
}

func configCommandError(cmd *cobra.Command, jsonOut bool, err error) error {
	if err == nil {
		return nil
	}
	if jsonOut {
		_ = json.NewEncoder(cmd.ErrOrStderr()).Encode(configErrorResult{
			OK:       false,
			Error:    err.Error(),
			ExitCode: exitcode.CodeConfigFailed,
			Reason:   configErrorReason(err),
		})
	} else {
		fmt.Fprintln(cmd.ErrOrStderr(), err.Error())
	}
	return exitcode.New(exitcode.CodeConfigFailed, err)
}

func configErrorReason(err error) string {
	if err == nil {
		return ""
	}
	msg := strings.ToLower(strings.TrimSpace(err.Error()))
	switch {
	case strings.Contains(msg, "unknown unlock method"):
		return reasonUnknownUnlockMethod
	case strings.Contains(msg, "exec method requires a command"):
		return reasonMissingUnlockExecCommand
	case strings.Contains(msg, "invalid config json"):
		return reasonInvalidConfigJSON
	case strings.Contains(msg, "no user config dir"):
		return reasonConfigPathUnavailable
	default:
		return reasonConfigFailed
	}
}
