package projection

import (
	"context"
	"testing"
)

func TestResolveValue_DefaultSourceIsSecret(t *testing.T) {
	t.Parallel()

	v := fakeVault{m: map[string][]byte{
		"api_key": []byte("shh"),
	}}
	got, err := ResolveValue(context.Background(), v, "api_key")
	if err != nil {
		t.Fatalf("ResolveValue: %v", err)
	}
	if string(got) != "shh" {
		t.Fatalf("unexpected value: %q", string(got))
	}
}

func TestResolveValue_SecretPrefix(t *testing.T) {
	t.Parallel()

	v := fakeVault{m: map[string][]byte{
		"api_key": []byte("shh"),
	}}
	got, err := ResolveValue(context.Background(), v, "secret:api_key")
	if err != nil {
		t.Fatalf("ResolveValue: %v", err)
	}
	if string(got) != "shh" {
		t.Fatalf("unexpected value: %q", string(got))
	}
}

func TestResolveValue_ConstPrefix(t *testing.T) {
	t.Parallel()

	v := fakeVault{m: map[string][]byte{}}
	got, err := ResolveValue(context.Background(), v, "const:5050")
	if err != nil {
		t.Fatalf("ResolveValue: %v", err)
	}
	if string(got) != "5050" {
		t.Fatalf("unexpected value: %q", string(got))
	}
}

func TestResolveValue_ConstPrefixAllowsEmptyLiteral(t *testing.T) {
	t.Parallel()

	v := fakeVault{m: map[string][]byte{}}
	got, err := ResolveValue(context.Background(), v, "const:")
	if err != nil {
		t.Fatalf("ResolveValue: %v", err)
	}
	if string(got) != "" {
		t.Fatalf("expected empty literal, got: %q", string(got))
	}
}

func TestResolveValue_SecretPrefixRequiresName(t *testing.T) {
	t.Parallel()

	v := fakeVault{m: map[string][]byte{}}
	if _, err := ResolveValue(context.Background(), v, "secret:"); err == nil {
		t.Fatalf("expected error")
	}
}
