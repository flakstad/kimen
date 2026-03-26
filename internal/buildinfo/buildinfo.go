package buildinfo

import (
	"runtime/debug"
	"strings"
)

// Build-time values set via -ldflags -X.
// Defaults keep local go run/go test usable.
var (
	Version = "dev"
	Commit  = "none"
	Date    = "unknown"
)

// DisplayVersion returns a user-facing version.
// Numeric versions are normalized with a leading "v".
func DisplayVersion() string {
	v := strings.TrimSpace(Version)

	if v == "" || v == "dev" {
		if bi, ok := debug.ReadBuildInfo(); ok {
			mv := strings.TrimSpace(bi.Main.Version)
			if mv != "" && mv != "(devel)" {
				v = mv
			}
		}
	}

	v = strings.TrimSpace(v)
	if v == "" || v == "dev" || v == "(devel)" {
		return "dev"
	}
	if strings.HasPrefix(v, "v") {
		return v
	}
	if v[0] >= '0' && v[0] <= '9' {
		return "v" + v
	}
	return v
}
