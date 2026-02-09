package bundle

import (
	"bytes"
	"os"
	"path/filepath"
	"testing"

	"filippo.io/age"
)

func TestSealOpenRoundTrip(t *testing.T) {
	t.Parallel()

	dir := t.TempDir()
	vaultPath := filepath.Join(dir, "vault.db")
	bundlePath := filepath.Join(dir, "vault.age")
	outPath := filepath.Join(dir, "vault.out.db")

	input := []byte("vault bytes")
	if err := os.WriteFile(vaultPath, input, 0o600); err != nil {
		t.Fatalf("WriteFile: %v", err)
	}

	id, err := age.GenerateX25519Identity()
	if err != nil {
		t.Fatalf("GenerateX25519Identity: %v", err)
	}

	if err := SealVaultFile(vaultPath, bundlePath, []string{id.Recipient().String()}); err != nil {
		t.Fatalf("SealVaultFile: %v", err)
	}

	if err := OpenToVaultFile(bundlePath, outPath, id, false); err != nil {
		t.Fatalf("OpenToVaultFile: %v", err)
	}

	got, err := os.ReadFile(outPath)
	if err != nil {
		t.Fatalf("ReadFile: %v", err)
	}
	if !bytes.Equal(got, input) {
		t.Fatalf("mismatch: got=%q want=%q", string(got), string(input))
	}
}

func TestOpenOverwriteProtection(t *testing.T) {
	t.Parallel()

	dir := t.TempDir()
	bundlePath := filepath.Join(dir, "vault.age")
	outPath := filepath.Join(dir, "vault.db")

	id, err := age.GenerateX25519Identity()
	if err != nil {
		t.Fatalf("GenerateX25519Identity: %v", err)
	}
	if err := os.WriteFile(bundlePath, []byte("not a bundle"), 0o600); err != nil {
		t.Fatalf("WriteFile: %v", err)
	}
	if err := os.WriteFile(outPath, []byte("existing"), 0o600); err != nil {
		t.Fatalf("WriteFile: %v", err)
	}

	if err := OpenToVaultFile(bundlePath, outPath, id, false); err == nil {
		t.Fatalf("expected error")
	}
}
