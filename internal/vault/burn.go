package vault

// Burn overwrites b with zeroes (best-effort).
func Burn(b []byte) {
	for i := range b {
		b[i] = 0
	}
}
