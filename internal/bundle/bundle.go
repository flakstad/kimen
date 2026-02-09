package bundle

import (
	"bufio"
	"errors"
	"fmt"
	"io"
	"os"
	"path/filepath"

	"filippo.io/age"
)

func SealVaultFile(vaultPath, outPath string, recipientStrings []string) error {
	recipients, err := parseRecipients(recipientStrings)
	if err != nil {
		return err
	}

	in, err := os.Open(vaultPath)
	if err != nil {
		return err
	}
	defer in.Close()

	if err := os.MkdirAll(filepath.Dir(outPath), 0o700); err != nil {
		return err
	}
	out, err := os.OpenFile(outPath, os.O_CREATE|os.O_TRUNC|os.O_WRONLY, 0o600)
	if err != nil {
		return err
	}
	defer out.Close()

	w, err := age.Encrypt(out, recipients...)
	if err != nil {
		return err
	}
	if _, err := io.Copy(w, in); err != nil {
		_ = w.Close()
		return err
	}
	return w.Close()
}

func OpenToVaultFile(bundlePath, outVaultPath string, id age.Identity, overwrite bool) error {
	if !overwrite {
		if _, err := os.Stat(outVaultPath); err == nil {
			return fmt.Errorf("refusing to overwrite existing vault: %s", outVaultPath)
		}
	}

	in, err := os.Open(bundlePath)
	if err != nil {
		return err
	}
	defer in.Close()

	r, err := age.Decrypt(in, id)
	if err != nil {
		return err
	}

	if err := os.MkdirAll(filepath.Dir(outVaultPath), 0o700); err != nil {
		return err
	}
	tmp := outVaultPath + ".tmp"
	out, err := os.OpenFile(tmp, os.O_CREATE|os.O_TRUNC|os.O_WRONLY, 0o600)
	if err != nil {
		return err
	}
	if _, err := io.Copy(out, r); err != nil {
		out.Close()
		_ = os.Remove(tmp)
		return err
	}
	if err := out.Close(); err != nil {
		_ = os.Remove(tmp)
		return err
	}
	if err := os.Chmod(tmp, 0o600); err != nil {
		_ = os.Remove(tmp)
		return err
	}
	return os.Rename(tmp, outVaultPath)
}

func LoadIdentity(identityFile string, fromStdin bool, stdin io.Reader) (age.Identity, error) {
	var r io.Reader
	if fromStdin {
		r = stdin
	} else {
		if identityFile == "" {
			return nil, errors.New("missing identity file")
		}
		f, err := os.Open(identityFile)
		if err != nil {
			return nil, err
		}
		defer f.Close()
		r = f
	}

	ids, err := age.ParseIdentities(bufio.NewReader(r))
	if err != nil {
		return nil, err
	}
	if len(ids) == 0 {
		return nil, errors.New("no identities found")
	}
	if len(ids) > 1 {
		// Keep it simple for now; the caller can provide a file with a single identity.
		return nil, errors.New("multiple identities found; provide exactly one")
	}
	return ids[0], nil
}

func parseRecipients(recipientStrings []string) ([]age.Recipient, error) {
	var recipients []age.Recipient
	for _, s := range recipientStrings {
		r, err := age.ParseX25519Recipient(s)
		if err != nil {
			return nil, fmt.Errorf("invalid recipient %q: %w", s, err)
		}
		recipients = append(recipients, r)
	}
	return recipients, nil
}
