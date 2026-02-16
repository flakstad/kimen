package cli

import (
	"errors"
	"fmt"
	"os"

	"github.com/spf13/cobra"
)

func NewRootCommand() *cobra.Command {
	cmd := &cobra.Command{
		Use:           "kimen",
		Short:         "Kimen is a local-first secret projection tool",
		SilenceUsage:  true,
		SilenceErrors: true,
	}

	cmd.AddCommand(newVaultCommand())
	cmd.AddCommand(newSecretCommand())
	cmd.AddCommand(newPlanCommand())
	cmd.AddCommand(newRunCommand(runUsageRoot, runMissingCommandRoot))
	cmd.AddCommand(newRenderCommand())
	cmd.AddCommand(newEnvfileCommand())
	cmd.AddCommand(newProjectCommand())
	cmd.AddCommand(newBundleCommand())
	cmd.AddCommand(newConfigCommand())
	cmd.AddCommand(newMapCommand())
	cmd.AddCommand(newDoctorCommand())

	cmd.SetErr(os.Stderr)
	cmd.SetOut(os.Stdout)

	cmd.SetFlagErrorFunc(func(c *cobra.Command, err error) error {
		return fmt.Errorf("%w\n\n%s", err, c.UsageString())
	})

	cmd.PersistentPreRunE = func(c *cobra.Command, args []string) error {
		if c.Name() == "help" {
			return nil
		}
		return nil
	}

	cmd.SetHelpCommand(&cobra.Command{
		Use:   "help [command]",
		Short: "Show help",
		Args:  cobra.ArbitraryArgs,
		RunE: func(c *cobra.Command, args []string) error {
			root := c.Root()
			if len(args) == 0 {
				return root.Help()
			}
			target, _, err := root.Find(args)
			if err != nil || target == root {
				return errors.New("unknown command")
			}
			return target.Help()
		},
	})

	return cmd
}
