package vault

import (
	"context"
	"errors"
	"os"
	"path/filepath"
	"testing"

	bolt "go.etcd.io/bbolt"
)

func TestInitOpenPutGetList(t *testing.T) {
	t.Parallel()

	dir := t.TempDir()
	path := filepath.Join(dir, "vault.db")
	pass := []byte("correct horse battery staple")

	v, err := Init(path, pass)
	if err != nil {
		t.Fatalf("Init: %v", err)
	}
	if err := v.Close(); err != nil {
		t.Fatalf("Close: %v", err)
	}

	v, err = Open(path, pass)
	if err != nil {
		t.Fatalf("Open: %v", err)
	}
	defer v.Close()

	if err := v.PutSecret(context.Background(), Secret{Name: "api_key", Type: "string", Value: []byte("shh")}); err != nil {
		t.Fatalf("PutSecret: %v", err)
	}

	sec, err := v.GetSecret(context.Background(), "api_key")
	if err != nil {
		t.Fatalf("GetSecret: %v", err)
	}
	if string(sec.Value) != "shh" {
		t.Fatalf("unexpected value: %q", string(sec.Value))
	}
	Burn(sec.Value)

	names, err := v.ListSecretNames(context.Background())
	if err != nil {
		t.Fatalf("ListSecretNames: %v", err)
	}
	if len(names) != 1 || names[0] != "api_key" {
		t.Fatalf("unexpected names: %#v", names)
	}
}

func TestOpenWrongPassphrase(t *testing.T) {
	t.Parallel()

	dir := t.TempDir()
	path := filepath.Join(dir, "vault.db")
	pass := []byte("pass-1")

	v, err := Init(path, pass)
	if err != nil {
		t.Fatalf("Init: %v", err)
	}
	_ = v.Close()

	_, err = Open(path, []byte("pass-2"))
	if err == nil {
		t.Fatalf("expected error")
	}
	if err != ErrWrongPassphrase {
		t.Fatalf("expected ErrWrongPassphrase, got %v", err)
	}
}

func TestReadMetadata(t *testing.T) {
	t.Parallel()

	dir := t.TempDir()
	path := filepath.Join(dir, "vault.db")
	pass := []byte("pass")

	v, err := Init(path, pass)
	if err != nil {
		t.Fatalf("Init: %v", err)
	}
	_ = v.Close()

	meta, err := ReadMetadata(path)
	if err != nil {
		t.Fatalf("ReadMetadata: %v", err)
	}
	if meta.FormatVersion != formatVersionV1 {
		t.Fatalf("unexpected format version: %q", meta.FormatVersion)
	}
	if meta.KDF != "argon2id" {
		t.Fatalf("unexpected kdf: %q", meta.KDF)
	}
}

func TestTamperSecretRecord(t *testing.T) {
	t.Parallel()

	dir := t.TempDir()
	path := filepath.Join(dir, "vault.db")
	pass := []byte("pass")

	v, err := Init(path, pass)
	if err != nil {
		t.Fatalf("Init: %v", err)
	}
	defer v.Close()

	if err := v.PutSecret(context.Background(), Secret{Name: "x", Value: []byte("y")}); err != nil {
		t.Fatalf("PutSecret: %v", err)
	}
	if err := v.Close(); err != nil {
		t.Fatalf("Close: %v", err)
	}

	db, err := bolt.Open(path, 0o600, nil)
	if err != nil {
		t.Fatalf("bolt.Open: %v", err)
	}
	if err := db.Update(func(tx *bolt.Tx) error {
		b := tx.Bucket(bucketSecrets)
		raw := b.Get([]byte("x"))
		cp := append([]byte(nil), raw...)
		cp[len(cp)-1] ^= 0xff
		return b.Put([]byte("x"), cp)
	}); err != nil {
		t.Fatalf("tamper: %v", err)
	}
	_ = db.Close()

	v, err = Open(path, pass)
	if err != nil {
		t.Fatalf("Open: %v", err)
	}
	defer v.Close()

	_, err = v.GetSecret(context.Background(), "x")
	if err == nil {
		t.Fatalf("expected error")
	}
	if err != ErrCorruptedRecord {
		t.Fatalf("expected ErrCorruptedRecord, got %v", err)
	}

	// Ensure the vault file is still present; corruption is scoped to a record.
	if _, err := os.Stat(path); err != nil {
		t.Fatalf("stat vault: %v", err)
	}
}

func TestDeleteSecret(t *testing.T) {
	t.Parallel()

	dir := t.TempDir()
	path := filepath.Join(dir, "vault.db")
	pass := []byte("pass")

	v, err := Init(path, pass)
	if err != nil {
		t.Fatalf("Init: %v", err)
	}
	defer v.Close()

	if err := v.PutSecret(context.Background(), Secret{Name: "api_key", Value: []byte("shh")}); err != nil {
		t.Fatalf("PutSecret: %v", err)
	}

	if err := v.DeleteSecret(context.Background(), "api_key"); err != nil {
		t.Fatalf("DeleteSecret: %v", err)
	}

	_, err = v.GetSecret(context.Background(), "api_key")
	if !errors.Is(err, ErrSecretNotFound) {
		t.Fatalf("expected ErrSecretNotFound after delete, got %v", err)
	}

	err = v.DeleteSecret(context.Background(), "api_key")
	if !errors.Is(err, ErrSecretNotFound) {
		t.Fatalf("expected ErrSecretNotFound on second delete, got %v", err)
	}
}

func TestRenameSecret(t *testing.T) {
	t.Parallel()

	dir := t.TempDir()
	path := filepath.Join(dir, "vault.db")
	pass := []byte("pass")

	v, err := Init(path, pass)
	if err != nil {
		t.Fatalf("Init: %v", err)
	}
	defer v.Close()

	if err := v.PutSecret(context.Background(), Secret{Name: "old", Type: "string", Value: []byte("value")}); err != nil {
		t.Fatalf("PutSecret(old): %v", err)
	}

	before, err := v.GetSecret(context.Background(), "old")
	if err != nil {
		t.Fatalf("GetSecret(old): %v", err)
	}

	if err := v.RenameSecret(context.Background(), "old", "new"); err != nil {
		t.Fatalf("RenameSecret: %v", err)
	}

	_, err = v.GetSecret(context.Background(), "old")
	if !errors.Is(err, ErrSecretNotFound) {
		t.Fatalf("expected old name to be removed, got %v", err)
	}

	after, err := v.GetSecret(context.Background(), "new")
	if err != nil {
		t.Fatalf("GetSecret(new): %v", err)
	}
	if string(after.Value) != "value" {
		t.Fatalf("unexpected value: %q", after.Value)
	}
	if after.Type != "string" {
		t.Fatalf("unexpected type: %q", after.Type)
	}
	if !after.CreatedAt.Equal(before.CreatedAt) {
		t.Fatalf("created_at changed during rename")
	}
	if after.UpdatedAt.Before(before.UpdatedAt) {
		t.Fatalf("updated_at moved backwards")
	}
	Burn(before.Value)
	Burn(after.Value)

	if err := v.PutSecret(context.Background(), Secret{Name: "existing", Value: []byte("x")}); err != nil {
		t.Fatalf("PutSecret(existing): %v", err)
	}
	err = v.RenameSecret(context.Background(), "new", "existing")
	if !errors.Is(err, ErrSecretExists) {
		t.Fatalf("expected ErrSecretExists, got %v", err)
	}

	err = v.RenameSecret(context.Background(), "missing", "other")
	if !errors.Is(err, ErrSecretNotFound) {
		t.Fatalf("expected ErrSecretNotFound, got %v", err)
	}
}
