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

var openTTY = func() (*os.File, error) {
	return os.OpenFile("/dev/tty", os.O_RDWR, 0)
}

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
			return promptPassphrase()
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

	return promptPassphrase()
}

func resolvePassphraseFromExec(args []string) ([]byte, error) {
	out, err := exec.Command(args[0], args[1:]...).Output()
	if err != nil {
		return nil, fmt.Errorf("passphrase command failed: %w", err)
	}
	return readLine(bytes.NewReader(out))
}

func readPassphraseFile(path string) ([]byte, error) {
	p := strings.TrimSpace(path)
	if p == "" {
		return nil, errors.New("empty passphrase file path")
	}
	f, err := os.Open(p)
	if err != nil {
		return nil, err
	}
	defer f.Close()
	return readLine(f)
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

func promptPassphrase() ([]byte, error) {
	return promptPassphraseWith(os.Stdin, os.Stderr, openTTY, term.IsTerminal, term.ReadPassword)
}

func promptPassphraseWith(
	stdin *os.File,
	stderr io.Writer,
	openTTYFn func() (*os.File, error),
	isTerminalFn func(int) bool,
	readPasswordFn func(int) ([]byte, error),
) ([]byte, error) {
	promptOnFD := func(fd int, out io.Writer) ([]byte, error) {
		fmt.Fprint(out, "Passphrase: ")
		b, err := readPasswordFn(fd)
		fmt.Fprintln(out)
		if err != nil {
			return nil, err
		}
		if len(b) == 0 {
			return nil, errors.New("empty passphrase")
		}
		return b, nil
	}

	if stdin != nil && isTerminalFn(int(stdin.Fd())) {
		return promptOnFD(int(stdin.Fd()), stderr)
	}

	if openTTYFn != nil {
		tty, err := openTTYFn()
		if err == nil && tty != nil {
			defer tty.Close()
			if isTerminalFn(int(tty.Fd())) {
				return promptOnFD(int(tty.Fd()), tty)
			}
		}
	}

	return nil, errors.New("no passphrase provided (set KIMEN_PASSPHRASE, use --passphrase-stdin/--passphrase-cmd, or configure `kimen config unlock ...`)")
}
