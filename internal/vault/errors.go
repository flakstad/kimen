package vault

import "errors"

var (
	ErrVaultNotFound    = errors.New("vault not found")
	ErrWrongPassphrase  = errors.New("wrong passphrase or corrupted vault")
	ErrSecretNotFound   = errors.New("secret not found")
	ErrCorruptedRecord  = errors.New("corrupted secret record")
	ErrInvalidVaultFile = errors.New("invalid vault file")
)
