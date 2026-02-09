package cli

import (
	"bufio"
	"errors"
	"fmt"
	"io"
	"os"
	"strings"

	"golang.org/x/term"
)

const envPassphrase = "KIMEN_PASSPHRASE"

type passphraseSource struct {
	fromEnv   bool
	fromStdin bool
}

func (s passphraseSource) resolve() ([]byte, error) {
	if p := os.Getenv(envPassphrase); p != "" {
		return []byte(p), nil
	}
	if s.fromStdin {
		return readLine(os.Stdin)
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
	return nil, errors.New("no passphrase provided (set KIMEN_PASSPHRASE or use --passphrase-stdin)")
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
