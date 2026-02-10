package projection

import (
	"bytes"
	"context"
	"errors"
	"fmt"
	"os/exec"
	"strings"

	"kimen/internal/vault"
)

const execValuePrefix = "exec:"

func ResolveValue(ctx context.Context, r vault.Reader, spec string) ([]byte, error) {
	spec = strings.TrimSpace(spec)
	if spec == "" {
		return nil, errors.New("empty value spec")
	}

	if strings.HasPrefix(spec, execValuePrefix) {
		cmdStr := strings.TrimSpace(strings.TrimPrefix(spec, execValuePrefix))
		if cmdStr == "" {
			return nil, errors.New("empty exec command in value spec")
		}
		argv := strings.Fields(cmdStr)
		if len(argv) == 0 {
			return nil, errors.New("empty exec command in value spec")
		}
		cmd := exec.CommandContext(ctx, argv[0], argv[1:]...)
		out, err := cmd.Output()
		if err != nil {
			return nil, fmt.Errorf("exec source failed: %w", err)
		}
		return stripOneTrailingNewline(out), nil
	}

	sec, err := r.GetSecret(ctx, spec)
	if err != nil {
		return nil, err
	}
	return sec.Value, nil
}

func stripOneTrailingNewline(b []byte) []byte {
	if bytes.HasSuffix(b, []byte("\r\n")) {
		return b[:len(b)-2]
	}
	if bytes.HasSuffix(b, []byte("\n")) {
		return b[:len(b)-1]
	}
	return b
}

