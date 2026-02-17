package cli

import (
	"errors"
	"os"
	"path/filepath"
	"regexp"
	"strings"
	"testing"
)

var (
	reasonCodePattern    = regexp.MustCompile(`^[a-z0-9]+(?:_[a-z0-9]+)*$`)
	reasonLiteralPattern = regexp.MustCompile(`Reason:\s*"[a-z0-9_]+"`)
)

func TestReasonCodes_UniqueAndSnakeCase(t *testing.T) {
	codes := allReasonCodes()
	if len(codes) == 0 {
		t.Fatal("expected non-empty reason catalog")
	}

	seen := make(map[string]struct{}, len(codes))
	for _, code := range codes {
		trimmed := strings.TrimSpace(code)
		if trimmed == "" {
			t.Fatal("reason code must not be empty")
		}
		if !reasonCodePattern.MatchString(trimmed) {
			t.Fatalf("reason code %q is not snake_case", trimmed)
		}
		if _, ok := seen[trimmed]; ok {
			t.Fatalf("duplicate reason code: %q", trimmed)
		}
		seen[trimmed] = struct{}{}
	}
}

func TestReasonCodes_NoLiteralReasonAssignments(t *testing.T) {
	files, err := filepath.Glob("*.go")
	if err != nil {
		t.Fatalf("glob go files: %v", err)
	}
	for _, file := range files {
		if strings.HasSuffix(file, "_test.go") {
			continue
		}
		data, err := os.ReadFile(file)
		if err != nil {
			t.Fatalf("read %s: %v", file, err)
		}
		if match := reasonLiteralPattern.FindString(string(data)); match != "" {
			t.Fatalf("found literal reason assignment in %s: %s", file, match)
		}
	}
}

func TestSyncErrorReason_UsesCatalog(t *testing.T) {
	tests := []struct {
		msg  string
		want string
	}{
		{msg: "--stale-threshold must be >= 0", want: reasonInvalidStaleThreshold},
		{msg: "remote identity is not configured (set --identity on `remote add`)", want: reasonRemoteIdentityMissing},
		{msg: "keys are not current conflict keys: api_key", want: reasonResolveKeysNotConflicts},
		{msg: "some unknown error", want: reasonSyncFailed},
	}

	for _, tc := range tests {
		if got := syncErrorReason(errors.New(tc.msg)); got != tc.want {
			t.Fatalf("syncErrorReason(%q) = %q, want %q", tc.msg, got, tc.want)
		}
	}
}
