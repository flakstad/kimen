package main

import (
	"context"
	"errors"
	"fmt"
	"os"

	"kimen/internal/cli"
	"kimen/internal/exitcode"
)

func main() {
	ctx := context.Background()
	cmd := cli.NewRootCommand()
	if err := cmd.ExecuteContext(ctx); err != nil {
		var ec *exitcode.Error
		if errors.As(err, &ec) {
			os.Exit(ec.Code)
		}
		fmt.Fprintln(os.Stderr, err)
		os.Exit(1)
	}
}
