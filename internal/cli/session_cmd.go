package cli

import (
	"encoding/base64"
	"encoding/json"
	"errors"
	"fmt"
	"net"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"sync"
	"time"

	"github.com/spf13/cobra"

	"kimen/internal/exitcode"
	"kimen/internal/vault"
)

const (
	envSessionDir     = "KIMEN_SESSION_DIR"
	envXDGRuntimeDir  = "XDG_RUNTIME_DIR"
	defaultSessionTTL = 15 * time.Minute
)

type sessionRequest struct {
	Op            string `json:"op"`
	VaultPath     string `json:"vault_path,omitempty"`
	PassphraseB64 string `json:"passphrase_b64,omitempty"`
	TTLSeconds    int64  `json:"ttl_seconds,omitempty"`
}

type sessionResponse struct {
	OK            bool   `json:"ok"`
	Action        string `json:"action,omitempty"`
	Error         string `json:"error,omitempty"`
	ExitCode      int    `json:"exit_code,omitempty"`
	Running       bool   `json:"running"`
	Locked        bool   `json:"locked"`
	VaultPath     string `json:"vault_path,omitempty"`
	SocketPath    string `json:"socket_path,omitempty"`
	PID           int    `json:"pid,omitempty"`
	TTLSeconds    int64  `json:"ttl_seconds,omitempty"`
	ExpiresAt     string `json:"expires_at,omitempty"`
	PassphraseB64 string `json:"passphrase_b64,omitempty"`
}

type sessionState struct {
	mu         sync.Mutex
	passphrase []byte
	vaultPath  string
	idleTTL    time.Duration
	expiresAt  time.Time
}

func newSessionCommand() *cobra.Command {
	cmd := &cobra.Command{
		Use:   "session",
		Short: "Manage a short-lived local unlock session",
	}
	cmd.AddCommand(newSessionStartCommand())
	cmd.AddCommand(newSessionStatusCommand())
	cmd.AddCommand(newSessionLockCommand())
	cmd.AddCommand(newSessionStopCommand())
	cmd.AddCommand(newSessionServeCommand())
	return cmd
}

func newSessionStartCommand() *cobra.Command {
	var vaultPath string
	var passphraseCmd string
	var passphraseStdin bool
	var ttl time.Duration
	var jsonOut bool

	cmd := &cobra.Command{
		Use:   "start",
		Short: "Unlock the vault for a short local session",
		RunE: func(cmd *cobra.Command, args []string) error {
			resolvedVaultPath, _, err := resolveVaultPath(vaultPath)
			if err != nil {
				return sessionCommandError(cmd, jsonOut, err)
			}
			resolvedVaultPath = canonicalVaultPath(resolvedVaultPath)

			if ttl <= 0 {
				return sessionCommandError(cmd, jsonOut, errors.New("--ttl must be greater than zero"))
			}

			pp, err := resolvePassphrase(passphraseCmd, passphraseStdin)
			if err != nil {
				return sessionCommandError(cmd, jsonOut, err)
			}
			defer vault.Burn(pp)

			v, err := vault.Open(resolvedVaultPath, pp)
			if err != nil {
				return sessionCommandError(cmd, jsonOut, err)
			}
			_ = v.Close()

			socketPath, err := defaultSessionSocketPath()
			if err != nil {
				return sessionCommandError(cmd, jsonOut, err)
			}
			if err := ensureSessionDaemon(socketPath); err != nil {
				return sessionCommandError(cmd, jsonOut, err)
			}

			resp, err := sendSessionRequest(socketPath, sessionRequest{
				Op:            "unlock",
				VaultPath:     resolvedVaultPath,
				PassphraseB64: base64.StdEncoding.EncodeToString(pp),
				TTLSeconds:    int64(ttl.Seconds()),
			})
			if err != nil {
				return sessionCommandError(cmd, jsonOut, err)
			}
			if !resp.OK {
				return sessionCommandError(cmd, jsonOut, errors.New(resp.Error))
			}

			resp.Action = "session_start"
			resp.ExitCode = 0
			resp.SocketPath = socketPath
			if jsonOut {
				return json.NewEncoder(cmd.OutOrStdout()).Encode(resp)
			}
			fmt.Fprintf(cmd.OutOrStdout(), "session unlocked until %s\n", resp.ExpiresAt)
			return nil
		},
	}
	cmd.Flags().StringVar(&vaultPath, "vault", "", "vault path (defaults to $KIMEN_VAULT, config.vault.path, or user config dir)")
	cmd.Flags().BoolVar(&passphraseStdin, "passphrase-stdin", false, "read passphrase from stdin (single line)")
	cmd.Flags().StringVar(&passphraseCmd, "passphrase-cmd", "", "execute command to obtain passphrase (reads one line from stdout)")
	cmd.Flags().DurationVar(&ttl, "ttl", defaultSessionTTL, "idle unlock TTL")
	cmd.Flags().BoolVar(&jsonOut, "json", false, "output JSON")
	return cmd
}

func newSessionStatusCommand() *cobra.Command {
	var jsonOut bool
	cmd := &cobra.Command{
		Use:   "status",
		Short: "Show local unlock session status",
		RunE: func(cmd *cobra.Command, args []string) error {
			resp, err := sessionStatusResponse()
			if err != nil {
				return sessionCommandError(cmd, jsonOut, err)
			}
			resp.Action = "session_status"
			resp.ExitCode = 0
			if jsonOut {
				return json.NewEncoder(cmd.OutOrStdout()).Encode(resp)
			}
			switch {
			case !resp.Running:
				fmt.Fprintln(cmd.OutOrStdout(), "session: stopped")
			case resp.Locked:
				fmt.Fprintf(cmd.OutOrStdout(), "session: locked (pid=%d)\n", resp.PID)
			default:
				fmt.Fprintf(cmd.OutOrStdout(), "session: unlocked (vault=%s expires_at=%s pid=%d)\n", resp.VaultPath, resp.ExpiresAt, resp.PID)
			}
			return nil
		},
	}
	cmd.Flags().BoolVar(&jsonOut, "json", false, "output JSON")
	return cmd
}

func newSessionLockCommand() *cobra.Command {
	var jsonOut bool
	cmd := &cobra.Command{
		Use:   "lock",
		Short: "Forget the in-memory session passphrase",
		RunE: func(cmd *cobra.Command, args []string) error {
			socketPath, err := defaultSessionSocketPath()
			if err != nil {
				return sessionCommandError(cmd, jsonOut, err)
			}
			resp, err := sendSessionRequest(socketPath, sessionRequest{Op: "lock"})
			if err != nil {
				if isSessionUnavailable(err) {
					resp = sessionResponse{OK: true, Action: "session_lock", ExitCode: 0, Running: false, Locked: true, SocketPath: socketPath}
				} else {
					return sessionCommandError(cmd, jsonOut, err)
				}
			} else if !resp.OK {
				return sessionCommandError(cmd, jsonOut, errors.New(resp.Error))
			}
			resp.Action = "session_lock"
			resp.ExitCode = 0
			resp.SocketPath = socketPath
			if jsonOut {
				return json.NewEncoder(cmd.OutOrStdout()).Encode(resp)
			}
			fmt.Fprintln(cmd.OutOrStdout(), "session locked")
			return nil
		},
	}
	cmd.Flags().BoolVar(&jsonOut, "json", false, "output JSON")
	return cmd
}

func newSessionStopCommand() *cobra.Command {
	var jsonOut bool
	cmd := &cobra.Command{
		Use:   "stop",
		Short: "Stop the local session daemon",
		RunE: func(cmd *cobra.Command, args []string) error {
			socketPath, err := defaultSessionSocketPath()
			if err != nil {
				return sessionCommandError(cmd, jsonOut, err)
			}
			resp, err := sendSessionRequest(socketPath, sessionRequest{Op: "stop"})
			if err != nil {
				if isSessionUnavailable(err) {
					resp = sessionResponse{OK: true, Action: "session_stop", ExitCode: 0, Running: false, Locked: true, SocketPath: socketPath}
				} else {
					return sessionCommandError(cmd, jsonOut, err)
				}
			} else if !resp.OK {
				return sessionCommandError(cmd, jsonOut, errors.New(resp.Error))
			}
			resp.Action = "session_stop"
			resp.ExitCode = 0
			resp.SocketPath = socketPath
			if jsonOut {
				return json.NewEncoder(cmd.OutOrStdout()).Encode(resp)
			}
			fmt.Fprintln(cmd.OutOrStdout(), "session stopped")
			return nil
		},
	}
	cmd.Flags().BoolVar(&jsonOut, "json", false, "output JSON")
	return cmd
}

func newSessionServeCommand() *cobra.Command {
	var socketPath string
	cmd := &cobra.Command{
		Use:    "serve",
		Short:  "Run the local session daemon",
		Hidden: true,
		RunE: func(cmd *cobra.Command, args []string) error {
			if strings.TrimSpace(socketPath) == "" {
				p, err := defaultSessionSocketPath()
				if err != nil {
					return err
				}
				socketPath = p
			}
			return serveSession(socketPath)
		},
	}
	cmd.Flags().StringVar(&socketPath, "socket", "", "session socket path")
	return cmd
}

func sessionStatusResponse() (sessionResponse, error) {
	socketPath, err := defaultSessionSocketPath()
	if err != nil {
		return sessionResponse{}, err
	}
	resp, err := sendSessionRequest(socketPath, sessionRequest{Op: "status"})
	if err != nil {
		if isSessionUnavailable(err) {
			return sessionResponse{OK: true, Running: false, Locked: true, SocketPath: socketPath}, nil
		}
		return sessionResponse{}, err
	}
	resp.SocketPath = socketPath
	return resp, nil
}

func resolvePassphraseFromSession(vaultPath string) ([]byte, bool) {
	socketPath, err := defaultSessionSocketPath()
	if err != nil {
		return nil, false
	}
	resp, err := sendSessionRequest(socketPath, sessionRequest{
		Op:        "passphrase",
		VaultPath: canonicalVaultPath(vaultPath),
	})
	if err != nil || !resp.OK || strings.TrimSpace(resp.PassphraseB64) == "" {
		return nil, false
	}
	pp, err := base64.StdEncoding.DecodeString(resp.PassphraseB64)
	if err != nil || len(pp) == 0 {
		return nil, false
	}
	return pp, true
}

func ensureSessionDaemon(socketPath string) error {
	if resp, err := sendSessionRequest(socketPath, sessionRequest{Op: "status"}); err == nil && resp.OK {
		return nil
	}
	_ = os.Remove(socketPath)
	if err := os.MkdirAll(filepath.Dir(socketPath), 0o700); err != nil {
		return err
	}
	if err := os.Chmod(filepath.Dir(socketPath), 0o700); err != nil {
		return err
	}

	exe, err := os.Executable()
	if err != nil {
		return err
	}
	daemon := exec.Command(exe, "session", "serve", "--socket", socketPath)
	daemon.Env = sessionDaemonEnv(os.Environ())

	devNull, err := os.OpenFile(os.DevNull, os.O_RDWR, 0)
	if err != nil {
		return err
	}
	defer devNull.Close()
	daemon.Stdin = devNull
	daemon.Stdout = devNull
	daemon.Stderr = devNull

	if err := daemon.Start(); err != nil {
		return err
	}
	_ = daemon.Process.Release()

	deadline := time.Now().Add(2 * time.Second)
	var lastErr error
	for time.Now().Before(deadline) {
		resp, err := sendSessionRequest(socketPath, sessionRequest{Op: "status"})
		if err == nil && resp.OK {
			return nil
		}
		lastErr = err
		time.Sleep(25 * time.Millisecond)
	}
	if lastErr != nil {
		return fmt.Errorf("session daemon did not become ready: %w", lastErr)
	}
	return errors.New("session daemon did not become ready")
}

func serveSession(socketPath string) error {
	if err := os.MkdirAll(filepath.Dir(socketPath), 0o700); err != nil {
		return err
	}
	if err := os.Chmod(filepath.Dir(socketPath), 0o700); err != nil {
		return err
	}
	_ = os.Remove(socketPath)

	listener, err := net.Listen("unix", socketPath)
	if err != nil {
		return err
	}
	defer listener.Close()
	defer os.Remove(socketPath)

	if err := os.Chmod(socketPath, 0o600); err != nil {
		return err
	}

	st := &sessionState{}
	stop := make(chan struct{})
	var stopOnce sync.Once
	stopFn := func() {
		stopOnce.Do(func() {
			close(stop)
			_ = listener.Close()
		})
	}

	go func() {
		ticker := time.NewTicker(time.Second)
		defer ticker.Stop()
		for {
			select {
			case <-ticker.C:
				st.expireIfNeeded(time.Now())
			case <-stop:
				st.lock()
				return
			}
		}
	}()

	for {
		conn, err := listener.Accept()
		if err != nil {
			select {
			case <-stop:
				return nil
			default:
				return err
			}
		}
		go handleSessionConn(conn, socketPath, st, stopFn)
	}
}

func handleSessionConn(conn net.Conn, socketPath string, st *sessionState, stopFn func()) {
	defer conn.Close()

	var req sessionRequest
	if err := json.NewDecoder(conn).Decode(&req); err != nil {
		_ = json.NewEncoder(conn).Encode(sessionResponse{OK: false, Error: err.Error(), ExitCode: 1})
		return
	}

	resp := st.handle(req, socketPath)
	if req.Op == "stop" && resp.OK {
		defer stopFn()
	}
	_ = json.NewEncoder(conn).Encode(resp)
}

func (s *sessionState) handle(req sessionRequest, socketPath string) sessionResponse {
	now := time.Now()
	s.expireIfNeeded(now)

	switch req.Op {
	case "status":
		return s.status(socketPath)
	case "unlock":
		pp, err := base64.StdEncoding.DecodeString(req.PassphraseB64)
		if err != nil || len(pp) == 0 {
			return sessionResponse{OK: false, Error: "invalid passphrase payload", ExitCode: 1, Running: true, Locked: true, SocketPath: socketPath, PID: os.Getpid()}
		}
		ttl := time.Duration(req.TTLSeconds) * time.Second
		if ttl <= 0 {
			ttl = defaultSessionTTL
		}
		s.unlock(canonicalVaultPath(req.VaultPath), pp, ttl, now)
		vault.Burn(pp)
		return s.status(socketPath)
	case "passphrase":
		pp, expiresAt, ok := s.sessionPassphrase(canonicalVaultPath(req.VaultPath), now)
		if !ok {
			return sessionResponse{OK: false, Error: "no active session for vault", ExitCode: 1, Running: true, Locked: true, SocketPath: socketPath, PID: os.Getpid()}
		}
		defer vault.Burn(pp)
		return sessionResponse{
			OK:            true,
			Running:       true,
			Locked:        false,
			SocketPath:    socketPath,
			PID:           os.Getpid(),
			VaultPath:     canonicalVaultPath(req.VaultPath),
			ExpiresAt:     expiresAt.UTC().Format(time.RFC3339),
			PassphraseB64: base64.StdEncoding.EncodeToString(pp),
		}
	case "lock":
		s.lock()
		return s.status(socketPath)
	case "stop":
		s.lock()
		resp := s.status(socketPath)
		resp.Running = false
		return resp
	default:
		return sessionResponse{OK: false, Error: fmt.Sprintf("unknown session op %q", req.Op), ExitCode: 1, Running: true, Locked: true, SocketPath: socketPath, PID: os.Getpid()}
	}
}

func (s *sessionState) unlock(vaultPath string, passphrase []byte, ttl time.Duration, now time.Time) {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.lockLocked()
	s.passphrase = append([]byte(nil), passphrase...)
	s.vaultPath = canonicalVaultPath(vaultPath)
	s.idleTTL = ttl
	s.expiresAt = now.Add(ttl)
}

func (s *sessionState) sessionPassphrase(vaultPath string, now time.Time) ([]byte, time.Time, bool) {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.expireIfNeededLocked(now)
	if len(s.passphrase) == 0 || s.vaultPath != canonicalVaultPath(vaultPath) {
		return nil, time.Time{}, false
	}
	s.expiresAt = now.Add(s.idleTTL)
	return append([]byte(nil), s.passphrase...), s.expiresAt, true
}

func (s *sessionState) status(socketPath string) sessionResponse {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.expireIfNeededLocked(time.Now())
	locked := len(s.passphrase) == 0
	resp := sessionResponse{
		OK:         true,
		Running:    true,
		Locked:     locked,
		SocketPath: socketPath,
		PID:        os.Getpid(),
	}
	if !locked {
		resp.VaultPath = s.vaultPath
		resp.TTLSeconds = int64(s.idleTTL.Seconds())
		resp.ExpiresAt = s.expiresAt.UTC().Format(time.RFC3339)
	}
	return resp
}

func (s *sessionState) lock() {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.lockLocked()
}

func (s *sessionState) lockLocked() {
	if len(s.passphrase) > 0 {
		vault.Burn(s.passphrase)
	}
	s.passphrase = nil
	s.vaultPath = ""
	s.idleTTL = 0
	s.expiresAt = time.Time{}
}

func (s *sessionState) expireIfNeeded(now time.Time) {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.expireIfNeededLocked(now)
}

func (s *sessionState) expireIfNeededLocked(now time.Time) {
	if len(s.passphrase) > 0 && !s.expiresAt.IsZero() && !now.Before(s.expiresAt) {
		s.lockLocked()
	}
}

func sendSessionRequest(socketPath string, req sessionRequest) (sessionResponse, error) {
	conn, err := net.DialTimeout("unix", socketPath, 500*time.Millisecond)
	if err != nil {
		return sessionResponse{}, err
	}
	defer conn.Close()
	_ = conn.SetDeadline(time.Now().Add(time.Second))

	if err := json.NewEncoder(conn).Encode(req); err != nil {
		return sessionResponse{}, err
	}

	var resp sessionResponse
	if err := json.NewDecoder(conn).Decode(&resp); err != nil {
		return sessionResponse{}, err
	}
	return resp, nil
}

func defaultSessionSocketPath() (string, error) {
	dir, err := defaultSessionDir()
	if err != nil {
		return "", err
	}
	return filepath.Join(dir, "session.sock"), nil
}

func sessionDaemonEnv(env []string) []string {
	out := make([]string, 0, len(env))
	prefix := envPassphrase + "="
	for _, entry := range env {
		if strings.HasPrefix(entry, prefix) {
			continue
		}
		out = append(out, entry)
	}
	return out
}

func defaultSessionDir() (string, error) {
	if p := strings.TrimSpace(os.Getenv(envSessionDir)); p != "" {
		return p, nil
	}
	if p := strings.TrimSpace(os.Getenv(envXDGRuntimeDir)); p != "" {
		return filepath.Join(p, "kimen"), nil
	}
	cacheDir, err := os.UserCacheDir()
	if err != nil {
		return "", err
	}
	if cacheDir == "" {
		return "", errors.New("no user cache dir")
	}
	return filepath.Join(cacheDir, "kimen", "session"), nil
}

func canonicalVaultPath(path string) string {
	p := strings.TrimSpace(path)
	if p == "" {
		return ""
	}
	abs, err := filepath.Abs(p)
	if err == nil {
		p = abs
	}
	return filepath.Clean(p)
}

func isSessionUnavailable(err error) bool {
	if err == nil {
		return false
	}
	var opErr *net.OpError
	if errors.As(err, &opErr) {
		return true
	}
	return errors.Is(err, os.ErrNotExist)
}

func sessionCommandError(cmd *cobra.Command, jsonOut bool, err error) error {
	if err == nil {
		return nil
	}
	if jsonOut {
		_ = json.NewEncoder(cmd.ErrOrStderr()).Encode(sessionResponse{
			OK:       false,
			Error:    err.Error(),
			ExitCode: 1,
		})
	} else {
		fmt.Fprintln(cmd.ErrOrStderr(), err.Error())
	}
	return exitcode.New(1, err)
}
