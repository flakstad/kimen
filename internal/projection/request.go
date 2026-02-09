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
