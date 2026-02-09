package vault

import (
	"encoding/json"
	"errors"
	"fmt"
	"os"

	bolt "go.etcd.io/bbolt"
)

const (
	formatVersionV1 = "kimen-vault/v1"
)

type KDFParams struct {
	Name       string `json:"name"`
	SaltB64    string `json:"salt_b64"`
	Time       uint32 `json:"time"`
	MemoryKiB  uint32 `json:"memory_kib"`
	Threads    uint8  `json:"threads"`
	KeyLen     uint32 `json:"key_len"`
	WrappedDEK string `json:"wrapped_dek_b64"`
}

type Metadata struct {
	FormatVersion string `json:"format_version"`
	KDF           string `json:"kdf"`
}

func ReadMetadata(path string) (Metadata, error) {
	_, err := os.Stat(path)
	if err != nil {
		if errors.Is(err, os.ErrNotExist) {
			return Metadata{}, ErrVaultNotFound
		}
		return Metadata{}, err
	}

	db, err := bolt.Open(path, 0o600, &bolt.Options{ReadOnly: true})
	if err != nil {
		return Metadata{}, err
	}
	defer db.Close()

	var meta Metadata
	err = db.View(func(tx *bolt.Tx) error {
		b := tx.Bucket(bucketMeta)
		if b == nil {
			return ErrInvalidVaultFile
		}
		v := b.Get(keyFormatVersion)
		if v == nil {
			return ErrInvalidVaultFile
		}
		meta.FormatVersion = string(v)
		kdf := b.Get(keyKDFParams)
		if kdf == nil {
			return ErrInvalidVaultFile
		}
		var params KDFParams
		if err := json.Unmarshal(kdf, &params); err != nil {
			return fmt.Errorf("%w: %v", ErrInvalidVaultFile, err)
		}
		meta.KDF = params.Name
		return nil
	})
	return meta, err
}
