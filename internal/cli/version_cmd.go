package cli

import (
	"encoding/json"
	"fmt"

	"github.com/spf13/cobra"

	"kimen/internal/buildinfo"
)

type versionInfo struct {
	OK         bool   `json:"ok"`
	Action     string `json:"action"`
	Version    string `json:"version"`
	RawVersion string `json:"raw_version"`
	Commit     string `json:"commit"`
	Date       string `json:"date"`
}

func newVersionCommand() *cobra.Command {
	var jsonOut bool
	cmd := &cobra.Command{
		Use:   "version",
		Short: "Print build information",
		RunE: func(cmd *cobra.Command, args []string) error {
			info := versionInfo{
				OK:         true,
				Action:     "version",
				Version:    buildinfo.DisplayVersion(),
				RawVersion: buildinfo.Version,
				Commit:     buildinfo.Commit,
				Date:       buildinfo.Date,
			}
			if jsonOut {
				return json.NewEncoder(cmd.OutOrStdout()).Encode(info)
			}
			fmt.Fprintf(cmd.OutOrStdout(), "%s\n", info.Version)
			return nil
		},
	}
	cmd.Flags().BoolVar(&jsonOut, "json", false, "output JSON")
	return cmd
}
