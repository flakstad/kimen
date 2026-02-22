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
const secretValuePrefix = "secret:"
const constValuePrefix = "const:"

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

	if strings.HasPrefix(spec, constValuePrefix) {
		// const: keeps bytes inline in the map/flag value instead of reading from vault.
		return []byte(strings.TrimPrefix(spec, constValuePrefix)), nil
	}

	secretName := spec
	if strings.HasPrefix(spec, secretValuePrefix) {
		secretName = strings.TrimSpace(strings.TrimPrefix(spec, secretValuePrefix))
		if secretName == "" {
			return nil, errors.New("empty secret name in value spec")
		}
	}

	sec, err := r.GetSecret(ctx, secretName)
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
