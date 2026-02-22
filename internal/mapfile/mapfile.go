package mapfile

import (
	"bufio"
	"errors"
	"fmt"
	"io"
	"os"
	"path/filepath"
	"regexp"
	"strings"

	"kimen/internal/projection"
)

// Format (v0) is line-oriented and comment-friendly.
//
// Examples:
//
//	env LINJE_API_TOKEN=linje.prod.api_token
//	env PORT=const:5050
//	file key.json=gcp.sa_key_json
//	stdin exec:some-command
//	envpath GOOGLE_APPLICATION_CREDENTIALS=key.json
//
// Blank lines and lines starting with # are ignored.
//
// Rules:
// - "env" and "file" use the same mapping shape as flags: <lhs>=<value>.
// - "stdin" sets the command stdin source for run projections.
// - "envpath" maps an env var to a file path under KIMEN_FILES_DIR. It does not itself create the file mapping.
type Map struct {
	Request  projection.Request
	EnvPaths []projection.EnvPathMapping
}

func ParseFile(path string) (Map, error) {
	f, err := os.Open(path)
	if err != nil {
		return Map{}, err
	}
	defer f.Close()

	m, err := Parse(f)
	if err != nil {
		return Map{}, err
	}
	return m, nil
}

func Parse(r io.Reader) (Map, error) {
	var envMappings []string
	var fileMappings []string
	var envPathMappings []string
	var stdin string

	sc := bufio.NewScanner(r)
	lineNo := 0
	for sc.Scan() {
		lineNo++
		line := strings.TrimSpace(sc.Text())
		if line == "" || strings.HasPrefix(line, "#") {
			continue
		}

		// Strip inline comments: "…  # comment"
		if i := strings.Index(line, "#"); i > 0 {
			if strings.TrimSpace(line[:i]) != "" && (line[i-1] == ' ' || line[i-1] == '\t') {
				line = strings.TrimSpace(line[:i])
			}
		}

		kind, rest := splitKindAndRest(line)
		if kind == "" || rest == "" {
			return Map{}, fmt.Errorf("invalid map line %d: expected '<kind> <mapping>'", lineNo)
		}
		switch kind {
		case "env":
			envMappings = append(envMappings, rest)
		case "file":
			fileMappings = append(fileMappings, rest)
		case "stdin":
			if stdin != "" {
				return Map{}, fmt.Errorf("invalid map line %d: multiple stdin entries", lineNo)
			}
			stdin = rest
		case "envpath":
			envPathMappings = append(envPathMappings, rest)
		default:
			return Map{}, fmt.Errorf("invalid map line %d: unknown kind %q", lineNo, kind)
		}
	}
	if err := sc.Err(); err != nil {
		return Map{}, err
	}

	req, err := projection.ParseRequest(envMappings, fileMappings, stdin)
	if err != nil {
		return Map{}, err
	}
	envPaths, err := projection.ParseEnvPathMappings(envPathMappings)
	if err != nil {
		return Map{}, err
	}
	return Map{Request: req, EnvPaths: envPaths}, nil
}

func splitKindAndRest(line string) (kind, rest string) {
	for i, r := range line {
		if r == ' ' || r == '\t' {
			kind = line[:i]
			rest = strings.TrimSpace(line[i:])
			return kind, rest
		}
	}
	return "", ""
}

// ResolveProfile looks up a profile name to a map file path.
//
// Search order:
// - $KIMEN_PROFILE_DIR/<name>.kmap
// - ./.kimen/profiles/<name>.kmap (relative to cwd)
// - <UserConfigDir>/kimen/profiles/<name>.kmap
const envProfileDir = "KIMEN_PROFILE_DIR"

var profileNameRE = regexp.MustCompile(`^[A-Za-z0-9_.-]+$`)

func ResolveProfile(name string) (string, error) {
	name = strings.TrimSpace(name)
	if name == "" {
		return "", errors.New("empty profile name")
	}
	if !profileNameRE.MatchString(name) {
		return "", fmt.Errorf("invalid profile name %q (allowed: letters, digits, ., _, -)", name)
	}
	filename := name + ".kmap"

	if dir := os.Getenv(envProfileDir); dir != "" {
		p := filepath.Join(dir, filename)
		if _, err := os.Stat(p); err == nil {
			return p, nil
		}
	}

	p := filepath.Join(".kimen", "profiles", filename)
	if _, err := os.Stat(p); err == nil {
		return p, nil
	}

	cfgDir, err := os.UserConfigDir()
	if err != nil {
		return "", err
	}
	if cfgDir != "" {
		p = filepath.Join(cfgDir, "kimen", "profiles", filename)
		if _, err := os.Stat(p); err == nil {
			return p, nil
		}
	}

	return "", fmt.Errorf("profile %q not found (set %s or create .kimen/profiles/%s)", name, envProfileDir, filename)
}
