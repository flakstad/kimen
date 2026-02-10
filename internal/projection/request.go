package projection

import (
	"errors"
	"fmt"
	"path"
	"regexp"
	"strings"
)

var envVarRE = regexp.MustCompile(`^[A-Za-z_][A-Za-z0-9_]*$`)

type Request struct {
	Envs  []EnvMapping
	Files []FileMapping
}

type EnvMapping struct {
	Var  string
	Name string
}

type FileMapping struct {
	RelPath string
	Name    string
}

type EnvPathMapping struct {
	Var     string
	RelPath string
}

func ParseRequest(envMappings, fileMappings []string) (Request, error) {
	var req Request
	for _, m := range envMappings {
		varName, secretName, ok := strings.Cut(m, "=")
		if !ok {
			return Request{}, fmt.Errorf("invalid --env mapping %q (expected VAR=secretName)", m)
		}
		varName = strings.TrimSpace(varName)
		secretName = strings.TrimSpace(secretName)
		if !envVarRE.MatchString(varName) {
			return Request{}, fmt.Errorf("invalid env var name %q", varName)
		}
		if secretName == "" {
			return Request{}, errors.New("empty secret name in --env mapping")
		}
		req.Envs = append(req.Envs, EnvMapping{Var: varName, Name: secretName})
	}

	for _, m := range fileMappings {
		rel, secretName, ok := strings.Cut(m, "=")
		if !ok {
			return Request{}, fmt.Errorf("invalid --file mapping %q (expected relpath=secretName)", m)
		}
		rel = strings.TrimSpace(rel)
		secretName = strings.TrimSpace(secretName)
		if rel == "" {
			return Request{}, errors.New("empty path in --file mapping")
		}
		if secretName == "" {
			return Request{}, errors.New("empty secret name in --file mapping")
		}
		clean := path.Clean(rel)
		if clean == "." || clean == "/" || strings.HasPrefix(clean, "../") || clean == ".." || strings.HasPrefix(clean, "/") {
			return Request{}, fmt.Errorf("invalid relative path %q", rel)
		}
		req.Files = append(req.Files, FileMapping{RelPath: clean, Name: secretName})
	}

	return req, nil
}

func ParseEnvPathMappings(mappings []string) ([]EnvPathMapping, error) {
	var out []EnvPathMapping
	for _, m := range mappings {
		varName, rel, ok := strings.Cut(m, "=")
		if !ok {
			return nil, fmt.Errorf("invalid envpath mapping %q (expected VAR=relpath)", m)
		}
		varName = strings.TrimSpace(varName)
		rel = strings.TrimSpace(rel)
		if !envVarRE.MatchString(varName) {
			return nil, fmt.Errorf("invalid env var name %q", varName)
		}
		if rel == "" {
			return nil, errors.New("empty relpath in envpath mapping")
		}
		clean := path.Clean(rel)
		if clean == "." || clean == "/" || strings.HasPrefix(clean, "../") || clean == ".." || strings.HasPrefix(clean, "/") {
			return nil, fmt.Errorf("invalid relative path %q", rel)
		}
		out = append(out, EnvPathMapping{Var: varName, RelPath: clean})
	}
	return out, nil
}
