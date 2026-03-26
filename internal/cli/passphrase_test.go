package cli

import (
	"bytes"
	"errors"
	"os"
	"testing"

	"kimen/internal/vault"
)

func TestPromptPassphraseWith_UsesStdinTTY(t *testing.T) {
	stdinR, stdinW, err := os.Pipe()
	if err != nil {
		t.Fatalf("os.Pipe stdin: %v", err)
	}
	defer stdinR.Close()
	defer stdinW.Close()

	stderr := &bytes.Buffer{}
	openTTYCalled := false
	isTerminal := func(fd int) bool {
		return fd == int(stdinR.Fd())
	}
	readPassword := func(fd int) ([]byte, error) {
		if fd != int(stdinR.Fd()) {
			t.Fatalf("expected stdin fd %d, got %d", int(stdinR.Fd()), fd)
		}
		return []byte("stdin-pass"), nil
	}

	got, err := promptPassphraseWith(
		stdinR,
		stderr,
		func() (*os.File, error) {
			openTTYCalled = true
			return nil, errors.New("should not open tty")
		},
		isTerminal,
		readPassword,
	)
	if err != nil {
		t.Fatalf("promptPassphraseWith: %v", err)
	}
	if string(got) != "stdin-pass" {
		t.Fatalf("expected stdin-pass, got %q", string(got))
	}
	if openTTYCalled {
		t.Fatalf("expected openTTY not to be called")
	}
	if stderr.String() != "Passphrase: \n" {
		t.Fatalf("unexpected stderr prompt output: %q", stderr.String())
	}
}

func TestPromptPassphraseWith_FallsBackToTTY(t *testing.T) {
	stdinR, stdinW, err := os.Pipe()
	if err != nil {
		t.Fatalf("os.Pipe stdin: %v", err)
	}
	defer stdinR.Close()
	defer stdinW.Close()

	ttyR, ttyW, err := os.Pipe()
	if err != nil {
		t.Fatalf("os.Pipe tty: %v", err)
	}
	defer ttyR.Close()
	defer ttyW.Close()

	isTerminal := func(fd int) bool {
		return fd == int(ttyR.Fd())
	}
	readPassword := func(fd int) ([]byte, error) {
		if fd != int(ttyR.Fd()) {
			t.Fatalf("expected tty fd %d, got %d", int(ttyR.Fd()), fd)
		}
		return []byte("tty-pass"), nil
	}

	got, err := promptPassphraseWith(
		stdinR,
		&bytes.Buffer{},
		func() (*os.File, error) {
			return ttyR, nil
		},
		isTerminal,
		readPassword,
	)
	if err != nil {
		t.Fatalf("promptPassphraseWith: %v", err)
	}
	if string(got) != "tty-pass" {
		t.Fatalf("expected tty-pass, got %q", string(got))
	}
}

func TestPromptPassphraseWith_NoTTYAvailable(t *testing.T) {
	stdinR, stdinW, err := os.Pipe()
	if err != nil {
		t.Fatalf("os.Pipe stdin: %v", err)
	}
	defer stdinR.Close()
	defer stdinW.Close()

	_, err = promptPassphraseWith(
		stdinR,
		&bytes.Buffer{},
		func() (*os.File, error) {
			return nil, errors.New("no tty")
		},
		func(_ int) bool { return false },
		func(_ int) ([]byte, error) { return []byte("unused"), nil },
	)
	if err == nil {
		t.Fatalf("expected error")
	}
	if err.Error() != "no passphrase provided (set KIMEN_PASSPHRASE, use --passphrase-stdin/--passphrase-cmd, or configure `kimen config unlock ...`)" {
		t.Fatalf("unexpected error: %v", err)
	}
}

func TestWithResolvedPassphraseRetry_RetriesPromptWrongPassphraseOnce(t *testing.T) {
	t.Parallel()

	attempts := 0
	pp, err := withResolvedPassphraseRetry(
		func() ([]byte, passphraseSource, error) {
			attempts++
			if attempts == 1 {
				return []byte("wrong"), passphraseSourcePrompt, nil
			}
			return []byte("right"), passphraseSourcePrompt, nil
		},
		func(pp []byte) error {
			if string(pp) == "wrong" {
				return vault.ErrWrongPassphrase
			}
			return nil
		},
	)
	if err != nil {
		t.Fatalf("withResolvedPassphraseRetry: %v", err)
	}
	if string(pp) != "right" {
		t.Fatalf("expected right passphrase, got %q", string(pp))
	}
	if attempts != 2 {
		t.Fatalf("expected 2 attempts, got %d", attempts)
	}
}

func TestWithResolvedPassphraseRetry_DoesNotRetryNonPromptSource(t *testing.T) {
	t.Parallel()

	attempts := 0
	_, err := withResolvedPassphraseRetry(
		func() ([]byte, passphraseSource, error) {
			attempts++
			return []byte("wrong"), passphraseSourceCmd, nil
		},
		func(pp []byte) error {
			return vault.ErrWrongPassphrase
		},
	)
	if !errors.Is(err, vault.ErrWrongPassphrase) {
		t.Fatalf("expected wrong passphrase error, got %v", err)
	}
	if attempts != 1 {
		t.Fatalf("expected 1 attempt, got %d", attempts)
	}
}
