package cli

import (
	"bufio"
	"bytes"
	"errors"
	"fmt"
	"io"
	"os"
	"os/exec"
	"strings"

	"golang.org/x/term"
)

const envPassphrase = "KIMEN_PASSPHRASE"

func resolvePassphrase(passphraseCmdFlag string, passphraseStdinFlag bool) ([]byte, error) {
	if p := os.Getenv(envPassphrase); p != "" {
		return []byte(p), nil
	}

	if passphraseCmdFlag != "" {
		args := strings.Fields(passphraseCmdFlag)
		if len(args) == 0 {
			return nil, errors.New("empty --passphrase-cmd")
		}
		return resolvePassphraseFromExec(args)
	}
	if passphraseStdinFlag {
		return readLine(os.Stdin)
	}

	c, _, err := loadConfig()
	if err != nil {
		return nil, err
	}
	if c.Unlock != nil {
		switch strings.ToLower(strings.TrimSpace(c.Unlock.Method)) {
		case "", "prompt":
			// fall through to prompt
		case "env":
			return nil, fmt.Errorf("unlock.method=env but %s is not set", envPassphrase)
		case "stdin":
			return readLine(os.Stdin)
		case "exec":
			if len(c.Unlock.Exec) == 0 {
				return nil, errors.New("unlock.method=exec but unlock.exec is empty")
			}
			return resolvePassphraseFromExec(c.Unlock.Exec)
		default:
			return nil, fmt.Errorf("unknown unlock.method %q (expected prompt/env/stdin/exec)", c.Unlock.Method)
		}
	}

	if term.IsTerminal(int(os.Stdin.Fd())) {
		fmt.Fprint(os.Stderr, "Passphrase: ")
		b, err := term.ReadPassword(int(os.Stdin.Fd()))
		fmt.Fprintln(os.Stderr)
		if err != nil {
			return nil, err
		}
		if len(b) == 0 {
			return nil, errors.New("empty passphrase")
		}
		return b, nil
	}
	return nil, errors.New("no passphrase provided (set KIMEN_PASSPHRASE, use --passphrase-stdin/--passphrase-cmd, or configure `kimen config unlock ...`)")
}

func resolvePassphraseFromExec(args []string) ([]byte, error) {
	out, err := exec.Command(args[0], args[1:]...).Output()
	if err != nil {
		return nil, fmt.Errorf("passphrase command failed: %w", err)
	}
	return readLine(bytes.NewReader(out))
}

func readLine(r io.Reader) ([]byte, error) {
	br := bufio.NewReader(r)
	line, err := br.ReadString('\n')
	if err != nil && !errors.Is(err, io.EOF) {
		return nil, err
	}
	line = strings.TrimRight(line, "\r\n")
	if line == "" {
		return nil, errors.New("empty passphrase")
	}
	return []byte(line), nil
}
