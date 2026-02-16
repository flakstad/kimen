package vault

import (
	"context"
	"crypto/rand"
	"encoding/base64"
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"time"

	"go.etcd.io/bbolt"
	"golang.org/x/crypto/argon2"
	"golang.org/x/crypto/chacha20poly1305"
)

var (
	bucketMeta    = []byte("meta")
	bucketSecrets = []byte("secrets")

	keyFormatVersion = []byte("format_version")
	keyKDFParams     = []byte("kdf_params_json")
)

type Secret struct {
	Name      string    `json:"name"`
	Type      string    `json:"type,omitempty"`
	CreatedAt time.Time `json:"created_at"`
	UpdatedAt time.Time `json:"updated_at"`
	Value     []byte    `json:"value"`
}

type Vault struct {
	path string
	db   *bbolt.DB
	dek  []byte
}

type Reader interface {
	GetSecret(ctx context.Context, name string) (Secret, error)
}

func Init(path string, passphrase []byte) (*Vault, error) {
	if _, err := os.Stat(path); err == nil {
		return nil, fmt.Errorf("vault already exists: %s", path)
	}
	if err := os.MkdirAll(filepath.Dir(path), 0o700); err != nil {
		return nil, err
	}

	db, err := bbolt.Open(path, 0o600, &bbolt.Options{Timeout: 2 * time.Second})
	if err != nil {
		return nil, err
	}

	v := &Vault{path: path, db: db}
	defer func() {
		if err != nil {
			db.Close()
		}
	}()

	kdf, kek, err := deriveKEK(passphrase)
	if err != nil {
		return nil, err
	}
	defer Burn(kek)

	dek := make([]byte, 32)
	if _, err := rand.Read(dek); err != nil {
		return nil, err
	}
	defer func() {
		if err != nil {
			Burn(dek)
		}
	}()

	wrappedDEK, err := wrapDEK(kek, dek, kdf.aad())
	if err != nil {
		return nil, err
	}

	kdf.WrappedDEK = base64.RawStdEncoding.EncodeToString(wrappedDEK)
	kdfJSON, err := json.Marshal(kdf)
	if err != nil {
		return nil, err
	}

	if err := db.Update(func(tx *bbolt.Tx) error {
		meta, err := tx.CreateBucketIfNotExists(bucketMeta)
		if err != nil {
			return err
		}
		if err := meta.Put(keyFormatVersion, []byte(formatVersionV1)); err != nil {
			return err
		}
		if err := meta.Put(keyKDFParams, kdfJSON); err != nil {
			return err
		}
		_, err = tx.CreateBucketIfNotExists(bucketSecrets)
		return err
	}); err != nil {
		return nil, err
	}

	v.dek = dek
	return v, nil
}

func Open(path string, passphrase []byte) (*Vault, error) {
	_, err := os.Stat(path)
	if err != nil {
		if errors.Is(err, os.ErrNotExist) {
			return nil, ErrVaultNotFound
		}
		return nil, err
	}

	db, err := bbolt.Open(path, 0o600, &bbolt.Options{Timeout: 2 * time.Second})
	if err != nil {
		return nil, err
	}

	var kdf KDFParams
	var format string
	if err := db.View(func(tx *bbolt.Tx) error {
		meta := tx.Bucket(bucketMeta)
		if meta == nil {
			return ErrInvalidVaultFile
		}
		f := meta.Get(keyFormatVersion)
		if f == nil {
			return ErrInvalidVaultFile
		}
		format = string(f)
		raw := meta.Get(keyKDFParams)
		if raw == nil {
			return ErrInvalidVaultFile
		}
		return json.Unmarshal(raw, &kdf)
	}); err != nil {
		db.Close()
		return nil, err
	}
	if format != formatVersionV1 {
		db.Close()
		return nil, fmt.Errorf("%w: unsupported format %q", ErrInvalidVaultFile, format)
	}

	kek, err := deriveKEKWithParams(passphrase, kdf)
	if err != nil {
		db.Close()
		return nil, err
	}
	defer Burn(kek)

	wrapped, err := base64.RawStdEncoding.DecodeString(kdf.WrappedDEK)
	if err != nil {
		db.Close()
		return nil, ErrInvalidVaultFile
	}
	dek, err := unwrapDEK(kek, wrapped, kdf.aad())
	if err != nil {
		db.Close()
		return nil, ErrWrongPassphrase
	}

	return &Vault{
		path: path,
		db:   db,
		dek:  dek,
	}, nil
}

func (v *Vault) Close() error {
	if v == nil {
		return nil
	}
	if v.dek != nil {
		Burn(v.dek)
		v.dek = nil
	}
	if v.db != nil {
		return v.db.Close()
	}
	return nil
}

func (v *Vault) PutSecret(ctx context.Context, s Secret) error {
	_ = ctx
	if s.Name == "" {
		return errors.New("secret name required")
	}
	now := time.Now().UTC()
	if s.CreatedAt.IsZero() {
		s.CreatedAt = now
	}
	s.UpdatedAt = now

	record, err := v.encryptSecretRecord(s)
	if err != nil {
		return err
	}

	return v.db.Update(func(tx *bbolt.Tx) error {
		b := tx.Bucket(bucketSecrets)
		if b == nil {
			return ErrInvalidVaultFile
		}
		return b.Put([]byte(s.Name), record)
	})
}

func (v *Vault) GetSecret(ctx context.Context, name string) (Secret, error) {
	_ = ctx
	var record []byte
	if err := v.db.View(func(tx *bbolt.Tx) error {
		b := tx.Bucket(bucketSecrets)
		if b == nil {
			return ErrInvalidVaultFile
		}
		raw := b.Get([]byte(name))
		if raw == nil {
			return ErrSecretNotFound
		}
		record = append([]byte(nil), raw...)
		return nil
	}); err != nil {
		return Secret{}, err
	}

	return v.decryptSecretRecord(name, record)
}

func (v *Vault) ListSecretNames(ctx context.Context) ([]string, error) {
	_ = ctx
	var names []string
	err := v.db.View(func(tx *bbolt.Tx) error {
		b := tx.Bucket(bucketSecrets)
		if b == nil {
			return ErrInvalidVaultFile
		}
		return b.ForEach(func(k, _ []byte) error {
			names = append(names, string(k))
			return nil
		})
	})
	return names, err
}

func (v *Vault) DeleteSecret(ctx context.Context, name string) error {
	_ = ctx
	if name == "" {
		return errors.New("secret name required")
	}
	return v.db.Update(func(tx *bbolt.Tx) error {
		b := tx.Bucket(bucketSecrets)
		if b == nil {
			return ErrInvalidVaultFile
		}
		if b.Get([]byte(name)) == nil {
			return ErrSecretNotFound
		}
		return b.Delete([]byte(name))
	})
}

func (v *Vault) RenameSecret(ctx context.Context, fromName, toName string) error {
	_ = ctx
	if fromName == "" || toName == "" {
		return errors.New("secret name required")
	}
	if fromName == toName {
		return errors.New("source and destination names must differ")
	}

	return v.db.Update(func(tx *bbolt.Tx) error {
		b := tx.Bucket(bucketSecrets)
		if b == nil {
			return ErrInvalidVaultFile
		}
		raw := b.Get([]byte(fromName))
		if raw == nil {
			return ErrSecretNotFound
		}
		if b.Get([]byte(toName)) != nil {
			return ErrSecretExists
		}

		sec, err := v.decryptSecretRecord(fromName, raw)
		if err != nil {
			return err
		}
		sec.Name = toName
		sec.UpdatedAt = time.Now().UTC()

		record, err := v.encryptSecretRecord(sec)
		if err != nil {
			return err
		}
		if err := b.Put([]byte(toName), record); err != nil {
			return err
		}
		return b.Delete([]byte(fromName))
	})
}

func (v *Vault) encryptSecretRecord(s Secret) ([]byte, error) {
	plaintext, err := json.Marshal(s)
	if err != nil {
		return nil, err
	}
	defer Burn(plaintext)

	aead, err := chacha20poly1305.NewX(v.dek)
	if err != nil {
		return nil, err
	}
	nonce := make([]byte, chacha20poly1305.NonceSizeX)
	if _, err := rand.Read(nonce); err != nil {
		return nil, err
	}
	aad := []byte("secret:" + s.Name)
	ciphertext := aead.Seal(nil, nonce, plaintext, aad)
	return append(nonce, ciphertext...), nil
}

func (v *Vault) decryptSecretRecord(name string, record []byte) (Secret, error) {
	aead, err := chacha20poly1305.NewX(v.dek)
	if err != nil {
		return Secret{}, err
	}
	if len(record) < chacha20poly1305.NonceSizeX {
		return Secret{}, ErrCorruptedRecord
	}
	nonce := record[:chacha20poly1305.NonceSizeX]
	ciphertext := record[chacha20poly1305.NonceSizeX:]
	aad := []byte("secret:" + name)
	plaintext, err := aead.Open(nil, nonce, ciphertext, aad)
	if err != nil {
		return Secret{}, ErrCorruptedRecord
	}
	defer Burn(plaintext)

	var s Secret
	if err := json.Unmarshal(plaintext, &s); err != nil {
		return Secret{}, ErrCorruptedRecord
	}
	if s.Name != name {
		return Secret{}, ErrCorruptedRecord
	}
	return s, nil
}

type kdfDerived struct {
	KDFParams
	salt []byte
}

func deriveKEK(passphrase []byte) (kdfDerived, []byte, error) {
	salt := make([]byte, 16)
	if _, err := rand.Read(salt); err != nil {
		return kdfDerived{}, nil, err
	}
	params := KDFParams{
		Name:      "argon2id",
		SaltB64:   base64.RawStdEncoding.EncodeToString(salt),
		Time:      3,
		MemoryKiB: 64 * 1024,
		Threads:   4,
		KeyLen:    32,
	}
	kek := argon2.IDKey(passphrase, salt, params.Time, params.MemoryKiB, params.Threads, params.KeyLen)
	return kdfDerived{KDFParams: params, salt: salt}, kek, nil
}

func deriveKEKWithParams(passphrase []byte, params KDFParams) ([]byte, error) {
	if params.Name != "argon2id" {
		return nil, fmt.Errorf("%w: unsupported kdf %q", ErrInvalidVaultFile, params.Name)
	}
	salt, err := base64.RawStdEncoding.DecodeString(params.SaltB64)
	if err != nil || len(salt) < 8 {
		return nil, ErrInvalidVaultFile
	}
	if params.KeyLen != 32 {
		return nil, ErrInvalidVaultFile
	}
	if params.Time == 0 || params.MemoryKiB == 0 || params.Threads == 0 {
		return nil, ErrInvalidVaultFile
	}
	return argon2.IDKey(passphrase, salt, params.Time, params.MemoryKiB, params.Threads, params.KeyLen), nil
}

func (k kdfDerived) aad() []byte {
	// Bind wrapped DEK to the KDF parameters.
	// Note: WrappedDEK itself is set later and excluded here.
	a := fmt.Sprintf("%s|%s|%s|%d|%d|%d|%d", formatVersionV1, k.Name, k.SaltB64, k.Time, k.MemoryKiB, k.Threads, k.KeyLen)
	return []byte(a)
}

func (k KDFParams) aad() []byte {
	a := fmt.Sprintf("%s|%s|%s|%d|%d|%d|%d", formatVersionV1, k.Name, k.SaltB64, k.Time, k.MemoryKiB, k.Threads, k.KeyLen)
	return []byte(a)
}

func wrapDEK(kek, dek, aad []byte) ([]byte, error) {
	aead, err := chacha20poly1305.NewX(kek)
	if err != nil {
		return nil, err
	}
	nonce := make([]byte, chacha20poly1305.NonceSizeX)
	if _, err := rand.Read(nonce); err != nil {
		return nil, err
	}
	ct := aead.Seal(nil, nonce, dek, aad)
	return append(nonce, ct...), nil
}

func unwrapDEK(kek, wrapped, aad []byte) ([]byte, error) {
	aead, err := chacha20poly1305.NewX(kek)
	if err != nil {
		return nil, err
	}
	if len(wrapped) < chacha20poly1305.NonceSizeX {
		return nil, ErrInvalidVaultFile
	}
	nonce := wrapped[:chacha20poly1305.NonceSizeX]
	ct := wrapped[chacha20poly1305.NonceSizeX:]
	dek, err := aead.Open(nil, nonce, ct, aad)
	if err != nil {
		return nil, err
	}
	if len(dek) != 32 {
		Burn(dek)
		return nil, ErrInvalidVaultFile
	}
	return dek, nil
}
