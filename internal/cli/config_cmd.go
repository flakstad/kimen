package cli

import (
	"encoding/json"
	"errors"
	"fmt"
	"strings"

	"github.com/spf13/cobra"
)

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
	return &cobra.Command{
		Use:   "path",
		Short: "Print the config file path",
		RunE: func(cmd *cobra.Command, args []string) error {
			p, err := defaultConfigPath()
			if err != nil {
				return err
			}
			fmt.Fprintln(cmd.OutOrStdout(), p)
			return nil
		},
	}
}

func newConfigShowCommand() *cobra.Command {
	var pretty bool
	cmd := &cobra.Command{
		Use:   "show",
		Short: "Show the current config as JSON",
		RunE: func(cmd *cobra.Command, args []string) error {
			c, _, err := loadConfig()
			if err != nil {
				return err
			}
			var b []byte
			if pretty {
				b, err = json.MarshalIndent(c, "", "  ")
			} else {
				b, err = json.Marshal(c)
			}
			if err != nil {
				return err
			}
			b = append(b, '\n')
			_, err = cmd.OutOrStdout().Write(b)
			return err
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
	return &cobra.Command{
		Use:   "show",
		Short: "Show the configured unlock method",
		RunE: func(cmd *cobra.Command, args []string) error {
			c, _, err := loadConfig()
			if err != nil {
				return err
			}
			if c.Unlock == nil {
				fmt.Fprintln(cmd.OutOrStdout(), "method: prompt (default)")
				return nil
			}
			fmt.Fprintf(cmd.OutOrStdout(), "method: %s\n", c.Unlock.Method)
			if c.Unlock.Method == "exec" {
				fmt.Fprintf(cmd.OutOrStdout(), "exec: %s\n", strings.Join(c.Unlock.Exec, " "))
			}
			return nil
		},
	}
}

func newConfigUnlockClearCommand() *cobra.Command {
	return &cobra.Command{
		Use:   "clear",
		Short: "Remove unlock configuration (revert to prompt)",
		RunE: func(cmd *cobra.Command, args []string) error {
			c, _, err := loadConfig()
			if err != nil {
				return err
			}
			c.Unlock = nil
			p, err := saveConfig(c)
			if err != nil {
				return err
			}
			fmt.Fprintf(cmd.OutOrStdout(), "ok (%s)\n", p)
			return nil
		},
	}
}

func newConfigUnlockSetCommand() *cobra.Command {
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
					return errors.New("exec method requires a command (use: kimen config unlock set exec -- <command> [args...])")
				}
			default:
				return fmt.Errorf("unknown unlock method %q", method)
			}

			c, _, err := loadConfig()
			if err != nil {
				return err
			}
			c.Unlock = &uc
			p, err := saveConfig(c)
			if err != nil {
				return err
			}
			fmt.Fprintf(cmd.OutOrStdout(), "ok (%s)\n", p)
			return nil
		},
	}
	return cmd
}
